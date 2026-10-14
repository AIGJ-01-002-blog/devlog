package com.team.blog.shorts.application;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import com.team.blog.media.storage.ObjectStorage;
import com.team.blog.shared.error.ApiException;
import com.team.blog.shared.error.NotFoundException;
import com.team.blog.shared.markdown.ImageUrls;
import com.team.blog.shared.stats.Counts;
import com.team.blog.shared.time.Times;
import com.team.blog.shorts.application.ShortsScript.Scene;

/**
 * 쇼츠 대본과 영상 만들기 (099). 회원 본인 것만 읽고 고친다(남의 쇼츠는 없는 것과 같다).
 * 대본은 사용자 쪽 AI가 MCP로 쓰거나 회원이 스튜디오에서 쓴다. 영상 만들기는 회원이 화면에서 누를 때만 대기열에 넣고,
 * 그 전에 양산형 방지 검사(OriginalityCheck)를 통과해야 한다. 렌더는 RenderQueue를 통해 워커가 한다.
 */
@Service
public class ShortsService {
    private static final Logger log = LoggerFactory.getLogger(ShortsService.class);

    public enum Status { DRAFT, QUEUED, RENDERING, READY, FAILED }

    public record Item(long id, Long postId, String postTitle, String postUrl, String title, List<Scene> scenes, Status status,
                       int attempts, String videoUrl, Integer durationMs, Long videoBytes, String error, double estimatedSeconds,
                       Instant queuedAt, Instant renderedAt, Instant createdAt, Instant updatedAt, int version) {}

    /** @param workerSeenAt 렌더 워커가 마지막으로 일을 받으러 온 시각. 없으면 아직 한 번도 오지 않았거나 오래 전 */
    public record Overview(List<Item> items, Instant workerSeenAt, int rendersToday, int dailyRenders) {}

    public record Draft(Long postId, String title, List<Scene> scenes) {}

    public record Edit(String title, List<Scene> scenes, Integer version) {}

    static final String WORKER_SEEN_KEY = "shorts:worker:seen";
    static final int LIST_MAX = 100;
    private static final TypeReference<List<Scene>> SCENES = new TypeReference<>() {};
    private static final String SELECT = """
            SELECT s.*, p.title AS post_title, m.handle AS post_handle
            FROM shorts s
            LEFT JOIN post p ON p.id = s.post_id AND p.deleted_at IS NULL
            LEFT JOIN member m ON m.id = p.author_id
            """;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final ObjectStorage storage;
    private final ImageUrls urls;
    private final StringRedisTemplate redis;
    private final ShortsProperties props;
    private final Clock clock;
    private final JsonMapper json = JsonMapper.builder().build();

