package com.team.blog.series.application;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Service;

import com.team.blog.account.domain.Visibility;
import com.team.blog.account.application.MemberProfileQuery;
import com.team.blog.friend.application.FriendsVisibilityRule;
import com.team.blog.post.access.PostAccessPolicy;
import com.team.blog.post.access.ReadablePost;
import com.team.blog.post.access.Viewer;
import com.team.blog.post.domain.PostStatus;
import com.team.blog.post.infra.PostSql;
import com.team.blog.post.query.PostCard;
import com.team.blog.post.query.PostCardQuery;
import com.team.blog.shared.jdbc.Columns;
import com.team.blog.shared.markdown.ImageUrls;
import com.team.blog.tag.application.TagSql;

/**
 * 시리즈 읽기 (024 US2). 보이는 글은 블로그 목록과 같은 조건이다 (FR-004): 남은 공개 글, 친구는 친구 공개 글까지,
 * 주인은 비공개·숨김 글까지(임시글은 빼고). 개수·순서·대표 사진 모두 보는 사람이 읽을 수 있는 글로만 센다.
 */
@Service
public class SeriesQuery {
    /** 주인에게 보이는 글: 발행했고 휴지통에 없는 글 */
    static final String OWNER_CONDITION = "p.status = 'PUBLISHED' AND p.deleted_at IS NULL";
    private static final String BRANCHES_SQL = """
            WITH wanted AS (SELECT DISTINCT sp.series_id FROM series_post sp WHERE sp.post_id = ANY (?)),
            listed AS (
                SELECT sp.series_id, sp.post_id,
                       row_number() OVER (PARTITION BY sp.series_id ORDER BY sp.position) AS idx,
                       count(*) OVER (PARTITION BY sp.series_id) AS total
                FROM series_post sp JOIN wanted w ON w.series_id = sp.series_id
                JOIN post p ON p.id = sp.post_id JOIN member m ON m.id = p.author_id
                WHERE\s""" + PostAccessPolicy.PUBLIC_LIST_CONDITION + """
            )
            SELECT l.post_id, l.idx, l.total, s.id, s.name, s.slug, m.handle
            FROM listed l JOIN series s ON s.id = l.series_id JOIN member m ON m.id = s.member_id
            WHERE l.post_id = ANY (?)""";

    private final JdbcTemplate jdbc;
    private final PostCardQuery cards;
    private final MemberProfileQuery members;
    private final PostAccessPolicy policy;
    private final ImageUrls imageUrls;
    private final SeriesSubscriptions subscriptions;

    public SeriesQuery(JdbcTemplate jdbc, PostCardQuery cards, MemberProfileQuery members, PostAccessPolicy policy, ImageUrls imageUrls,
                       SeriesSubscriptions subscriptions) {
        this.jdbc = jdbc;
        this.cards = cards;
        this.members = members;
        this.policy = policy;
        this.imageUrls = imageUrls;
        this.subscriptions = subscriptions;
    }

    public record Summary(long id, String name, String slug, int postCount, String thumbnailUrl, Instant updatedAt) {}

    /** @param personal 친구·주인이라 남과 다른 응답 */
    public record Listing(List<Summary> items, boolean personal) {}

    /**
     * @param subscribed 로그인한 남이 이 시리즈를 구독했는지(072). 비회원·주인은 null
     * @param personal 친구·주인·회원이라 남과 다른 응답. 어디에도 저장하지 않는다 (docs/06 R-5)
     */
    public record Detail(long id, String name, String slug, Instant updatedAt, boolean mine, List<PostCard> posts,
                         @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
                         Boolean subscribed,
                         @com.fasterxml.jackson.annotation.JsonIgnore boolean personal) {}

    public record Item(long id, String title, String url) {}

    /**
     * 글 상세의 시리즈 상자. index는 1부터이고, 지금 글이 목록에 없으면(주인이 보는 임시글) null이다.
     * @param publiclyVisible 공개 글만 담겨 브라우저에 잠시 둬도 되는 응답인지
     */
    public record Navigation(long id, String name, String slug, String handle, Integer index, List<Item> posts,
                             @com.fasterxml.jackson.annotation.JsonIgnore boolean publiclyVisible) {}

    private record Scope(long ownerId, boolean mine, boolean friend) {
        String condition() {
            if (mine) return OWNER_CONDITION;
            return friend ? PostAccessPolicy.FRIENDS_LIST_CONDITION : PostAccessPolicy.PUBLIC_LIST_CONDITION;
        }
    }

