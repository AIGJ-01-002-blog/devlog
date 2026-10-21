package com.team.blog.shorts.application;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import com.team.blog.media.storage.ObjectStorage;
import com.team.blog.shared.error.ApiException;
import com.team.blog.shared.error.FieldErrorItem;
import com.team.blog.shared.error.NotFoundException;
import com.team.blog.shared.markdown.ImageUrls;
import com.team.blog.shared.stats.Counts;
import com.team.blog.shared.text.TextCleaner;
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
                       Instant queuedAt, Instant renderedAt, Instant createdAt, Instant updatedAt, int version,
                       List<String> postTags, List<Upload> uploads, Long batchId, Integer topic, TradeReplay replay, String character,
                       boolean outro, boolean onBlog, Playlist playlist) {
        Item with(List<String> tags, List<Upload> ups) {
            return new Item(id, postId, postTitle, postUrl, title, scenes, status, attempts, videoUrl, durationMs, videoBytes, error,
                    estimatedSeconds, queuedAt, renderedAt, createdAt, updatedAt, version, tags, ups, batchId, topic, replay, character, outro, onBlog,
                    playlist);
        }
    }

    /** 회원이 SNS에 직접 올린 뒤 붙여 넣은 영상 주소 (#238) */
    public record Upload(UploadLink.Platform platform, String url, Instant uploadedAt) {}

    public record UploadBody(String url) {}

    /** @param workerSeenAt 렌더 워커가 마지막으로 일을 받으러 온 시각. 없으면 아직 한 번도 오지 않았거나 오래 전 */
    public record Overview(List<Item> items, Instant workerSeenAt, int rendersToday, int dailyRenders) {}

    /**
     * @param batchId·topic 주제 묶음(#276)에서 만들 때만. 근거 확인은 ShortsBatches가 한다
     * @param sourceText MCP로 받은 맥락 근거(선택). 양산형 검사가 원문 복사를 비교한다
     * @param character 연기할 캐릭터(선택, #304 ShortsScript.CHARACTERS). 비우면 워커 기본(로그냥)
     * @param outro 영상 끝 3초 블로그 광고를 붙일지(선택). 비우면 붙인다
     */
    public record Draft(Long postId, String title, List<Scene> scenes, Long batchId, Integer topic, String sourceText, String character,
                        Boolean outro) {
        public Draft(Long postId, String title, List<Scene> scenes, Long batchId, Integer topic, String sourceText, String character) {
            this(postId, title, scenes, batchId, topic, sourceText, character, null);
        }

        public Draft(Long postId, String title, List<Scene> scenes, Long batchId, Integer topic, String sourceText) {
            this(postId, title, scenes, batchId, topic, sourceText, null, null);
        }

        public Draft(Long postId, String title, List<Scene> scenes) {
            this(postId, title, scenes, null, null, null, null, null);
        }
    }

    /** null인 칸은 그대로 둔다. character를 빈 문자열로 주면 워커 기본 캐릭터로 돌린다. outro는 끝 블로그 광고 켜고 끄기 */
    public record Edit(String title, List<Scene> scenes, Integer version, String character, Boolean outro) {
        public Edit(String title, List<Scene> scenes, Integer version, String character) {
            this(title, scenes, version, character, null);
        }

        public Edit(String title, List<Scene> scenes, Integer version) {
            this(title, scenes, version, null, null);
        }
    }

    static final String WORKER_SEEN_KEY = "shorts:worker:seen";
    static final int LIST_MAX = 100;
    private static final TypeReference<List<Scene>> SCENES = new TypeReference<>() {};
    private static final String SELECT = """
            SELECT s.*, p.title AS post_title, m.handle AS post_handle
            FROM shorts s
            LEFT JOIN post p ON p.id = s.post_id AND p.deleted_at IS NULL
            LEFT JOIN member m ON m.id = p.author_id
            """;
    /** 대본을 고치면 대기·완성 상태를 비우고 대본 상태로 돌린다 */
    private static final String RESET_TO_DRAFT = """
            UPDATE shorts SET title = ?, scenes = ?::jsonb, mascot = ?, outro = ?, status = 'DRAFT', attempts = 0, claim_token = NULL, queued_at = NULL,
                   claimed_at = NULL, video_key = NULL, video_bytes = NULL, duration_ms = NULL, rendered_at = NULL, error = NULL,
                   version = version + 1, updated_at = ?
            WHERE id = ?
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
        List<Item> items = enrich(jdbc.query(SELECT + " WHERE s.member_id = ? ORDER BY s.updated_at DESC, s.id DESC LIMIT " + LIST_MAX,
                this::map, memberId));
        return new Overview(items, workerSeenAt().orElse(null), rendersToday(memberId), props.dailyRenders());
    }

    public Item get(long memberId, long id) {
        requireEnabled();
        return find(memberId, id).orElseThrow(NotFoundException::new);
    }

    public Item create(long memberId, Draft draft) {
        return create(memberId, draft, null);
    }

    /**
     * 매매 재현 쇼츠 (#265). 고른 체결과 차트 구간을 대본과 함께 남긴다. 체결을 고르고 구간을 정하는 일은 매매 일지(TradeReplays)가 한다.
     * 대본을 고쳐도 체결·구간은 그대로다(다른 체결로 만들려면 새로 만든다).
     */
    public Item createReplay(long memberId, Draft draft, TradeReplay replay) {
        return create(memberId, draft, Objects.requireNonNull(replay));
    }

    private Item create(long memberId, Draft draft, TradeReplay replay) {
        requireEnabled();
        String title = ShortsScript.cleanTitle(draft.title());
        List<Scene> scenes = scenes(draft.scenes());
        String source = draft.sourceText() == null ? "" : TextCleaner.cleanMultiline(draft.sourceText());
        String character = ShortsScript.cleanCharacter(draft.character());
        List<FieldErrorItem> errors = new ArrayList<>(ShortsScript.checkSave(title, scenes));
        errors.addAll(imageErrors(scenes));
        errors.addAll(ShortsScript.checkCharacter(character));
        if (source.length() > ShortsBatches.SOURCE_MAX) {
            errors.add(new FieldErrorItem("sourceText", "TOO_LONG", "맥락 근거는 " + ShortsBatches.SOURCE_MAX + "자까지예요."));
        }
        reject(errors);
        if (draft.postId() != null) ownPost(memberId, draft.postId());
        Instant now = Times.now(clock);
        Long id = jdbc.queryForObject("""
                INSERT INTO shorts (member_id, post_id, title, scenes, batch_id, topic, source_text, replay, mascot, outro, created_at, updated_at)
                VALUES (?, ?, ?, ?::jsonb, ?, ?, ?, ?::jsonb, ?, ?, ?, ?) RETURNING id
                """, Long.class, memberId, draft.postId(), title, json.writeValueAsString(scenes), draft.batchId(), draft.topic(), source,
                replay == null ? null : json.writeValueAsString(replay), character, draft.outro() == null || draft.outro(),
                Timestamp.from(now), Timestamp.from(now));
        return get(memberId, id);
    }

    /**
     * 대본을 고친다. 대기 중이면 대기에서 빼고, 완성된 영상이 있으면 지운다(대본과 영상이 어긋나지 않게).
     * 워커가 만드는 중이면 고칠 수 없다. version이 오면 그 사이 다른 곳(AI·다른 창)에서 고쳤는지 확인한다.
     */
    public Item update(long memberId, long id, Edit edit) {
        requireEnabled();
        Optional<String> oldVideo = tx.execute(s -> {
            Item cur = lock(memberId, id);
            if (cur.status() == Status.RENDERING) throw ApiException.conflict("SHORTS_RENDERING", "영상을 만드는 중이에요. 다 만들어진 뒤에 고쳐 주세요.");
            if (edit.version() != null && edit.version() != cur.version()) {
                throw ApiException.conflict("VERSION_CONFLICT", "다른 곳에서 먼저 고쳤어요. 새로 불러온 뒤 다시 고쳐 주세요.");
            }
            String title = edit.title() == null ? cur.title() : ShortsScript.cleanTitle(edit.title());
            List<Scene> scenes = edit.scenes() == null ? cur.scenes() : scenes(edit.scenes());
            String character = edit.character() == null ? cur.character() : ShortsScript.cleanCharacter(edit.character());
            boolean outro = edit.outro() == null ? cur.outro() : edit.outro();
            List<FieldErrorItem> errors = new ArrayList<>(ShortsScript.checkSave(title, scenes));
            errors.addAll(imageErrors(scenes));
            errors.addAll(ShortsScript.checkCharacter(character));
            reject(errors);
            Optional<String> video = videoKey(id);
            jdbc.update(RESET_TO_DRAFT, title, json.writeValueAsString(scenes), character, outro, Timestamp.from(Times.now(clock)), id);
            return video;
        });
        oldVideo.ifPresent(storage::delete);
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

    /** 회원 대시보드(#239)의 쇼츠 칸. recent는 최근에 올린 영상 주소 */
    public record Summary(long scripts, long ready, Map<UploadLink.Platform, Long> uploads, List<UploadLine> recent) {}

    public record UploadLine(long shortsId, String title, UploadLink.Platform platform, String url, Instant uploadedAt) {}

    static final int RECENT_UPLOADS = 5;

    /** 쇼츠가 꺼져 있으면 비어 있다(화면이 칸을 숨긴다) */
    public Optional<Summary> summary(long memberId) {
        if (!props.enabled()) return Optional.empty();
        long[] counts = jdbc.queryForObject("""
                SELECT count(*), count(*) FILTER (WHERE status = 'READY') FROM shorts WHERE member_id = ?
                """, (rs, i) -> new long[] {rs.getLong(1), rs.getLong(2)}, memberId);
        Map<UploadLink.Platform, Long> uploads = new java.util.EnumMap<>(UploadLink.Platform.class);
        for (UploadLink.Platform p : UploadLink.Platform.values()) uploads.put(p, 0L);
        jdbc.query("""
                SELECT u.platform, count(*) FROM shorts_upload u JOIN shorts s ON s.id = u.shorts_id
                WHERE s.member_id = ? GROUP BY u.platform
                """, (RowCallbackHandler) rs -> uploads.put(UploadLink.Platform.valueOf(rs.getString(1)), rs.getLong(2)), memberId);
        List<UploadLine> recent = jdbc.query("""
                SELECT s.id, s.title, u.platform, u.url, u.uploaded_at FROM shorts_upload u JOIN shorts s ON s.id = u.shorts_id
                WHERE s.member_id = ? ORDER BY u.uploaded_at DESC, s.id DESC LIMIT ?
                """, (rs, i) -> new UploadLine(rs.getLong(1), rs.getString(2), UploadLink.Platform.valueOf(rs.getString(3)),
                rs.getString(4), rs.getTimestamp(5).toInstant()), memberId, RECENT_UPLOADS);
        return Optional.of(new Summary(counts[0], counts[1], uploads, recent));
    }

    /**
     * 직접 올린 영상 주소를 남긴다 (#238). 완성된 영상이 있을 때만, 플랫폼마다 하나(다시 붙여 넣으면 바꾼다).
     * devlog는 SNS에 아무것도 보내지 않는다. 주소가 그 플랫폼 도메인의 https 주소인지만 본다.
     */
    public Item recordUpload(long memberId, long id, String platformRaw, UploadBody body) {
        requireEnabled();
        UploadLink.Platform platform = UploadLink.Platform.parse(platformRaw).orElseThrow(NotFoundException::new);
        String url = UploadLink.clean(platform, body == null ? null : body.url()).orElseThrow(() -> ApiException.validation(List.of(
                new FieldErrorItem("url", "INVALID", "올린 영상의 https 주소를 붙여 넣어 주세요. 고른 플랫폼의 주소여야 해요."))));
        tx.executeWithoutResult(s -> {
            Item cur = lock(memberId, id);
            if (cur.status() != Status.READY) throw ApiException.conflict("SHORTS_NOT_READY", "영상을 다 만든 뒤에 올린 주소를 남길 수 있어요.");
            jdbc.update("""
                    INSERT INTO shorts_upload (shorts_id, platform, url, uploaded_at) VALUES (?, ?, ?, ?)
                    ON CONFLICT (shorts_id, platform) DO UPDATE SET url = EXCLUDED.url, uploaded_at = EXCLUDED.uploaded_at
                    """, id, platform.name(), url, Timestamp.from(Times.now(clock)));
        });
        return get(memberId, id);
    }

    public record BlogBody(Boolean visible) {}

    /** 재생목록 묶음. label은 YouTube에 만들 재생목록 이름에 쓴다 */
    public enum Playlist {
        DEPLOY("배포"), DB("DB"), SECURITY("보안"), PERFORMANCE("성능"), AI("AI협업");

        public final String label;

        Playlist(String label) {
            this.label = label;
        }

        /** 이름(DEPLOY)이나 한글 이름(배포) 모두 받는다. 대소문자는 가리지 않는다 */
        public static Optional<Playlist> parse(String raw) {
            if (raw == null) return Optional.empty();
            String v = raw.strip();
            for (Playlist p : values()) if (p.name().equalsIgnoreCase(v) || p.label.equalsIgnoreCase(v)) return Optional.of(p);
            return Optional.empty();
        }
    }

    public record PlaylistBody(String playlist) {}

    /**
     * 남긴 영상 주소를 블로그·글 화면에 보일지 (spec 103, #399). 기본은 보인다. 대본이 아니라서 버전을 올리지 않는다.
     */
    public Item setOnBlog(long memberId, long id, BlogBody body) {
        requireEnabled();
        if (body == null || body.visible() == null) {
            throw ApiException.validation(List.of(new FieldErrorItem("visible", "REQUIRED", "블로그에 보일지 골라 주세요.")));
        }
        tx.executeWithoutResult(s -> {
            lock(memberId, id);
            jdbc.update("UPDATE shorts SET on_blog = ? WHERE id = ?", body.visible(), id);
        });
        return get(memberId, id);
    }

    /**
     * 쇼츠를 넣을 재생목록 묶음 (2026-10-21 블로그 주인 결정: 배포·DB·보안·성능·AI협업 재생목록으로 몰아보게).
     * 재생목록은 회원이 YouTube에서 직접 만들고 넣는다. 대본이 아니라서 버전을 올리지 않고 만든 영상도 그대로 둔다. 빈 값이면 고른 것을 지운다.
     */
    public Item setPlaylist(long memberId, long id, PlaylistBody body) {
        requireEnabled();
        String raw = body == null || body.playlist() == null ? "" : body.playlist().strip();
        Playlist playlist = raw.isEmpty() ? null : Playlist.parse(raw).orElseThrow(() -> ApiException.validation(List.of(
                new FieldErrorItem("playlist", "INVALID", "재생목록은 배포·DB·보안·성능·AI협업 가운데 하나를 골라 주세요."))));
        tx.executeWithoutResult(s -> {
            lock(memberId, id);
            jdbc.update("UPDATE shorts SET playlist = ? WHERE id = ?", playlist == null ? null : playlist.name(), id);
        });
        return get(memberId, id);
    }

    public Item deleteUpload(long memberId, long id, String platformRaw) {
        requireEnabled();
        UploadLink.Platform platform = UploadLink.Platform.parse(platformRaw).orElseThrow(NotFoundException::new);
        tx.executeWithoutResult(s -> {
            lock(memberId, id);
            jdbc.update("DELETE FROM shorts_upload WHERE shorts_id = ? AND platform = ?", id, platform.name());
        });
        return get(memberId, id);
    }

    public void delete(long memberId, long id) {
        requireEnabled();
        Optional<String> video = tx.execute(s -> {
            lock(memberId, id);
            Optional<String> key = videoKey(id);
            jdbc.update("DELETE FROM shorts WHERE id = ?", id);
            return key;
        });
        video.ifPresent(storage::delete);
    }

    /** 잠근 쇼츠의 영상 파일 키. 아직 영상이 없으면 비어 있다 */
    private Optional<String> videoKey(long id) {
        return Optional.ofNullable(jdbc.queryForObject("SELECT video_key FROM shorts WHERE id = ?", String.class, id));
    }

    /**
     * 탈퇴 정리 (FR-001): 행을 지우고, 영상 파일은 정리 트랜잭션이 커밋된 뒤에 지운다.
     * 뒤 단계가 실패해 롤백되면 행과 파일이 함께 남아 다음 정리 때 다시 한다(롤백된 행이 지운 파일을 가리키지 않게).
     */
    public void purge(long memberId) {
        List<String> keys = jdbc.query("SELECT video_key FROM shorts WHERE member_id = ? AND video_key IS NOT NULL", (rs, i) -> rs.getString(1), memberId);
        jdbc.update("DELETE FROM shorts WHERE member_id = ?", memberId);
        jdbc.update("DELETE FROM shorts_batch WHERE member_id = ?", memberId);
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

    /** 근거는 바탕 글 본문과 MCP로 받은 맥락(#276)을 함께 본다. 같은 묶음의 다른 편은 따로 표시해 겹침을 더 낮은 기준으로 본다 */
    OriginalityCheck.Report originality(long memberId, Item item) {
        String post = item.postId() == null ? null : postBody(memberId, item.postId());
        String context = jdbc.queryForObject("SELECT source_text FROM shorts WHERE id = ?", String.class, item.id());
        String source = source(post, context);
        List<OriginalityCheck.OtherScript> others = new ArrayList<>();
        jdbc.query("SELECT id, title, scenes::text, batch_id FROM shorts WHERE member_id = ? AND id <> ? ORDER BY updated_at DESC LIMIT " + LIST_MAX,
                rs -> {
                    long batch = rs.getLong(4);
                    boolean sibling = !rs.wasNull() && item.batchId() != null && batch == item.batchId();
                    others.add(new OriginalityCheck.OtherScript(rs.getLong(1), rs.getString(2), parse(rs.getString(3)), sibling));
                },
                memberId, item.id());
        return OriginalityCheck.check(item.scenes(), source, others);
    }

    private String postBody(long memberId, long postId) {
        return jdbc.query("SELECT content_md FROM post WHERE id = ? AND author_id = ? AND deleted_at IS NULL",
                rs -> rs.next() ? rs.getString(1) : null, postId, memberId);
    }

    /** 근거가 하나도 없으면 null */
    private static String source(String post, String context) {
        boolean noContext = context == null || context.isEmpty();
        if (post == null && noContext) return null;
        return String.join("\n\n", Objects.toString(post, ""), Objects.toString(context, ""));
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
        return enrich(jdbc.query(SELECT + " WHERE s.id = ? AND s.member_id = ?", this::map, id, memberId)).stream().findFirst();
    }

    /** 바탕 글의 태그(올리기 안내의 해시태그)와 직접 올린 기록을 붙인다. 목록은 많아야 LIST_MAX개라 두 번에 나눠 읽는다 */
    private List<Item> enrich(List<Item> items) {
        if (items.isEmpty()) return items;
        Map<Long, List<String>> tags = new HashMap<>();
        List<Long> postIds = items.stream().map(Item::postId).filter(Objects::nonNull).distinct().toList();
        if (!postIds.isEmpty()) {
            jdbc.query("SELECT pt.post_id, t.name FROM post_tag pt JOIN tag t ON t.id = pt.tag_id WHERE pt.post_id = ANY (?) ORDER BY pt.post_id, pt.position",
                    (RowCallbackHandler) rs -> tags.computeIfAbsent(rs.getLong(1), k -> new ArrayList<>()).add(rs.getString(2)),
                    (Object) postIds.toArray(Long[]::new));
        }
        Map<Long, List<Upload>> uploads = new HashMap<>();
        List<Long> ids = items.stream().map(Item::id).toList();
        jdbc.query("SELECT shorts_id, platform, url, uploaded_at FROM shorts_upload WHERE shorts_id = ANY (?) ORDER BY platform",
                (RowCallbackHandler) rs -> uploads.computeIfAbsent(rs.getLong(1), k -> new ArrayList<>()).add(new Upload(
                        UploadLink.Platform.valueOf(rs.getString(2)), rs.getString(3), rs.getTimestamp(4).toInstant())),
                (Object) ids.toArray(Long[]::new));
        return items.stream().map(it -> it.with(
                it.postId() == null ? List.of() : List.copyOf(tags.getOrDefault(it.postId(), List.of())),
                List.copyOf(uploads.getOrDefault(it.id(), List.of())))).toList();
    }

    private Item lock(long memberId, long id) {
        List<Long> locked = jdbc.query("SELECT id FROM shorts WHERE id = ? AND member_id = ? FOR UPDATE",
                (rs, i) -> rs.getLong(1), id, memberId);
        if (locked.isEmpty()) throw new NotFoundException();
        return find(memberId, id).orElseThrow(NotFoundException::new);
    }

    private void ownPost(long memberId, long postId) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM post WHERE id = ? AND author_id = ? AND deleted_at IS NULL",
                Integer.class, postId, memberId);
        if (n == null || n == 0) throw ApiException.badRequest("POST_NOT_FOUND", "내 글에서만 쇼츠를 만들 수 있어요. 글 번호를 확인해 주세요.");
    }

    private static List<Scene> scenes(List<Scene> raw) {
        if (raw == null) return List.of();
        return raw.stream().map(s -> s == null ? new Scene("", "", null) : new Scene(s.caption(), s.narration(), s.code(), s.image(), s.visual(), s.effects(), s.diagram())).toList();
    }

    /**
     * 장면 그림은 렌더 워커가 내려받으므로 우리 그림만 받는다: devlog에 올린 그림 주소이거나 "/"로 시작하는 사이트 경로.
     * 바깥 주소를 받으면 워커가 아무 곳에나 요청을 보내게 된다.
     */
    private List<FieldErrorItem> imageErrors(List<Scene> scenes) {
        List<FieldErrorItem> errors = new ArrayList<>();
        for (int i = 0; i < scenes.size(); i++) {
            String image = scenes.get(i).image();
            if (image == null) continue;
            boolean sitePath = image.startsWith("/") && !image.startsWith("//") && image.chars().noneMatch(c -> Character.isWhitespace(c) || c == '\\');
            if (image.length() > ShortsScript.IMAGE_MAX || !(sitePath || urls.keyOf(image) != null)) {
                errors.add(new FieldErrorItem("scenes[" + i + "].image", "INVALID",
                        (i + 1) + "번 장면 그림은 devlog에 올린 그림 주소나 /로 시작하는 사이트 경로만 쓸 수 있어요."));
            }
        }
        return errors;
    }

    private static void reject(List<FieldErrorItem> errors) {
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
                rs.getInt("version"), List.of(), List.of(), nullableLong(rs, "batch_id"), nullableInt(rs, "topic"), replay(rs.getString("replay")),
                rs.getString("mascot"), rs.getBoolean("outro"), rs.getBoolean("on_blog"), Playlist.parse(rs.getString("playlist")).orElse(null));
    }

    private TradeReplay replay(String raw) {
        return raw == null ? null : json.readValue(raw, TradeReplay.class);
    }

    private static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long v = rs.getLong(column);
        return rs.wasNull() ? null : v;
    }

    private static Integer nullableInt(ResultSet rs, String column) throws SQLException {
        int v = rs.getInt(column);
        return rs.wasNull() ? null : v;
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp t = rs.getTimestamp(column);
        return t == null ? null : t.toInstant();
    }
}
