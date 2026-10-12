package com.team.blog.topic.application;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.team.blog.post.access.PostAccessPolicy;
import com.team.blog.post.query.PostCard;
import com.team.blog.tag.application.TagSql;

/** 주제 브랜치 읽기 (072). 공개 글만 세므로 누구에게나 같은 응답이다. */
@Service
public class TopicQuery {
    private static final String BRANCH_URL = "/?branch=t";
    private static final String BRANCHES_SQL = """
            WITH wanted AS (SELECT DISTINCT t.topic_key FROM post_topic t WHERE t.post_id = ANY (?)),
            listed AS (
                SELECT t.post_id, t.topic_key, t.topic_name,
                       row_number() OVER (PARTITION BY t.topic_key ORDER BY p.first_public_at, p.id) AS idx,
                       count(*) OVER (PARTITION BY t.topic_key) AS total
                FROM post_topic t JOIN wanted w ON w.topic_key = t.topic_key
                JOIN post p ON p.id = t.post_id JOIN member m ON m.id = p.author_id
                WHERE\s""" + PostAccessPolicy.PUBLIC_LIST_CONDITION + """
            )
            SELECT post_id, topic_key, topic_name, idx, total FROM listed WHERE post_id = ANY (?) AND total > 1""";

    private final JdbcTemplate jdbc;

    public TopicQuery(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 홈 브랜치 그래프: 글마다 주제 브랜치. 묶이지 않았거나 공개 목록에 없는 글은 빠진다 */
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
            long key = rs.getLong(2);
            byPost.put(rs.getLong(1), new PostCard.Branch(PostCard.Branch.TOPIC, "t" + key, rs.getString(3), rs.getInt(4),
                    rs.getInt(5), BRANCH_URL + key));
        });
        return byPost;
    }

    /** @param postCount 공개 글 수 */
    public record Popular(String key, String name, int postCount, String url) {}

    /** 글이 많은 주제 브랜치 (홈 옆 칸 "이어지는 주제") */
    public List<Popular> popular(int limit) {
        return jdbc.query("""
                SELECT t.topic_key, min(t.topic_name) AS name, count(*) AS cnt FROM post_topic t
                JOIN post p ON p.id = t.post_id JOIN member m ON m.id = p.author_id
                WHERE\s""" + PostAccessPolicy.PUBLIC_LIST_CONDITION + """
                 GROUP BY t.topic_key HAVING count(*) > 1
                ORDER BY cnt DESC, max(p.first_public_at) DESC LIMIT ?
                """, (rs, i) -> new Popular("t" + rs.getLong(1), rs.getString(2), rs.getInt(3), BRANCH_URL + rs.getLong(1)), limit);
    }

    public record Item(long id, String title, String url, Instant firstPublicAt) {}

    /** 글 화면 브랜치 상자(072). index는 1부터, 공개 글을 처음 공개한 순서로 센다 */
    public record Navigation(String key, String name, String url, int index, List<Item> posts) {}

    /** 공개 글이 든 주제 브랜치. 글이 공개 목록에 없거나 묶이지 않았으면 비어 있다 */
    public Optional<Navigation> forPost(long postId) {
        List<Object[]> head = jdbc.query("""
                SELECT t.topic_key, t.topic_name FROM post_topic t JOIN post p ON p.id = t.post_id JOIN member m ON m.id = p.author_id
                WHERE t.post_id = ? AND\s""" + PostAccessPolicy.PUBLIC_LIST_CONDITION,
                (rs, i) -> new Object[] {rs.getLong(1), rs.getString(2)}, postId);
        if (head.isEmpty()) return Optional.empty();
        long key = (Long) head.get(0)[0];
        List<Item> items = jdbc.query("""
                SELECT p.id, p.title, m.handle, p.first_public_at FROM post_topic t JOIN post p ON p.id = t.post_id
                JOIN member m ON m.id = p.author_id
                WHERE t.topic_key = ? AND\s""" + PostAccessPolicy.PUBLIC_LIST_CONDITION + " ORDER BY p.first_public_at, p.id", (rs, i) -> new Item(rs.getLong(1), rs.getString(2),
                "/@" + rs.getString(3) + "/posts/" + rs.getLong(1), rs.getTimestamp(4).toInstant()), key);
        if (items.size() < 2) return Optional.empty();
        int index = 0;
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).id() == postId) index = i + 1;
        }
        return Optional.of(new Navigation("t" + key, (String) head.get(0)[1], BRANCH_URL + key, index, items));
    }

    /** @param tags 브랜치 글에 쓰인 태그(겹치면 한 번) */
    public record TagProfile(long key, String name, int postCount, List<String> tags) {}

    /** 발행 창 브랜치 추천(072): 주제 브랜치마다 공개 글에 쓴 태그 */
    public List<TagProfile> tagProfiles() {
        Map<Long, String> names = new LinkedHashMap<>();
        Map<Long, Integer> counts = new HashMap<>();
        Map<Long, List<String>> tags = new HashMap<>();
        jdbc.query("SELECT t.topic_key, t.topic_name, " + TagSql.NAMES_COLUMN + """
                 FROM post_topic t JOIN post p ON p.id = t.post_id JOIN member m ON m.id = p.author_id
                WHERE\s""" + PostAccessPolicy.PUBLIC_LIST_CONDITION + " ORDER BY t.topic_key", rs -> {
            long key = rs.getLong(1);
            names.putIfAbsent(key, rs.getString(2));
            counts.merge(key, 1, Integer::sum);
            List<String> list = tags.computeIfAbsent(key, k -> new ArrayList<>());
            for (Object tag : (Object[]) rs.getArray(3).getArray()) {
                if (!list.contains(tag)) list.add((String) tag);
            }
        });
        List<TagProfile> out = new ArrayList<>();
        names.forEach((key, name) -> {
            if (counts.get(key) > 1) out.add(new TagProfile(key, name, counts.get(key), tags.get(key)));
        });
        return out;
    }

    /** 작성자가 [묶지 않기]를 골랐는지 */
    public boolean optedOut(long postId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM post_topic_optout WHERE post_id = ?)", Boolean.class, postId));
    }

    /** [묶지 않기]는 다음 계산을 기다리지 않고 지금 브랜치에서도 바로 뺀다 */
    public void setOptOut(long postId, boolean optOut) {
        if (optOut) {
            jdbc.update("INSERT INTO post_topic_optout (post_id) VALUES (?) ON CONFLICT DO NOTHING", postId);
            jdbc.update("DELETE FROM post_topic WHERE post_id = ?", postId);
        } else {
            jdbc.update("DELETE FROM post_topic_optout WHERE post_id = ?", postId);
        }
    }

    /** 브랜치 이름 (거른 홈 화면 제목). 없으면 null */
    public String name(long topicKey) {
        List<String> names = jdbc.query("SELECT topic_name FROM post_topic WHERE topic_key = ? LIMIT 1", (rs, i) -> rs.getString(1), topicKey);
        return names.isEmpty() ? null : names.get(0);
    }
}
