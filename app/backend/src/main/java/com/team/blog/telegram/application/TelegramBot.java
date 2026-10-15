package com.team.blog.telegram.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.team.blog.ai.application.MemoDraftService;
import com.team.blog.post.application.PostCommandService;
import com.team.blog.shared.config.BlogProperties;
import com.team.blog.shared.error.ApiException;
import com.team.blog.shared.scheduling.JobLock;
import com.team.blog.telegram.infra.TelegramApi;

/**
 * 블로그 봇 (023 US1·US3). 웹훅 대신 롱 폴링으로 업데이트를 받는다: 공개 https 주소가 없어도 되고,
 * 서버가 여러 대여도 잠금을 잡은 한 대만 받는다(FR-009). 받은 위치(offset)는 Redis에 둔다.
 * <p>
 * 명령: /start 코드(연결) · /stop(연결 끊기) · /help. 그 밖의 글자는 메모로 임시글을 만든다. 개인 대화에서만 답한다.
 * 메모 처리(AI 호출)는 폴링을 붙잡지 않게 보내기 실행기에서 한다.
 */
@Component
public class TelegramBot {
    private static final Logger log = LoggerFactory.getLogger(TelegramBot.class);
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    static final String OFFSET_KEY = "tg:offset";
    static final String MEMO_PREFIX = "tg:memo:";
    private static final Duration FAILURE_BACKOFF = Duration.ofSeconds(30);
    /** 한 번 받은 업데이트를 처리하는 시간 상한 (넘으면 나머지는 다음 폴링에서) */
    private static final Duration HANDLE_BUDGET = Duration.ofSeconds(60);

    private final TelegramProperties props;
    private final TelegramApi api;
    private final TelegramLinks links;
    private final MemoDraftService drafts;
    private final PostCommandService posts;
    private final JdbcTemplate jdbc;
    private final StringRedisTemplate redis;
    private final JobLock lock;
    private final Clock clock;
    private final String baseUrl;
    private volatile Instant pausedUntil = Instant.MIN;

    public TelegramBot(TelegramProperties props, TelegramApi api, TelegramLinks links, MemoDraftService drafts,
                       PostCommandService posts, JdbcTemplate jdbc, StringRedisTemplate redis, JobLock lock,
                       BlogProperties blog, Clock clock) {
        this.props = props;
        this.api = api;
        this.links = links;
        this.drafts = drafts;
        this.posts = posts;
        this.jdbc = jdbc;
        this.redis = redis;
        this.lock = lock;
        this.clock = clock;
        String b = blog.site().baseUrl() == null ? "" : blog.site().baseUrl();
        this.baseUrl = b.endsWith("/") ? b.substring(0, b.length() - 1) : b;
    }

    @Scheduled(fixedDelay = 1000, initialDelay = 5000)
    public void scheduled() {
        if (!props.available() || !props.poll() || Instant.now().isBefore(pausedUntil)) return;
        lock.runExclusively("telegram-poll", props.pollTimeout().plus(HANDLE_BUDGET).plusSeconds(60), this::pollOnce);
    }

    /** 한 번 받아 처리한다. 실패하면 30초 쉰다(로그가 매초 쌓이지 않게). */
    void pollOnce() {
        String saved = redis.opsForValue().get(OFFSET_KEY);
        long offset = saved == null ? 0 : Long.parseLong(saved);
        Optional<List<TelegramApi.Update>> received = api.updates(offset);
        if (received.isEmpty()) {
            pausedUntil = Instant.now().plus(FAILURE_BACKOFF);
            return;
        }
        List<TelegramApi.Update> updates = received.get();
        // 처리한 뒤에 오프셋을 넘긴다: 서버가 처리 도중 멈추면 다음 폴링이 그 업데이트를 다시 받는다.
        // 메모는 AI를 거쳐 느릴 수 있어 잠금 시간 안에서만 처리하고, 남은 것은 다음 폴링에서 다시 받는다
        Instant until = Instant.now().plus(HANDLE_BUDGET);
        for (TelegramApi.Update u : updates) {
            if (Instant.now().isAfter(until)) break;
            try {
                handle(u);
            } catch (RuntimeException e) {
                // 실패해도 다음으로 넘어간다: 같은 메시지를 계속 다시 받지 않게
                log.warn("텔레그램 업데이트를 처리하지 못했습니다 ({}): {}", u.updateId(), e.getClass().getSimpleName());
            }
            redis.opsForValue().set(OFFSET_KEY, Long.toString(u.updateId() + 1));
        }
    }

