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
import com.team.blog.post.infra.PostSql;
import com.team.blog.shared.error.ApiException;
import com.team.blog.shared.error.NotFoundException;
import com.team.blog.shared.markdown.ImageUrls;
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
    /** 워커에게 알려 줄 최근 연출 수(같은 회원). 워커가 이 조합들을 피해 고른다 */
    static final int RECENT_LOOKS = 5;
    /** 워커가 알려 주는 연출 이름(룩:배경음). 모양이 다르면 적지 않는다 */
    private static final java.util.regex.Pattern LOOK = java.util.regex.Pattern.compile("[a-z]{1,16}:[0-9]{1,2}");
    private static final TypeReference<List<Scene>> SCENES = new TypeReference<>() {};
    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("yyyy/MM").withZone(ZoneOffset.UTC);
    /** 가장 먼저 대기한 일 하나를 잠가 RENDERING으로 바꾸고 워커에게 줄 칸을 돌려준다 */
    private static final String CLAIM_SQL = """
            WITH next AS (
                SELECT id FROM shorts WHERE status = 'QUEUED' ORDER BY queued_at, id LIMIT 1 FOR UPDATE SKIP LOCKED
            )
            UPDATE shorts s SET status = 'RENDERING', claim_token = ?, claimed_at = ?, attempts = s.attempts + 1, updated_at = ?
            FROM next WHERE s.id = next.id
            RETURNING s.id, s.attempts, s.title, s.scenes::text, s.post_id, s.replay::text AS replay, s.mascot, s.outro,
                      CASE WHEN s.rendered_at >= ? THEN s.look END AS own_look,
                      (SELECT count(*) FROM shorts WHERE member_id = s.member_id AND id <> s.id AND replay IS NULL
                          AND (rendered_at >= ? OR (status = 'RENDERING' AND claimed_at >= ?))) AS color_slot,
                      (SELECT handle FROM member WHERE id = s.member_id) AS handle,
                      (SELECT m.handle FROM post p JOIN member m ON m.id = p.author_id WHERE p.id = s.post_id AND p.deleted_at IS NULL) AS post_handle,
                      (SELECT %s FROM post p WHERE p.id = s.post_id AND p.deleted_at IS NULL) AS cover_key,
                      (SELECT string_agg(r.look, ',' ORDER BY r.rendered_at DESC, r.id DESC) FROM (
                          SELECT look, rendered_at, id FROM shorts
                          WHERE member_id = s.member_id AND id <> s.id AND look IS NOT NULL AND rendered_at IS NOT NULL
                          ORDER BY rendered_at DESC, id DESC LIMIT %d) r) AS recent_looks
            """.formatted(PostSql.THUMBNAIL_KEY.replace(" AS thumbnail_key", ""), RECENT_LOOKS);

    /**
     * 워커에게 주는 일. postUrl은 영상 끝 화면에 적을 원본 글 주소(사이트 안 경로).
     * coverImage는 원본 글 대표 사진(#277): 워커가 그림 없는 첫 장면의 가운데 카드로 쓴다. 장면마다 그림은 scenes[].image
     * replay가 있으면 매매 재현 영상(#265)이다: 워커가 그 구간의 공개 캔들을 받아 캔들과 체결 표시를 그린다.
     * character는 연기할 캐릭터(#304). 비면 워커 기본(로그냥). outro가 false면 영상 끝 3초 블로그 광고를 붙이지 않는다.
     * recentLooks는 같은 회원의 최근 영상 연출(룩:배경음, 최근 것부터). 워커가 같은 조합을 되풀이하지 않게 피해 고른다.
     * look은 세 편씩 같은 색 계열로 묶은 룩({@link ColorGroups}). 워커는 이 룩으로 그리고 배경음 등은 최근 조합을 피해 고른다.
     * 매매 재현 영상과 묶음을 세기 시작하기 전에는 비운다(워커가 전처럼 고른다. 재현 영상은 늘 paper)
     */
    public record Job(long id, String claim, int attempt, String title, List<JobScene> scenes, String handle, String postUrl, String coverImage,
                      List<JobEffect> effects, TradeReplay replay, String character, boolean outro, List<String> recentLooks, String look) {}

    /** 워커가 읽는 장면. visual은 장면 연출 이름(#277), 비면 워커가 자막·내레이션·코드에서 고른다. diagram은 설명 도식(대본 그대로) */
    public record JobScene(String caption, String narration, String code, String image, String visual, java.util.Map<String, Object> diagram) {}

    /** 작업 단위 효과음(#277). scene은 0부터 센 장면 번호, at은 그 장면이 시작하고 몇 초 뒤 */
    public record JobEffect(int scene, double at, String sound, Double volume) {}

    static List<JobScene> jobScenes(List<Scene> scenes) {
        return scenes.stream().map(s -> new JobScene(s.caption(), s.narration(), s.code(), s.image(), s.visual(), s.diagram())).toList();
    }

    static List<JobEffect> jobEffects(List<Scene> scenes) {
        List<JobEffect> out = new java.util.ArrayList<>();
        for (int i = 0; i < scenes.size(); i++) {
            for (ShortsScript.Effect e : scenes.get(i).effects()) out.add(new JobEffect(i, e.at(), e.sound(), e.volume()));
        }
        return out;
    }

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final ObjectStorage storage;
    private final StringRedisTemplate redis;
    private final ShortsProperties props;
    private final ImageUrls imageUrls;
    private final Clock clock;
    private final JsonMapper json = JsonMapper.builder().build();

    public RenderQueue(JdbcTemplate jdbc, PlatformTransactionManager txm, ObjectStorage storage, StringRedisTemplate redis,
                       ShortsProperties props, ImageUrls imageUrls, Clock clock) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(txm);
        this.storage = storage;
        this.redis = redis;
        this.props = props;
        this.imageUrls = imageUrls;
        this.clock = clock;
    }

    public Optional<Job> claim() {
        markWorkerSeen();
        Instant now = Times.now(clock);
        String claim = UUID.randomUUID().toString();
        return Optional.ofNullable(tx.execute(s -> jdbc.query(CLAIM_SQL, rs -> {
            if (!rs.next()) return null;
            long postId = rs.getLong("post_id");
            String postHandle = rs.getString("post_handle");
            String postUrl = rs.wasNull() || postHandle == null ? null : "/@" + postHandle + "/posts/" + postId;
            List<Scene> scenes = json.readValue(rs.getString(4), SCENES);
            String replay = rs.getString("replay");
            return new Job(rs.getLong("id"), claim, rs.getInt("attempts"), rs.getString("title"),
                    jobScenes(scenes), rs.getString("handle"), postUrl,
                    postUrl == null ? null : imageUrls.urlOf(rs.getString("cover_key")), jobEffects(scenes),
                    replay == null ? null : json.readValue(replay, TradeReplay.class), rs.getString("mascot"),
                    rs.getBoolean("outro"), recentLooks(rs.getString("recent_looks")),
                    replay == null && !now.isBefore(props.colorGroupsSince()) ? groupLook(rs.getString("own_look"), rs.getLong("color_slot")) : null);
        }, claim, Timestamp.from(now), Timestamp.from(now), Timestamp.from(props.colorGroupsSince()), Timestamp.from(props.colorGroupsSince()),
                Timestamp.from(props.colorGroupsSince()))));
    }

    /**
     * 묶음을 센 뒤 이미 그린 영상을 다시 그리면 그때 룩을 그대로 쓴다(격자 자리가 바뀌지 않게). 아니면 몇 번째 영상인지로 정한다.
     * slot은 같은 회원이 기준 시각 뒤에 다 그렸거나 지금 그리는 중인 글 쇼츠 수다. 그리는 중인 것도 세서 워커 여럿이 동시에 받아도
     * 같은 자리를 겹쳐 받지 않는다(받기는 SKIP LOCKED로 하나씩 커밋된다). 실패해 대기로 돌아간 작업은 다시 받을 때 센다
     */
    static String groupLook(String ownLook, long slot) {
        if (ownLook != null) {
            String name = ownLook.split(":", 2)[0];
            if (ColorGroups.ORDER.contains(name)) return name;
        }
        return ColorGroups.lookFor(slot);
    }

    static List<String> recentLooks(String joined) {
        return joined == null || joined.isEmpty() ? List.of() : List.of(joined.split(","));
    }

    public void complete(long id, String claim, byte[] video, Integer durationMs) {
        complete(id, claim, video, durationMs, null);
    }

    /** 영상 받기. MP4(ftyp)인지와 크기는 컨트롤러가 먼저 본다. look은 워커가 고른 연출(모양이 틀리면 버린다) */
    public void complete(long id, String claim, byte[] video, Integer durationMs, String look) {
        String lookName = look != null && LOOK.matcher(look).matches() ? look : null;
        String key = "shorts/" + MONTH.format(Times.now(clock)) + "/" + UUID.randomUUID() + ".mp4";
        requireClaim(id, claim);
        storage.put(key, video, "video/mp4");
        Boolean saved;
        try {
            saved = tx.execute(s -> {
                if (!holds(id, claim)) return false;
                Instant now = Times.now(clock);
                jdbc.update("""
                        UPDATE shorts SET status = 'READY', claim_token = NULL, video_key = ?, video_bytes = ?, duration_ms = ?, rendered_at = ?,
                               error = NULL, updated_at = ?, version = version + 1, look = ?
                        WHERE id = ?
                        """, key, (long) video.length, durationMs, Timestamp.from(now), Timestamp.from(now), lookName, id);
                return true;
            });
        } catch (RuntimeException e) {
            // DB에 적지 못한 영상은 어떤 행도 가리키지 않으니 바로 지운다
            storage.delete(key);
            throw e;
        }
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
