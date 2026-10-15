package com.team.blog.view.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.team.blog.account.domain.Visibility;
import com.team.blog.post.access.PostAccessPolicy;
import com.team.blog.post.access.ReadablePost;
import com.team.blog.post.access.Viewer;
import com.team.blog.post.domain.PostStatus;
import com.team.blog.shared.config.BlogProperties;
import com.team.blog.shared.error.NotFoundException;
import com.team.blog.shared.web.RateLimiter;

/**
 * 조회 기록 (spec 013, docs/31). 제외 판정은 여기 한곳에서 한다(FR-012). 통과한 조회는 Redis에 모아 두었다가
 * {@link ViewFlushJob}이 1분마다 post_view 행으로 옮긴다. 원래 IP는 어디에도 남기지 않는다(FR-034).
 */
@Service
public class ViewRecorder {
    private static final Logger log = LoggerFactory.getLogger(ViewRecorder.class);
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    static final String PENDING = "view:pending";

    /**
     * 중복 판정과 모아 두기를 한 번에 (FR-003). KEYS[1] 방문자·글 기록, KEYS[2] 모아 두는 해시,
     * KEYS[3](있으면) 이번 응답으로 새로 준 방문자 쿠키의 기록. 첫 요청은 해시 키로 세고 다음 요청은 쿠키로 오므로,
     * 쿠키 쪽에도 같은 기록을 남겨 두 번 세지 않게 한다.
     * ARGV: 기간(ms), 최대 횟수, 글 번호. 처음 본 순간부터 기간이 흐른다(FR-002).
     */
    private static final RedisScript<Long> COUNT = RedisScript.of("""
            local n = redis.call('INCR', KEYS[1])
            if n == 1 then redis.call('PEXPIRE', KEYS[1], ARGV[1]) end
            if KEYS[3] then redis.call('SET', KEYS[3], n, 'PX', ARGV[1], 'NX') end
            if n <= tonumber(ARGV[2]) then
              redis.call('HINCRBY', KEYS[2], ARGV[3], 1)
              return 1
            end
            return 0
            """, Long.class);

    public enum Outcome { COUNTED, DUPLICATE, EXCLUDED, SKIPPED }

    /**
     * 기록 요청에서 읽은 것. IP는 해시를 만드는 데만 쓰고 버린다.
     * @param issuedVisitorId 이번 응답으로 처음 주는 방문자 쿠키 값 (비회원 첫 방문만)
     */
    public record Visit(Long memberId, boolean admin, String visitorId, String issuedVisitorId, String ip, String userAgent,
                        boolean prefetch) {}

    private final JdbcTemplate jdbc;
    private final StringRedisTemplate redis;
    private final PostAccessPolicy policy;
    private final RateLimiter rateLimiter;
    private final BlogProperties.View props;
    private final Pattern bots;
    private final Clock clock;

    public ViewRecorder(JdbcTemplate jdbc, StringRedisTemplate redis, PostAccessPolicy policy, RateLimiter rateLimiter,
                        BlogProperties props, Clock clock) {
        this.jdbc = jdbc;
        this.redis = redis;
        this.policy = policy;
        this.rateLimiter = rateLimiter;
        this.props = props.view();
        this.bots = Pattern.compile(this.props.botPattern(), Pattern.CASE_INSENSITIVE);
        this.clock = clock;
    }

