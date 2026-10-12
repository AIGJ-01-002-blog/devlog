package com.team.blog.post.query;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.team.blog.account.domain.Visibility;
import com.team.blog.post.access.PostAccessPolicy;
import com.team.blog.post.infra.PostSql;
import com.team.blog.shared.config.BlogProperties;
import com.team.blog.shared.cursor.CursorCodec;
import com.team.blog.shared.markdown.ContentRenderer;
import com.team.blog.shared.markdown.ImageUrls;
import com.team.blog.shared.time.Times;

/**
 * 글 카드 읽기의 유일한 곳 (docs/10 §4·§5·§7). 목록당 쿼리 1번(글 + 작성자 + 프로필 사진 + 수),
 * 본문은 앞 600자만 읽어 요약을 만든다(V3: 요약·대표 사진·수를 저장하지 않는다). 9개씩, 10개를 읽어 다음 페이지 여부를 판단한다.
 * 목록 조건은 PostAccessPolicy의 상수와 다른 모듈이 넘기는 PostFilter뿐이다.
 */
@Service
public class PostCardQuery {
    /** 카드 태그: 입력 순서대로 글마다 배열 하나 (같은 쿼리 안에서 읽어 목록당 쿼리 1번을 지킨다) */
    private static final String TAGS =
            "ARRAY(SELECT t.name FROM post_tag pt JOIN tag t ON t.id = pt.tag_id WHERE pt.post_id = p.id ORDER BY pt.position) AS tags";
    private static final String CARD_SELECT =
            "SELECT p.id, p.title, p.first_public_at, p.published_at, p.visibility, s.comment_count, s.like_count, s.view_count, m.id AS author_id, m.handle, m.nickname, "
            + PostSql.SUMMARY_SOURCE + ", " + PostSql.THUMBNAIL_KEY + ", " + PostSql.PROFILE_IMAGE_KEY + ", " + TAGS
            + " FROM post p JOIN member m ON m.id = p.author_id " + PostSql.STAT_JOIN + " " + PostSql.PROFILE_IMAGE_JOIN
            + " WHERE ";

    private final JdbcTemplate jdbc;
    private final CursorCodec cursors;
    private final ImageUrls imageUrls;
    private final ContentRenderer renderer;
    private final int pageSize;

    public PostCardQuery(JdbcTemplate jdbc, CursorCodec cursors, ImageUrls imageUrls, ContentRenderer renderer, BlogProperties props) {
        this.jdbc = jdbc;
        this.cursors = cursors;
        this.imageUrls = imageUrls;
        this.renderer = renderer;
        this.pageSize = props.feed().pageSize();
    }

    public int pageSize() {
        return pageSize;
    }

    /**
     * 번호로 카드 읽기 (트렌딩 순위표). 한 번의 조회로 읽고 지금 공개 목록 조건을 다시 확인해, 그 사이 볼 수 없게 된 글은 빠진다.
     * 순서는 받은 번호 순서 그대로다.
     */
    public List<PostCard> publicCards(List<Long> ids) {
        return cards(ids, PostAccessPolicy.PUBLIC_LIST_CONDITION);
    }

    /**
     * 번호로 카드 읽기, 목록 조건을 지정한다 (024 시리즈: 보는 사람에 따라 공개·친구·본인 조건). condition은 코드 안 상수만 넘긴다.
     * 순서는 받은 번호 순서 그대로이고 조건에 맞지 않는 글은 빠진다.
     */
    public List<PostCard> cards(List<Long> ids, String condition) {
        if (ids.isEmpty()) return List.of();
        List<PostCard> found = jdbc.query(con -> {
            var ps = con.prepareStatement(CARD_SELECT + condition + " AND p.id = ANY (?)");
            ps.setArray(1, con.createArrayOf("bigint", ids.toArray()));
            return ps;
        }, this::card);
        Map<Long, PostCard> byId = new HashMap<>();
        found.forEach(c -> byId.put(c.id(), c));
        return ids.stream().map(byId::get).filter(Objects::nonNull).toList();
    }

    /** 한 사람의 목록에 나오는 글 수. condition은 코드 안 상수만 넘긴다. */
    public long count(long authorId, String condition) {
        Long n = jdbc.queryForObject("SELECT count(*) FROM post p JOIN member m ON m.id = p.author_id WHERE p.author_id = ? AND "
                + condition, Long.class, authorId);
        return n == null ? 0 : n;
    }

