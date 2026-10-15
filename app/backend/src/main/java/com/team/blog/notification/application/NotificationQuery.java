package com.team.blog.notification.application;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.team.blog.account.domain.Visibility;
import com.team.blog.post.access.PostAccessPolicy;
import com.team.blog.post.access.ReadablePost;
import com.team.blog.post.access.Viewer;
import com.team.blog.post.domain.PostStatus;
import com.team.blog.shared.cursor.CursorCodec;
import com.team.blog.shared.error.NotFoundException;
import com.team.blog.shared.time.Times;

/**
 * 내 알림 보기·읽음·삭제·설정 (015 US2·US3·US5, docs/25 §2·§3·§5). 주소에 회원 번호가 없고 로그인한 본인 알림만 다룬다.
 * 남의 알림 번호는 관리자를 포함해 404다(FR-033).
 * <p>
 * 보여줄 때 지금의 닉네임·제목·댓글 내용을 다시 읽고(FR-017), 받는 사람이 지금 그 글을 읽을 수 없으면 제목·내용을 빼고
 * 링크를 없앤다(FR-018). 목록은 쿼리 한 번으로 행동한 사람·글·읽기 판정 재료를 함께 읽는다(FR-032).
 */
@Service
public class NotificationQuery {
    static final int COMMENT_PREVIEW = 50;
    /** AI 글 제안 알림이 가는 곳 (071): 내 글 관리 임시글 탭의 제안 목록 */
    static final String PROPOSALS_LINK = "/manage/posts?tab=drafts#ai-proposals";
    static final String FRIENDS_LINK = "/settings#friends";
    private static final String POSTS_SEGMENT = "/posts/";

    private final JdbcTemplate jdbc;
    private final CursorCodec cursors;
    private final PostAccessPolicy policy;

    public NotificationQuery(JdbcTemplate jdbc, CursorCodec cursors, PostAccessPolicy policy) {
        this.jdbc = jdbc;
        this.cursors = cursors;
        this.policy = policy;
    }

    /** @param withdrawn 탈퇴 신청·익명 처리된 사람이면 true이고 닉네임·주소는 null ("탈퇴한 사용자") */
    public record Actor(String nickname, String handle, boolean withdrawn) {}

    /** @param title 받는 사람이 지금 그 글을 볼 수 없으면 null (화면은 "볼 수 없는 글이에요") */
    public record PostRef(Long id, String title, boolean readable) {}

    /**
     * @param actor        대표(가장 최근) 행동자
     * @param othersCount  묶음에서 대표를 뺀 사람 수 ("외 N명")
     * @param commentPreview 댓글 내용 앞 50자 (글자 그대로, 화면이 이스케이프)
     * @param link         누르면 갈 곳. 볼 수 없으면 null
     */
    /**
     * 내 콘텐츠 숨김 알림의 대상 (docs/25 §2). reason은 지금 숨김 사유 코드이고, 그 사이 숨김이 풀렸으면 stillHidden=false·reason=null
     * ("숨겨졌었어요 (지금은 다시 보여요)").
     */
    public record Hidden(String targetType, String reason, boolean stillHidden) {}

    /**
     * @param result 신고 처리 결과 (REPORT_RESOLVED): ACTION_TAKEN / NO_VIOLATION. 신고 대상의 내용·작성자는 싣지 않는다
     * @param hidden 숨김 알림(CONTENT_HIDDEN)의 대상
     * @param inquiry 답변이 온 내 문의(INQUIRY_ANSWERED, 054)
     * @param proposal AI가 남긴 글 제안(AI_PROPOSAL, 071)
     */
    public record Item(long id, NotificationType type, boolean read, Instant at, Actor actor, int othersCount,
                       PostRef post, String commentPreview, String link, String result, Hidden hidden, InquiryRef inquiry,
                       ProposalRef proposal) {}

    /** AI 글 제안 (071). status는 지금 상태(OPEN·DRAFTED·DISMISSED) */
    public record ProposalRef(long id, String title, String status) {}

    /** 답변이 온 내 문의 (054). status는 지금 처리 상태 */
    public record InquiryRef(long id, String title, String status) {}

    public record Page(List<Item> items, String nextCursor) {}

    public record Settings(Set<NotificationType> muted) {}

