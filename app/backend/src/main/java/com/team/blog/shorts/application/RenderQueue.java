package com.team.blog.shorts.application;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import com.team.blog.media.storage.ObjectStorage;
import com.team.blog.shared.error.ApiException;
import com.team.blog.shared.error.NotFoundException;
import com.team.blog.shared.time.Times;
import com.team.blog.shorts.application.ShortsScript.Scene;

/**
 * 렌더 워커와 주고받는 대기열 (099 FR-006). 워커는 devlog에 일을 받으러 나오기만 하므로(바깥으로만 연결) 학교 방화벽과 상관없다.
 * <ul>
 *   <li>claim: 가장 오래 기다린 QUEUED 하나를 잠가 RENDERING으로 바꾸고 일회용 claim 값을 준다. 여러 워커가 동시에 와도 하나만 받는다(SKIP LOCKED).</li>
 *   <li>complete: claim 값이 맞고 아직 RENDERING일 때만 영상을 저장하고 READY로 바꾼다. 늦게 온 결과(시간 초과로 되돌린 뒤)는 버린다.</li>
 *   <li>fail·timeout: 시도가 남았으면 QUEUED로, 아니면 FAILED로.</li>
 * </ul>
 */
@Service
public class RenderQueue {
    private static final Logger log = LoggerFactory.getLogger(RenderQueue.class);
    static final int ERROR_MAX = 500;
    private static final TypeReference<List<Scene>> SCENES = new TypeReference<>() {};
    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("yyyy/MM").withZone(ZoneOffset.UTC);

    /** 워커에게 주는 일. postUrl은 영상 끝 화면에 적을 원본 글 주소(사이트 안 경로) */
    public record Job(long id, String claim, int attempt, String title, List<Scene> scenes, String handle, String postUrl) {}

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final ObjectStorage storage;
    private final StringRedisTemplate redis;
    private final ShortsProperties props;
    private final Clock clock;
    private final JsonMapper json = JsonMapper.builder().build();

