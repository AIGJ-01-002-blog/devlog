package com.team.blog.post.application;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.team.blog.post.infra.AutosaveStore;
import com.team.blog.post.infra.PostSql;
import com.team.blog.shared.config.BlogProperties;
import com.team.blog.shared.jdbc.Columns;
import com.team.blog.shared.scheduling.JobLock;
import com.team.blog.shared.time.Times;

/**
 * 빈 임시글 정리 (docs/04 §2-5, spec 002 FR-016, spec 090). 아무것도 쓰지 않았고({@link PostSql#BLANK_DRAFT}), 만든 지와 마지막 수정 후 모두
 * 24시간이 지났고, 자동 저장 키도 없는 임시글을 완전 삭제한다. 매일 새벽 4시(KST).
 */
@Component
public class EmptyDraftCleanupJob {
    private static final Logger log = LoggerFactory.getLogger(EmptyDraftCleanupJob.class);

    private final JdbcTemplate jdbc;
    private final AutosaveStore autosave;
    private final JobLock lock;
    private final Clock clock;
    private final Duration ttl;

    public EmptyDraftCleanupJob(JdbcTemplate jdbc, AutosaveStore autosave, JobLock lock, Clock clock, BlogProperties props) {
        this.jdbc = jdbc;
        this.autosave = autosave;
        this.lock = lock;
        this.clock = clock;
        this.ttl = props.post().emptyDraftTtl();
    }

    @Scheduled(cron = "${blog.post.empty-draft-cleanup-cron:0 0 4 * * *}", zone = "Asia/Seoul")
    public void scheduled() {
        lock.runExclusively("empty-draft-cleanup", Duration.ofMinutes(30), this::cleanup);
    }

    /** @return 지운 글 수 */
    public int cleanup() {
        Timestamp cutoff = Timestamp.from(Times.now(clock).minus(ttl));
        List<Long> candidates = Columns.longs(jdbc, """
                SELECT p.id FROM post p
                WHERE p.deleted_at IS NULL AND p.created_at < ? AND p.updated_at < ? AND
                """ + PostSql.BLANK_DRAFT + " ORDER BY p.id LIMIT 5000", cutoff, cutoff);
        int deleted = 0;
        for (long id : candidates) {
            boolean hasAutosave;
            try {
                hasAutosave = autosave.exists(id);
            } catch (RuntimeException e) {
                log.warn("자동 저장 확인을 못해 빈 임시글 정리를 멈춥니다: {}", e.getMessage());
                break; // Redis를 확인할 수 없으면 지우지 않는다 (쓰던 내용이 있을 수 있음)
            }
            if (!hasAutosave) {
                deleted += jdbc.update("""
                        DELETE FROM post p WHERE p.id = ? AND p.created_at < ? AND p.updated_at < ? AND
                        """ + PostSql.BLANK_DRAFT, id, cutoff, cutoff);
            }
        }
        if (deleted > 0) log.info("빈 임시글 {}개를 정리했습니다", deleted);
        return deleted;
    }
}
