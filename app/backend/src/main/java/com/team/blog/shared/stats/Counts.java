package com.team.blog.shared.stats;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;

/**
 * 관리자 통계(062)의 집계 도우미. 각 모듈이 자기 테이블을 세는 SQL을 넘기면 날짜별·번호별 묶음으로 돌려준다.
 * 날짜는 한국 시각 기준이다.
 */
public final class Counts {
    public static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private Counts() {}

    /**
     * @param sql `SELECT (시각 AT TIME ZONE 'Asia/Seoul')::date AS d, count(*) AS n ... WHERE 시각 >= ? GROUP BY d` 모양.
     *            첫 번째 자리표시자는 시작 시각이다
     */
    public static Map<LocalDate, Long> byDay(JdbcTemplate jdbc, String sql, Instant from) {
        Map<LocalDate, Long> out = new HashMap<>();
        jdbc.query(sql, (RowCallbackHandler) rs -> out.put(rs.getObject("d", LocalDate.class), rs.getLong("n")), Timestamp.from(from));
        return out;
    }

    /**
     * 몇몇 글(키)만 날짜별로 센다 (회원 대시보드 #239).
     * @param sql `SELECT (시각 AT TIME ZONE 'Asia/Seoul')::date AS d, count(*) AS n ... WHERE 키 = ANY (?) AND 시각 >= ? GROUP BY d` 모양
     */
    public static Map<LocalDate, Long> byDayOfKeys(JdbcTemplate jdbc, String sql, Collection<Long> keys, Instant from, Object... more) {
        Map<LocalDate, Long> out = new HashMap<>();
        if (keys.isEmpty()) return out;
        Object[] args = new Object[2 + more.length];
        args[0] = keys.toArray(Long[]::new);
        args[1] = Timestamp.from(from);
        System.arraycopy(more, 0, args, 2, more.length);
        jdbc.query(sql, (RowCallbackHandler) rs -> out.put(rs.getObject("d", LocalDate.class), rs.getLong("n")), args);
        return out;
    }

    /**
     * @param sql `SELECT 키 AS k, count(*) AS n ... WHERE 키 = ANY (?) GROUP BY k` 모양
     */
    public static Map<Long, Long> byKeys(JdbcTemplate jdbc, String sql, Collection<Long> keys) {
        Map<Long, Long> out = new HashMap<>();
        if (keys.isEmpty()) return out;
        jdbc.query(sql, (RowCallbackHandler) rs -> out.put(rs.getLong("k"), rs.getLong("n")), (Object) keys.toArray(Long[]::new));
        return out;
    }

    public static long one(JdbcTemplate jdbc, String sql, Object... args) {
        Long n = jdbc.queryForObject(sql, Long.class, args);
        return n == null ? 0 : n;
    }
}
