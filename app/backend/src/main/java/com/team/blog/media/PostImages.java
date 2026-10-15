package com.team.blog.media;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.team.blog.media.storage.ObjectStorage;
import com.team.blog.shared.config.BlogProperties;
import com.team.blog.shared.error.ApiException;
import com.team.blog.shared.markdown.ImageUrls;
import com.team.blog.shared.time.Times;
import com.team.blog.shared.web.RateLimiter;

/**
 * 본문 사진 올리기 (009, docs/23 §3·§4). 브라우저가 줄이고(긴 변 1920px) 사진 정보를 지운 원본과 가로 640px 썸네일을 함께 보낸다.
 * 서버는 파일 머리로 형식·크기·해상도를 다시 검사한다. 학교 저장소가 http만 열려 있어 https 화면에서 저장소로 바로 올릴 수
 * 없으므로 서버 경유로 받는다(FR-005의 대체 방식, 규칙은 같다).
 * <p>
 * 저장 공간(1GB)은 회원 행을 잠근 트랜잭션에서 "지금 사용량 + 이번 크기"로 검사하고 기록까지 넣는다. 동시에 여러 장을 올려도
 * 합계가 한도를 넘지 않는다. 파일은 기록이 확정된 뒤 올리고, 실패하면 기록을 지운다.
 */
@Service
public class PostImages {
    private static final Logger log = LoggerFactory.getLogger(PostImages.class);
    public static final int MAX_BYTES = 10 * 1024 * 1024;
    public static final int MAX_THUMB_BYTES = 1024 * 1024;
    public static final int MAX_EDGE = 1920;
    public static final int THUMB_WIDTH = 640;
    /** 해상도 폭탄 방지 (FR-006). 브라우저가 줄여 보내므로 정상 사진은 여기에 닿지 않는다. */
    public static final int MAX_PIXELS_EDGE = 10_000;
    static final int MAX_GIF_FRAMES = 300;
    static final ZoneId ZONE = ZoneId.of("Asia/Seoul");
    private static final String INSERT_IMAGE_SQL = """
            WITH r AS (
                INSERT INTO resource (uploader_id, storage_key, content_type, size_bytes, kind, created_at)
                VALUES (?, ?, ?, ?, 'IMAGE', ?) RETURNING id)
            INSERT INTO resource_image (resource_id, width, height, thumb_storage_key, thumb_size_bytes)
            SELECT id, ?, ?, ?, ? FROM r RETURNING resource_id
            """;
    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("yyyy/MM").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter DAY = DateTimeFormatter.BASIC_ISO_DATE;

    private final JdbcTemplate jdbc;
    private final ObjectStorage storage;
    private final ImageUrls urls;
    private final RateLimiter rateLimiter;
    private final TransactionTemplate tx;
    private final BlogProperties.Image rules;
    private final Clock clock;

    public PostImages(JdbcTemplate jdbc, ObjectStorage storage, ImageUrls urls, RateLimiter rateLimiter, TransactionTemplate tx,
                      BlogProperties props, Clock clock) {
        this.jdbc = jdbc;
        this.storage = storage;
        this.urls = urls;
        this.rateLimiter = rateLimiter;
        this.tx = tx;
        this.rules = props.image();
        this.clock = clock;
    }

    public record Uploaded(long id, String url, String thumbUrl, String contentType, int width, int height) {}

    /** @param todayCount 오늘(한국 0시 기준) 업로드 시도 수 */
    public record Usage(long usedBytes, long quotaBytes, long todayCount, int dailyLimit) {}