    private static final String SELECT = """
                SELECT n.id, n.type, n.read_at, n.updated_at,
                       nc.comment_id, c.content AS comment_content, c.deleted_at IS NOT NULL OR c.hidden_at IS NOT NULL AS comment_gone,
                       p.id AS post_id, p.title, p.author_id, p.status, p.visibility,
                       p.deleted_at IS NOT NULL AS post_deleted, p.hidden_at IS NOT NULL AS post_hidden,
                       pa.handle AS post_handle, pa.withdrawn_at IS NOT NULL AS post_author_withdrawn,
                       a.actor_count, am.nickname AS actor_nickname, am.handle AS actor_handle,
                       am.status = 'WITHDRAWN' OR am.deleted_at IS NOT NULL AS actor_withdrawn,
                       rc.status AS case_status, rc.target_type AS case_target, rc.comment_id AS hidden_comment_id,
                       p.hidden_reason AS post_hidden_reason, hc.hidden_at IS NOT NULL AS comment_hidden, hc.hidden_reason AS comment_hidden_reason,
                       iq.id AS inquiry_id, iq.title AS inquiry_title, iq.status AS inquiry_status,
                       ap.id AS proposal_id, ap.title AS proposal_title, ap.status AS proposal_status
                FROM notification n
                LEFT JOIN notification_comment nc ON nc.notification_id = n.id
                LEFT JOIN comment c ON c.id = nc.comment_id
                LEFT JOIN notification_post np ON np.notification_id = n.id
                LEFT JOIN notification_report nr ON nr.notification_id = n.id
                LEFT JOIN report rp ON rp.id = nr.report_id
                LEFT JOIN notification_case ncs ON ncs.notification_id = n.id
                LEFT JOIN notification_inquiry ni ON ni.notification_id = n.id
                LEFT JOIN inquiry iq ON iq.id = ni.inquiry_id
                LEFT JOIN notification_ai_proposal nap ON nap.notification_id = n.id
                LEFT JOIN ai_post_proposal ap ON ap.id = nap.proposal_id
                LEFT JOIN report_case rc ON rc.id = COALESCE(rp.case_id, ncs.case_id)
                LEFT JOIN comment hc ON hc.id = rc.comment_id AND n.type = 'CONTENT_HIDDEN'
                LEFT JOIN post p ON p.id = COALESCE(c.post_id, np.post_id, CASE WHEN n.type = 'CONTENT_HIDDEN' THEN COALESCE(rc.post_id, hc.post_id) END)
                LEFT JOIN member pa ON pa.id = p.author_id
                LEFT JOIN LATERAL (SELECT x.actor_id, count(*) OVER () AS actor_count FROM notification_actor x
                                   WHERE x.notification_id = n.id ORDER BY x.created_at DESC, x.actor_id DESC LIMIT 1) a ON true
                LEFT JOIN member am ON am.id = COALESCE(a.actor_id, c.author_id, CASE WHEN n.type = 'NEW_POST' THEN p.author_id END)
            """;

    /** 알림 하나를 화면 목록과 같은 판정으로 (023 텔레그램 FR-005). 그 사이 지워졌으면 비어 있다. */
    public Optional<Item> find(long memberId, long id) {
        return jdbc.query(SELECT + "                WHERE n.receiver_id = ? AND n.id = ?", this::row, memberId, id).stream()
                .findFirst().map(r -> item(r, memberId));
    }

    public long unreadCount(long memberId) {
        Long n = jdbc.queryForObject("SELECT count(*) FROM notification WHERE receiver_id = ? AND read_at IS NULL", Long.class, memberId);
        return n == null ? 0 : n;
    }

    /** 마지막 갱신 시각 최신순, 같으면 번호 큰 순 (FR-030). */
    public Page page(long memberId, String cursor, int size) {
        String list = "notifications:" + memberId;
        List<Object> args = new ArrayList<>(List.of(memberId));
        String after = "";
        long[] k = cursors.decode(cursor, list, 2);
        if (k.length > 0) {
            after = " AND (n.updated_at, n.id) < (?, ?)";
            args.add(Timestamp.from(Times.fromEpochMicros(k[0])));
            args.add(k[1]);
        }
        args.add(size + 1);
        List<Row> rows = jdbc.query(SELECT + "WHERE n.receiver_id = ?" + after + """

                ORDER BY n.updated_at DESC, n.id DESC LIMIT ?
                """, this::row, args.toArray());
        List<Item> items = rows.stream().map(r -> item(r, memberId)).toList();
        CursorCodec.Page<Item> page = CursorCodec.page(items, size, i -> cursors.encode(list, Times.toEpochMicros(i.at()), i.id()));
        return new Page(page.items(), page.nextCursor());
    }

    /** 누른 알림 하나만 읽음 (FR-028). 이미 읽었으면 그대로. */
    @Transactional
    public void markRead(long memberId, long id) {
        int n = jdbc.update("UPDATE notification SET read_at = COALESCE(read_at, now()) WHERE id = ? AND receiver_id = ?", id, memberId);
        if (n == 0) throw new NotFoundException();
    }

