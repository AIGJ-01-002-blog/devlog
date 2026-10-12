package com.team.blog.media;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import com.team.blog.media.storage.ObjectStorage;
import com.team.blog.shared.jdbc.Columns;
import com.team.blog.shared.scheduling.JobLock;
import com.team.blog.shared.time.Times;

/**
 * 본문 사진 정리 (009 FR-041, docs/04 §4-4). 매일 새벽, 쓰지 않는 사진의 원본·썸네일 파일을 먼저 지우고 기록을 지운다.
 * 저장소 삭제가 실패하면 그 행의 트랜잭션을 되돌려 기록이 남고 다음 정리 때 다시 시도한다.
 * <p>
 * "쓰지 않는다" = 어떤 발행 글에도 연결되지 않았고(post_image), 어느 글의 썸네일로도 고르지 않았고(047 post_thumbnail), 올린 사람의 글·작업본·블로그 소개(042) 원문 어디에도 키가 없음.
 * 원문까지 확인하므로 아직 발행하지 않은 임시글·수정 중인 작업본의 사진은 지워지지 않는다(연결은 발행 때만 만든다).
 * 그 위에 기간 조건: 올리고 한 번도 연결되지 않은 사진은 24시간, 연결이 끊긴 사진은 끊긴 지 7일.
 * <p>
 * 첨부파일(022 FR-014)도 같은 기간으로 지운다. 첨부는 편집 화면에서 바꿀 때마다 바로 연결되므로 post_file만 본다.
 */
@Component
public class PostImageCleanupJob {
    private static final Logger log = LoggerFactory.getLogger(PostImageCleanupJob.class);
    static final Duration UNUSED_TTL = Duration.ofHours(24);
    static final Duration DETACHED_TTL = Duration.ofDays(7);
    private static final int BATCH = 200;

    /** 행 하나를 잠그며 다시 확인한다. 다른 정리가 잡고 있으면 건너뛴다. */
    private static final String UNUSED = """
            ((r.detached_at IS NULL AND r.created_at < ?) OR r.detached_at < ?)
            AND ((r.storage_key LIKE 'images/%'
                  AND NOT EXISTS (SELECT 1 FROM post_image pi WHERE pi.resource_id = r.id)
                  AND NOT EXISTS (SELECT 1 FROM post_thumbnail pt WHERE pt.resource_id = r.id)
                  AND NOT EXISTS (SELECT 1 FROM post p WHERE p.author_id = r.uploader_id AND strpos(p.content_md, r.storage_key) > 0)
                  AND NOT EXISTS (SELECT 1 FROM post_draft d JOIN post p ON p.id = d.post_id
                                  WHERE p.author_id = r.uploader_id AND strpos(d.content_md, r.storage_key) > 0)
                  AND NOT EXISTS (SELECT 1 FROM member_about a WHERE a.member_id = r.uploader_id AND strpos(a.content_md, r.storage_key) > 0))
              OR (r.storage_key LIKE 'files/%'
                  AND NOT EXISTS (SELECT 1 FROM post_file pf WHERE pf.resource_id = r.id)))
            """;

    private final JdbcTemplate jdbc;
    private final ObjectStorage storage;
    private final TransactionTemplate tx;
    private final JobLock lock;
    private final Clock clock;

    public PostImageCleanupJob(JdbcTemplate jdbc, ObjectStorage storage, TransactionTemplate tx, JobLock lock, Clock clock) {
        this.jdbc = jdbc;
        this.storage = storage;
        this.tx = tx;
        this.lock = lock;
        this.clock = clock;
    }

    @Scheduled(cron = "${blog.media.post-image-cleanup-cron:0 40 3 * * *}", zone = "Asia/Seoul")
    public void scheduled() {
        lock.runExclusively("post-image-cleanup", Duration.ofMinutes(30), this::runOnce);
    }

    /** @return 지운 사진 수 */
    public int runOnce() {
        Instant now = Times.now(clock);
        Timestamp unusedBefore = Timestamp.from(now.minus(UNUSED_TTL));
        Timestamp detachedBefore = Timestamp.from(now.minus(DETACHED_TTL));
        int deleted = 0;
        int failed = 0;
        long after = 0;
        while (true) {
            List<Long> ids = Columns.longs(jdbc, "SELECT r.id FROM resource r WHERE r.id > ? AND " + UNUSED + " ORDER BY r.id LIMIT " + BATCH, after, unusedBefore, detachedBefore);
            for (long id : ids) {
                after = id;
                try {
                    if (Boolean.TRUE.equals(tx.execute(s -> deleteOne(id, unusedBefore, detachedBefore)))) deleted++;
                } catch (RuntimeException e) {
                    failed++;
                    log.warn("사진을 지우지 못해 다음 정리 때 다시 시도합니다 (resource {}): {}", id, e.getMessage());
                }
            }
            if (ids.size() < BATCH) break;
        }
        if (deleted > 0 || failed > 0) log.info("본문 사진 정리: {}장 삭제, {}장 실패", deleted, failed);
        return deleted;
    }

    private boolean deleteOne(long id, Timestamp unusedBefore, Timestamp detachedBefore) {
        List<String[]> rows = jdbc.query("""
                SELECT r.storage_key, ri.thumb_storage_key FROM resource r
                LEFT JOIN resource_image ri ON ri.resource_id = r.id
                WHERE r.id = ? AND """ + " " + UNUSED + " FOR UPDATE OF r SKIP LOCKED",
                (rs, i) -> new String[] {rs.getString(1), rs.getString(2)}, id, unusedBefore, detachedBefore);
        if (rows.isEmpty()) return false;
        // 파일 먼저: 실패하면 예외로 이 트랜잭션이 되돌아가 기록이 남는다
        storage.delete(rows.getFirst()[0]);
        if (rows.getFirst()[1] != null) storage.delete(rows.getFirst()[1]);
        jdbc.update("DELETE FROM resource WHERE id = ?", id);
        return true;
    }
}
