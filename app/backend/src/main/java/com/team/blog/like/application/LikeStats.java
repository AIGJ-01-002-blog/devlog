package com.team.blog.like.application;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.team.blog.shared.stats.Counts;

/** 관리자 페이지(062)의 좋아요 집계 */
@Service
public class LikeStats {
    private final JdbcTemplate jdbc;

    public LikeStats(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long total() {
        return Counts.one(jdbc, "SELECT count(*) FROM post_like");
    }

    public Map<LocalDate, Long> byDay(Instant from) {
        return Counts.byDay(jdbc, """
                SELECT (created_at AT TIME ZONE 'Asia/Seoul')::date AS d, count(*) AS n
                FROM post_like WHERE created_at >= ? GROUP BY d
                """, from);
    }

    /** 몇몇 글(회원 한 명의 글)이 날짜별로 받은 좋아요 (#239) */
    public Map<LocalDate, Long> byDayOfPosts(List<Long> postIds, Instant from) {
        return Counts.byDayOfKeys(jdbc, """
                SELECT (created_at AT TIME ZONE 'Asia/Seoul')::date AS d, count(*) AS n
                FROM post_like WHERE post_id = ANY (?) AND created_at >= ? GROUP BY d
                """, postIds, from);
    }

    public Map<Long, Long> byPosts(List<Long> postIds) {
        return Counts.byKeys(jdbc, "SELECT post_id AS k, count(*) AS n FROM post_like WHERE post_id = ANY (?) GROUP BY k", postIds);
    }
}
