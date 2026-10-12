package com.team.blog.topic.application;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.team.blog.post.access.PostAccessPolicy;
import com.team.blog.post.query.PostCard;
import com.team.blog.post.query.PostCardQuery;
import com.team.blog.search.semantic.SemanticSearch;
import com.team.blog.series.application.SeriesSql;
import com.team.blog.tag.application.TagSql;

/**
 * 글 화면 "내용이 비슷한 다른 글" (072 2단계). 임베딩이 있으면 가까운 글을 먼저, 모자라면 태그가 많이 겹치는 글로 채운다.
 * 같은 시리즈·주제 브랜치 글은 브랜치 상자에 이미 보이므로 뺀다. 공개 글끼리만 이어 주므로 누구에게나 같은 응답이다.
 */
@Service
public class SimilarPosts {
    private static final String BASE = " FROM post p JOIN member m ON m.id = p.author_id WHERE " + PostAccessPolicy.PUBLIC_LIST_CONDITION
            + " AND p.id <> ? AND NOT " + SeriesSql.SAME_SERIES_AS + " AND NOT " + TopicSql.SAME_TOPIC_AS;

    private final JdbcTemplate jdbc;
    private final SemanticSearch semantic;
    private final PostCardQuery cards;

    public SimilarPosts(JdbcTemplate jdbc, SemanticSearch semantic, PostCardQuery cards) {
        this.jdbc = jdbc;
        this.semantic = semantic;
        this.cards = cards;
    }

    public List<PostCard> of(long postId, int limit) {
        Boolean listed = jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM post p JOIN member m ON m.id = p.author_id WHERE p.id = ? AND "
                + PostAccessPolicy.PUBLIC_LIST_CONDITION + ")", Boolean.class, postId);
        if (!Boolean.TRUE.equals(listed)) return List.of();
        Set<Long> ids = new LinkedHashSet<>();
        List<Long> near = semantic.neighbors(postId, limit * 4);
        if (!near.isEmpty()) {
            // BASE의 자리표시자 셋(p.id <> ?, 시리즈·주제 조건) 다음 넷째가 후보 목록이다
            List<Long> allowed = jdbc.query("SELECT p.id" + BASE + " AND p.id = ANY (?)", ps -> {
                ps.setLong(1, postId);
                ps.setLong(2, postId);
                ps.setLong(3, postId);
                ps.setArray(4, ps.getConnection().createArrayOf("bigint", near.toArray()));
            }, (rs, i) -> rs.getLong(1));
            near.stream().filter(allowed::contains).limit(limit).forEach(ids::add);
        }
        if (ids.size() < limit) {
            ids.addAll(jdbc.query("SELECT p.id, " + TagSql.SHARED_COUNT + " AS shared" + BASE
                    + " AND " + TagSql.SHARED_COUNT + " > 0 ORDER BY shared DESC, p.first_public_at DESC, p.id DESC LIMIT ?",
                    (rs, i) -> rs.getLong(1), postId, postId, postId, postId, postId, limit));
        }
        return cards.publicCards(new ArrayList<>(ids).subList(0, Math.min(limit, ids.size())));
    }
}
