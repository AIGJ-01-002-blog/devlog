package com.team.blog.notification.application;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.List;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import com.team.blog.account.domain.Visibility;
import com.team.blog.post.access.PostAccessPolicy;
import com.team.blog.post.access.ReadablePost;
import com.team.blog.post.access.Viewer;
import com.team.blog.post.domain.PostStatus;
import com.team.blog.series.application.SeriesSql;

/**
 * 알림 만들기·지우기 (015, docs/25 §4, docs/20 §4). 사건은 커밋 뒤에 오므로 여기서 최신 상태를 다시 확인하고,
 * 사건의 도착 순서에 기대지 않는다(FR-044). 알림에는 식별자만 저장한다(FR-017).
 * <p>
 * 만들기 전 확인 순서 (FR-005): ① 본인 행동 ② 받는 사람 탈퇴 신청 ③ 행동한 사람 탈퇴 신청 ④ 받는 사람이 끈 종류 ⑤ 받는 사람이 글을 읽을 수 있나.
 */
@Service
public class NotificationService {
    static final Duration FOLLOW_REPEAT = Duration.ofDays(7);

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final PostAccessPolicy policy;
    private final ApplicationEventPublisher events;

    public NotificationService(JdbcTemplate jdbc, PlatformTransactionManager txManager, PostAccessPolicy policy,
                               ApplicationEventPublisher events) {
        this.jdbc = jdbc;
        // 커밋 뒤(afterCommit)에 같은 스레드에서 불려도 끝난 트랜잭션에 묻히지 않게 항상 새 트랜잭션으로 쓴다
        this.tx = new TransactionTemplate(txManager);
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.policy = policy;
        this.events = events;
    }

    /**
     * 댓글·답글 알림 (FR-002·FR-003). 글 작성자에게 댓글 알림, 답글 대상에게 답글 알림. 둘이 같은 사람이면 답글만.
     * @param replyTarget 답글 대상(답글에 답했으면 그 작성자, 아니면 최상위 댓글 작성자). 최상위 댓글이면 null
     */
    public void commentCreated(long commentId, long postId, long postAuthorId, long actorId, Long replyTarget, Instant at) {
        if (!commentAlive(commentId)) return; // 처리 전에 지워졌거나 숨겨진 댓글
        if (replyTarget != null) createComment(NotificationType.REPLY, replyTarget, actorId, postId, commentId, at);
        if (replyTarget == null || replyTarget != postAuthorId) {
            createComment(NotificationType.COMMENT, postAuthorId, actorId, postId, commentId, at);
        }
    }

    /** 댓글이 지워지면(자리만 남는 경우 포함) 그 댓글로 생긴 알림도 지운다 (FR-013). */
    public void commentDeleted(long commentId) {
        tx.executeWithoutResult(s -> jdbc.update("DELETE FROM notification WHERE id IN (SELECT notification_id FROM notification_comment WHERE comment_id = ?)", commentId));
    }

