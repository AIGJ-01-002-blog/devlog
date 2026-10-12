package com.team.blog.comment.application;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.team.blog.account.domain.Visibility;
import com.team.blog.post.access.PostAccessPolicy;
import com.team.blog.post.access.ReadablePost;
import com.team.blog.post.access.Viewer;
import com.team.blog.post.domain.PostStatus;
import com.team.blog.post.infra.PostSql;
import com.team.blog.shared.cursor.CursorCodec;
import com.team.blog.shared.error.NotFoundException;
import com.team.blog.shared.markdown.ImageUrls;
import com.team.blog.shared.time.Times;

/**
 * 댓글 읽기 (docs/21 §3·§6, 011 FR-018~FR-030). 한 페이지는 쿼리 두 번이다: 최상위 21개(ix_comment_root)와 그 최상위들의
 * 처음 답글 3개 + 답글 수(ix_comment_reply, 창 함수). 상태는 탈퇴 → 삭제된 자리 → 숨김 → 정상 순서로 하나만 정하고,
 * 내용·작성자를 내려보내지 않는 상태에서는 아예 빼고 보낸다.
 */
@Service
public class CommentQuery {
    public static final int PAGE_SIZE = 20;
    public static final int FIRST_REPLIES = 3;

    private static final String COLUMNS = """
            c.id, c.post_id, c.parent_id, c.author_id, c.content, c.created_at, c.updated_at,
            c.deleted_at IS NOT NULL AS deleted, c.hidden_at IS NOT NULL AS hidden,
            (m.withdrawn_at IS NOT NULL OR m.deleted_at IS NOT NULL) AS author_gone, m.handle, m.nickname,
            """ + PostSql.PROFILE_IMAGE_KEY + """
            ,
            c.reply_to_member_id, rt.handle AS rt_handle, rt.nickname AS rt_nickname,
            (rt.withdrawn_at IS NOT NULL OR rt.deleted_at IS NOT NULL) AS rt_gone
            """;
    private static final String FROM = """
            FROM comment c
            JOIN member m ON m.id = c.author_id
            """ + PostSql.PROFILE_IMAGE_JOIN + """

            LEFT JOIN member rt ON rt.id = c.reply_to_member_id
            """;
    private static final String SELECT_ROWS = "SELECT " + COLUMNS + FROM;

    private final JdbcTemplate jdbc;
    private final PostAccessPolicy policy;
    private final CursorCodec cursors;
    private final ImageUrls imageUrls;

    public CommentQuery(JdbcTemplate jdbc, PostAccessPolicy policy, CursorCodec cursors, ImageUrls imageUrls) {
        this.jdbc = jdbc;
        this.policy = policy;
        this.cursors = cursors;
        this.imageUrls = imageUrls;
    }

    public enum State { NORMAL, DELETED, HIDDEN, WITHDRAWN_AUTHOR }

    public record Author(String handle, String nickname, String profileImageUrl, boolean isPostAuthor) {}

    /** 답글 대상. 대상이 탈퇴했으면 handle·nickname 없이 withdrawn=true. */
    public record ReplyTo(String handle, String nickname, boolean withdrawn) {}

    public record CommentView(long id, State state, String content, Instant createdAt, boolean edited, Author author,
                              ReplyTo replyTo, boolean mine, List<CommentView> replies, Integer replyCount,
                              String repliesNextCursor) {}

    /**
     * @param canWrite    지금 보는 사람이 댓글을 쓸 수 있는지 (로그인 + 쓸 수 있는 글)
     * @param prevCursor  특정 댓글부터 볼 때 앞에 댓글이 더 있으면 [이전 댓글 보기]용
     */
    public record CommentPage(List<CommentView> items, String nextCursor, String prevCursor, long commentCount,
                              boolean canWrite, boolean publiclyVisible) {}

    public record ReplyPage(List<CommentView> items, String nextCursor) {}

    /** 댓글 판정에 쓰는 글 상태. 임시글은 작성자에게도 댓글이 없는 글이다 (FR-040). */
    public record PostGate(long postId, long authorId, boolean readable, boolean writable, boolean publiclyVisible) {}

