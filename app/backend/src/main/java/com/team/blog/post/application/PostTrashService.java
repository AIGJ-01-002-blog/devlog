package com.team.blog.post.application;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.team.blog.post.application.PostLifecycleEvents.PostPurged;
import com.team.blog.post.application.PostLifecycleEvents.PostRestored;
import com.team.blog.post.application.PostLifecycleEvents.PostTrashed;
import com.team.blog.post.application.PostLifecycleEvents.Reason;
import com.team.blog.post.infra.AutosaveStore;
import com.team.blog.post.infra.PostSql;
import com.team.blog.shared.error.NotFoundException;
import com.team.blog.shared.jdbc.Columns;
import com.team.blog.shared.time.Times;

/**
 * 글 삭제·휴지통·복구·완전 삭제 (007, docs/13 §2). 작성자 본인 글만, 남의 글·없는 글은 관리자라도 404다.
 * 같은 글의 요청은 행 잠금으로 하나씩 처리한다. 휴지통에 있는 동안 상태·공개 범위·처음 공개 시각·updated_at은 그대로라
 * 복구하면 목록의 원래 자리로 돌아온다.
 */
@Service
public class PostTrashService {
    public static final Duration RETENTION = Duration.ofDays(30);
    private static final String DRAFT = "DRAFT";

    private final JdbcTemplate jdbc;
    private final AutosaveFlushJob autosaveFlush;
    private final AutosaveStore autosave;
    private final List<PurgeExtension> purgeExtensions;
    private final ApplicationEventPublisher events;
    private final TransactionTemplate tx;
    private final Clock clock;

    public PostTrashService(JdbcTemplate jdbc, AutosaveFlushJob autosaveFlush, AutosaveStore autosave,
                            List<PurgeExtension> purgeExtensions, ApplicationEventPublisher events, TransactionTemplate tx,
                            Clock clock) {
        this.jdbc = jdbc;
        this.autosaveFlush = autosaveFlush;
        this.autosave = autosave;
        this.purgeExtensions = purgeExtensions;
        this.events = events;
        this.tx = tx;
        this.clock = clock;
    }

    public enum Outcome { TRASHED, ALREADY_TRASHED, DELETED_EMPTY }

    public record TrashResult(Outcome result, Instant deletedAt, Instant purgeAt) {}

    public record RestoreResult(String status, String visibility) {}

    private record Locked(long authorId, String status, String visibility, Instant deletedAt) {}

    /** [삭제]: 휴지통으로 옮긴다. 아무것도 쓰지 않은 임시글은 바로 완전 삭제한다 (FR-002). 이미 휴지통이면 그대로 (FR-007). */
    public TrashResult trash(long memberId, long postId) {
        return tx.execute(s -> {
            Locked p = lockOwn(postId, memberId);
            if (p.deletedAt() != null) return new TrashResult(Outcome.ALREADY_TRASHED, p.deletedAt(), p.deletedAt().plus(RETENTION));
            Instant now = Times.now(clock);
            // 서버에 잠시 보관 중인 자동 저장분을 먼저 반영해, 복구했을 때 마지막 내용이 남게 한다 (FR-005)
            autosaveFlush.flushPost(postId);
            if (DRAFT.equals(p.status()) && isBlankDraft(postId)) {
                purgeLocked(postId, p.authorId(), Reason.EMPTY, now);
                return new TrashResult(Outcome.DELETED_EMPTY, null, null);
            }
            jdbc.update("UPDATE post SET deleted_at = ? WHERE id = ?", Timestamp.from(now), postId);
            events.publishEvent(new PostTrashed(postId, p.authorId(), now));
            return new TrashResult(Outcome.TRASHED, now, now.plus(RETENTION));
        });
    }

