package com.team.blog.comment.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.team.blog.post.access.Viewer;
import com.team.blog.shared.error.ApiException;
import com.team.blog.shared.error.NotFoundException;
import com.team.blog.shared.jdbc.Columns;
import com.team.blog.shared.time.Times;
import com.team.blog.shared.web.RateLimiter;
import com.team.blog.shared.web.TooManyRequestsException;

/**
 * 댓글 쓰기·고치기·지우기 (docs/21 §5·§7·§8, 011 FR-001~FR-017·FR-031~FR-039).
 * 검사 순서는 로그인(컨트롤러) → 계정 상태(AccountStateFilter) → 요청 제한 → 글을 읽을 수 있나(404) → 내용(400) → 대상(400).
 * 답글 작성은 대상과 최상위 행을 FOR SHARE, 삭제는 FOR UPDATE로 잠가 "삭제와 답글이 동시에" 오는 경우의 순서를 정한다.
 * 잠그는 순서는 항상 답글 → 최상위다.
 */
@Service
public class CommentService {
    private static final Logger log = LoggerFactory.getLogger(CommentService.class);
    static final int CREATE_PER_MINUTE = 10;
    static final int EDIT_PER_MINUTE = 20;
    private static final Duration DEDUPE_TTL = Duration.ofSeconds(10);
    private static final String PENDING = "-";
    private static final String PARENT_ID = "parent_id";

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final CommentQuery query;
    private final RateLimiter rateLimiter;
    private final StringRedisTemplate redis;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    public CommentService(JdbcTemplate jdbc, TransactionTemplate tx, CommentQuery query, RateLimiter rateLimiter,
                          StringRedisTemplate redis, ApplicationEventPublisher events, Clock clock) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.query = query;
        this.rateLimiter = rateLimiter;
        this.redis = redis;
        this.events = events;
        this.clock = clock;
    }

    /** @return created=false면 10초 안에 같은 요청이 다시 와서 처음 댓글을 돌려준 것이다 */
    public record Created(CommentQuery.CommentView comment, boolean created) {}

    public Created create(long memberId, long postId, String rawContent, Long replyToCommentId) {
        limit("comment:create:" + memberId, CREATE_PER_MINUTE);
        Viewer viewer = new Viewer(memberId, false);
        CommentQuery.PostGate gate = query.gate(postId, viewer);
        if (!gate.writable()) throw new NotFoundException();
        String content = CommentText.validate(rawContent);

        String dedupeKey = "cmt:dedupe:" + memberId + ":" + postId + ":" + hash(content + "\u0000" + replyToCommentId);
        Long earlier = claim(dedupeKey);
        if (earlier != null) return new Created(query.one(earlier, viewer), false);

        long id;
        try {
            id = tx.execute(s -> insert(gate, memberId, content, replyToCommentId));
        } catch (RuntimeException e) {
            release(dedupeKey);
            throw e;
        }
        remember(dedupeKey, id);
        return new Created(query.one(id, viewer), true);
    }

    private long insert(CommentQuery.PostGate gate, long memberId, String content, Long replyToCommentId) {
        Long parentId = null;
        Long replyToMember = null;
        Long rootAuthor = null;
        if (replyToCommentId != null) {
            Target t = lockTarget(replyToCommentId, gate.postId());
            if (t.parentId == null) {
                parentId = t.id;
                rootAuthor = t.authorId;
            } else {
                // 답글에 답하면 같은 최상위 아래에, 그 답글 작성자를 대상으로. 내 답글에 답해도 대상을 남긴다:
                // 비워 두면 화면에 "@누구에게"가 없어 최상위에 바로 단 답글과 구별되지 않았다 (자기 알림은 알림 쪽에서 거른다)
                List<Long> root = Columns.longs(jdbc, "SELECT author_id FROM comment WHERE id = ? FOR SHARE", t.parentId);
                if (root.isEmpty()) throw unavailable();
                parentId = t.parentId;
                rootAuthor = root.get(0);
                replyToMember = t.authorId;
            }
        }
        Instant now = Times.now(clock);
        long id = Objects.requireNonNull(jdbc.queryForObject("""
                INSERT INTO comment (post_id, author_id, parent_id, reply_to_member_id, content, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?) RETURNING id
                """, Long.class, gate.postId(), memberId, parentId, replyToMember, content, Timestamp.from(now), Timestamp.from(now)));
        events.publishEvent(new CommentEvents.CommentCreated(id, gate.postId(), gate.authorId(), memberId, parentId, rootAuthor,
                replyToMember, now));
        return id;
    }

    private record Target(long id, Long parentId, long authorId) {}

    /** 같은 글의 정상 댓글(삭제·숨김·작성자 탈퇴 아님)만 답글 대상이 된다 (FR-012). */
    private Target lockTarget(long commentId, long postId) {
        List<Target> rows = jdbc.query("""
                SELECT c.id, c.parent_id, c.author_id FROM comment c JOIN member m ON m.id = c.author_id
                WHERE c.id = ? AND c.post_id = ? AND c.deleted_at IS NULL AND c.hidden_at IS NULL
                  AND m.withdrawn_at IS NULL AND m.deleted_at IS NULL
                FOR SHARE OF c
                """, (rs, i) -> new Target(rs.getLong("id"), rs.getObject(PARENT_ID, Long.class), rs.getLong("author_id")),
                commentId, postId);
        if (rows.isEmpty()) throw unavailable();
        return rows.get(0);
    }

    public CommentQuery.CommentView update(long memberId, long commentId, String rawContent) {
        limit("comment:edit:" + memberId, EDIT_PER_MINUTE);
        Viewer viewer = new Viewer(memberId, false);
        tx.execute(s -> {
            List<Map<String, Object>> rows = jdbc.queryForList("""
                    SELECT post_id, content, hidden_at IS NOT NULL AS hidden FROM comment
                    WHERE id = ? AND author_id = ? AND deleted_at IS NULL FOR UPDATE
                    """, commentId, memberId);
            if (rows.isEmpty()) throw new NotFoundException();
            Map<String, Object> c = rows.get(0);
            long post = ((Number) c.get("post_id")).longValue();
            if (!query.gate(post, viewer).readable()) throw new NotFoundException();
            if (Boolean.TRUE.equals(c.get("hidden"))) {
                throw new ApiException(HttpStatus.CONFLICT, "COMMENT_HIDDEN", "숨겨진 댓글은 수정할 수 없어요.");
            }
            String content = CommentText.validate(rawContent);
            // 정리한 뒤 같으면 아무것도 바꾸지 않는다 ("수정됨"이 붙지 않음, FR-032)
            if (!content.equals(c.get("content"))) {
                jdbc.update("UPDATE comment SET content = ?, updated_at = ? WHERE id = ?", content,
                        Timestamp.from(Times.now(clock)), commentId);
            }
            return post;
        });
        return query.one(commentId, viewer);
    }

    /** 답글이 있는 최상위는 자리로 남기고, 그 밖에는 행을 지운다. 빈 자리는 함께 지운다 (FR-034·FR-035). */
    public void delete(long memberId, long commentId) {
        tx.executeWithoutResult(s -> {
            List<Map<String, Object>> rows = jdbc.queryForList("""
                    SELECT c.post_id, c.parent_id, p.author_id AS post_author_id FROM comment c JOIN post p ON p.id = c.post_id
                    WHERE c.id = ? AND c.author_id = ? AND c.deleted_at IS NULL FOR UPDATE OF c
                    """, commentId, memberId);
            if (rows.isEmpty()) throw new NotFoundException();
            Map<String, Object> c = rows.get(0);
            long postId = ((Number) c.get("post_id")).longValue();
            Long rootId = c.get(PARENT_ID) == null ? null : ((Number) c.get(PARENT_ID)).longValue();
            Instant now = Times.now(clock);
            if (rootId == null) {
                Boolean hasReplies = jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM comment WHERE parent_id = ?)", Boolean.class, commentId);
                if (Boolean.TRUE.equals(hasReplies)) {
                    jdbc.update("UPDATE comment SET content = '', deleted_at = ? WHERE id = ?", Timestamp.from(now), commentId);
                } else {
                    jdbc.update("DELETE FROM comment WHERE id = ?", commentId);
                }
            } else {
                jdbc.update("DELETE FROM comment WHERE id = ?", commentId);
                Columns.longs(jdbc, "SELECT id FROM comment WHERE id = ? FOR UPDATE", rootId);
                jdbc.update("""
                        DELETE FROM comment r WHERE r.id = ? AND r.deleted_at IS NOT NULL
                          AND NOT EXISTS (SELECT 1 FROM comment x WHERE x.parent_id = r.id)
                        """, rootId);
            }
            events.publishEvent(new CommentEvents.CommentDeleted(commentId, postId, ((Number) c.get("post_author_id")).longValue(),
                    memberId, rootId, now));
        });
    }

    // ------------------------------------------------------------------

    private void limit(String key, int perMinute) {
        if (!rateLimiter.tryAcquire(key, perMinute, Duration.ofMinutes(1))) {
            throw new TooManyRequestsException(60, "RATE_LIMITED", "잠시 후 다시 시도해 주세요.");
        }
    }

    /**
     * 10초 안의 같은 요청을 하나로 (FR-013, SC-003). 처음 요청이 자리를 잡고("-"), 만들고 나면 번호를 적는다.
     * 뒤따른 요청은 번호가 적힐 때까지 잠깐 기다렸다가 그 댓글을 돌려준다. Redis가 안 되면 거르지 않는다.
     * @return 이미 있는 댓글 번호, 처음 요청이면 null
     */
    private Long claim(String key) {
        try {
            for (int i = 0; i < 50; i++) {
                Boolean first = redis.opsForValue().setIfAbsent(key, PENDING, DEDUPE_TTL);
                if (Boolean.TRUE.equals(first)) return null;
                String v = redis.opsForValue().get(key);
                if (v != null && !v.equals(PENDING)) {
                    long id = Long.parseLong(v);
                    if (exists(id)) return id;
                    redis.delete(key);
                    continue;
                }
                Thread.sleep(100);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException e) {
            log.warn("댓글 중복 확인을 하지 못해 그대로 저장합니다: {}", e.getMessage());
        }
        return null;
    }

    private boolean exists(long id) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM comment WHERE id = ? AND deleted_at IS NULL)",
                Boolean.class, id));
    }

    private void remember(String key, long id) {
        try {
            redis.opsForValue().set(key, String.valueOf(id), DEDUPE_TTL);
        } catch (RuntimeException e) {
            log.warn("댓글 중복 확인 값을 쓰지 못했습니다: {}", e.getMessage());
        }
    }

    private void release(String key) {
        try {
            redis.delete(key);
        } catch (RuntimeException e) {
            // 10초 뒤 저절로 사라진다
        }
    }

    private static ApiException unavailable() {
        return ApiException.badRequest("REPLY_TARGET_UNAVAILABLE", "답글을 달 수 없는 댓글이에요.");
    }

    private static String hash(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)), 0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