    public Uploaded upload(long memberId, byte[] image, byte[] thumb) {
        // 장수 제한은 검사보다 먼저 센다: 실패한 업로드도 1장이다 (FR-037)
        rateLimiter.check("upload:min:" + memberId, rules.perMinute(), Duration.ofMinutes(1));
        if (!rateLimiter.tryAcquire(dayKey(memberId), rules.dailyLimit(), Duration.ofHours(25))) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "IMAGE_DAILY_LIMIT",
                    "오늘은 사진을 " + rules.dailyLimit() + "장까지 올릴 수 있어요. 내일 다시 시도해 주세요.");
        }
        if (image.length > MAX_BYTES) throw new ApiException(HttpStatus.CONTENT_TOO_LARGE, "IMAGE_TOO_LARGE", "사진은 10MB까지 올릴 수 있어요.");
        if (thumb.length > MAX_THUMB_BYTES) throw invalid("THUMB_INVALID", "썸네일을 만들지 못했어요. 다른 사진으로 시도해 주세요.");

        ImageInspector.ImageInfo info = ImageInspector.inspect(image)
                .orElseThrow(() -> invalid("IMAGE_TYPE", "jpg·png·gif·webp 사진만 올릴 수 있어요."));
        checkImage(info, image);
        ImageInspector.ImageInfo t = ImageInspector.inspect(thumb)
                .filter(x -> !x.contentType().equals("image/gif") && x.width() <= THUMB_WIDTH && !x.hasMetadata())
                .orElseThrow(() -> invalid("THUMB_INVALID", "썸네일을 만들지 못했어요. 다른 사진으로 시도해 주세요."));

        Instant now = Times.now(clock);
        String base = "images/" + MONTH.format(now) + "/" + UUID.randomUUID();
        String key = base + "." + info.extension();
        String thumbKey = base + "_thumb." + t.extension();
        long size = (long) image.length + thumb.length;
        long quota = rules.quota().toBytes();

        Long id = tx.execute(s -> {
            jdbc.queryForObject("SELECT id FROM member WHERE id = ? FOR UPDATE", Long.class, memberId);
            if (usedBytes(memberId) + size > quota) {
                throw new ApiException(HttpStatus.CONTENT_TOO_LARGE, "STORAGE_QUOTA",
                        "사진 저장 공간(" + human(quota) + ")을 다 썼어요. 쓰지 않는 사진이 든 글을 지우면 7일 뒤 공간이 돌아와요.");
            }
            return jdbc.queryForObject(INSERT_IMAGE_SQL, Long.class, memberId, key, info.contentType(), image.length, Timestamp.from(now),
                    info.width(), info.height(), thumbKey, thumb.length);
        });
        try {
            storage.put(key, image, info.contentType());
            storage.put(thumbKey, thumb, t.contentType());
        } catch (RuntimeException e) {
            jdbc.update("DELETE FROM resource WHERE id = ?", id);
            deleteQuietly(key);
            deleteQuietly(thumbKey);
            log.warn("사진을 저장소에 올리지 못했습니다: {}", e.getMessage());
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "STORAGE_UNAVAILABLE", "사진을 올리지 못했어요. 잠시 뒤 다시 시도해 주세요.");
        }
        return new Uploaded(id, urls.urlOf(key), urls.urlOf(thumbKey), info.contentType(), info.width(), info.height());
    }

    /** GIF는 크기·장면 수를, 그 밖의 사진은 해상도와 남은 사진 정보를 검사한다 */
    private static void checkImage(ImageInspector.ImageInfo info, byte[] image) {
        if (info.contentType().equals("image/gif")) {
            if (info.width() > MAX_EDGE || info.height() > MAX_EDGE) {
                throw invalid("GIF_DIMENSION", "GIF는 가로·세로 1920px까지 올릴 수 있어요.");
            }
            int frames = ImageInspector.gifFrames(image, MAX_GIF_FRAMES);
            if (frames < 0) throw invalid("IMAGE_TYPE", "사진 파일이 손상됐어요.");
            if (frames > MAX_GIF_FRAMES) throw invalid("GIF_FRAMES", "GIF는 300장면까지 올릴 수 있어요.");
        } else {
            if (info.width() > MAX_PIXELS_EDGE || info.height() > MAX_PIXELS_EDGE) {
                throw invalid("IMAGE_DIMENSION", "사진 해상도가 너무 커요.");
            }
            // 브라우저가 다시 그려 보내면 사진 정보가 남지 않는다. 남아 있으면 우회한 업로드다
            if (info.hasMetadata()) throw invalid("IMAGE_METADATA", "위치 같은 사진 정보를 지운 뒤 올려 주세요.");
        }
    }

    public Usage usage(long memberId) {
        return new Usage(usedBytes(memberId), rules.quota().toBytes(), rateLimiter.count(dayKey(memberId)), rules.dailyLimit());
    }

    /** 원본 + 썸네일, 프로필 사진·정리 대기 사진 포함 (FR-035). 정리가 실제로 지울 때 줄어든다. */
    long usedBytes(long memberId) {
        Long used = jdbc.queryForObject("""
                SELECT COALESCE(sum(r.size_bytes + COALESCE(ri.thumb_size_bytes, 0)), 0)
                FROM resource r LEFT JOIN resource_image ri ON ri.resource_id = r.id
                WHERE r.uploader_id = ?
                """, Long.class, memberId);
        return used == null ? 0 : used;
    }

    void deleteQuietly(String key) {
        if (key == null) return;
        try {
            storage.delete(key);
        } catch (RuntimeException e) {
            log.warn("저장소에서 사진을 지우지 못했습니다(다음 정리 때 다시 시도하지 않는 파일): {} {}", key, e.getMessage());
        }
    }

    private String dayKey(long memberId) {
        return "upload:day:" + memberId + ":" + DAY.format(LocalDate.ofInstant(Times.now(clock), ZONE));
    }

    static String human(long bytes) {
        if (bytes % (1024L * 1024 * 1024) == 0) return bytes / (1024L * 1024 * 1024) + "GB";
        return bytes / (1024 * 1024) + "MB";
    }

    private static ApiException invalid(String code, String message) {
        return ApiException.badRequest(code, message);
    }
}