    public RenderQueue(JdbcTemplate jdbc, PlatformTransactionManager txm, ObjectStorage storage, StringRedisTemplate redis,
                       ShortsProperties props, Clock clock) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(txm);
        this.storage = storage;
        this.redis = redis;
        this.props = props;
        this.clock = clock;
    }

    public Optional<Job> claim() {
        markWorkerSeen();
        Instant now = Times.now(clock);
        String claim = UUID.randomUUID().toString();
        return Optional.ofNullable(tx.execute(s -> jdbc.query("""
                WITH next AS (
                    SELECT id FROM shorts WHERE status = 'QUEUED' ORDER BY queued_at, id LIMIT 1 FOR UPDATE SKIP LOCKED
                )
                UPDATE shorts s SET status = 'RENDERING', claim_token = ?, claimed_at = ?, attempts = s.attempts + 1, updated_at = ?
                FROM next WHERE s.id = next.id
                RETURNING s.id, s.attempts, s.title, s.scenes::text, s.post_id,
                          (SELECT handle FROM member WHERE id = s.member_id) AS handle,
                          (SELECT m.handle FROM post p JOIN member m ON m.id = p.author_id WHERE p.id = s.post_id AND p.deleted_at IS NULL) AS post_handle
                """, rs -> {
            if (!rs.next()) return null;
            long postId = rs.getLong("post_id");
            String postHandle = rs.getString("post_handle");
            String postUrl = rs.wasNull() || postHandle == null ? null : "/@" + postHandle + "/posts/" + postId;
            return new Job(rs.getLong("id"), claim, rs.getInt("attempts"), rs.getString("title"),
                    json.readValue(rs.getString(4), SCENES), rs.getString("handle"), postUrl);
        }, claim, Timestamp.from(now), Timestamp.from(now))));
    }

    /** 영상 받기. MP4(ftyp)인지와 크기는 컨트롤러가 먼저 본다 */
    public void complete(long id, String claim, byte[] video, Integer durationMs) {
        String key = "shorts/" + MONTH.format(Times.now(clock)) + "/" + UUID.randomUUID() + ".mp4";
        requireClaim(id, claim);
        storage.put(key, video, "video/mp4");
        Boolean saved = tx.execute(s -> {
            if (!holds(id, claim)) return false;
            Instant now = Times.now(clock);
            jdbc.update("""
                    UPDATE shorts SET status = 'READY', claim_token = NULL, video_key = ?, video_bytes = ?, duration_ms = ?, rendered_at = ?,
                           error = NULL, updated_at = ?, version = version + 1
                    WHERE id = ?
                    """, key, (long) video.length, durationMs, Timestamp.from(now), Timestamp.from(now), id);
            return true;
        });
        if (!Boolean.TRUE.equals(saved)) {
            storage.delete(key);
            throw staleClaim();
        }
    }

    public void fail(long id, String claim, String message) {
        Boolean done = tx.execute(s -> {
            if (!holds(id, claim)) return false;
            giveBack(id, message == null || message.isBlank() ? "렌더 기기에서 영상을 만들지 못했어요." : message);
            return true;
        });
        if (!Boolean.TRUE.equals(done)) throw staleClaim();
    }

    /** 워커가 renderTimeout 넘게 결과를 주지 않은 작업을 되돌린다. 워커가 꺼졌거나 중간에 죽었을 때 */
    public int releaseStale() {
        Instant before = Times.now(clock).minus(props.renderTimeout());
        List<Long> ids = jdbc.queryForList("SELECT id FROM shorts WHERE status = 'RENDERING' AND claimed_at < ?", Long.class, Timestamp.from(before));
        int n = 0;
        for (long id : ids) {
            Boolean done = tx.execute(s -> {
                Integer locked = jdbc.query("SELECT 1 FROM shorts WHERE id = ? AND status = 'RENDERING' AND claimed_at < ? FOR UPDATE SKIP LOCKED",
                        rs -> rs.next() ? 1 : null, id, Timestamp.from(before));
                if (locked == null) return false;
                giveBack(id, "렌더 기기가 " + props.renderTimeout().toMinutes() + "분 동안 응답하지 않았어요.");
                return true;
            });
            if (Boolean.TRUE.equals(done)) n++;
        }
        if (n > 0) log.info("응답 없는 쇼츠 렌더 {}건을 되돌렸습니다", n);
        return n;
    }

    private void giveBack(long id, String message) {
        String error = message.length() > ERROR_MAX ? message.substring(0, ERROR_MAX) : message;
        Instant now = Times.now(clock);
        jdbc.update("""
                UPDATE shorts SET status = CASE WHEN attempts >= ? THEN 'FAILED' ELSE 'QUEUED' END,
                       claim_token = NULL, claimed_at = NULL, error = ?, updated_at = ?, version = version + 1
                WHERE id = ?
                """, props.maxAttempts(), error, Timestamp.from(now), id);
    }

    private boolean holds(long id, String claim) {
        return Boolean.TRUE.equals(jdbc.query("SELECT claim_token FROM shorts WHERE id = ? AND status = 'RENDERING' FOR UPDATE",
                rs -> rs.next() && claim != null && claim.equals(rs.getString(1)), id));
    }

    private void requireClaim(long id, String claim) {
        Boolean ok = jdbc.query("SELECT claim_token FROM shorts WHERE id = ? AND status = 'RENDERING'",
                rs -> rs.next() && claim != null && claim.equals(rs.getString(1)), id);
        if (!Boolean.TRUE.equals(ok)) {
            Integer exists = jdbc.queryForObject("SELECT count(*) FROM shorts WHERE id = ?", Integer.class, id);
            if (exists == null || exists == 0) throw new NotFoundException();
            throw staleClaim();
        }
    }

    private static ApiException staleClaim() {
        return ApiException.conflict("STALE_CLAIM", "이 작업은 이미 다른 상태예요(시간 초과로 되돌렸거나 대본이 바뀜). 결과를 버려 주세요.");
    }

    private void markWorkerSeen() {
        try {
            redis.opsForValue().set(ShortsService.WORKER_SEEN_KEY, Long.toString(clock.millis()));
        } catch (RuntimeException e) {
            log.debug("워커 확인 시각을 적지 못했습니다: {}", e.getMessage());
        }
    }
}