    /** 여러 글이 들어 있는 시리즈 이름 (059 내보내기). 시리즈에 없는 글은 빠진다. */
    public Map<Long, String> seriesNamesOf(Collection<Long> postIds) {
        Map<Long, String> byPost = new HashMap<>();
        if (postIds.isEmpty()) return byPost;
        jdbc.query(con -> {
            var ps = con.prepareStatement("""
                    SELECT sp.post_id, s.name FROM series_post sp JOIN series s ON s.id = sp.series_id
                    WHERE sp.post_id = ANY (?)""");
            ps.setArray(1, con.createArrayOf("bigint", postIds.toArray()));
            return ps;
        }, (RowCallbackHandler) rs -> byPost.put(rs.getLong(1), rs.getString(2)));
        return byPost;
    }

    /**
     * 홈 브랜치 그래프의 시리즈 브랜치 (072). 공개 글만 세므로 누구에게나 같은 응답이다.
     * 시리즈에 없거나 공개 목록에 없는 글은 빠진다.
     */
    public Map<Long, PostCard.Branch> branchesOf(Collection<Long> postIds) {
        Map<Long, PostCard.Branch> byPost = new HashMap<>();
        if (postIds.isEmpty()) return byPost;
        jdbc.query(con -> {
            var ps = con.prepareStatement(BRANCHES_SQL);
            var arr = con.createArrayOf("bigint", postIds.toArray());
            ps.setArray(1, arr);
            ps.setArray(2, arr);
            return ps;
        }, rs -> {
            long seriesId = rs.getLong(4);
            byPost.put(rs.getLong(1), new PostCard.Branch(PostCard.Branch.SERIES, "s" + seriesId, rs.getString(5), rs.getInt(2),
                    rs.getInt(3), "/@" + rs.getString(7) + "/series/" + rs.getString(6)));
        });
        return byPost;
    }

    /** @param tags 시리즈 글에 쓰인 태그(겹치면 한 번) */
    public record TagProfile(long id, String name, List<String> tags) {}

    /** 발행 창 브랜치 추천(072): 내 시리즈마다 글에 쓴 태그. 휴지통 글은 빼고, 글이 없는 시리즈는 빠진다 */
    public List<TagProfile> tagProfiles(long memberId) {
        Map<Long, TagProfile> byId = new LinkedHashMap<>();
        jdbc.query("SELECT s.id, s.name, " + TagSql.NAMES_COLUMN + """
                 FROM series s JOIN series_post sp ON sp.series_id = s.id JOIN post p ON p.id = sp.post_id
                WHERE s.member_id = ? AND p.deleted_at IS NULL ORDER BY s.updated_at DESC, s.id DESC""", rs -> {
            long id = rs.getLong(1);
            TagProfile t = byId.get(id);
            if (t == null) {
                t = new TagProfile(id, rs.getString(2), new ArrayList<>());
                byId.put(id, t);
            }
            for (Object tag : (Object[]) rs.getArray(3).getArray()) {
                if (!t.tags().contains(tag)) t.tags().add((String) tag);
            }
        }, memberId);
        return List.copyOf(byId.values());
    }

    /** 블로그의 시리즈 탭. 최근 수정 순. 남에게는 읽을 수 있는 글이 없는 시리즈를 숨긴다 (US2-3). */
    public Optional<Listing> list(String handle, Long viewerId) {
        return scope(handle, viewerId).map(s -> new Listing(jdbc.query(listSql(s.condition()), (rs, i) -> new Summary(rs.getLong("id"), rs.getString("name"), rs.getString("slug"), rs.getInt("cnt"),
                imageUrls.urlOf(rs.getString("thumbnail_key")), rs.getTimestamp("updated_at").toInstant()),
                s.ownerId(), s.mine()), s.mine() || s.friend()));
    }

    private static String listSql(String condition) {
        return """
                SELECT s.id, s.name, s.slug, s.updated_at, v.cnt, f.thumbnail_key
                FROM series s
                CROSS JOIN LATERAL (SELECT count(*) AS cnt FROM series_post sp
                                    JOIN post p ON p.id = sp.post_id JOIN member m ON m.id = p.author_id
                                    WHERE sp.series_id = s.id AND """ + " " + condition + " " + """
                                    ) v
                LEFT JOIN LATERAL (SELECT """ + " " + PostSql.THUMBNAIL_KEY + " " + """
                                   FROM series_post sp JOIN post p ON p.id = sp.post_id JOIN member m ON m.id = p.author_id
                                   WHERE sp.series_id = s.id AND """ + " " + condition + " " + """
                                   ORDER BY sp.position LIMIT 1) f ON TRUE
                WHERE s.member_id = ? AND (? OR v.cnt > 0)
                ORDER BY s.updated_at DESC, s.id DESC
                """;
    }