    public PostGate gate(long postId, Viewer viewer) {
        List<PostGate> rows = jdbc.query("""
                SELECT p.id, p.author_id, p.status, p.visibility, p.deleted_at IS NOT NULL AS deleted, p.hidden_at IS NOT NULL AS hidden,
                       (m.withdrawn_at IS NOT NULL OR m.deleted_at IS NOT NULL) AS withdrawn
                FROM post p JOIN member m ON m.id = p.author_id WHERE p.id = ?
                """, (rs, i) -> {
            PostStatus status = PostStatus.valueOf(rs.getString("status"));
            Visibility visibility = Visibility.valueOf(rs.getString("visibility"));
            boolean hidden = rs.getBoolean("hidden");
            ReadablePost rp = new ReadablePost(rs.getLong("author_id"), status, visibility, rs.getBoolean("deleted"), hidden,
                    rs.getBoolean("withdrawn"));
            boolean readable = status == PostStatus.PUBLISHED && policy.canRead(rp, viewer);
            // 숨긴 글은 작성자가 읽을 수는 있어도 댓글은 쓸 수 없다
            return new PostGate(rs.getLong("id"), rp.authorId(), readable, readable && !hidden,
                    readable && !hidden && visibility == Visibility.PUBLIC);
        }, postId);
        return rows.isEmpty() ? new PostGate(postId, 0, false, false, false) : rows.get(0);
    }

    public PostGate readable(long postId, Viewer viewer) {
        PostGate g = gate(postId, viewer);
        if (!g.readable()) throw new NotFoundException();
        return g;
    }