    /**
     * 좋아요 (FR-007~FR-011). 받는 사람·글마다 안 읽은 묶음 하나에 사람을 더한다. 같은 사람은 그 글에 대해 보관 기간 안에 한 번만.
     * (받는 사람, 종류, 글) 단위 트랜잭션 잠금으로 동시에 와도 안 읽은 묶음은 하나다.
     */
    public void postLiked(long postId, long authorId, long likerId, Instant at) {
        if (!allowed(NotificationType.LIKE, authorId, likerId, postId)) return;
        tx.executeWithoutResult(s -> {
            lock(authorId, NotificationType.LIKE, postId);
            // 취소가 먼저 처리됐으면 지금은 좋아요가 없다 (FR-044)
            if (!Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM post_like WHERE post_id = ? AND member_id = ?)",
                    Boolean.class, postId, likerId))) return;
            Boolean already = jdbc.queryForObject("""
                    SELECT EXISTS (SELECT 1 FROM notification n
                                   JOIN notification_post np ON np.notification_id = n.id AND np.post_id = ?
                                   JOIN notification_actor a ON a.notification_id = n.id AND a.actor_id = ?
                                   WHERE n.receiver_id = ? AND n.type = 'LIKE')
                    """, Boolean.class, postId, likerId, authorId);
            if (Boolean.TRUE.equals(already)) return; // FR-009
            Long open = unreadLike(authorId, postId);
            Timestamp ts = Timestamp.from(at);
            if (open == null) {
                open = insert(authorId, NotificationType.LIKE, ts);
                jdbc.update("INSERT INTO notification_post (notification_id, type, post_id) VALUES (?, 'LIKE', ?)", open, postId);
            } else {
                // 새 사람이 더해지면 맨 위로 (FR-008)
                jdbc.update("UPDATE notification SET updated_at = GREATEST(updated_at, ?) WHERE id = ?", ts, open);
            }
            jdbc.update("INSERT INTO notification_actor (notification_id, actor_id, created_at) VALUES (?, ?, ?) ON CONFLICT DO NOTHING",
                    open, likerId, ts);
        });
    }

    /** 좋아요 취소 (FR-012): 안 읽은 묶음에서만 빼고, 0명이면 지운다. 대표·수는 참여자에서 세므로 다시 계산할 것이 없다. */
    public void postUnliked(long postId, long authorId, long likerId) {
        tx.executeWithoutResult(s -> {
            lock(authorId, NotificationType.LIKE, postId);
            // 다시 눌렀으면 그대로 둔다 (도착 순서와 상관없이 지금 상태 기준)
            if (Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM post_like WHERE post_id = ? AND member_id = ?)",
                    Boolean.class, postId, likerId))) return;
            Long open = unreadLike(authorId, postId);
            if (open == null) return;
            jdbc.update("DELETE FROM notification_actor WHERE notification_id = ? AND actor_id = ?", open, likerId);
            jdbc.update("DELETE FROM notification n WHERE n.id = ? AND NOT EXISTS (SELECT 1 FROM notification_actor a WHERE a.notification_id = n.id)", open);
        });
    }

    /**
     * 새 팔로워 (FR-007·FR-010). 받는 사람마다 안 읽은 묶음 하나에 사람을 더하고, 같은 사람은 7일에 한 번만 알린다.
     * 언팔로우했다가 안 읽은 동안 다시 팔로우하면 묶음에서 빠졌던 사람이 돌아올 뿐 알림 수는 늘지 않는다.
     */
    public void followed(long followerId, long followeeId, Instant at) {
        if (!allowed(NotificationType.FOLLOW, followeeId, followerId, null)) return;
        tx.executeWithoutResult(s -> {
            lock(followeeId, NotificationType.FOLLOW, 0);
            if (!following(followerId, followeeId)) return; // 언팔로우가 먼저 처리됐다
            Boolean recent = jdbc.queryForObject("""
                    SELECT EXISTS (SELECT 1 FROM notification n JOIN notification_actor a ON a.notification_id = n.id AND a.actor_id = ?
                                   WHERE n.receiver_id = ? AND n.type = 'FOLLOW' AND a.created_at > ?)
                    """, Boolean.class, followerId, followeeId, Timestamp.from(at.minus(FOLLOW_REPEAT)));
            if (Boolean.TRUE.equals(recent)) return;
            Long open = unread(followeeId, NotificationType.FOLLOW);
            Timestamp ts = Timestamp.from(at);
            if (open == null) {
                open = insert(followeeId, NotificationType.FOLLOW, ts);
            } else {
                jdbc.update("UPDATE notification SET updated_at = GREATEST(updated_at, ?) WHERE id = ?", ts, open);
            }
            jdbc.update("INSERT INTO notification_actor (notification_id, actor_id, created_at) VALUES (?, ?, ?) ON CONFLICT DO NOTHING",
                    open, followerId, ts);
        });
    }

    /**
     * 언팔로우 (FR-012): 안 읽은 묶음에서만 빼고, 상대에게 따로 알리지 않는다. 마지막 한 명이 빠지면 지우지 않고 읽음으로 돌린다.
     * 텔레그램(023)으로 이미 나간 알림은 거둘 수 없어서, 지우면 다시 팔로우할 때 새 알림이 또 나간다(FR-010 7일에 한 번).
     */
    public void unfollowed(long followerId, long followeeId) {
        tx.executeWithoutResult(s -> {
            lock(followeeId, NotificationType.FOLLOW, 0);
            if (following(followerId, followeeId)) return;
            Long open = unread(followeeId, NotificationType.FOLLOW);
            if (open == null) return;
            Boolean others = jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM notification_actor WHERE notification_id = ? AND actor_id <> ?)",
                    Boolean.class, open, followerId);
            if (Boolean.TRUE.equals(others)) {
                jdbc.update("DELETE FROM notification_actor WHERE notification_id = ? AND actor_id = ?", open, followerId);
            } else {
                jdbc.update("UPDATE notification SET read_at = now() WHERE id = ? AND read_at IS NULL", open);
            }
        });
    }

    /**
     * 친구 요청 (015 A-1, 008 FR-014). 묶지 않고 요청마다 하나다. 같은 사람의 요청은 7일에 한 번만 알린다(요청 취소 뒤 다시 보내도).
     * 처리 시점에 요청이 이미 수락·거절·취소됐으면 만들지 않는다. 거절·취소·끊기는 알리지 않는다.
     */
    public void friendRequested(long requesterId, long receiverId, Instant at) {
        friend(NotificationType.FRIEND_REQUEST, receiverId, requesterId, at);
    }

    /** 친구 수락: 요청한 사람에게 (맞요청으로 바로 맺어진 경우 포함). 그 사이 끊겼으면 만들지 않는다. 같은 사람은 7일에 한 번. */
    public void friendAccepted(long requesterId, long accepterId, Instant at) {
        friend(NotificationType.FRIEND_ACCEPTED, requesterId, accepterId, at);
    }

    /** 요청이면 행동자가 보낸 요청이 아직 대기 중일 때, 수락이면 아직 친구일 때만 만든다. */
    private void friend(NotificationType type, long receiverId, long actorId, Instant at) {
        if (!allowed(type, receiverId, actorId, null)) return;
        boolean request = type == NotificationType.FRIEND_REQUEST;
        String still = request ? "status = 'PENDING' AND requested_by = ?" : "status = 'ACCEPTED'";
        Object[] stillArgs = request ? new Object[] {Math.min(receiverId, actorId), Math.max(receiverId, actorId), actorId}
                : new Object[] {Math.min(receiverId, actorId), Math.max(receiverId, actorId)};
        tx.executeWithoutResult(s -> {
            lock(receiverId, type, actorId);
            if (!Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM friendship WHERE member_a_id = ? AND member_b_id = ? AND "
                    + still + ")", Boolean.class, stillArgs))) return;
            Boolean recent = jdbc.queryForObject("""
                    SELECT EXISTS (SELECT 1 FROM notification n JOIN notification_actor a ON a.notification_id = n.id AND a.actor_id = ?
                                   WHERE n.receiver_id = ? AND n.type = ? AND a.created_at > ?)
                    """, Boolean.class, actorId, receiverId, type.name(), Timestamp.from(at.minus(FOLLOW_REPEAT)));
            if (Boolean.TRUE.equals(recent)) return;
            Timestamp ts = Timestamp.from(at);
            long id = insert(receiverId, type, ts);
            jdbc.update("INSERT INTO notification_actor (notification_id, actor_id, created_at) VALUES (?, ?, ?)", id, actorId, ts);
        });
    }

    /**
     * 팔로우한 사람의 새 글 (FR-004·FR-006), 구독한 시리즈의 새 글 (072). 팔로우와 구독을 함께 하는 사람에게는 하나만 간다. 처음 전체 공개될 때 한 번 오는 사건이라 중복이 없다. 처리 시점에 이미 공개 목록 조건을
     * 벗어났으면 만들지 않는다. 팔로워 전원에게 문장 하나로 만든다(끈 사람·탈퇴 신청한 사람 제외). 행동자 = 글 작성자라 따로 적지 않는다.
     */
    public int newPost(long postId, long authorId, Instant at) {
        return Objects.requireNonNullElse(tx.execute(s -> {
            Boolean listed = jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM post p JOIN member m ON m.id = p.author_id WHERE p.id = ? AND "
                    + PostAccessPolicy.PUBLIC_LIST_CONDITION + " AND m.status <> 'WITHDRAWN' AND m.deleted_at IS NULL)", Boolean.class, postId);
            if (!Boolean.TRUE.equals(listed)) return 0;
            Timestamp ts = Timestamp.from(at);
            return publish(jdbc.query("""
                    WITH receivers AS (
                        SELECT r.id AS follower_id FROM member r
                        WHERE r.id IN (SELECT f.follower_id FROM follow f WHERE f.followee_id = ?
                                       UNION\s""" + SeriesSql.SUBSCRIBERS_OF_POST + """
                                      )
                          AND r.id <> ? AND r.status <> 'WITHDRAWN' AND r.deleted_at IS NULL
                          AND NOT EXISTS (SELECT 1 FROM notification_mute nm WHERE nm.member_id = r.id AND nm.type = 'NEW_POST')
                    ), made AS (
                        INSERT INTO notification (receiver_id, type, created_at, updated_at)
                        SELECT follower_id, 'NEW_POST', ?, ? FROM receivers RETURNING id, receiver_id
                    ), linked AS (
                        INSERT INTO notification_post (notification_id, type, post_id) SELECT id, 'NEW_POST', ? FROM made RETURNING notification_id
                    )
                    SELECT made.id, made.receiver_id FROM made JOIN linked ON linked.notification_id = made.id
                    """, CREATED_ROW, authorId, postId, authorId, ts, ts, postId), NotificationType.NEW_POST);
        }), 0);
    }

    /**
     * 신고 처리 결과 (019 FR-039, docs/25 §2): 사건의 신고 한 건(신고자 한 명)마다 하나. 결과는 사건 상태에서 읽어 따로 저장하지 않는다.
     * 탈퇴 신청한 신고자에게는 만들지 않는다. 운영 알림이라 끌 수 없다.
     */
    public int reportsResolved(long caseId, Instant at) {
        return Objects.requireNonNullElse(tx.execute(s -> {
            Timestamp ts = Timestamp.from(at);
            return publish(jdbc.query("""
                    WITH targets AS (
                        SELECT r.id AS report_id, r.reporter_id FROM report r JOIN report_case rc ON rc.id = r.case_id
                        JOIN member m ON m.id = r.reporter_id
                        WHERE r.case_id = ? AND rc.status IN ('HIDDEN', 'REJECTED') AND m.status <> 'WITHDRAWN' AND m.deleted_at IS NULL
                          AND NOT EXISTS (SELECT 1 FROM notification_report x WHERE x.report_id = r.id)
                    ), made AS (
                        INSERT INTO notification (receiver_id, type, created_at, updated_at)
                        SELECT reporter_id, 'REPORT_RESOLVED', ?, ? FROM targets ORDER BY report_id RETURNING id, receiver_id
                    ), linked AS (
                        INSERT INTO notification_report (notification_id, type, report_id)
                        SELECT made.id, 'REPORT_RESOLVED', targets.report_id FROM made JOIN targets ON targets.reporter_id = made.receiver_id
                        RETURNING notification_id
                    )
                    SELECT made.id, made.receiver_id FROM made WHERE made.id IN (SELECT notification_id FROM linked)
                    """, CREATED_ROW, caseId, ts, ts), NotificationType.REPORT_RESOLVED);
        }), 0);
    }

    /**
     * 내 콘텐츠 숨김 (019 FR-040): 작성자에게 한 번. 사유는 보여줄 때 대상에서 읽는다. 댓글이 숨겨지면 그 댓글로 생긴 알림은 지운다.
     */
    public void contentHidden(long caseId, long ownerId, boolean comment, long targetId, Instant at) {
        if (comment) commentDeleted(targetId);
        tx.executeWithoutResult(s -> {
            Boolean active = jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM member WHERE id = ? AND status <> 'WITHDRAWN' AND deleted_at IS NULL)",
                    Boolean.class, ownerId);
            if (!Boolean.TRUE.equals(active)) return;
            if (Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM notification_case WHERE case_id = ?)", Boolean.class, caseId))) {
                return;
            }
            long id = insert(ownerId, NotificationType.CONTENT_HIDDEN, Timestamp.from(at));
            jdbc.update("INSERT INTO notification_case (notification_id, type, case_id) VALUES (?, 'CONTENT_HIDDEN', ?)", id, caseId);
        });
    }

    /** 문의 답변 (054): 답변이 새로 붙거나 바뀔 때마다 접수한 회원에게. 탈퇴 신청한 회원에게는 만들지 않는다. 운영 알림이라 끌 수 없다. */
    public void inquiryAnswered(long inquiryId, long memberId, Instant at) {
        tx.executeWithoutResult(s -> {
            Boolean active = jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM member WHERE id = ? AND status <> 'WITHDRAWN' AND deleted_at IS NULL)",
                    Boolean.class, memberId);
            if (!Boolean.TRUE.equals(active)) return;
            long id = insert(memberId, NotificationType.INQUIRY_ANSWERED, Timestamp.from(at));
            jdbc.update("INSERT INTO notification_inquiry (notification_id, type, inquiry_id) VALUES (?, 'INQUIRY_ANSWERED', ?)", id, inquiryId);
        });
    }

    /**
     * AI 글 제안 (071): 연결한 AI가 새 제안을 남기면 그 회원에게. 같은 제목의 제안을 고쳐 쓴 것은 새 제안이 아니라 다시 알리지 않는다.
     * 받는 사람이 끈 종류이거나 탈퇴 신청했으면 만들지 않는다. 제안을 지우면(탈퇴 정리) 알림도 FK로 함께 지워진다.
     */
    public void aiProposed(long proposalId, long memberId, Instant at) {
        tx.executeWithoutResult(s -> {
            Boolean ok = jdbc.queryForObject("""
                    SELECT EXISTS (SELECT 1 FROM member m JOIN ai_post_proposal p ON p.member_id = m.id AND p.id = ? AND p.status = 'OPEN'
                                   WHERE m.id = ? AND m.status <> 'WITHDRAWN' AND m.deleted_at IS NULL
                                     AND NOT EXISTS (SELECT 1 FROM notification_mute nm WHERE nm.member_id = m.id AND nm.type = 'AI_PROPOSAL'))
                    """, Boolean.class, proposalId, memberId);
            if (!Boolean.TRUE.equals(ok)) return;
            long id = insert(memberId, NotificationType.AI_PROPOSAL, Timestamp.from(at));
            jdbc.update("INSERT INTO notification_ai_proposal (notification_id, type, proposal_id) VALUES (?, 'AI_PROPOSAL', ?)", id, proposalId);
        });
    }

    private boolean following(long followerId, long followeeId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM follow WHERE follower_id = ? AND followee_id = ?)",
                Boolean.class, followerId, followeeId));
    }

    private void createComment(NotificationType type, long receiverId, long actorId, long postId, long commentId, Instant at) {
        if (!allowed(type, receiverId, actorId, postId)) return;
        tx.executeWithoutResult(s -> {
            long id = insert(receiverId, type, Timestamp.from(at));
            jdbc.update("INSERT INTO notification_comment (notification_id, type, comment_id) VALUES (?, ?, ?)", id, type.name(), commentId);
        });
    }

    /** FR-005 ①~⑤. postId가 null이면(팔로우) ⑤를 건너뛴다. */
    private boolean allowed(NotificationType type, long receiverId, long actorId, Long postId) {
        if (receiverId == actorId) return false;
        List<Boolean> withdrawn = jdbc.query("SELECT status = 'WITHDRAWN' OR deleted_at IS NOT NULL FROM member WHERE id IN (?, ?)",
                (rs, i) -> rs.getBoolean(1), receiverId, actorId);
        if (withdrawn.size() != 2 || withdrawn.contains(true)) return false;
        if (type.mutable() && Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM notification_mute WHERE member_id = ? AND type = ?)", Boolean.class, receiverId, type.name()))) {
            return false;
        }
        return postId == null || readable(postId, receiverId);
    }

    /** 글 상세와 같은 판정 (FR-005 ⑤): 발행·숨김 아님·공개 범위 규칙. */
    boolean readable(long postId, long viewerId) {
        List<ReadablePost> rows = jdbc.query("""
                SELECT p.author_id, p.status, p.visibility, p.deleted_at IS NOT NULL AS deleted, p.hidden_at IS NOT NULL AS hidden,
                       m.withdrawn_at IS NOT NULL AS withdrawn
                FROM post p JOIN member m ON m.id = p.author_id WHERE p.id = ?
                """, (rs, i) -> new ReadablePost(rs.getLong("author_id"), PostStatus.valueOf(rs.getString("status")),
                Visibility.valueOf(rs.getString("visibility")), rs.getBoolean("deleted"), rs.getBoolean("hidden"),
                rs.getBoolean("withdrawn")), postId);
        if (rows.isEmpty()) return false;
        ReadablePost p = rows.get(0);
        return p.status() == PostStatus.PUBLISHED && !p.hidden() && policy.canRead(p, new Viewer(viewerId, false));
    }

    private boolean commentAlive(long commentId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM comment WHERE id = ? AND deleted_at IS NULL AND hidden_at IS NULL)", Boolean.class, commentId));
    }

    private Long unread(long receiverId, NotificationType type) {
        return jdbc.query("SELECT id FROM notification WHERE receiver_id = ? AND type = ? AND read_at IS NULL ORDER BY id DESC LIMIT 1",
                (rs, i) -> rs.getLong(1), receiverId, type.name()).stream().findFirst().orElse(null);
    }

    private Long unreadLike(long receiverId, long postId) {
        return jdbc.query("""
                SELECT n.id FROM notification n JOIN notification_post np ON np.notification_id = n.id
                WHERE n.receiver_id = ? AND n.type = 'LIKE' AND n.read_at IS NULL AND np.post_id = ?
                ORDER BY n.id DESC LIMIT 1
                """, (rs, i) -> rs.getLong(1), receiverId, postId).stream().findFirst().orElse(null);
    }

    private static final RowMapper<long[]> CREATED_ROW = (rs, i) -> new long[] {rs.getLong(1), rs.getLong(2)};

    /** 한 문장으로 여러 행을 만든 경우에도 행마다 사건을 낸다(023 텔레그램). @return 만든 수 */
    private int publish(List<long[]> rows, NotificationType type) {
        rows.forEach(r -> events.publishEvent(new NotificationCreated(r[0], r[1], type)));
        return rows.size();
    }

    /** 새 알림 행. 커밋 뒤 다른 전달 수단(023 텔레그램)이 받도록 사건을 낸다. 묶음에 사람이 더해질 때는 내지 않는다. */
    private long insert(long receiverId, NotificationType type, Timestamp at) {
        long id = Objects.requireNonNull(jdbc.queryForObject("""
                INSERT INTO notification (receiver_id, type, created_at, updated_at) VALUES (?, ?, ?, ?) RETURNING id
                """, Long.class, receiverId, type.name(), at, at));
        events.publishEvent(new NotificationCreated(id, receiverId, type));
        return id;
    }

    private void lock(long receiverId, NotificationType type, long target) {
        jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))", rs -> null,
                "notification:" + receiverId + ":" + type.name() + ":" + target);
    }
}