    /** 최신 공개 글 limit개, 커서 없음 (RSS). authorId가 null이면 전체. */
    public List<PostCard> recentPublic(Long authorId, int limit) {
        String sql = CARD_SELECT + PostAccessPolicy.PUBLIC_LIST_CONDITION + (authorId == null ? "" : " AND p.author_id = ?")
                + " ORDER BY p.first_public_at DESC, p.id DESC LIMIT " + limit;
        Object[] args = authorId == null ? new Object[0] : new Object[] {authorId};
        return jdbc.query(sql, this::card, args);
    }

    /** 검색 엔진 사이트맵용 공개 글 주소와 마지막 발행 시각. 본문·통계를 읽지 않는 가벼운 쿼리다. AI 일기는 뺀다 (spec 088) */
    public List<SitemapEntry> sitemapEntries(int limit) {
        String sql = "SELECT p.id, m.handle, p.published_at FROM post p JOIN member m ON m.id = p.author_id WHERE "
                + PostAccessPolicy.PUBLIC_LIST_CONDITION + " AND NOT p.ai_diary ORDER BY p.first_public_at DESC, p.id DESC LIMIT " + limit;
        return jdbc.query(sql, (rs, i) -> new SitemapEntry(rs.getString("handle"), rs.getLong("id"),
                rs.getTimestamp("published_at").toInstant()));
    }

    public record SitemapEntry(String handle, long postId, Instant publishedAt) {}

    public PostCardPage page(PostListSpec spec, String cursor) {
        boolean friendsView = spec.friendsView();
        StringBuilder sql = new StringBuilder(CARD_SELECT)
                .append(friendsView ? PostAccessPolicy.FRIENDS_LIST_CONDITION : PostAccessPolicy.PUBLIC_LIST_CONDITION);
        String sortColumn = friendsView ? "p.published_at" : "p.first_public_at";
        List<Object> args = new ArrayList<>();
        if (spec.authorId() != null) {
            sql.append(" AND p.author_id = ?");
            args.add(spec.authorId());
        }
        for (PostFilter f : spec.filters()) {
            sql.append(" AND ").append(f.sql());
            args.add(f.arg());
        }
        if (cursor != null && !cursor.isBlank()) {
            long[] k = cursors.decode(cursor, spec.name(), 2);
            sql.append(" AND (").append(sortColumn).append(", p.id) < (?, ?)");
            args.add(Timestamp.from(Times.fromEpochMicros(k[0])));
            args.add(k[1]);
        }
        sql.append(" ORDER BY ").append(sortColumn).append(" DESC, p.id DESC LIMIT ").append(pageSize + 1);
        List<PostCard> cards = jdbc.query(sql.toString(), this::card, args.toArray());
        CursorCodec.Page<PostCard> page = CursorCodec.page(cards, pageSize,
                c -> cursors.encode(spec.name(), Times.toEpochMicros(friendsView ? c.publishedAt() : c.firstPublicAt()), c.id()));
        return new PostCardPage(page.items(), page.nextCursor(), friendsView);
    }

    private PostCard card(ResultSet rs, int i) throws SQLException {
        String handle = rs.getString("handle");
        long id = rs.getLong("id");
        return new PostCard(id, "/@" + handle + "/posts/" + id, rs.getString("title"),
                renderer.summary(rs.getString("summary"), rs.getString("content_head")), imageUrls.urlOf(rs.getString("thumbnail_key")),
                instant(rs.getTimestamp("first_public_at")), rs.getTimestamp("published_at").toInstant(),
                Visibility.valueOf(rs.getString("visibility")),
                rs.getInt("comment_count"), rs.getInt("like_count"), rs.getLong("view_count"), tags(rs.getArray("tags")),
                new PostCard.Author(rs.getLong("author_id"), handle, rs.getString("nickname"),
                        imageUrls.urlOf(rs.getString("profile_image_key"))));
    }

    private static List<String> tags(Array array) throws SQLException {
        return array == null ? List.of() : List.of((String[]) array.getArray());
    }

    private static Instant instant(Timestamp t) {
        return t == null ? null : t.toInstant();
    }
}
