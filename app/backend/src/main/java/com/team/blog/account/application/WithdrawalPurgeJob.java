package com.team.blog.account.application;

import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import com.team.blog.account.infra.AuthTokens;
import com.team.blog.account.infra.LoginAttempts;
import com.team.blog.shared.jdbc.Columns;
import com.team.blog.shared.scheduling.JobLock;
import com.team.blog.shared.security.SessionTerminator;
import com.team.blog.shared.web.RateLimiter;

/**
 * 탈퇴 신청 후 30일이 지난 회원을 정리한다 (020 FR-023~FR-030, docs/44 §4). 매일 04:50(KST) 한 서버에서만 돈다.
 * 회원 1명이 한 트랜잭션이다: 단계 하나라도 실패하면 그 회원만 전부 되돌리고 다음 날 다시 한다.
 * 확정 뒤에는 Redis의 세션·토큰·실패 횟수·요청 횟수를 지운다. 사건은 만들지 않는다(FR-029).
 */
@Component
public class WithdrawalPurgeJob {
    private static final Logger log = LoggerFactory.getLogger(WithdrawalPurgeJob.class);
    private static final int BATCH = 50;
    /** 회원 번호가 붙는 요청 횟수 키 (RateLimiter). 새 기능이 회원별 제한을 더하면 여기에도 더한다. */
    private static final List<String> MEMBER_RATE_KEYS = List.of("ai-tags", "autosave", "friend-request", "like", "post-create",
            "post-delete", "preview", "report-day", "report-minute", "tag-suggest", "upload:min", "verify:day", "verify:min");

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final List<WithdrawalPurgeStep> steps;
    private final JobLock lock;
    private final SessionTerminator sessions;
    private final AuthTokens tokens;
    private final LoginAttempts attempts;
    private final RateLimiter limiter;

    public WithdrawalPurgeJob(JdbcTemplate jdbc, TransactionTemplate tx, List<WithdrawalPurgeStep> steps, JobLock lock,
                              SessionTerminator sessions, AuthTokens tokens, LoginAttempts attempts, RateLimiter limiter) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.steps = steps.stream().sorted(Comparator.comparingInt(WithdrawalPurgeStep::order)).toList();
        this.lock = lock;
        this.sessions = sessions;
        this.tokens = tokens;
        this.attempts = attempts;
        this.limiter = limiter;
    }

    @Scheduled(cron = "${blog.account.withdraw-purge-cron:0 50 4 * * *}", zone = "Asia/Seoul")
    public void scheduled() {
        lock.runExclusively("withdraw-purge", Duration.ofHours(1), this::run);
    }

    /** @return 정리한 회원 수 */
    public int run() {
        // 탈퇴 신청 때 바로 지우지만(FriendshipWithdrawalListener), 배포 중 옛 서버가 받은 탈퇴는 남을 수 있어 매일 한 번 더 지운다
        jdbc.update("DELETE FROM friendship f WHERE EXISTS (SELECT 1 FROM member m"
                + " WHERE m.status = 'WITHDRAWN' AND m.id IN (f.member_a_id, f.member_b_id))");
        int done = 0;
        long after = 0;
        while (true) {
            List<Long> ids = Columns.longs(jdbc, "SELECT id FROM member WHERE " + DUE + " AND id > ? ORDER BY id LIMIT " + BATCH, after);
            if (ids.isEmpty()) break;
            for (long id : ids) {
                if (purgeOne(id)) done++;
            }
            after = ids.getLast();
        }
        if (done > 0) log.info("탈퇴 30일이 지난 회원 {}명을 정리했습니다", done);
        return done;
    }

    private static final String WITHDRAWN = "status = 'WITHDRAWN' AND deleted_at IS NULL";
    private static final String DUE = WITHDRAWN + " AND withdrawn_at < now() - make_interval(days => "
            + WithdrawalService.GRACE.toDays() + ")";

    /**
     * 관리자가 유예 기간을 기다리지 않고 탈퇴 회원 한 명을 바로 정리한다 (020 FR-039). 정리 단계는 30일 정리와 같다.
     * @return 정리했으면 true. 탈퇴 상태가 아니거나(그 사이 복구) 이미 정리했으면 false
     */
    public boolean purgeNow(long memberId) {
        boolean done = purgeOne(memberId, WITHDRAWN);
        if (done) log.info("탈퇴 회원 {}을 유예 기간 전에 바로 정리했습니다", memberId);
        return done;
    }

    /** 회원 한 명. 그 사이 복구했으면 건너뛴다. */
    boolean purgeOne(long memberId) {
        return purgeOne(memberId, DUE);
    }

    private boolean purgeOne(long memberId, String condition) {
        Optional<String> email;
        try {
            // 비어 있으면 그 사이 복구했거나 다른 실행이 잡고 있어 건너뛴다
            email = Objects.requireNonNullElse(tx.execute(s -> {
                List<Long> locked = Columns.longs(jdbc, "SELECT id FROM member WHERE id = ? AND " + condition + " FOR UPDATE SKIP LOCKED",
                        memberId);
                if (locked.isEmpty()) return Optional.<String>empty();
                // 로그인 수단 단계(50)가 지우기 전에 실패 횟수 키를 지울 이메일을 읽어 둔다
                List<String> emails = Columns.strings(jdbc, "SELECT email FROM auth_identity WHERE member_id = ? AND email IS NOT NULL",
                        memberId);
                steps.forEach(step -> step.purge(memberId));
                return Optional.of(emails.isEmpty() ? "" : emails.getFirst());
            }), Optional.empty());
        } catch (RuntimeException e) {
            log.warn("탈퇴 회원 정리에 실패해 다음에 다시 합니다 ({}): {}", memberId, e.getClass().getSimpleName());
            return false;
        }
        if (email.isEmpty()) return false;
        // DB 정리는 이미 커밋됐다. 세션·Redis 키가 남아도 정리 결과는 그대로 성공이다
        try {
            forgetTransient(memberId, email.get());
        } catch (RuntimeException e) {
            log.warn("정리한 회원 {}의 세션·임시 키를 다 지우지 못했습니다: {}", memberId, e.getClass().getSimpleName());
        }
        return true;
    }

    private void forgetTransient(long memberId, String email) {
        sessions.terminate(memberId, null);
        for (AuthTokens.Kind kind : AuthTokens.Kind.values()) tokens.revoke(kind, memberId);
        attempts.reset("pwchange:" + memberId);
        attempts.reset("withdraw:" + memberId);
        if (!email.isEmpty()) attempts.reset("login:" + email);
        MEMBER_RATE_KEYS.forEach(k -> limiter.forget(k + ":" + memberId));
    }
}