    /**
     * 에디터 [나가기]: 아무것도 쓰지 않은 임시글이면 휴지통을 거치지 않고 바로 지운다 (spec 091). 무언가 쓴 글·발행한 글·
     * 휴지통의 글은 그대로 두고 false. 자동 저장분을 확인할 수 없으면 쓰던 내용이 있을 수 있으니 지우지 않는다.
     */
    public boolean discardIfBlank(long memberId, long postId) {
        Boolean done = tx.execute(s -> {
            Locked p = lockOwn(postId, memberId);
            if (p.deletedAt() != null || !DRAFT.equals(p.status())) return false;
            boolean hasAutosave;
            try {
                hasAutosave = autosave.read(postId).isPresent();
            } catch (RuntimeException e) {
                return false;
            }
            if (hasAutosave) autosaveFlush.flushPost(postId);
            if (!isBlankDraft(postId)) return false;
            purgeLocked(postId, p.authorId(), Reason.EMPTY, Times.now(clock));
            return true;
        });
        return Boolean.TRUE.equals(done);
    }

    /** [복구]: 휴지통에 있는 글만. 원래 상태·공개 범위·목록 위치로 돌아온다 (FR-008). */
    public RestoreResult restore(long memberId, long postId) {
        return tx.execute(s -> {
            Locked p = lockOwn(postId, memberId);
            if (p.deletedAt() == null) throw new NotFoundException();
            Instant now = Times.now(clock);
            jdbc.update("UPDATE post SET deleted_at = NULL WHERE id = ?", postId);
            events.publishEvent(new PostRestored(postId, p.authorId(), now));
            return new RestoreResult(p.status(), DRAFT.equals(p.status()) ? null : p.visibility());
        });
    }

    /** [영구 삭제]: 휴지통에 있는 글만 (FR-009). */
    public void purge(long memberId, long postId) {
        tx.executeWithoutResult(s -> {
            Locked p = lockOwn(postId, memberId);
            if (p.deletedAt() == null) throw new NotFoundException();
            purgeLocked(postId, p.authorId(), Reason.USER, Times.now(clock));
        });
    }

    /** 휴지통에 옮긴 지 30일 지난 글 하나를 지운다 (정리 배치). 그 사이 복구됐으면 지우지 않는다. */
    public boolean purgeExpired(long postId, Instant cutoff) {
        Boolean done = tx.execute(s -> {
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT author_id FROM post WHERE id = ? AND deleted_at < ? FOR UPDATE SKIP LOCKED", postId, Timestamp.from(cutoff));
            if (rows.isEmpty()) return false;
            purgeLocked(postId, ((Number) rows.getFirst().get("author_id")).longValue(), Reason.EXPIRED, Times.now(clock));
            return true;
        });
        return Boolean.TRUE.equals(done);
    }

    /** 탈퇴 30일 뒤 정리(020 FR-025 (10)): 휴지통을 포함한 그 회원의 글 전부. 정리 배치의 트랜잭션 안에서 부른다. */
    void purgeAllOf(long authorId) {
        Instant now = Times.now(clock);
        for (long postId : Columns.longs(jdbc, "SELECT id FROM post WHERE author_id = ? ORDER BY id FOR UPDATE", authorId)) {
            purgeLocked(postId, authorId, Reason.WITHDRAW, now);
        }
    }

    /** 탈퇴 정리(020)도 같은 절차를 쓴다. 행을 잠근 트랜잭션 안에서 부른다. */
    void purgeLocked(long postId, long authorId, Reason reason, Instant now) {
        purgeExtensions.forEach(e -> e.beforePurge(postId));
        // 댓글·좋아요·태그 연결·사진 연결·작업본·조회 기록은 FK(ON DELETE CASCADE)로 함께 지워진다 (FR-011)
        jdbc.update("DELETE FROM post WHERE id = ?", postId);
        events.publishEvent(new PostPurged(postId, authorId, reason, now));
    }

    private Locked lockOwn(long postId, long memberId) {
        List<Locked> rows = jdbc.query("""
                SELECT author_id, status, visibility, deleted_at FROM post WHERE id = ? AND author_id = ? FOR UPDATE
                """, (rs, i) -> new Locked(rs.getLong(1), rs.getString(2), rs.getString(3),
                rs.getTimestamp(4) == null ? null : rs.getTimestamp(4).toInstant()), postId, memberId);
        if (rows.isEmpty()) throw new NotFoundException();
        return rows.getFirst();
    }

    private boolean isBlankDraft(long postId) {
        Boolean blank = jdbc.queryForObject(
                "SELECT " + PostSql.BLANK_DRAFT + " FROM post p WHERE p.id = ?", Boolean.class, postId);
        return Boolean.TRUE.equals(blank);
    }
}
