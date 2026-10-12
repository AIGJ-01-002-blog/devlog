package com.team.blog.post.application;

import java.time.Clock;
import java.time.Duration;
import java.sql.Timestamp;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import com.team.blog.post.infra.AutosaveStore;
import com.team.blog.shared.scheduling.JobLock;
import com.team.blog.shared.time.Times;

/**
 * Redis 자동 저장을 1분마다 DB에 반영한다 (docs/04 §2-4). 임시글은 post, 발행한 글은 post_draft에 쓴다.
 * 오래된 버전으로 덮어쓰지 않도록 모든 쓰기에 "edit_version < 새 버전" 조건을 건다.
 * dirty 집합에서 먼저 빼고 읽는다 — 그 사이 들어온 자동 저장은 다시 집합에 들어가므로 놓치지 않는다.
 */
@Component
public class AutosaveFlushJob {
    private static final Logger log = LoggerFactory.getLogger(AutosaveFlushJob.class);

    /** 아직 발행하지 않은 글은 본문에 바로 쓴다 */
    private static final String UPDATE_DRAFT = """
            UPDATE post SET title = ?, content_md = ?, edit_version = ?, updated_at = ?
            WHERE id = ? AND author_id = ? AND status = 'DRAFT' AND edit_version < ? AND deleted_at IS NULL
            """;

    /** 발행한 글은 작업본에 쓴다 */
    private static final String UPSERT_WORKING_COPY = """
            INSERT INTO post_draft (post_id, title, content_md, edit_version, created_at, updated_at)
            SELECT id, ?, ?, ?, ?, ? FROM post
            WHERE id = ? AND author_id = ? AND status = 'PUBLISHED' AND edit_version < ? AND deleted_at IS NULL
            ON CONFLICT (post_id) DO UPDATE
            SET title = EXCLUDED.title, content_md = EXCLUDED.content_md,
                edit_version = EXCLUDED.edit_version, updated_at = EXCLUDED.updated_at
            WHERE post_draft.edit_version < EXCLUDED.edit_version
            """;

    private final AutosaveStore autosave;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final JobLock lock;
    private final Clock clock;

    public AutosaveFlushJob(AutosaveStore autosave, JdbcTemplate jdbc, TransactionTemplate tx, JobLock lock, Clock clock) {
        this.autosave = autosave;
        this.jdbc = jdbc;
        this.tx = tx;
        this.lock = lock;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${blog.post.autosave-flush-interval:60s}", initialDelayString = "${blog.post.autosave-flush-interval:60s}")
    public void scheduled() {
        lock.runExclusively("autosave-flush", Duration.ofMinutes(5), this::flushAll);
    }

    /** @return 반영한 글 수 */
    public int flushAll() {
        int flushed = 0;
        for (long postId : autosave.dirtyPostIds()) {
            try {
                autosave.removeDirty(postId);
                Optional<AutosaveStore.Snapshot> snap = autosave.read(postId);
                if (snap.isPresent() && flush(postId, snap.get())) flushed++;
            } catch (RuntimeException e) {
                log.warn("자동 저장을 DB에 반영하지 못했습니다 (post {}). 다음 차례에 다시 시도합니다: {}", postId, e.getMessage());
                try { autosave.markDirty(postId); } catch (RuntimeException ignored) { /* Redis 장애 */ }
            }
        }
        return flushed;
    }

    /**
     * 한 글의 자동 저장분을 바로 DB에 반영한다 (휴지통으로 옮기기 직전, 007 FR-005). 호출한 쪽 트랜잭션에 참여한다.
     * @return 반영했으면 true
     */
    public boolean flushPost(long postId) {
        Optional<AutosaveStore.Snapshot> snap;
        try {
            snap = autosave.read(postId);
        } catch (RuntimeException e) {
            // Redis가 멈춰도 삭제는 막지 않는다. 마지막 자동 저장분은 DB에 반영된 것까지만 남는다
            log.warn("자동 저장분을 읽지 못해 반영 없이 진행합니다 (post {}): {}", postId, e.getMessage());
            return false;
        }
        if (snap.isEmpty()) return false;
        return flush(postId, snap.get());
    }

    private boolean flush(long postId, AutosaveStore.Snapshot s) {
        Timestamp now = Timestamp.from(Times.now(clock));
        Boolean done = tx.execute(st -> {
            int drafts = jdbc.update(UPDATE_DRAFT, s.title(), s.contentMd(), s.version(), now, postId, s.memberId(), s.version());
            if (drafts > 0) return true;
            int working = jdbc.update(UPSERT_WORKING_COPY, s.title(), s.contentMd(), s.version(), now, now, postId, s.memberId(), s.version());
            if (working > 0) {
                jdbc.update("UPDATE post SET updated_at = ? WHERE id = ?", now, postId);
                return true;
            }
            return false;
        });
        return Boolean.TRUE.equals(done);
    }
}
