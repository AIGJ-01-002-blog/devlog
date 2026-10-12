package com.team.blog.tag.application;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Service;

import com.team.blog.post.access.PostAccessPolicy;
import com.team.blog.post.infra.PostSql;
import com.team.blog.post.query.PostCard;
import com.team.blog.post.query.PostCardQuery;

/**
 * 태그 둘러보기 (spec 089): 태그를 검색·정렬해 몇 개씩 이어서 내리고, 줄마다 그 태그의 인기 글 카드를 싣는다.
 * 글 수와 인기 글은 모두 공개 목록 조건의 글만 센다(010 FR-018과 같음).
 * <pre>
 * 인기 점수 = 3 × 좋아요 + 2 × 댓글 + 0.1 × 조회수   (트렌딩(017)과 같은 무게, 기간 감쇠 없음)
 * </pre>
 */
@Service
public class TagBrowse {
    /** 한 번에 내리는 태그 줄 수 */
    public static final int PAGE_SIZE = 6;
    /** 줄마다 싣는 인기 글 수. 글이 더 있으면 화면이 줄 끝에 [전체 보기]를 단다 */
    public static final int ROW_POSTS = 10;
    /** 커서(건너뛸 줄 수) 상한: 태그가 수천 개를 넘을 일이 없고, 터무니없는 OFFSET을 막는다 */
    static final int MAX_OFFSET = 5_000;

    public enum Sort { POPULAR, NAME, RECENT;
        /** 모르는 값은 기본(인기순)으로 본다: 주소를 손으로 고쳐도 400 대신 기본 목록이다 */
        public static Sort parse(String raw) {
            if ("name".equalsIgnoreCase(raw)) return NAME;
            if ("recent".equalsIgnoreCase(raw)) return RECENT;
            return POPULAR;
        }
    }

    /** 태그 한 줄: 이름, 공개 글 수, 마지막으로 쓰인 공개 글의 처음 공개 시각, 인기 글 카드(최대 ROW_POSTS) */
    public record Row(String name, long postCount, Instant lastUsedAt, List<PostCard> posts) {}

    /** total은 첫 쪽에만 싣는다(검색어에 맞는 태그 수) */
    public record Page(List<Row> items, String nextCursor, Long total) {}

    private record Head(String name, long postCount, Instant lastUsedAt) {}

    private static final String PUBLIC_TAGGED = " FROM tag t JOIN post_tag pt ON pt.tag_id = t.id JOIN post p ON p.id = pt.post_id"
            + " JOIN member m ON m.id = p.author_id WHERE " + PostAccessPolicy.PUBLIC_LIST_CONDITION;

    /** 태그마다 인기 점수 순으로 ROW_POSTS개까지 글 번호를 고른다. 태그 이름 목록(1)과 줄당 글 수(2)는 바인딩한다 */
    private static final String POPULAR_SQL = """
            SELECT name, id FROM (
                SELECT t.name, p.id,
                       row_number() OVER (PARTITION BY t.name
                           ORDER BY 3 * s.like_count + 2 * s.comment_count + 0.1 * s.view_count DESC,
                                    p.first_public_at DESC, p.id DESC) AS nth
                FROM tag t JOIN post_tag pt ON pt.tag_id = t.id JOIN post p ON p.id = pt.post_id
                JOIN member m ON m.id = p.author_id\s""" + PostSql.STAT_JOIN + " WHERE " + PostAccessPolicy.PUBLIC_LIST_CONDITION + """
                  AND t.name = ANY (?)
            ) ranked WHERE nth <= ? ORDER BY name, nth""";

    private final JdbcTemplate jdbc;
    private final PostCardQuery cards;

    public TagBrowse(JdbcTemplate jdbc, PostCardQuery cards) {
        this.jdbc = jdbc;
        this.cards = cards;
    }