    /** 업데이트 하나 (테스트가 직접 부른다) */
    public void handle(TelegramApi.Update u) {
        if (u.chatId() == null || !"private".equals(u.chatType())) return;
        long chat = u.chatId();
        String text = u.text() == null ? null : u.text().strip();
        if (text == null || text.isEmpty()) {
            reply(chat, links.memberOf(chat).isPresent() ? TelegramMessages.TEXT_ONLY : TelegramMessages.NOT_LINKED);
            return;
        }
        if (text.startsWith("/")) {
            command(chat, text);
            return;
        }
        Long member = links.memberOf(chat).orElse(null);
        if (member == null) {
            reply(chat, TelegramMessages.NOT_LINKED);
            return;
        }
        memo(chat, member, text);
    }

    private void command(long chat, String text) {
        String[] parts = text.split("\\s+", 2);
        String cmd = parts[0].contains("@") ? parts[0].substring(0, parts[0].indexOf('@')) : parts[0];
        switch (cmd) {
            case "/start" -> {
                if (parts.length > 1) {
                    reply(chat, links.link(parts[1].strip(), chat).map(TelegramMessages::linked).orElse(TelegramMessages.CODE_EXPIRED));
                } else {
                    reply(chat, links.memberOf(chat).isPresent() ? TelegramMessages.HELP : TelegramMessages.NOT_LINKED);
                }
            }
            case "/stop" -> reply(chat, links.unlinkChat(chat) ? TelegramMessages.UNLINKED : TelegramMessages.NOT_LINKED);
            default -> reply(chat, TelegramMessages.HELP);
        }
    }

    /** 메모 → 임시글 (US3). 계정 상태를 먼저 본다(US3-4). */
    void memo(long chat, long memberId, String text) {
        String blocked = blocked(memberId);
        if (blocked != null) {
            reply(chat, blocked);
            return;
        }
        if (text.codePointCount(0, text.length()) > props.memoMaxChars()) {
            reply(chat, "메모는 " + String.format("%,d", props.memoMaxChars()) + "자까지 저장할 수 있어요. 나눠서 보내 주세요.");
            return;
        }
        String key = MEMO_PREFIX + memberId + ":" + LocalDate.now(clock.withZone(KST));
        Long used = redis.opsForValue().increment(key);
        redis.expire(key, Duration.ofDays(2));
        if (used == null || used > props.memoDailyLimit()) {
            reply(chat, "오늘은 메모를 " + props.memoDailyLimit() + "개까지 저장할 수 있어요. 내일 다시 보내 주세요.");
            return;
        }
        MemoDraftService.Draft d;
        long id;
        try {
            d = drafts.draft(memberId, text);
            id = posts.create(memberId, d.title(), d.contentMd()).id();
        } catch (RuntimeException e) {
            // 저장하지 못했으면 오늘 횟수에서 뺀다. 저장한 뒤 답장이 실패한 경우는 빼지 않는다
            redis.opsForValue().decrement(key);
            log.warn("메모를 임시글로 저장하지 못했습니다: {}", e.getClass().getSimpleName());
            reply(chat, e instanceof ApiException ae ? "임시글을 저장하지 못했어요: " + ae.getMessage()
                    : "임시글을 저장하지 못했어요. 잠시 뒤 다시 보내 주세요.");
            return;
        }
        reply(chat, TelegramMessages.drafted(d.title(), baseUrl + "/write/" + id, d.note()));
    }

    private static final Map<String, String> BLOCKED = Map.of(
            "WITHDRAWN", "탈퇴 신청한 계정이라 메모를 저장할 수 없어요. 블로그에서 복구하면 다시 쓸 수 있어요.",
            "SUSPENDED", "정지된 계정이라 메모를 저장할 수 없어요.");

    /** @return 막는 이유. 저장할 수 있으면 null */
    private String blocked(long memberId) {
        List<String[]> rows = jdbc.query("""
                SELECT m.status, a.email_verified_at IS NOT NULL FROM member m LEFT JOIN auth_identity a ON a.member_id = m.id
                WHERE m.id = ? AND m.deleted_at IS NULL
                """, (rs, i) -> new String[] {rs.getString(1), Boolean.toString(rs.getBoolean(2))}, memberId);
        if (rows.isEmpty()) return TelegramMessages.NOT_LINKED;
        String status = rows.getFirst()[0];
        if (BLOCKED.containsKey(status)) return BLOCKED.get(status);
        if (!Boolean.parseBoolean(rows.getFirst()[1])) return "이메일 인증을 마친 뒤 메모를 저장할 수 있어요.";
        return null;
    }

    private void reply(long chat, String text) {
        if (api.send(chat, text) == TelegramApi.SendResult.GONE) links.unlinkChat(chat);
    }
}
