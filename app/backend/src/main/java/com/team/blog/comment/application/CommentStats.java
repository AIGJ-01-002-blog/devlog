package com.team.blog.comment.application;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.team.blog.shared.stats.Counts;

/** 관리자 페이지(062)의 댓글 집계. 지운 댓글은 세지 않고, 숨긴 댓글은 쓴 수에는 넣는다. */
@Service
public class CommentStats {
    private final JdbcTemplate jdbc;

    public CommentStats(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long total() {
        return Counts.one(jdbc, "SELECT count(*) FROM comment WHERE deleted_at IS NULL");
    }

    public Map<LocalDate, Long> byDay(Instant from) {
        return Counts.byDay(jdbc, """
                SELECT (created_at AT TIME ZONE 'Asia/Seoul')::date AS d, count(*) AS n
                FROM comment WHERE created_at >= ? AND deleted_at IS NULL GROUP BY d
                """, from);
    }

    /** 회원이 쓴 댓글 수 */
    public Map<Long, Long> byAuthors(List<Long> authorIds) {
        return Counts.byKeys(jdbc, """
                SELECT author_id AS k, count(*) AS n FROM comment WHERE author_id = ANY (?) AND deleted_at IS NULL GROUP BY k
                """, authorIds);
    }

    /** 글마다 보이는 댓글 수 (글 화면의 댓글 수와 같다) */
    /** 몇몇 글(회원 한 명의 글)이 날짜별로 받은 댓글. 글쓴이가 단 댓글(답글)은 빼고, 숨긴 댓글도 뺀다 (#239) */
    public Map<LocalDate, Long> byDayOfPosts(List<Long> postIds, Instant from, long exceptAuthorId) {
        return Counts.byDayOfKeys(jdbc, """
                SELECT (created_at AT TIME ZONE 'Asia/Seoul')::date AS d, count(*) AS n
                FROM comment WHERE post_id = ANY (?) AND created_at >= ? AND author_id <> ? AND deleted_at IS NULL AND hidden_at IS NULL
                GROUP BY d
                """, postIds, from, exceptAuthorId);
    }

    public Map<Long, Long> byPosts(List<Long> postIds) {
        return Counts.byKeys(jdbc, """
                SELECT post_id AS k, count(*) AS n FROM comment WHERE post_id = ANY (?) AND deleted_at IS NULL AND hidden_at IS NULL GROUP BY k
                """, postIds);
    }
}
