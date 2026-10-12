package com.team.blog.media;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.team.blog.account.domain.Visibility;
import com.team.blog.media.storage.ObjectStorage;
import com.team.blog.post.access.PostAccessPolicy;
import com.team.blog.post.access.ReadablePost;
import com.team.blog.post.access.Viewer;
import com.team.blog.post.domain.PostStatus;
import com.team.blog.shared.config.BlogProperties;
import com.team.blog.shared.error.ApiException;
import com.team.blog.shared.error.FieldErrorItem;
import com.team.blog.shared.error.NotFoundException;
import com.team.blog.shared.jdbc.Columns;
import com.team.blog.shared.time.Times;
import com.team.blog.shared.web.RateLimiter;

/**
 * 글 첨부파일 (022). 사진과 같은 리소스 테이블에 kind=FILE로 두고 원래 이름은 resource_file에, 글 연결은 post_file에 둔다.
 * <p>
 * 올리기는 사진(009)과 같이 서버를 거친다(학교 저장소가 http만 열려 있어 https 화면에서 바로 올릴 수 없다). 규칙은 같다:
 * 사진과 합쳐 1분 20건, 저장 공간 1GB 안, 형식은 서버가 내용으로 다시 검사, 저장 경로는 서버가 정한 추측할 수 없는 키.
 * <p>
 * 연결: 편집 화면에서 첨부 목록을 바꿀 때마다 바로 맞춘다(FR-007). 본문과 달리 첨부는 작업본이 따로 없어 발행한 글이면
 * 독자에게도 바로 바뀐다. 대신 올린 파일이 연결되지 않은 채 하루 뒤 정리되는 일이 없다.
 * <p>
 * 내려받기: 첨부는 공개 주소가 없다. 글 상세와 같은 읽기 판정(docs/42)을 통과하면 앱이 저장소에서 읽어 항상 내려받기로 준다.
 */
@Service
public class PostFiles {
    private static final Logger log = LoggerFactory.getLogger(PostFiles.class);
    public static final String KEY_PREFIX = "files/";
    public static final int MAX_BYTES = 20 * 1024 * 1024;
    public static final int MAX_PER_POST = 20;
    static final int MAX_NAME = 255;
    static final String TYPE_MESSAGE = "pdf, zip, txt, md, csv, docx, xlsx, pptx 20MB 이하만 첨부할 수 있어요.";
    private static final String FILE_IDS = "fileIds";
    private static final String INSERT_FILE_SQL = """
            WITH r AS (
                INSERT INTO resource (uploader_id, storage_key, content_type, size_bytes, kind, created_at)
                VALUES (?, ?, ?, ?, 'FILE', ?) RETURNING id)
            INSERT INTO resource_file (resource_id, original_name) SELECT id, ? FROM r RETURNING resource_id
            """;
    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("yyyy/MM").withZone(ZoneOffset.UTC);

    private final JdbcTemplate jdbc;
    private final ObjectStorage storage;
    private final RateLimiter rateLimiter;
    private final TransactionTemplate tx;
    private final PostImages images;
    private final PostAccessPolicy policy;
    private final BlogProperties.Image rules;
    private final Clock clock;

    public PostFiles(JdbcTemplate jdbc, ObjectStorage storage, RateLimiter rateLimiter, TransactionTemplate tx, PostImages images,
                     PostAccessPolicy policy, BlogProperties props, Clock clock) {
        this.jdbc = jdbc;
        this.storage = storage;
        this.rateLimiter = rateLimiter;
        this.tx = tx;
        this.images = images;
        this.policy = policy;
        this.rules = props.image();
        this.clock = clock;
    }

    public record Attachment(long id, String name, long sizeBytes, String contentType) {}

    /** 배열 필드라 내용으로 비교하고, 문자열에는 크기만 남긴다 */
    public record Download(String name, String contentType, byte[] data) {
        @Override public boolean equals(Object o) {
            return o instanceof Download(String n, String type, byte[] bytes) && name.equals(n) && contentType.equals(type) && Arrays.equals(data, bytes);
        }
        @Override public int hashCode() { return 31 * Objects.hash(name, contentType) + Arrays.hashCode(data); }
        @Override public String toString() { return "Download[" + name + ", " + contentType + ", " + data.length + " bytes]"; }
    }

