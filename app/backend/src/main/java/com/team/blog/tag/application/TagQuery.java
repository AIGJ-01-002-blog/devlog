package com.team.blog.tag.application;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Service;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import com.team.blog.post.access.PostAccessPolicy;
import com.team.blog.shared.jdbc.Columns;

/**
 * 태그 읽기 (docs/22 §5~§9). 글 수는 모두 "공개 목록 조건"(발행·전체 공개·휴지통 아님·숨김 아님·작성자 탈퇴 신청 아님)의 글만
 * 센다(FR-018). 태그 행은 남아 있어도 공개 글 수가 0이면 목록·자동완성에 나오지 않는다.
 */
@Service
public class TagQuery {
    private static final Logger log = LoggerFactory.getLogger(TagQuery.class);
    static final String TOP_KEY = "tags:top";
    static final int TOP_LIMIT = 100;
    private static final Duration TOP_TTL = Duration.ofMinutes(10);
    private static final int SUGGEST_LIMIT = 10;
    private static final int BLOG_TAG_LIMIT = 200;
    private static final String POST_COUNT = "post_count";

    /** 공개 글(p)과 작성자(m)를 잇는 조각. 별칭 pt·p·m. */
    private static final String PUBLIC_POSTS = " JOIN post p ON p.id = pt.post_id JOIN member m ON m.id = p.author_id WHERE "
            + PostAccessPolicy.PUBLIC_LIST_CONDITION;

    private final JdbcTemplate jdbc;
    private final StringRedisTemplate redis;
    private final JsonMapper json = JsonMapper.builder().build();

    public TagQuery(JdbcTemplate jdbc, StringRedisTemplate redis) {
        this.jdbc = jdbc;
        this.redis = redis;
    }

    public record TagCount(String name, long postCount) {}

    public record Suggestion(String name, long postCount, boolean mine) {}

    /** 글 상세·에디터: 입력한 순서대로. */
    public List<String> tagsOf(long postId) {
        return Columns.strings(jdbc, "SELECT t.name FROM post_tag pt JOIN tag t ON t.id = pt.tag_id WHERE pt.post_id = ? ORDER BY pt.position",
                postId);
    }

    /** 여러 글의 태그를 한 번에 (059 내보내기). 글마다 입력한 순서대로, 태그 없는 글은 빠진다. */
    public Map<Long, List<String>> tagsOf(Collection<Long> postIds) {
        Map<Long, List<String>> byPost = new LinkedHashMap<>();
        if (postIds.isEmpty()) return byPost;
        jdbc.query(con -> {
            var ps = con.prepareStatement("""
                    SELECT pt.post_id, t.name FROM post_tag pt JOIN tag t ON t.id = pt.tag_id
                    WHERE pt.post_id = ANY (?) ORDER BY pt.post_id, pt.position""");
            ps.setArray(1, con.createArrayOf("bigint", postIds.toArray()));
            return ps;
        }, (RowCallbackHandler) rs -> byPost.computeIfAbsent(rs.getLong(1), k -> new ArrayList<>()).add(rs.getString(2)));
        return byPost;
    }

    /** 태그마다 쓴 공개 글 수 (발행 창 브랜치 추천의 희소도, 072). 공개 글에 쓰이지 않은 태그는 빠진다 */
    public Map<String, Long> publicUsage(Collection<String> names) {
        Map<String, Long> out = new HashMap<>();
        if (names.isEmpty()) return out;
        jdbc.query(con -> {
            var ps = con.prepareStatement("SELECT t.name, count(*) FROM tag t JOIN post_tag pt ON pt.tag_id = t.id" + PUBLIC_POSTS
                    + " AND t.name = ANY (?) GROUP BY t.name");
            ps.setArray(1, con.createArrayOf("varchar", names.toArray()));
            return ps;
        }, (RowCallbackHandler) rs -> out.put(rs.getString(1), rs.getLong(2)));
        return out;
    }

    /** 공개 글 전체 수 */
    public long publicPostTotal() {
        Long n = jdbc.queryForObject("SELECT count(*) FROM post p JOIN member m ON m.id = p.author_id WHERE "
                + PostAccessPolicy.PUBLIC_LIST_CONDITION, Long.class);
        return n == null ? 0 : n;
    }

