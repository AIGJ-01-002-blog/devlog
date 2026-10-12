package com.team.blog.follow.application;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.team.blog.post.infra.PostSql;
import com.team.blog.shared.cursor.CursorCodec;
import com.team.blog.shared.error.NotFoundException;
import com.team.blog.shared.jdbc.Columns;
import com.team.blog.shared.markdown.ImageUrls;
import com.team.blog.shared.text.BioText;
import com.team.blog.shared.time.Times;

/**
 * 팔로워·팔로잉 수와 목록 (016 US3). 수는 저장하지 않고 그때그때 세며, 탈퇴 신청한 회원은 수·목록에서 뺀다(FR-011·FR-013).
 * 관계는 지우지 않으므로 복구하면 그대로 돌아온다(FR-027). 목록은 쿼리 한 번에 보는 사람의 팔로우 여부까지 읽는다(FR-014).
 * 주인이 목록을 비공개로 두면(079) 본인·관리자 말고는 빈 목록과 hidden=true만 받는다. 수는 그대로 보인다.
 */
@Service
public class FollowQuery {
    static final int PAGE_SIZE = 20;
    /** 지금 활동 중인 회원 조건 (블로그 주인 찾기와 같음). 별칭 m. */
    static final String ACTIVE = "m.withdrawn_at IS NULL AND m.deleted_at IS NULL";
    static final String FOLLOWER_COUNT =
            "SELECT count(*) FROM follow f JOIN member m ON m.id = f.follower_id WHERE f.followee_id = ? AND " + ACTIVE;
    static final String FOLLOWING_COUNT =
            "SELECT count(*) FROM follow f JOIN member m ON m.id = f.followee_id WHERE f.follower_id = ? AND " + ACTIVE;

    private final JdbcTemplate jdbc;
    private final CursorCodec cursors;
    private final ImageUrls imageUrls;

    public FollowQuery(JdbcTemplate jdbc, CursorCodec cursors, ImageUrls imageUrls) {
        this.jdbc = jdbc;
        this.cursors = cursors;
        this.imageUrls = imageUrls;
    }

    public enum Direction { FOLLOWERS, FOLLOWING }

    public record Counts(long followers, long following) {}

    /**
     * @param following 보는 사람이 이 사람을 팔로우하나. 비회원이면 false
     * @param me        보는 사람 본인이면 true (버튼 없음)
     */
    public record Person(long id, String handle, String nickname, String bioFirstLine, String profileImageUrl,
                         boolean following, boolean me) {}

    /** @param hidden 주인이 목록을 비공개로 둬서 보는 사람에게 보이지 않는다 (079). 이때 items는 비어 있다 */
    public record Page(List<Person> items, String nextCursor, boolean hidden) {
        static final Page HIDDEN_PAGE = new Page(List.of(), null, true);
    }

    public Counts counts(long memberId) {
        return jdbc.queryForObject("SELECT (" + FOLLOWER_COUNT + "), (" + FOLLOWING_COUNT + ")",
                (rs, i) -> new Counts(rs.getLong(1), rs.getLong(2)), memberId, memberId);
    }

    public boolean isFollowing(long viewerId, long targetId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM follow WHERE follower_id = ? AND followee_id = ?)", Boolean.class, viewerId, targetId));
    }

    /**
     * 최근에 팔로우한 순(같으면 회원 번호 큰 순) 20개씩 (FR-012).
     * @param staff 보는 사람이 관리자·매니저면 true. 비공개 목록도 본다 (079)
     */
    public Page list(String handle, Direction direction, String cursor, Long viewerId, boolean staff) {
        if (handle == null) throw new NotFoundException();
        record Owner(long id, boolean open) {}
        Owner o = jdbc.query("SELECT m.id, m.follow_list_public FROM member m WHERE m.handle = ? AND " + ACTIVE,
                        (rs, i) -> new Owner(rs.getLong(1), rs.getBoolean(2)), handle)
                .stream().findFirst().orElseThrow(NotFoundException::new);
        long owner = o.id();
        if (!o.open() && !staff && (viewerId == null || viewerId != owner)) return Page.HIDDEN_PAGE;
        boolean followers = direction == Direction.FOLLOWERS;
        String listName = (followers ? "followers:" : "following:") + owner;
        // 목록의 사람 = 팔로워 목록이면 팔로우한 쪽, 팔로잉 목록이면 받은 쪽
        String person = followers ? "f.follower_id" : "f.followee_id";
        String ownerColumn = followers ? "f.followee_id" : "f.follower_id";
        List<Object> args = new ArrayList<>();
        args.add(viewerId == null ? -1L : viewerId);
        args.add(owner);
        String after = "";
        long[] k = cursors.decode(cursor, listName, 2);
        if (k != null) {
            after = " AND (f.created_at, m.id) < (?, ?)";
            args.add(Timestamp.from(Times.fromEpochMicros(k[0])));
            args.add(k[1]);
        }
        args.add(PAGE_SIZE + 1);
        record Row(Person person, Instant at) {}
        List<Row> rows = jdbc.query("SELECT m.id, m.handle, m.nickname, m.bio, f.created_at, " + PostSql.PROFILE_IMAGE_KEY
                + ", v.follower_id IS NOT NULL AS following"
                + " FROM follow f JOIN member m ON m.id = " + person + " " + PostSql.PROFILE_IMAGE_JOIN
                + " LEFT JOIN follow v ON v.follower_id = ? AND v.followee_id = m.id"
                + " WHERE " + ownerColumn + " = ? AND " + ACTIVE + after
                + " ORDER BY f.created_at DESC, m.id DESC LIMIT ?",
                (rs, i) -> new Row(new Person(rs.getLong("id"), rs.getString("handle"), rs.getString("nickname"),
                        BioText.firstLine(rs.getString("bio")), imageUrls.urlOf(rs.getString("profile_image_key")),
                        rs.getBoolean("following"), viewerId != null && viewerId == rs.getLong("id")),
                        rs.getTimestamp("created_at").toInstant()),
                args.toArray());
        CursorCodec.Page<Row> page = CursorCodec.page(rows, PAGE_SIZE,
                r -> cursors.encode(listName, Times.toEpochMicros(r.at()), r.person().id()));
        return new Page(page.items().stream().map(Row::person).toList(), page.nextCursor(), false);
    }

    /** 탈퇴 신청하지 않은 회원의 번호. 없거나 탈퇴 신청했으면 비어 있다 (FR-006). */
    static Optional<Long> activeMember(JdbcTemplate jdbc, String handle) {
        if (handle == null) return Optional.empty();
        return Columns.firstLong(jdbc, "SELECT m.id FROM member m WHERE m.handle = ? AND " + ACTIVE, handle);
    }
}
