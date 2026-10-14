package com.team.blog.shorts.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.sql.Timestamp;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.team.blog.shared.stats.Counts;
import com.team.blog.shared.time.Times;

/**
 * 렌더 워커가 살아 있는지와 대기열 상태 (#241). 워커가 API를 부를 때마다 마지막 연결 시각을 Redis에 적고(앱이 여러 대여도 같은 값),
 * 관리자 대시보드가 읽어 "대기 중인 영상이 있는데 워커가 안 보인다"를 알린다. 옛 토큰으로 들어온 적이 있으면 하루 동안 표시한다.
 * Redis가 안 되면 기록을 건너뛰고(워커 요청은 막지 않는다) 마지막 연결 시각을 모른다고 보여 준다.
 */
@Component
public class RenderWorkerStatus {
    private static final Logger log = LoggerFactory.getLogger(RenderWorkerStatus.class);
    static final String SEEN_KEY = "shorts:worker:seen";
    static final String PREVIOUS_TOKEN_KEY = "shorts:worker:previous-token";

    /**
     * @param tokenSet      워커 토큰을 넣었는가 (없으면 워커 API가 꺼져 있다)
     * @param lastSeen      워커가 마지막으로 부른 때. 한 번도 없거나 모르면 null
     * @param previousToken 하루 안에 옛 토큰(SHORTS_WORKER_TOKEN_PREVIOUS)으로 들어온 워커가 있다
     * @param oldestQueuedAt 가장 오래 기다린 영상이 대기에 들어간 때
     */
    public record Status(boolean tokenSet, Instant lastSeen, boolean previousToken, long queued, long rendering, long failedToday,
                         Instant oldestQueuedAt) {}

    private final StringRedisTemplate redis;
    private final JdbcTemplate jdbc;
    private final ShortsProperties props;
    private final Clock clock;

    public RenderWorkerStatus(StringRedisTemplate redis, JdbcTemplate jdbc, ShortsProperties props, Clock clock) {
        this.redis = redis;
        this.jdbc = jdbc;
        this.props = props;
        this.clock = clock;
    }

    /** 워커가 토큰을 맞게 보냈을 때 부른다 */
    public void seen(boolean previousToken) {
        try {
            redis.opsForValue().set(SEEN_KEY, String.valueOf(Times.now(clock).toEpochMilli()), Duration.ofDays(30));
            if (previousToken) redis.opsForValue().set(PREVIOUS_TOKEN_KEY, "1", Duration.ofDays(1));
        } catch (RuntimeException e) {
            log.debug("렌더 워커 연결 시각을 적지 못했습니다: {}", e.getMessage());
        }
    }

    /** 쇼츠가 꺼져 있으면 비어 있다 */
    public Optional<Status> status() {
        if (!props.enabled()) return Optional.empty();
        Instant lastSeen = null;
        boolean previous = false;
        try {
            String v = redis.opsForValue().get(SEEN_KEY);
            if (v != null) lastSeen = Instant.ofEpochMilli(Long.parseLong(v));
            previous = Boolean.TRUE.equals(redis.hasKey(PREVIOUS_TOKEN_KEY));
        } catch (RuntimeException e) {
            log.debug("렌더 워커 연결 시각을 읽지 못했습니다: {}", e.getMessage());
        }
        Instant dayStart = LocalDate.ofInstant(Times.now(clock), Counts.KST).atStartOfDay(Counts.KST).toInstant();
        Status s = jdbc.queryForObject("""
                SELECT count(*) FILTER (WHERE status = 'QUEUED') AS queued,
                       count(*) FILTER (WHERE status = 'RENDERING') AS rendering,
                       count(*) FILTER (WHERE status = 'FAILED' AND updated_at >= ?) AS failed,
                       min(queued_at) FILTER (WHERE status = 'QUEUED') AS oldest
                FROM shorts WHERE status IN ('QUEUED', 'RENDERING', 'FAILED')
                """, (rs, i) -> {
            Timestamp oldest = rs.getTimestamp("oldest");
            return new Status(props.workerEnabled(), null, false, rs.getLong("queued"), rs.getLong("rendering"), rs.getLong("failed"),
                    oldest == null ? null : oldest.toInstant());
        }, Timestamp.from(dayStart));
        return Optional.of(new Status(s.tokenSet(), lastSeen, previous, s.queued(), s.rendering(), s.failedToday(), s.oldestQueuedAt()));
    }
}