    /** 글 상세 아래 첫 페이지·[댓글 더 보기]·[이전 댓글 보기]·특정 댓글부터. */
    public CommentPage page(long postId, Viewer viewer, String cursor, String before, Long around) {
        PostGate gate = readable(postId, viewer);
        String list = "comments:" + postId;
        List<Row> roots;
        String prev = null;
        Long expandTarget = null;
        if (before != null && !before.isBlank()) {
            long[] k = cursors.decode(before, list + ":prev", 2);
            roots = jdbc.query(SELECT_ROWS
                            + " WHERE c.post_id = ? AND c.parent_id IS NULL AND (c.created_at, c.id) < (?, ?)"
                            + " ORDER BY c.created_at DESC, c.id DESC LIMIT " + (PAGE_SIZE + 1),
                    this::row, postId, ts(k[0]), k[1]);
            boolean more = roots.size() > PAGE_SIZE;
            roots = new ArrayList<>(roots.subList(0, Math.min(PAGE_SIZE, roots.size())));
            Collections.reverse(roots);
            if (more) prev = prevCursor(list, roots.get(0));
            // 이전 쪽은 다음 커서를 주지 않는다: 그 뒤는 이미 화면에 있다
            return new CommentPage(views(roots, gate, viewer, null), null, prev, count(postId), gate.writable() && viewer.memberId() != null,
                    gate.publiclyVisible());
        }
        Optional<Row> anchor = around == null ? Optional.empty() : anchorRoot(postId, around);
        if (anchor.isPresent()) {
            Row root = anchor.get();
            roots = jdbc.query(SELECT_ROWS
                            + " WHERE c.post_id = ? AND c.parent_id IS NULL AND (c.created_at, c.id) >= (?, ?)"
                            + " ORDER BY c.created_at, c.id LIMIT " + (PAGE_SIZE + 1),
                    this::row, postId, Timestamp.from(root.createdAt), root.id);
            Boolean earlier = jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM comment c WHERE c.post_id = ? AND c.parent_id IS NULL"
                    + " AND (c.created_at, c.id) < (?, ?))", Boolean.class, postId, Timestamp.from(root.createdAt), root.id);
            if (Boolean.TRUE.equals(earlier)) prev = prevCursor(list, root);
            expandTarget = around;
        } else {
            List<Object> args = new ArrayList<>(List.of(postId));
            String after = "";
            if (cursor != null && !cursor.isBlank()) {
                long[] k = cursors.decode(cursor, list, 2);
                after = " AND (c.created_at, c.id) > (?, ?)";
                args.add(ts(k[0]));
                args.add(k[1]);
            }
            roots = jdbc.query(SELECT_ROWS + " WHERE c.post_id = ? AND c.parent_id IS NULL" + after
                    + " ORDER BY c.created_at, c.id LIMIT " + (PAGE_SIZE + 1), this::row, args.toArray());
        }
        String next = null;
        if (roots.size() > PAGE_SIZE) {
            roots = roots.subList(0, PAGE_SIZE);
            Row last = roots.get(roots.size() - 1);
            next = cursors.encode(list, Times.toEpochMicros(last.createdAt), last.id);
        }
        return new CommentPage(views(roots, gate, viewer, expandTarget), next, prev, count(postId),
                gate.writable() && viewer.memberId() != null, gate.publiclyVisible());
    }

    /** [답글 N개 더 보기]: 4번째부터 20개씩. */
    public ReplyPage replies(long rootId, Viewer viewer, String cursor) {
        List<long[]> root = jdbc.query("SELECT post_id, parent_id FROM comment WHERE id = ?",
                (rs, i) -> new long[] {rs.getLong(1), rs.getObject(2) == null ? 0 : 1}, rootId);
        if (root.isEmpty() || root.get(0)[1] != 0) throw new NotFoundException();
        PostGate gate = readable(root.get(0)[0], viewer);
        String list = "replies:" + rootId;
        List<Object> args = new ArrayList<>(List.of(rootId));
        String after = "";
        if (cursor != null && !cursor.isBlank()) {
            long[] k = cursors.decode(cursor, list, 2);
            after = " AND (c.created_at, c.id) > (?, ?)";
            args.add(ts(k[0]));
            args.add(k[1]);
        }
        List<Row> rows = jdbc.query(SELECT_ROWS + " WHERE c.parent_id = ?" + after
                + " ORDER BY c.created_at, c.id LIMIT " + (PAGE_SIZE + 1), this::row, args.toArray());
        String next = null;
        if (rows.size() > PAGE_SIZE) {
            rows = rows.subList(0, PAGE_SIZE);
            Row last = rows.get(rows.size() - 1);
            next = cursors.encode(list, Times.toEpochMicros(last.createdAt), last.id);
        }
        List<CommentView> items = rows.stream().map(r -> view(r, gate, viewer, null, null, null)).toList();
        return new ReplyPage(items, next);
    }

    /** 작성·수정 응답용 댓글 하나. */
    public CommentView one(long commentId, Viewer viewer) {
        List<Row> rows = jdbc.query(SELECT_ROWS + " WHERE c.id = ?", this::row, commentId);
        if (rows.isEmpty()) throw new NotFoundException();
        Row r = rows.get(0);
        PostGate gate = readable(r.postId, viewer);
        return view(r, gate, viewer, r.parentId == null ? List.of() : null, r.parentId == null ? 0 : null, null);
    }

    public long count(long postId) {
        Long n = jdbc.queryForObject("SELECT comment_count FROM post_stat WHERE post_id = ?", Long.class, postId);
        return n == null ? 0 : n;
    }

    // ------------------------------------------------------------------

    /** 특정 댓글의 최상위. 그 글의 정상 댓글이 아니면 비어 있다(있는지 드러내지 않음, FR-027). */
    private Optional<Row> anchorRoot(long postId, long commentId) {
        List<Row> rows = jdbc.query(SELECT_ROWS + """
                 WHERE c.id = (SELECT COALESCE(t.parent_id, t.id) FROM comment t JOIN member tm ON tm.id = t.author_id
                               WHERE t.id = ? AND t.post_id = ? AND t.deleted_at IS NULL AND t.hidden_at IS NULL
                                 AND tm.withdrawn_at IS NULL AND tm.deleted_at IS NULL)
                """, this::row, commentId, postId);
        return rows.stream().findFirst();
    }

    private List<CommentView> views(List<Row> roots, PostGate gate, Viewer viewer, Long expandTarget) {
        if (roots.isEmpty()) return List.of();
        Long[] ids = roots.stream().map(r -> r.id).toArray(Long[]::new);
        // 대상이 4번째 이후 답글이면 그 최상위는 대상까지 펼친다
        Long expandRoot = null;
        int expandTo = FIRST_REPLIES;
        if (expandTarget != null) {
            List<long[]> pos = jdbc.query("""
                    SELECT t.parent_id, (SELECT count(*) FROM comment o WHERE o.parent_id = t.parent_id
                                          AND (o.created_at, o.id) <= (t.created_at, t.id))
                    FROM comment t WHERE t.id = ? AND t.parent_id IS NOT NULL
                    """, (rs, i) -> new long[] {rs.getLong(1), rs.getLong(2)}, expandTarget);
            if (!pos.isEmpty()) {
                expandRoot = pos.get(0)[0];
                expandTo = (int) Math.max(FIRST_REPLIES, pos.get(0)[1]);
            }
        }
        Map<Long, List<Row>> replies = new LinkedHashMap<>();
        Map<Long, Integer> replyCounts = new LinkedHashMap<>();
        jdbc.query("SELECT * FROM (SELECT " + COLUMNS + """
                       , row_number() OVER (PARTITION BY c.parent_id ORDER BY c.created_at, c.id) AS rn,
                         count(*) OVER (PARTITION BY c.parent_id) AS reply_count
                """ + FROM + """
                 WHERE c.parent_id = ANY (?)) t
                WHERE t.rn <= CASE WHEN t.parent_id = ? THEN ? ELSE ? END
                ORDER BY t.parent_id, t.created_at, t.id
                """, rs -> {
            Row r = row(rs, 0);
            replies.computeIfAbsent(r.parentId, k -> new ArrayList<>()).add(r);
            replyCounts.put(r.parentId, rs.getInt("reply_count"));
        }, ids, expandRoot == null ? -1L : expandRoot, expandTo, FIRST_REPLIES);

        List<CommentView> out = new ArrayList<>(roots.size());
        for (Row root : roots) {
            List<Row> rs = replies.getOrDefault(root.id, List.of());
            int total = replyCounts.getOrDefault(root.id, 0);
            String more = null;
            if (total > rs.size()) {
                Row last = rs.get(rs.size() - 1);
                more = cursors.encode("replies:" + root.id, Times.toEpochMicros(last.createdAt), last.id);
            }
            List<CommentView> rv = rs.stream().map(r -> view(r, gate, viewer, null, null, null)).toList();
            out.add(view(root, gate, viewer, rv, total, more));
        }
        return out;
    }

    private CommentView view(Row r, PostGate gate, Viewer viewer, List<CommentView> replies, Integer replyCount, String more) {
        boolean mine = viewer.is(r.authorId);
        State state = stateOf(r);
        boolean showBody = state == State.NORMAL || (state == State.HIDDEN && mine);
        Author author = showBody ? new Author(r.handle, r.nickname, imageUrls.urlOf(r.profileImageKey), r.authorId == gate.authorId()) : null;
        ReplyTo replyTo = r.replyToMemberId == null || !showBody ? null : replyToOf(r);
        return new CommentView(r.id, state, showBody ? r.content : null, r.createdAt,
                showBody && r.updatedAt.isAfter(r.createdAt), author, replyTo, mine && state != State.DELETED, replies, replyCount, more);
    }

    /** 탈퇴 → 삭제된 자리 → 숨김 → 정상 순서로 하나만. */
    private static State stateOf(Row r) {
        if (r.authorGone) return State.WITHDRAWN_AUTHOR;
        if (r.deleted) return State.DELETED;
        if (r.hidden) return State.HIDDEN;
        return State.NORMAL;
    }

    private static ReplyTo replyToOf(Row r) {
        return r.replyToGone ? new ReplyTo(null, null, true) : new ReplyTo(r.replyToHandle, r.replyToNickname, false);
    }

    private String prevCursor(String list, Row first) {
        return cursors.encode(list + ":prev", Times.toEpochMicros(first.createdAt), first.id);
    }

    private static Timestamp ts(long micros) {
        return Timestamp.from(Times.fromEpochMicros(micros));
    }

    private record Row(long id, long postId, Long parentId, long authorId, String content, Instant createdAt, Instant updatedAt,
                       boolean deleted, boolean hidden, boolean authorGone, String handle, String nickname,
                       String profileImageKey, Long replyToMemberId, String replyToHandle, String replyToNickname,
                       boolean replyToGone) {}

    private Row row(ResultSet rs, int i) throws SQLException {
        return new Row(rs.getLong("id"), rs.getLong("post_id"), rs.getObject("parent_id", Long.class),
                rs.getLong("author_id"), rs.getString("content"), rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant(), rs.getBoolean("deleted"), rs.getBoolean("hidden"),
                rs.getBoolean("author_gone"), rs.getString("handle"), rs.getString("nickname"), rs.getString("profile_image_key"),
                rs.getObject("reply_to_member_id", Long.class), rs.getString("rt_handle"), rs.getString("rt_nickname"),
                rs.getBoolean("rt_gone"));
    }
}