    public ShortsService(JdbcTemplate jdbc, PlatformTransactionManager txm, ObjectStorage storage, ImageUrls urls,
                         StringRedisTemplate redis, ShortsProperties props, Clock clock) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(txm);
        this.storage = storage;
        this.urls = urls;
        this.redis = redis;
        this.props = props;
        this.clock = clock;
    }

    public boolean enabled() {
        return props.enabled();
    }

    /** 꺼져 있으면 없는 주소처럼 404 */
    public void requireEnabled() {
        if (!props.enabled()) throw new NotFoundException();
    }

    public Overview overview(long memberId) {
        requireEnabled();
        List<Item> items = jdbc.query(SELECT + " WHERE s.member_id = ? ORDER BY s.updated_at DESC, s.id DESC LIMIT " + LIST_MAX,
                this::map, memberId);
        return new Overview(items, workerSeenAt().orElse(null), rendersToday(memberId), props.dailyRenders());
    }

    public Item get(long memberId, long id) {
        requireEnabled();
        return find(memberId, id).orElseThrow(NotFoundException::new);
    }

    public Item create(long memberId, Draft draft) {
        requireEnabled();
        String title = ShortsScript.cleanTitle(draft.title());
        List<Scene> scenes = scenes(draft.scenes());
        reject(ShortsScript.checkSave(title, scenes));
        if (draft.postId() != null) ownPost(memberId, draft.postId());
        Instant now = Times.now(clock);
        Long id = jdbc.queryForObject("""
                INSERT INTO shorts (member_id, post_id, title, scenes, created_at, updated_at)
                VALUES (?, ?, ?, ?::jsonb, ?, ?) RETURNING id
                """, Long.class, memberId, draft.postId(), title, json.writeValueAsString(scenes), Timestamp.from(now), Timestamp.from(now));
        return get(memberId, id);
    }

    /**
     * 대본을 고친다. 대기 중이면 대기에서 빼고, 완성된 영상이 있으면 지운다(대본과 영상이 어긋나지 않게).
     * 워커가 만드는 중이면 고칠 수 없다. version이 오면 그 사이 다른 곳(AI·다른 창)에서 고쳤는지 확인한다.
     */
    public Item update(long memberId, long id, Edit edit) {
        requireEnabled();
        String oldVideo = tx.execute(s -> {
            Item cur = lock(memberId, id);
            if (cur.status() == Status.RENDERING) throw ApiException.conflict("SHORTS_RENDERING", "영상을 만드는 중이에요. 다 만들어진 뒤에 고쳐 주세요.");
            if (edit.version() != null && edit.version() != cur.version()) {
                throw ApiException.conflict("VERSION_CONFLICT", "다른 곳에서 먼저 고쳤어요. 새로 불러온 뒤 다시 고쳐 주세요.");
            }
            String title = edit.title() == null ? cur.title() : ShortsScript.cleanTitle(edit.title());
            List<Scene> scenes = edit.scenes() == null ? cur.scenes() : scenes(edit.scenes());
            reject(ShortsScript.checkSave(title, scenes));
            String video = jdbc.queryForObject("SELECT video_key FROM shorts WHERE id = ?", String.class, id);
            jdbc.update("""
                    UPDATE shorts SET title = ?, scenes = ?::jsonb, status = 'DRAFT', attempts = 0, claim_token = NULL, queued_at = NULL,
                           claimed_at = NULL, video_key = NULL, video_bytes = NULL, duration_ms = NULL, rendered_at = NULL, error = NULL,
                           version = version + 1, updated_at = ?
                    WHERE id = ?
                    """, title, json.writeValueAsString(scenes), Timestamp.from(Times.now(clock)), id);
            return video;
        });
        if (oldVideo != null) storage.delete(oldVideo);
        return get(memberId, id);
    }

    /** 영상 만들기 전에 화면·AI가 미리 보는 검사 */
    public OriginalityCheck.Report check(long memberId, long id) {
        requireEnabled();
        Item item = get(memberId, id);
        return originality(memberId, item);
    }

    /**
     * 영상 만들기 (FR-003·FR-005). 대본 규칙 → 양산형 방지 검사 → 하루 한도 순으로 보고, 통과하면 대기열에 넣는다.
     * 막히면 422와 함께 검사 결과(details.report)를 돌려준다.
     */
    public Item render(long memberId, long id) {
        requireEnabled();
        tx.executeWithoutResult(s -> {
            Item cur = lock(memberId, id);
            if (cur.status() == Status.QUEUED || cur.status() == Status.RENDERING) return;
            if (cur.status() == Status.READY) throw ApiException.conflict("SHORTS_READY", "이미 만든 영상이 있어요. 다시 만들려면 대본을 고쳐 주세요.");
            reject(ShortsScript.checkRender(cur.title(), cur.scenes()));
            OriginalityCheck.Report report = originality(memberId, cur);
            if (!report.passed()) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "SHORTS_NOT_ORIGINAL",
                        "이대로는 양산형 영상으로 보일 수 있어요. 막힌 이유를 고친 뒤 다시 눌러 주세요.", List.of(), new ReportDetails(report));
            }
            // 하루 한도: 같은 회원의 요청을 잠근 채 세서 동시에 눌러도 넘지 않게 한다
            jdbc.queryForObject("SELECT id FROM member WHERE id = ? FOR UPDATE", Long.class, memberId);
            if (rendersToday(memberId) >= props.dailyRenders()) {
                throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "SHORTS_DAILY_LIMIT",
                        "영상은 하루에 " + props.dailyRenders() + "번까지 만들 수 있어요. 내일 다시 눌러 주세요.");
            }
            Instant now = Times.now(clock);
            jdbc.update("INSERT INTO shorts_render_request (member_id, shorts_id, requested_at) VALUES (?, ?, ?)", memberId, id, Timestamp.from(now));
            jdbc.update("""
                    UPDATE shorts SET status = 'QUEUED', attempts = 0, error = NULL, queued_at = ?, updated_at = ?, version = version + 1
                    WHERE id = ?
                    """, Timestamp.from(now), Timestamp.from(now), id);
        });
        return get(memberId, id);
    }

    public record ReportDetails(OriginalityCheck.Report report) {}

    public void delete(long memberId, long id) {
        requireEnabled();
        String video = tx.execute(s -> {
            lock(memberId, id);
            String key = jdbc.queryForObject("SELECT video_key FROM shorts WHERE id = ?", String.class, id);
            jdbc.update("DELETE FROM shorts WHERE id = ?", id);
            return key;
        });
        if (video != null) storage.delete(video);
    }

    /**
     * 탈퇴 정리 (FR-001): 행을 지우고, 영상 파일은 정리 트랜잭션이 커밋된 뒤에 지운다.
     * 뒤 단계가 실패해 롤백되면 행과 파일이 함께 남아 다음 정리 때 다시 한다(롤백된 행이 지운 파일을 가리키지 않게).
     */
    public void purge(long memberId) {
        List<String> keys = jdbc.queryForList("SELECT video_key FROM shorts WHERE member_id = ? AND video_key IS NOT NULL", String.class, memberId);
        jdbc.update("DELETE FROM shorts WHERE member_id = ?", memberId);
        if (keys.isEmpty()) return;
        afterCommit(() -> keys.forEach(this::deleteQuietly));
    }

    private void deleteQuietly(String key) {
        try {
            storage.delete(key);
        } catch (RuntimeException e) {
            log.warn("저장소에서 쇼츠 영상을 지우지 못했습니다: {} {}", key, e.getMessage());
        }
    }

    private static void afterCommit(Runnable r) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            r.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                r.run();
            }
        });
    }

    OriginalityCheck.Report originality(long memberId, Item item) {
        String source = item.postId() == null ? null : jdbc.query(
                "SELECT content_md FROM post WHERE id = ? AND author_id = ? AND deleted_at IS NULL",
                rs -> rs.next() ? rs.getString(1) : null, item.postId(), memberId);
        List<OriginalityCheck.OtherScript> others = new ArrayList<>();
        jdbc.query("SELECT id, title, scenes::text FROM shorts WHERE member_id = ? AND id <> ? ORDER BY updated_at DESC LIMIT " + LIST_MAX,
                rs -> { others.add(new OriginalityCheck.OtherScript(rs.getLong(1), rs.getString(2), parse(rs.getString(3)))); },
                memberId, item.id());
        return OriginalityCheck.check(item.scenes(), source, others);
    }

    int rendersToday(long memberId) {
        Instant start = LocalDate.now(clock.withZone(Counts.KST)).atStartOfDay(Counts.KST).toInstant();
        Integer n = jdbc.queryForObject("SELECT count(*) FROM shorts_render_request WHERE member_id = ? AND requested_at >= ?",
                Integer.class, memberId, Timestamp.from(start));
        return n == null ? 0 : n;
    }

    Optional<Instant> workerSeenAt() {
        try {
            String v = redis.opsForValue().get(WORKER_SEEN_KEY);
            return v == null ? Optional.empty() : Optional.of(Instant.ofEpochMilli(Long.parseLong(v)));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    private Optional<Item> find(long memberId, long id) {
        return jdbc.query(SELECT + " WHERE s.id = ? AND s.member_id = ?", this::map, id, memberId).stream().findFirst();
    }

    private Item lock(long memberId, long id) {
        Long locked = jdbc.query("SELECT id FROM shorts WHERE id = ? AND member_id = ? FOR UPDATE",
                rs -> rs.next() ? rs.getLong(1) : null, id, memberId);
        if (locked == null) throw new NotFoundException();
        return find(memberId, id).orElseThrow(NotFoundException::new);
    }

    private void ownPost(long memberId, long postId) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM post WHERE id = ? AND author_id = ? AND deleted_at IS NULL",
                Integer.class, postId, memberId);
        if (n == null || n == 0) throw ApiException.badRequest("POST_NOT_FOUND", "내 글에서만 쇼츠를 만들 수 있어요. 글 번호를 확인해 주세요.");
    }

    private static List<Scene> scenes(List<Scene> raw) {
        if (raw == null) return List.of();
        return raw.stream().map(s -> s == null ? new Scene("", "", null) : new Scene(s.caption(), s.narration(), s.code())).toList();
    }

    private static void reject(List<com.team.blog.shared.error.FieldErrorItem> errors) {
        if (!errors.isEmpty()) throw ApiException.validation(errors);
    }

    private List<Scene> parse(String raw) {
        return raw == null ? List.of() : scenes(json.readValue(raw, SCENES));
    }

    private Item map(ResultSet rs, int row) throws SQLException {
        List<Scene> scenes = parse(rs.getString("scenes"));
        long postId = rs.getLong("post_id");
        boolean hasPost = !rs.wasNull();
        String postTitle = rs.getString("post_title");
        String handle = rs.getString("post_handle");
        int duration = rs.getInt("duration_ms");
        Integer durationMs = rs.wasNull() ? null : duration;
        long bytes = rs.getLong("video_bytes");
        Long videoBytes = rs.wasNull() ? null : bytes;
        return new Item(rs.getLong("id"), hasPost ? postId : null, postTitle,
                hasPost && handle != null ? "/@" + handle + "/posts/" + postId : null,
                rs.getString("title"), scenes, Status.valueOf(rs.getString("status")), rs.getInt("attempts"),
                urls.urlOf(rs.getString("video_key")), durationMs, videoBytes, rs.getString("error"),
                Math.round(ShortsScript.estimateSeconds(scenes) * 10) / 10.0,
                instant(rs, "queued_at"), instant(rs, "rendered_at"), instant(rs, "created_at"), instant(rs, "updated_at"),
                rs.getInt("version"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp t = rs.getTimestamp(column);
        return t == null ? null : t.toInstant();
    }
}