    /** 지금 시각까지의 안 읽은 알림 모두 (FR-028). @return 바뀐 개수 */
    @Transactional
    public int markAllRead(long memberId) {
        return jdbc.update("UPDATE notification SET read_at = now() WHERE receiver_id = ? AND read_at IS NULL AND updated_at <= now()", memberId);
    }

    @Transactional
    public void delete(long memberId, long id) {
        if (jdbc.update("DELETE FROM notification WHERE id = ? AND receiver_id = ?", id, memberId) == 0) throw new NotFoundException();
    }

    public Settings settings(long memberId) {
        Set<NotificationType> muted = EnumSet.noneOf(NotificationType.class);
        jdbc.query("SELECT type FROM notification_mute WHERE member_id = ?",
                (RowCallbackHandler) rs -> muted.add(NotificationType.valueOf(rs.getString(1))), memberId);
        return new Settings(muted);
    }

    /** 끈 종류만 기록한다 (FR-036). 운영 알림은 끌 수 없어 무시한다(FR-035). */
    @Transactional
    public Settings updateSettings(long memberId, Set<NotificationType> muted) {
        Set<NotificationType> allowed = EnumSet.noneOf(NotificationType.class);
        for (NotificationType t : muted) if (t.mutable()) allowed.add(t);
        String[] names = allowed.stream().map(Enum::name).toArray(String[]::new);
        jdbc.update(con -> {
            var ps = con.prepareStatement("DELETE FROM notification_mute WHERE member_id = ? AND NOT (type = ANY (?))");
            ps.setLong(1, memberId);
            ps.setArray(2, con.createArrayOf("varchar", names));
            return ps;
        });
        for (String t : names) {
            jdbc.update("INSERT INTO notification_mute (member_id, type) VALUES (?, ?) ON CONFLICT DO NOTHING", memberId, t);
        }
        return settings(memberId);
    }

    private record Row(long id, NotificationType type, boolean read, Instant at, Long commentId, String commentContent,
                       boolean commentGone, Long postId, String title, ReadablePost post, String postHandle,
                       int actorCount, String actorNickname, String actorHandle, boolean actorWithdrawn,
                       String caseStatus, String caseTarget, Long hiddenCommentId, String postHiddenReason,
                       boolean commentHidden, String commentHiddenReason, InquiryRef inquiry, ProposalRef proposal) {}

    private Row row(ResultSet rs, int i) throws SQLException {
        long postId = rs.getLong("post_id");
        boolean hasPost = !rs.wasNull();
        ReadablePost post = hasPost ? new ReadablePost(rs.getLong("author_id"), PostStatus.valueOf(rs.getString("status")),
                Visibility.valueOf(rs.getString("visibility")), rs.getBoolean("post_deleted"), rs.getBoolean("post_hidden"),
                rs.getBoolean("post_author_withdrawn")) : null;
        long commentId = rs.getLong("comment_id");
        boolean hasComment = !rs.wasNull();
        return new Row(rs.getLong("id"), NotificationType.valueOf(rs.getString("type")), rs.getTimestamp("read_at") != null,
                rs.getTimestamp("updated_at").toInstant(), hasComment ? commentId : null, rs.getString("comment_content"),
                rs.getBoolean("comment_gone"), hasPost ? postId : null, rs.getString("title"), post, rs.getString("post_handle"),
                rs.getInt("actor_count"), rs.getString("actor_nickname"), rs.getString("actor_handle"), rs.getBoolean("actor_withdrawn"),
                rs.getString("case_status"), rs.getString("case_target"), nullableLong(rs, "hidden_comment_id"),
                rs.getString("post_hidden_reason"), rs.getBoolean("comment_hidden"), rs.getString("comment_hidden_reason"),
                nullableLong(rs, "inquiry_id") == null ? null
                        : new InquiryRef(rs.getLong("inquiry_id"), rs.getString("inquiry_title"), rs.getString("inquiry_status")),
                nullableLong(rs, "proposal_id") == null ? null
                        : new ProposalRef(rs.getLong("proposal_id"), rs.getString("proposal_title"), rs.getString("proposal_status")));
    }

    private static Long nullableLong(ResultSet rs, String col) throws SQLException {
        long v = rs.getLong(col);
        return rs.wasNull() ? null : v;
    }

