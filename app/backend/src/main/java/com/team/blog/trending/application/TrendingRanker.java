package com.team.blog.trending.application;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.team.blog.post.access.PostAccessPolicy;
import com.team.blog.shared.jdbc.Columns;

/**
 * 트렌딩 점수 계산 (017 FR-006~FR-011, docs/32). 쿼리 하나로 상위 글 번호를 순서대로 낸다.
 * <pre>
 * 점수 = (3 × 좋아요 + 2 × 작성자 외 댓글 작성자 수 + 0.1 × 조회수) ÷ (처음 공개 후 경과 시간 + 2)^1.5
 * </pre>
 * 대상: 공개 목록 공용 조건 + 처음 공개 7일 안 + 좋아요 1개 이상 또는 작성자 외 댓글 작성자 1명 이상. 한 작성자는 점수 높은 3개까지.
 * 동점은 처음 공개 최신 → 번호 큰 순. 수는 저장하지 않고 post_like·post_view·comment에서 센다(사건으로 세지 않음, FR-023).
 * 숨긴 댓글도 삭제된 댓글처럼 뺀다(A-3: 숨김은 운영 판단으로 반응에서 제외).
 */
@Component
public class TrendingRanker {
    static final int MAX = 100;
    static final int PER_AUTHOR = 3;
    static final int WINDOW_DAYS = 7;

    private final JdbcTemplate jdbc;

    public TrendingRanker(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Long> rank(Instant now, int limit) {
        Timestamp ts = Timestamp.from(now);
        return Columns.longs(jdbc, """
                WITH candidates AS (
                    SELECT p.id, p.author_id, p.first_public_at,
                           (SELECT count(*) FROM post_like l WHERE l.post_id = p.id) AS likes,
                           (SELECT count(DISTINCT c.author_id) FROM comment c
                             WHERE c.post_id = p.id AND c.author_id <> p.author_id AND c.deleted_at IS NULL AND c.hidden_at IS NULL) AS commenters,
                           (SELECT count(*) FROM post_view v WHERE v.post_id = p.id) AS views
                    FROM post p JOIN member m ON m.id = p.author_id
                    WHERE\s""" + PostAccessPolicy.PUBLIC_LIST_CONDITION + """
                      AND NOT p.ai_diary
                      AND p.first_public_at > ?::timestamptz - make_interval(days => ?)
                ), scored AS (
                    SELECT id, author_id, first_public_at,
                           (3 * likes + 2 * commenters + 0.1 * views)
                             / power(greatest(extract(epoch FROM (?::timestamptz - first_public_at)), 0) / 3600.0 + 2, 1.5) AS score
                    FROM candidates WHERE likes > 0 OR commenters > 0
                ), capped AS (
                    SELECT id, score, first_public_at,
                           row_number() OVER (PARTITION BY author_id ORDER BY score DESC, first_public_at DESC, id DESC) AS nth
                    FROM scored
                )
                SELECT id FROM capped WHERE nth <= ?
                ORDER BY score DESC, first_public_at DESC, id DESC
                LIMIT ?
                """, ts, WINDOW_DAYS, ts, PER_AUTHOR, limit);
    }
}
