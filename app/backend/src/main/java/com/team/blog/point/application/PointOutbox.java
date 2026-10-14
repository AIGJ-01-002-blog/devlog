package com.team.blog.point.application;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.team.blog.shared.scheduling.JobLock;
import com.team.blog.shared.time.Times;

/**
 * 글 발행 크레딧 미지급 목록 (#266, 블로그 주인 결정 "outbox 방식"). 발행 트랜잭션 안에서 줄을 넣어 두고({@link #enqueue}),
 * 커밋 뒤 바로({@link #deliver}) 또는 1분마다 도는 작업이 지급하면서 지운다. 지급은 글마다 한 번뿐이라(원장 키 post:글번호)
 * 두 곳에서 같은 줄을 처리해도 한 번만 들어간다. 적립 조건(글자 수·하루 상한)에 안 맞아 0이 나와도 줄은 지운다.
 */
@Component
public class PointOutbox {
    private static final Logger log = LoggerFactory.getLogger(PointOutbox.class);
    static final int BATCH = 100;
    static final Duration MAX_BACKOFF = Duration.ofHours(1);

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final PointLedger points;
    private final JobLock lock;
    private final Clock clock;

    public PointOutbox(JdbcTemplate jdbc, PlatformTransactionManager txm, PointLedger points, JobLock lock, Clock clock) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(txm);
        this.points = points;
        this.lock = lock;
        this.clock = clock;
    }

    /** 발행 트랜잭션 안에서 부른다. 발행이 되돌려지면 이 줄도 함께 사라진다 */
    void enqueue(long memberId, long postId) {
        if (!points.enabled()) return;
        Timestamp now = Timestamp.from(Times.now(clock));
        jdbc.update("INSERT INTO point_outbox (member_id, post_id, next_at, created_at) VALUES (?, ?, ?, ?) ON CONFLICT (post_id) DO NOTHING",
                memberId, postId, now, now);
    }

    /** 이 글의 줄을 지급한다. 다른 곳이 처리 중이면 건너뛴다 @return 처리했으면 true */
    public boolean deliver(long postId) {
        Boolean done = tx.execute(st -> {
            List<long[]> row = jdbc.query("SELECT id, member_id FROM point_outbox WHERE post_id = ? FOR UPDATE SKIP LOCKED",
                    (rs, i) -> new long[] {rs.getLong(1), rs.getLong(2)}, postId);
            if (row.isEmpty()) return false;
            points.rewardPost(row.getFirst()[1], postId);
            jdbc.update("DELETE FROM point_outbox WHERE id = ?", row.getFirst()[0]);
            return true;
        });
        return Boolean.TRUE.equals(done);
    }

    @Scheduled(fixedDelayString = "${blog.points.outbox-interval:60s}", initialDelayString = "${blog.points.outbox-interval:60s}")
    public void scheduled() {
        if (!points.enabled()) return;
        lock.runExclusively("point-outbox", Duration.ofMinutes(5), this::drain);
    }

    /** 지급할 때가 된 줄을 처리한다 @return 지급한(지운) 줄 수 */
    public int drain() {
        List<Long> due = jdbc.queryForList("SELECT post_id FROM point_outbox WHERE next_at <= ? ORDER BY id LIMIT ?", Long.class,
                Timestamp.from(Times.now(clock)), BATCH);
        int delivered = 0;
        for (long postId : due) {
            try {
                if (deliver(postId)) delivered++;
            } catch (RuntimeException e) {
                failed(postId, e);
            }
        }
        if (delivered > 0) log.info("밀린 글 발행 크레딧 {}건을 처리했습니다", delivered);
        return delivered;
    }

    /** 실패하면 1분, 2분, 4분… 최대 1시간 뒤에 다시 시도한다 */
    void failed(long postId, RuntimeException e) {
        log.warn("글 발행 크레딧을 주지 못했습니다 (post {}): {}", postId, e.getClass().getSimpleName());
        try {
            List<Integer> attempts = jdbc.queryForList("SELECT attempts FROM point_outbox WHERE post_id = ?", Integer.class, postId);
            if (attempts.isEmpty()) return;
            int n = attempts.getFirst() + 1;
            Duration wait = Duration.ofMinutes(1L << Math.min(n - 1, 6));
            if (wait.compareTo(MAX_BACKOFF) > 0) wait = MAX_BACKOFF;
            Instant next = Times.now(clock).plus(wait);
            String reason = e.getClass().getSimpleName();
            jdbc.update("UPDATE point_outbox SET attempts = ?, next_at = ?, last_error = ? WHERE post_id = ?", n, Timestamp.from(next),
                    reason.length() > 200 ? reason.substring(0, 200) : reason, postId);
        } catch (RuntimeException ignored) {
            // 기록도 못 하면 다음 차례에 그대로 다시 시도한다
        }
    }
}