    /** 올리기 (US1, FR-001~FR-006). 연결은 하지 않는다: 첨부 목록을 저장하거나 발행할 때 연결된다. */
    public Attachment upload(long memberId, String rawName, byte[] data) {
        // 사진과 합쳐 센다. 실패한 업로드도 1건이다 (FR-006)
        rateLimiter.check("upload:min:" + memberId, rules.perMinute(), Duration.ofMinutes(1));
        String name = cleanName(rawName);
        if (data.length > MAX_BYTES) throw new ApiException(HttpStatus.CONTENT_TOO_LARGE, "FILE_TOO_LARGE", TYPE_MESSAGE);
        if (data.length == 0) throw ApiException.badRequest("FILE_EMPTY", "빈 파일은 첨부할 수 없어요.");
        String ext = FileInspector.extension(name).orElse(null);
        switch (FileInspector.check(ext, data)) {
            case IMAGE -> throw ApiException.badRequest("FILE_IS_IMAGE", "사진은 본문에 넣어 주세요.");
            case NOT_ALLOWED -> throw ApiException.badRequest("FILE_TYPE", TYPE_MESSAGE);
            case MISMATCH -> throw ApiException.badRequest("FILE_TYPE_MISMATCH", "파일 내용이 확장자(." + ext + ")와 맞지 않아요.");
            case OK -> {
                // 통과: 아래에서 저장한다
            }
        }
        String contentType = FileInspector.TYPES.get(ext);
        Instant now = Times.now(clock);
        String key = KEY_PREFIX + MONTH.format(now) + "/" + UUID.randomUUID() + "." + ext;
        long quota = rules.quota().toBytes();

        Long id = tx.execute(s -> {
            // 사진과 같은 공간(1GB)을 쓴다. 회원 행을 잠가 동시에 올려도 합계가 한도를 넘지 않는다
            jdbc.queryForObject("SELECT id FROM member WHERE id = ? FOR UPDATE", Long.class, memberId);
            if (images.usedBytes(memberId) + data.length > quota) {
                throw new ApiException(HttpStatus.CONTENT_TOO_LARGE, "STORAGE_QUOTA",
                        "저장 공간(" + PostImages.human(quota) + ")을 다 썼어요. 쓰지 않는 사진·파일이 든 글을 지우면 7일 뒤 공간이 돌아와요.");
            }
            return jdbc.queryForObject(INSERT_FILE_SQL, Long.class, memberId, key, contentType, data.length, Timestamp.from(now), name);
        });
        try {
            storage.put(key, data, contentType);
        } catch (RuntimeException e) {
            try {
                jdbc.update("DELETE FROM resource WHERE id = ?", id);
            } catch (RuntimeException cleanup) {
                // 남은 행은 정리 작업이 지운다. 원래 오류를 덮지 않는다
                e.addSuppressed(cleanup);
            }
            images.deleteQuietly(key);
            log.warn("첨부파일을 저장소에 올리지 못했습니다: {}", e.getMessage());
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "STORAGE_UNAVAILABLE", "파일을 올리지 못했어요. 잠시 뒤 다시 시도해 주세요.");
        }
        return new Attachment(id, name, data.length, contentType);
    }

    /** 글의 첨부 목록(순서대로). 볼 수 없는 글이면 404 (FR-011). 작성자는 임시글·숨긴 글도 본다. */
    public List<Attachment> list(long postId, Viewer viewer) {
        requireReadable(postId, viewer);
        return attachments(postId);
    }

    List<Attachment> attachments(long postId) {
        return jdbc.query("""
                SELECT r.id, f.original_name, r.size_bytes, r.content_type
                FROM post_file pf JOIN resource r ON r.id = pf.resource_id JOIN resource_file f ON f.resource_id = r.id
                WHERE pf.post_id = ? ORDER BY pf.position
                """, (rs, i) -> new Attachment(rs.getLong(1), rs.getString(2), rs.getLong(3), rs.getString(4)), postId);
    }

    /**
     * 글의 첨부 목록을 바로 맞춘다 (FR-007). 첨부를 더하거나 빼거나 순서를 바꿀 때마다 편집 화면이 부른다.
     * 작성자가 아니거나 지운 글이면 404.
     */
    public List<Attachment> set(long memberId, long postId, List<Long> fileIds) {
        if (fileIds == null) {
            throw ApiException.validation(List.of(new FieldErrorItem(FILE_IDS, "INVALID_FILES", "첨부 목록을 확인해 주세요.")));
        }
        return tx.execute(s -> {
            List<Long> post = Columns.longs(jdbc, "SELECT id FROM post WHERE id = ? AND author_id = ? AND deleted_at IS NULL FOR UPDATE",
                    postId, memberId);
            if (post.isEmpty()) throw new NotFoundException();
            List<FieldErrorItem> errors = new ArrayList<>();
            validate(memberId, fileIds, errors);
            if (!errors.isEmpty()) throw ApiException.validation(errors);
            sync(postId, memberId, fileIds);
            return attachments(postId);
        });
    }

    /** 개수·중복·주인 확인 (FR-008·FR-010). 발행 검증도 같은 규칙을 쓴다. */
    void validate(long authorId, List<Long> fileIds, List<FieldErrorItem> errors) {
        if (fileIds == null) return;
        if (fileIds.size() > MAX_PER_POST) {
            errors.add(new FieldErrorItem(FILE_IDS, "TOO_MANY_FILES", "첨부는 " + MAX_PER_POST + "개까지 할 수 있어요."));
            return;
        }
        if (fileIds.stream().anyMatch(Objects::isNull) || new HashSet<>(fileIds).size() != fileIds.size()) {
            errors.add(new FieldErrorItem(FILE_IDS, "INVALID_FILES", "첨부 목록을 확인해 주세요."));
            return;
        }
        if (fileIds.isEmpty()) return;
        Long owned = jdbc.queryForObject("""
                SELECT count(*) FROM resource r JOIN resource_file f ON f.resource_id = r.id
                WHERE r.uploader_id = ? AND r.id = ANY (?)
                """, Long.class, authorId, fileIds.toArray(Long[]::new));
        if (owned == null || owned != fileIds.size()) {
            errors.add(new FieldErrorItem(FILE_IDS, "FILE_NOT_OWNED", "직접 올린 파일만 첨부할 수 있어요."));
        }
    }

    /** post_file을 목록 순서로 맞추고, 빠졌고 다른 글에서도 쓰지 않는 파일은 연결 해제 시각을 남긴다 (FR-007). */
    void sync(long postId, long authorId, List<Long> fileIds) {
        Long[] ids = fileIds.toArray(Long[]::new);
        jdbc.update("""
                UPDATE resource r SET detached_at = now()
                WHERE r.id IN (SELECT resource_id FROM post_file WHERE post_id = ?)
                  AND NOT (r.id = ANY (?))
                  AND NOT EXISTS (SELECT 1 FROM post_file o WHERE o.resource_id = r.id AND o.post_id <> ?)
                """, postId, ids, postId);
        jdbc.update("DELETE FROM post_file WHERE post_id = ?", postId);
        if (ids.length == 0) return;
        jdbc.update("""
                INSERT INTO post_file (post_id, resource_id, position)
                SELECT ?, f.resource_id, (k.ord - 1)::smallint
                FROM unnest(?::bigint[]) WITH ORDINALITY AS k(id, ord)
                JOIN resource r ON r.id = k.id AND r.uploader_id = ?
                JOIN resource_file f ON f.resource_id = r.id
                """, postId, ids, authorId);
        jdbc.update("UPDATE resource SET detached_at = NULL WHERE id = ANY (?) AND uploader_id = ?", ids, authorId);
    }

    /** 내려받기 (US2, FR-011·FR-012). 그 글에 연결된 첨부만, 글을 볼 수 있을 때만. */
    public Download download(long postId, long fileId, Viewer viewer) {
        requireReadable(postId, viewer);
        List<String[]> rows = jdbc.query("""
                SELECT r.storage_key, f.original_name, r.content_type
                FROM post_file pf JOIN resource r ON r.id = pf.resource_id JOIN resource_file f ON f.resource_id = r.id
                WHERE pf.post_id = ? AND pf.resource_id = ?
                """, (rs, i) -> new String[] {rs.getString(1), rs.getString(2), rs.getString(3)}, postId, fileId);
        if (rows.isEmpty()) throw new NotFoundException();
        String[] r = rows.getFirst();
        ObjectStorage.StoredObject o = storage.get(r[0]).orElseThrow(NotFoundException::new);
        return new Download(r[1], r[2], o.data());
    }

    private void requireReadable(long postId, Viewer viewer) {
        List<ReadablePost> rows = jdbc.query("""
                SELECT p.author_id, p.status, p.visibility, p.deleted_at IS NOT NULL, p.hidden_at IS NOT NULL,
                       m.withdrawn_at IS NOT NULL
                FROM post p JOIN member m ON m.id = p.author_id WHERE p.id = ?
                """, (rs, i) -> new ReadablePost(rs.getLong(1), PostStatus.valueOf(rs.getString(2)), Visibility.valueOf(rs.getString(3)),
                rs.getBoolean(4), rs.getBoolean(5), rs.getBoolean(6)), postId);
        if (rows.isEmpty()) throw new NotFoundException();
        ReadablePost p = rows.getFirst();
        // 작성자는 편집 화면에서 임시글·숨긴 글의 첨부도 본다. 그 밖에는 글 상세와 같은 판정이다
        boolean author = viewer.is(p.authorId()) && !p.deleted();
        if (!author && !policy.canRead(p, viewer)) throw new NotFoundException();
    }

    /**
     * 표시·내려받기 이름 (Edge Cases): 경로 부분과 제어 문자를 지우고 앞뒤 공백을 다듬는다. 저장 경로는 서버가 정하므로
     * 이름은 화면과 내려받기에만 쓰인다. 255자를 넘으면 확장자를 살려 앞부분을 줄인다.
     */
    static String cleanName(String raw) {
        String s = raw == null ? "" : raw;
        int slash = Math.max(s.lastIndexOf('/'), s.lastIndexOf('\\'));
        s = s.substring(slash + 1).replaceAll("[\\p{Cntrl}\\p{Cf}]", "").strip();
        while (s.startsWith(".")) s = s.substring(1);
        if (s.isEmpty()) throw ApiException.badRequest("FILE_NAME", "파일 이름을 확인해 주세요.");
        if (s.codePointCount(0, s.length()) > MAX_NAME) {
            String ext = FileInspector.extension(s).map(e -> "." + e).orElse("");
            int[] cps = s.substring(0, s.length() - ext.length()).codePoints().limit((long) MAX_NAME - ext.length()).toArray();
            s = new String(cps, 0, cps.length) + ext;
        }
        return s;
    }
}