    /** 시리즈 페이지. 남에게는 읽을 수 있는 글이 없으면 없는 시리즈다. */
    public Optional<Detail> detail(String handle, String slug, Long viewerId) {
        return scope(handle, viewerId).flatMap(s -> jdbc.query(
                "SELECT id, name, slug, updated_at FROM series WHERE member_id = ? AND slug = ?",
                (rs, i) -> new Object[] {rs.getLong(1), rs.getString(2), rs.getString(3), rs.getTimestamp(4)},
                s.ownerId(), slug).stream().findFirst().flatMap(row -> {
                    long id = (Long) row[0];
                    List<Long> ids = Columns.longs(jdbc, "SELECT post_id FROM series_post WHERE series_id = ? ORDER BY position", id);
                    List<PostCard> posts = cards.cards(ids, s.condition());
                    if (posts.isEmpty() && !s.mine()) return Optional.empty();
                    Boolean subscribed = viewerId == null || s.mine() ? null : subscriptions.subscribed(viewerId, id);
                    return Optional.of(new Detail(id, (String) row[1], (String) row[2], ((Timestamp) row[3]).toInstant(), s.mine(),
                            posts, subscribed, viewerId != null));
                }));
    }

    /**
     * 글 상세의 시리즈 상자 (US2-1). 글을 읽을 수 없거나 시리즈에 없으면 비어 있다.
     * 작성자는 임시글에서도 자기 시리즈 정보를 본다(글쓰기 화면이 지금 시리즈를 이걸로 안다).
     */
    public Optional<Navigation> forPost(long postId, Viewer viewer) {
        record Row(ReadablePost post, long seriesId, String name, String slug, String handle) {}
        Optional<Row> found = jdbc.query("""
                SELECT p.author_id, p.status, p.visibility, p.deleted_at IS NOT NULL AS deleted, p.hidden_at IS NOT NULL AS hidden,
                       m.withdrawn_at IS NOT NULL AS withdrawn, s.id AS series_id, s.name, s.slug, m.handle
                FROM series_post sp JOIN series s ON s.id = sp.series_id
                JOIN post p ON p.id = sp.post_id JOIN member m ON m.id = p.author_id
                WHERE sp.post_id = ?
                """, (rs, i) -> new Row(new ReadablePost(rs.getLong("author_id"), PostStatus.valueOf(rs.getString("status")),
                Visibility.valueOf(rs.getString("visibility")), rs.getBoolean("deleted"), rs.getBoolean("hidden"),
                rs.getBoolean("withdrawn")), rs.getLong("series_id"), rs.getString("name"), rs.getString("slug"),
                rs.getString("handle")), postId).stream().findFirst();
        if (found.isEmpty() || !policy.canRead(found.get().post(), viewer)) return Optional.empty();
        Row r = found.get();
        long ownerId = r.post().authorId();
        boolean mine = viewer.is(ownerId);
        boolean friend = !mine && viewer.memberId() != null && FriendsVisibilityRule.areFriends(jdbc, ownerId, viewer.memberId());
        Scope scope = new Scope(ownerId, mine, friend);
        List<Item> items = jdbc.query("""
                SELECT p.id, p.title FROM series_post sp JOIN post p ON p.id = sp.post_id JOIN member m ON m.id = p.author_id
                WHERE sp.series_id = ? AND """ + " " + scope.condition() + " " + """
                 ORDER BY sp.position
                """, (rs, i) -> new Item(rs.getLong(1), rs.getString(2), "/@" + r.handle() + "/posts/" + rs.getLong(1)), r.seriesId());
        Integer index = null;
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).id() == postId) index = i + 1;
        }
        return Optional.of(new Navigation(r.seriesId(), r.name(), r.slug(), r.handle(), index, items, !mine && !friend));
    }

    private Optional<Scope> scope(String handle, Long viewerId) {
        return members.activeId(handle).map(ownerId -> {
            boolean mine = ownerId.equals(viewerId); // 둘 다 Long이라 == 는 참조 비교
            boolean friend = !mine && viewerId != null && FriendsVisibilityRule.areFriends(jdbc, ownerId, viewerId);
            return new Scope(ownerId, mine, friend);
        });
    }
}