    /**
     * @throws NotFoundException 볼 수 없는 글 (상세와 같은 판정, FR-010)
     * @return 결과. 응답은 결과와 상관없이 같아야 하므로 컨트롤러는 이 값을 내보내지 않는다(FR-017)
     */
    public Outcome recordView(long postId, Visit visit) {
        Long authorId = readableAuthor(postId, new Viewer(visit.memberId(), visit.admin()));
        if (authorId == null) throw new NotFoundException();
        if (excluded(visit, authorId)) return Outcome.EXCLUDED;
        String visitor = visitorKey(visit);
        if (visitor == null) return Outcome.SKIPPED;
        // 같은 방문자 1분 60번 (FR-018). 저장소가 멈추면 제한은 통과시킨다(H8)
        if (!rateLimiter.tryAcquire("view:" + visitor, props.perMinute(), Duration.ofMinutes(1))) return Outcome.EXCLUDED;
        try {
            List<String> keys = new ArrayList<>(List.of(seenKey(postId, visitor), PENDING));
            if (visit.issuedVisitorId() != null) keys.add(seenKey(postId, sha256("v:" + visit.issuedVisitorId())));
            Long counted = redis.execute(COUNT, keys,
                    String.valueOf(props.window().toMillis()), String.valueOf(props.maxPerWindow()), String.valueOf(postId));
            return Long.valueOf(1).equals(counted) ? Outcome.COUNTED : Outcome.DUPLICATE;
        } catch (RuntimeException e) {
            // 중복 판정 저장소가 멈추면 세지 않는다 (FR-025). 방문자 값·IP는 로그에 남기지 않는다(FR-036)
            log.warn("조회 기록을 건너뜁니다 (post {}): {}", postId, e.getClass().getSimpleName());
            return Outcome.SKIPPED;
        }
    }

    private static String seenKey(long postId, String visitor) {
        return "view:seen:" + postId + ":" + visitor;
    }

    /** 작성자 본인, 관리자, 로봇·링크 미리보기, 미리 불러오기 (FR-007~FR-011). */
    private boolean excluded(Visit visit, long authorId) {
        if (visit.memberId() != null && visit.memberId() == authorId) return true;
        if (visit.admin()) return true;
        if (visit.prefetch()) return true;
        return isBot(visit.userAgent());
    }

    /** 브라우저 정보가 없거나 로봇·링크 미리보기면 참. 사이트 방문(064)도 같은 기준을 쓴다 */
    public boolean isBot(String ua) {
        return ua == null || ua.isBlank() || bots.matcher(ua).find();
    }

    /** 발행 + 상세와 같은 읽기 판정(숨김·휴지통·비공개·친구 아님은 null). 작성자 판정은 제외 규칙이 따로 한다. */
    private Long readableAuthor(long postId, Viewer viewer) {
        List<ReadablePost> rows = jdbc.query("""
                SELECT p.author_id, p.status, p.visibility, p.deleted_at IS NOT NULL AS deleted, p.hidden_at IS NOT NULL AS hidden,
                       m.withdrawn_at IS NOT NULL AS withdrawn
                FROM post p JOIN member m ON m.id = p.author_id WHERE p.id = ?
                """, (rs, i) -> new ReadablePost(rs.getLong("author_id"), PostStatus.valueOf(rs.getString("status")),
                Visibility.valueOf(rs.getString("visibility")), rs.getBoolean("deleted"), rs.getBoolean("hidden"),
                rs.getBoolean("withdrawn")), postId);
        if (rows.isEmpty()) return null;
        ReadablePost p = rows.get(0);
        if (p.status() != PostStatus.PUBLISHED || p.hidden() || !policy.canRead(p, viewer)) return null;
        return p.authorId();
    }

    /**
     * 회원은 회원 단위(FR-004), 비회원은 방문자 쿠키(FR-005), 쿠키가 없으면 IP·브라우저·그날 비밀값의 해시(FR-006).
     * 어느 쪽이든 해시해서 키에 쓰므로 원래 값이 저장소에 남지 않는다. 검색 요청 제한(014 FR-021)도 같은 구분을 쓴다.
     * @return 저장소가 멈춰 그날 비밀값을 못 읽으면 null
     */
    public String visitorKey(Visit visit) {
        if (visit.memberId() != null) return sha256("m:" + visit.memberId());
        if (visit.visitorId() != null && visit.visitorId().matches("[0-9a-f-]{36}")) return sha256("v:" + visit.visitorId());
        String salt = dailySalt();
        if (salt == null) return null;
        return sha256("a:" + visit.ip() + "|" + (visit.userAgent() == null ? "" : visit.userAgent()) + "|" + salt);
    }

    /** 한국 시간 0시마다 바뀌고 하루만 남는 비밀값 (FR-006). */
    private String dailySalt() {
        String key = "view:salt:" + LocalDate.now(clock.withZone(KST));
        try {
            redis.opsForValue().setIfAbsent(key, UUID.randomUUID().toString(), Duration.ofHours(25));
            return redis.opsForValue().get(key);
        } catch (RuntimeException e) {
            return null;
        }
    }

    static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)), 0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
