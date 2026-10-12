package com.team.blog.media;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.team.blog.media.storage.ObjectStorage;
import com.team.blog.shared.error.ApiException;
import com.team.blog.shared.jdbc.Columns;
import com.team.blog.shared.markdown.ImageUrls;
import com.team.blog.shared.time.Times;
import com.team.blog.shared.web.RateLimiter;

/**
 * 프로필 사진 (005 US2, docs/11 §4). 올리기와 연결을 나눈다: 올린 사진은 [저장]으로 연결해야 프로필이 된다.
 * 프로필 사진은 저장 키가 profiles/로 시작하는 256×256 사진 리소스다(목록용 작은 사진은 만들지 않는다).
 * 글 본문 사진 키(images/…)와 접두어가 달라 서로 섞어 쓸 수 없다.
 */
@Service
public class ProfileImages {
    private static final Logger log = LoggerFactory.getLogger(ProfileImages.class);
    public static final int SIZE = 256;
    public static final int MAX_BYTES = 1024 * 1024;
    static final String KEY_PREFIX = "profiles/";
    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("yyyy/MM").withZone(ZoneOffset.UTC);

    private final JdbcTemplate jdbc;
    private final ObjectStorage storage;
    private final ImageUrls urls;
    private final RateLimiter rateLimiter;
    private final Clock clock;

    public ProfileImages(JdbcTemplate jdbc, ObjectStorage storage, ImageUrls urls, RateLimiter rateLimiter, Clock clock) {
        this.jdbc = jdbc;
        this.storage = storage;
        this.urls = urls;
        this.rateLimiter = rateLimiter;
        this.clock = clock;
    }

    public record Uploaded(long id, String url) {}

    /**
     * 사진을 저장소에 올리고 리소스로 기록한다. 아직 프로필에 연결하지 않는다(24시간 안에 저장하지 않으면 정리된다).
     * 저장소에 먼저 쓰고 DB 기록이 실패하면 저장소에서 지운다(DB에는 없는 파일을 가리키는 행이 생기지 않게).
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public Uploaded upload(long memberId, byte[] data) {
        rateLimiter.check("upload:min:" + memberId, 20, java.time.Duration.ofMinutes(1));
        if (data.length > MAX_BYTES) throw tooLarge();
        ImageInspector.ImageInfo info = ImageInspector.inspect(data).orElseThrow(() -> invalid(
                "IMAGE_TYPE", "jpg·png·gif·webp 사진만 올릴 수 있어요."));
        if (info.width() != SIZE || info.height() != SIZE) {
            throw invalid("IMAGE_DIMENSION", "프로필 사진은 256×256 크기여야 해요.");
        }
        if (info.hasMetadata()) throw invalid("IMAGE_METADATA", "위치 같은 사진 정보를 지운 뒤 올려 주세요.");

        Instant now = Times.now(clock);
        String key = KEY_PREFIX + MONTH.format(now) + "/" + UUID.randomUUID() + "." + info.extension();
        storage.put(key, data, info.contentType());
        try {
            Long id = jdbc.queryForObject("""
                    WITH r AS (
                        INSERT INTO resource (uploader_id, storage_key, content_type, size_bytes, kind, created_at)
                        VALUES (?, ?, ?, ?, 'IMAGE', ?) RETURNING id)
                    INSERT INTO resource_image (resource_id, width, height) SELECT id, ?, ? FROM r RETURNING resource_id
                    """, Long.class, memberId, key, info.contentType(), data.length, java.sql.Timestamp.from(now),
                    info.width(), info.height());
            return new Uploaded(id, urls.urlOf(key));
        } catch (RuntimeException e) {
            deleteQuietly(key);
            throw e;
        }
    }

    /** 본인이 올린 프로필용 256×256 사진인지 (005 FR-014). 같은 요청의 다른 칸과 함께 검사하려고 따로 둔다. */
    @Transactional(readOnly = true)
    public boolean isAttachable(long memberId, long resourceId) {
        Integer n = jdbc.queryForObject("""
                SELECT count(*) FROM resource r JOIN resource_image ri ON ri.resource_id = r.id
                WHERE r.id = ? AND r.uploader_id = ? AND r.kind = 'IMAGE' AND r.storage_key LIKE 'profiles/%'
                  AND ri.width = ? AND ri.height = ?
                """, Integer.class, resourceId, memberId, SIZE, SIZE);
        return n != null && n > 0;
    }

    /**
     * 프로필 사진을 바꾼다. null이면 기본 이미지로 돌아간다. 이전 사진은 연결을 끊고 detached_at을 남겨 7일 뒤 정리된다.
     * 호출하는 쪽 트랜잭션 안에서 회원 행을 잠근 뒤 불러야 동시 저장에도 한 장만 연결된다 (FR-015).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void attach(long memberId, Long resourceId) {
        List<Long> current = Columns.longs(jdbc, "SELECT resource_id FROM member_profile_image WHERE member_id = ?", memberId);
        Long old = current.isEmpty() ? null : current.getFirst();
        if (java.util.Objects.equals(old, resourceId)) return;
        if (old != null) {
            jdbc.update("DELETE FROM member_profile_image WHERE member_id = ?", memberId);
            jdbc.update("UPDATE resource SET detached_at = ? WHERE id = ?", java.sql.Timestamp.from(Times.now(clock)), old);
        }
        if (resourceId != null) {
            jdbc.update("INSERT INTO member_profile_image (member_id, resource_id) VALUES (?, ?)", memberId, resourceId);
            jdbc.update("UPDATE resource SET detached_at = NULL WHERE id = ?", resourceId);
        }
    }

    @Transactional(readOnly = true)
    public Optional<String> currentUrl(long memberId) {
        return Columns.firstString(jdbc, """
                SELECT r.storage_key FROM member_profile_image p JOIN resource r ON r.id = p.resource_id WHERE p.member_id = ?
                """, memberId).map(urls::urlOf);
    }

    void deleteQuietly(String key) {
        try {
            storage.delete(key);
        } catch (RuntimeException e) {
            log.warn("저장소에서 사진을 지우지 못했습니다(나중에 직접 지워야 합니다): {} {}", key, e.getMessage());
        }
    }

    private static ApiException tooLarge() {
        return new ApiException(HttpStatus.CONTENT_TOO_LARGE, "IMAGE_TOO_LARGE", "프로필 사진은 1MB까지 올릴 수 있어요.");
    }

    private static ApiException invalid(String code, String message) {
        return ApiException.badRequest(code, message);
    }
}
