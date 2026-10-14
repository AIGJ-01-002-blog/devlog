package com.team.blog.view.application;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Service;

import com.team.blog.shared.stats.Counts;

/** 관리자 페이지(062)의 조회수 집계. 조회는 1분마다 Redis에서 post_view로 옮겨지므로 마지막 1분은 아직 안 보일 수 있다. */
@Service
public class ViewStats {
    private final JdbcTemplate jdbc;

    public ViewStats(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long total() {
        return Counts.one(jdbc, "SELECT count(*) FROM post_view");
    }

    public Map<LocalDate, Long> byDay(Instant from) {
        return Counts.byDay(jdbc, """
                SELECT (viewed_at AT TIME ZONE 'Asia/Seoul')::date AS d, count(*) AS n
                FROM post_view WHERE viewed_at >= ? GROUP BY d
                """, from);
    }

    /** 몇몇 글(회원 한 명의 글)만 날짜별 조회수 (#239) */
    public Map<LocalDate, Long> byDayOfPosts(List<Long> postIds, Instant from) {
        return Counts.byDayOfKeys(jdbc, """
                SELECT (viewed_at AT TIME ZONE 'Asia/Seoul')::date AS d, count(*) AS n
                FROM post_view WHERE post_id = ANY (?) AND viewed_at >= ? GROUP BY d
                """, postIds, from);
    }

    /** 몇몇 글 가운데 기간 안에 많이 본 글 번호 → 조회수 (#239) */
    public List<Map.Entry<Long, Long>> topOfPosts(List<Long> postIds, Instant from, int limit) {
        if (postIds.isEmpty()) return List.of();
        return jdbc.query("""
                SELECT post_id, count(*) AS n FROM post_view WHERE post_id = ANY (?) AND viewed_at >= ?
                GROUP BY post_id ORDER BY n DESC, post_id DESC LIMIT ?
                """, (rs, i) -> Map.entry(rs.getLong("post_id"), rs.getLong("n")), postIds.toArray(Long[]::new), Timestamp.from(from), limit);
    }

    public Map<Long, Long> byPosts(List<Long> postIds) {
        return Counts.byKeys(jdbc, "SELECT post_id AS k, count(*) AS n FROM post_view WHERE post_id = ANY (?) GROUP BY k", postIds);
    }

    /** 기간 안에 많이 본 글 번호 → 조회수 (많은 순) */
    public List<Map.Entry<Long, Long>> top(Instant from, int limit) {
        return jdbc.query("""
                SELECT post_id, count(*) AS n FROM post_view WHERE viewed_at >= ?
                GROUP BY post_id ORDER BY n DESC, post_id DESC LIMIT ?
                """, (rs, i) -> Map.entry(rs.getLong("post_id"), rs.getLong("n")), Timestamp.from(from), limit);
    }

    // --- 사이트 방문자 (064) ---

    /** 날짜별 순방문자 수 */
    public Map<LocalDate, Long> visitorsByDay(Instant from) {
        return dayCounts("count(*)", from);
    }

    /** 날짜별 방문 수 (30분 넘게 쉬었다 오면 한 번 더) */
    public Map<LocalDate, Long> visitsByDay(Instant from) {
        return dayCounts("sum(visits)", from);
    }

    private Map<LocalDate, Long> dayCounts(String agg, Instant from) {
        Map<LocalDate, Long> out = new java.util.HashMap<>();
        jdbc.query("SELECT day AS d, " + agg + " AS n FROM site_visit WHERE day >= ? GROUP BY day",
                (RowCallbackHandler) rs -> out.put(rs.getObject("d", LocalDate.class), rs.getLong("n")), LocalDate.ofInstant(from, Counts.KST));
        return out;
    }

    /**
     * 기간 [from, to] 동안 한 번이라도 온 사람 수. 같은 사람이 여러 날 와도 한 명이다.
     * 쿠키 없는 비회원은 날마다 값이 바뀌어 날마다 한 명으로 센다.
     */
    public record Visitors(long visitors, long members, long visits) {}

    public Visitors visitors(LocalDate from, LocalDate to) {
        return jdbc.queryForObject("""
                SELECT count(DISTINCT visitor) AS v, count(DISTINCT visitor) FILTER (WHERE member) AS m, coalesce(sum(visits), 0) AS n
                FROM site_visit WHERE day BETWEEN ? AND ?
                """, (rs, i) -> new Visitors(rs.getLong("v"), rs.getLong("m"), rs.getLong("n")), from, to);
    }

    // --- 유입 경로·많이 본 화면 (070) ---

    /** 날짜별 검색으로 온 방문 수 (구글·네이버·다음·빙) */
    public Map<LocalDate, Long> searchVisitsByDay(Instant from) {
        Map<LocalDate, Long> out = new java.util.HashMap<>();
        jdbc.query("SELECT day AS d, sum(visits) AS n FROM visit_source_day WHERE day >= ? AND source = ANY (?) GROUP BY day",
                (RowCallbackHandler) rs -> out.put(rs.getObject("d", LocalDate.class), rs.getLong("n")), LocalDate.ofInstant(from, Counts.KST),
                VisitSources.SEARCH.toArray(String[]::new));
        return out;
    }

    /** @param host source가 other일 때 그 사이트 (나머지 사이트를 모은 줄은 빈 값) */
    public record SourceLine(String source, String host, long visits) {}

    /**
     * 기간 [from, to]의 유입 경로별 새 방문 수 (많은 순). 기타 사이트는 많은 순으로 otherHosts개까지 따로 보이고 나머지는 한 줄로 모은다.
     */
    public List<SourceLine> sources(LocalDate from, LocalDate to, int otherHosts) {
        List<SourceLine> rows = jdbc.query("""
                SELECT source, host, sum(visits) AS n FROM visit_source_day WHERE day BETWEEN ? AND ?
                GROUP BY source, host ORDER BY n DESC, source, host
                """, (rs, i) -> new SourceLine(rs.getString("source"), rs.getString("host"), rs.getLong("n")), from, to);
        List<SourceLine> out = new java.util.ArrayList<>();
        long rest = 0;
        int others = 0;
        for (SourceLine r : rows) {
            if (!VisitSources.OTHER.equals(r.source()) || r.host().isEmpty()) out.add(r);
            else if (others++ < otherHosts) out.add(r);
            else rest += r.visits();
        }
        if (rest > 0) out.add(new SourceLine(VisitSources.OTHER, "", rest));
        return out;
    }

    public record PageLine(String path, long views) {}

    /** 기간 [from, to]에 많이 연 화면 (많은 순) */
    public List<PageLine> topPages(LocalDate from, LocalDate to, int limit) {
        return jdbc.query("""
                SELECT path, sum(views) AS n FROM page_view_day WHERE day BETWEEN ? AND ?
                GROUP BY path ORDER BY n DESC, path LIMIT ?
                """, (rs, i) -> new PageLine(rs.getString("path"), rs.getLong("n")), from, to, limit);
    }
}