    private Item item(Row r, long viewerId) {
        if (r.type() == NotificationType.REPORT_RESOLVED) {
            // 신고자에게는 결과만 (대상·작성자·관리자 없음, docs/25 §2)
            String result = "HIDDEN".equals(r.caseStatus()) ? "ACTION_TAKEN" : "NO_VIOLATION";
            return new Item(r.id(), r.type(), r.read(), r.at(), null, 0, null, null, null, result, null, null, null);
        }
        if (r.type() == NotificationType.INQUIRY_ANSWERED) {
            // 문의가 지워졌으면(탈퇴 정리) 알림도 FK로 함께 지워지므로 여기서는 늘 있다
            return new Item(r.id(), r.type(), r.read(), r.at(), null, 0, null, null,
                    r.inquiry() == null ? "/support" : "/support?id=" + r.inquiry().id(), null, null, r.inquiry(), null);
        }
        if (r.type() == NotificationType.AI_PROPOSAL) {
            // 받는 사람 = 제안한 회원이라 늘 볼 수 있다. 제안은 내 글 관리 임시글 탭 위에 있다
            return new Item(r.id(), r.type(), r.read(), r.at(), null, 0, null, null, PROPOSALS_LINK, null, null, null, r.proposal());
        }
        if (r.type() == NotificationType.CONTENT_HIDDEN) return hiddenItem(r, viewerId);
        Actor actor = actor(r);
        int others = Math.max(0, r.actorCount() - 1);
        boolean readable = r.post() != null && r.post().status() == PostStatus.PUBLISHED && !r.post().hidden()
                && policy.canRead(r.post(), new Viewer(viewerId, false));
        PostRef post = null;
        if (r.postId() != null) post = readable ? new PostRef(r.postId(), r.title(), true) : new PostRef(null, null, false);
        String preview = readable && isComment(r.type()) && !r.commentGone() ? preview(r.commentContent()) : null;
        return new Item(r.id(), r.type(), r.read(), r.at(), actor, others, post, preview, link(r, readable, actor), null, null, null, null);
    }

    private static boolean isComment(NotificationType type) {
        return type == NotificationType.COMMENT || type == NotificationType.REPLY;
    }

    /** 일반 알림을 눌렀을 때 갈 곳. 읽을 수 있는 글이면 글(댓글이면 그 댓글 위치)로 간다 */
    private static String link(Row r, boolean readable, Actor actor) {
        String link = null;
        if (readable) {
            String base = "/@" + r.postHandle() + POSTS_SEGMENT + r.postId();
            link = isComment(r.type()) ? base + "?comment=" + r.commentId() + "#comment-" + r.commentId() : base;
        }
        // 새 팔로워는 글이 없고 대표 팔로워의 블로그로 간다
        if ((r.type() == NotificationType.FOLLOW || r.type() == NotificationType.FRIEND_ACCEPTED) && actor != null && !actor.withdrawn()) {
            link = "/@" + actor.handle();
        }
        // 받은 친구 요청은 설정 > 친구에서 수락·거절한다
        if (r.type() == NotificationType.FRIEND_REQUEST) link = FRIENDS_LINK;
        return link;
    }

    private static Actor actor(Row r) {
        if (r.actorNickname() == null) return null;
        return r.actorWithdrawn() ? new Actor(null, null, true) : new Actor(r.actorNickname(), r.actorHandle(), false);
    }

    /**
     * 내 콘텐츠 숨김 (docs/25 §2). 글이면 받는 사람이 작성자라 숨겨진 상태여도 제목과 링크를 준다. 댓글이면 글 제목은 주지 않고,
     * 그 글을 지금 읽을 수 있을 때만 댓글 위치로 링크한다.
     */
    private Item hiddenItem(Row r, long viewerId) {
        boolean comment = "COMMENT".equals(r.caseTarget());
        boolean stillHidden = comment ? r.commentHidden() : r.post() != null && r.post().hidden();
        String reason = null;
        if (stillHidden) reason = comment ? r.commentHiddenReason() : r.postHiddenReason();
        Hidden hidden = new Hidden(comment ? "COMMENT" : "POST", reason, stillHidden);
        PostRef post = null;
        String link = null;
        if (r.post() != null && r.postId() != null) {
            if (!comment && policy.canRead(r.post(), new Viewer(viewerId, false))) {
                post = new PostRef(r.postId(), r.title(), true);
                link = "/@" + r.postHandle() + POSTS_SEGMENT + r.postId();
            } else if (comment && r.hiddenCommentId() != null && r.post().status() == PostStatus.PUBLISHED && !r.post().hidden()
                    && policy.canRead(r.post(), new Viewer(viewerId, false))) {
                link = "/@" + r.postHandle() + POSTS_SEGMENT + r.postId() + "?comment=" + r.hiddenCommentId() + "#comment-" + r.hiddenCommentId();
            }
        }
        return new Item(r.id(), r.type(), r.read(), r.at(), null, 0, post, null, link, null, hidden, null, null);
    }

    static String preview(String content) {
        if (content == null) return null;
        String s = content.replaceAll("\\s+", " ").strip();
        return s.codePointCount(0, s.length()) <= COMMENT_PREVIEW ? s : s.substring(0, s.offsetByCodePoints(0, COMMENT_PREVIEW)) + "…";
    }
}