    /** 태그 페이지 상단의 공개 글 수. 없는 태그는 0이다. */
    public long publicPostCount(String name) {
        Long n = jdbc.queryForObject("SELECT count(*) FROM tag t JOIN post_tag pt ON pt.tag_id = t.id" + PUBLIC_POSTS + " AND t.name = ?",
                Long.class, name);
        return n == null ? 0 : n;
    }

    /** 전체 태그 목록: 공개 글 수 많은 순, 같으면 이름 순 상위 100개. 10분 동안 Redis에 둔다(FR-024). */
    public List<TagCount> top(int limit) {
        int n = Math.clamp(limit, 1, TOP_LIMIT);
        List<TagCount> all = cachedTop();
        return all.size() > n ? all.subList(0, n) : all;
    }

    private List<TagCount> cachedTop() {
        try {
            String cached = redis.opsForValue().get(TOP_KEY);
            if (cached != null) return json.readValue(cached, new TypeReference<List<TagCount>>() {});
        } catch (RuntimeException e) {
            log.warn("태그 목록 캐시를 읽지 못해 바로 계산합니다: {}", e.getMessage());
        }
        List<TagCount> fresh = computeTop();
        try {
            redis.opsForValue().set(TOP_KEY, json.writeValueAsString(fresh), TOP_TTL);
        } catch (RuntimeException e) {
            log.warn("태그 목록 캐시를 쓰지 못했습니다: {}", e.getMessage());
        }
        return fresh;
    }

    List<TagCount> computeTop() {
        return jdbc.query("SELECT t.name, count(*) AS post_count FROM tag t JOIN post_tag pt ON pt.tag_id = t.id" + PUBLIC_POSTS
                        + " GROUP BY t.name ORDER BY post_count DESC, t.name LIMIT " + TOP_LIMIT,
                (rs, i) -> new TagCount(rs.getString("name"), rs.getLong(POST_COUNT)));
    }

    /**
     * 자동완성 (FR-025~FR-027). 후보는 내가 쓴 태그(공개 범위 무관)와 공개 글 수 1 이상인 태그뿐이라, 남의 비공개 글에만 쓰인
     * 태그는 나오지 않는다. 내가 쓴 태그 먼저, 그다음 공개 글 수 많은 순.
     */
    public List<Suggestion> suggest(long memberId, String q) {
        String prefix = TagNormalizer.clean(q);
        if (prefix.isEmpty() || prefix.codePointCount(0, prefix.length()) > TagNormalizer.MAX_LENGTH) return List.of();
        // 태그에 쓸 수 있는 _는 LIKE의 한 글자 와일드카드라 이스케이프한다 (%와 \\는 태그에 없다)
        String like = prefix.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
        return jdbc.query("""
                WITH c AS (SELECT id, name FROM tag WHERE name LIKE ? ESCAPE '\\'),
                     pub AS (SELECT pt.tag_id, count(*) AS n FROM post_tag pt""" + PUBLIC_POSTS + """
                              AND pt.tag_id IN (SELECT id FROM c) GROUP BY pt.tag_id),
                     mine AS (SELECT DISTINCT pt.tag_id FROM post_tag pt JOIN post p ON p.id = pt.post_id
                              WHERE p.author_id = ? AND p.deleted_at IS NULL AND pt.tag_id IN (SELECT id FROM c))
                SELECT c.name, COALESCE(pub.n, 0) AS post_count, mine.tag_id IS NOT NULL AS mine
                FROM c LEFT JOIN pub ON pub.tag_id = c.id LEFT JOIN mine ON mine.tag_id = c.id
                WHERE mine.tag_id IS NOT NULL OR pub.n > 0
                ORDER BY mine DESC, post_count DESC, c.name
                LIMIT """ + " " + SUGGEST_LIMIT,
                (rs, i) -> new Suggestion(rs.getString("name"), rs.getLong(POST_COUNT), rs.getBoolean("mine")), like, memberId);
    }

    /** 블로그 태그 줄: 블로그 목록과 같은 조건(본인이 봐도 공개 글만), 글 수 많은 순 (FR-030). */
    public List<TagCount> blogTags(long authorId) {
        return jdbc.query("SELECT t.name, count(*) AS post_count FROM tag t JOIN post_tag pt ON pt.tag_id = t.id" + PUBLIC_POSTS
                        + " AND p.author_id = ? GROUP BY t.name ORDER BY post_count DESC, t.name LIMIT " + BLOG_TAG_LIMIT,
                (rs, i) -> new TagCount(rs.getString("name"), rs.getLong(POST_COUNT)), authorId);
    }
}