    public Page page(String q, Sort sort, String cursor) {
        int offset = parseOffset(cursor);
        String like = likePattern(q);
        if (like == null) return new Page(List.of(), null, offset == 0 ? 0L : null);

        List<Object> args = new ArrayList<>();
        String filter = "";
        if (!like.isEmpty()) {
            filter = " AND t.name LIKE ? ESCAPE '\\'";
            args.add(like);
        }
        args.add(PAGE_SIZE + 1);
        args.add(offset);
        List<Head> heads = jdbc.query("SELECT t.name, count(*) AS post_count, max(p.first_public_at) AS last_used" + PUBLIC_TAGGED + filter
                        + " GROUP BY t.name ORDER BY " + order(sort) + " LIMIT ? OFFSET ?",
                (rs, i) -> new Head(rs.getString("name"), rs.getLong("post_count"), instant(rs.getTimestamp("last_used"))), args.toArray());

        boolean more = heads.size() > PAGE_SIZE;
        if (more) heads = heads.subList(0, PAGE_SIZE);
        Map<String, List<PostCard>> posts = popularPosts(heads.stream().map(Head::name).toList());
        List<Row> rows = heads.stream()
                .map(h -> new Row(h.name(), h.postCount(), h.lastUsedAt(), posts.getOrDefault(h.name(), List.of())))
                .toList();
        Long total = offset == 0 ? count(filter, like) : null;
        return new Page(rows, more ? String.valueOf(offset + PAGE_SIZE) : null, total);
    }

    /** 이름이 같은 줄끼리 순서가 흔들리지 않게 마지막은 언제나 이름 순이다 */
    private static String order(Sort sort) {
        return switch (sort) {
            case NAME -> "t.name";
            case RECENT -> "last_used DESC NULLS LAST, t.name";
            case POPULAR -> "post_count DESC, t.name";
        };
    }

    private long count(String filter, String like) {
        Long n = jdbc.queryForObject("SELECT count(DISTINCT t.id)" + PUBLIC_TAGGED + filter, Long.class,
                filter.isEmpty() ? new Object[0] : new Object[] {like});
        return n == null ? 0 : n;
    }

    /** 태그마다 인기 글 번호를 쿼리 한 번으로 고른 뒤, 카드는 한 번에 읽어 태그별로 나눈다 */
    Map<String, List<PostCard>> popularPosts(List<String> names) {
        Map<String, List<Long>> idsByTag = new LinkedHashMap<>();
        if (names.isEmpty()) return Map.of();
        jdbc.query(POPULAR_SQL, ps -> {
            ps.setArray(1, ps.getConnection().createArrayOf("varchar", names.toArray()));
            ps.setInt(2, ROW_POSTS);
        }, (RowCallbackHandler) rs -> idsByTag.computeIfAbsent(rs.getString("name"), k -> new ArrayList<>()).add(rs.getLong("id")));
        List<Long> all = idsByTag.values().stream().flatMap(List::stream).distinct().toList();
        Map<Long, PostCard> byId = new LinkedHashMap<>();
        cards.publicCards(all).forEach(c -> byId.put(c.id(), c));
        Map<String, List<PostCard>> out = new LinkedHashMap<>();
        idsByTag.forEach((name, ids) -> out.put(name, ids.stream().map(byId::get).filter(Objects::nonNull).toList()));
        return out;
    }

    /**
     * 검색어를 태그 이름 안 어디든 맞는 LIKE 모양으로. 빈 검색어는 ""(거르지 않음), 태그가 될 수 없는 길이면 null(결과 없음).
     * 앞의 #·대소문자·띄어쓰기는 태그 정규화와 같게 다듬는다.
     */
    static String likePattern(String q) {
        String cleaned = TagNormalizer.clean(q == null ? "" : q);
        if (cleaned.isEmpty()) return "";
        if (cleaned.codePointCount(0, cleaned.length()) > TagNormalizer.MAX_LENGTH) return null;
        // 태그에 쓸 수 있는 _는 LIKE의 한 글자 와일드카드라 이스케이프한다
        return "%" + cleaned.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
    }

    static int parseOffset(String cursor) {
        if (cursor == null || cursor.isBlank()) return 0;
        try {
            int n = Integer.parseInt(cursor.strip());
            return n < 0 ? 0 : Math.min(n, MAX_OFFSET);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static Instant instant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }
}
