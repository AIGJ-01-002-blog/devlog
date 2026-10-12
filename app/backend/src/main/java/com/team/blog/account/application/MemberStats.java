package com.team.blog.account.application;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Service;

import com.team.blog.shared.stats.Counts;

/**
 * 관리자 페이지(062)의 회원 집계와 회원 목록. 익명 처리가 끝난 회원(deleted_at)은 목록에 넣지 않는다.
 */
@Service
public class MemberStats {
    public static final int PAGE_SIZE = 20;

    private static final String SELECT = """
            SELECT m.id, m.handle, m.nickname, m.role, m.status, m.created_at, m.last_active_at, a.provider
            FROM member m LEFT JOIN auth_identity a ON a.member_id = m.id""";

    private final JdbcTemplate jdbc;

    public MemberStats(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** @param withdrawing 탈퇴 신청 뒤 30일 유예 중 */
    public record Summary(long total, long active, long suspended, long withdrawing, long managers, long admins) {}

    public record Row(long id, String handle, String nickname, String role, String status, String provider, Instant joinedAt,
                      Instant lastActiveAt) {}

    public record Page(List<Row> items, int page, long total, int pageSize) {}

    public Summary summary() {
        return jdbc.queryForObject("""
                SELECT count(*) AS total,
                       count(*) FILTER (WHERE status = 'ACTIVE') AS active,
                       count(*) FILTER (WHERE status = 'SUSPENDED') AS suspended,
                       count(*) FILTER (WHERE status = 'WITHDRAWN') AS withdrawing,
                       count(*) FILTER (WHERE role = 'MANAGER') AS managers,
                       count(*) FILTER (WHERE role = 'ADMIN') AS admins
                FROM member WHERE deleted_at IS NULL
                """, (rs, i) -> new Summary(rs.getLong("total"), rs.getLong("active"), rs.getLong("suspended"), rs.getLong("withdrawing"),
                rs.getLong("managers"), rs.getLong("admins")));
    }

    public Map<LocalDate, Long> signupsByDay(Instant from) {
        return Counts.byDay(jdbc, """
                SELECT (created_at AT TIME ZONE 'Asia/Seoul')::date AS d, count(*) AS n
                FROM member WHERE created_at >= ? GROUP BY d
                """, from);
    }

    /** 기간 안에 활동(로그인·글쓰기 등)한 회원 수. 최근 활동 시각은 하루에 한 번 정도만 갱신된다 */
    /** 날짜별 활동한 회원 수 (066). 탈퇴로 지워진 회원은 함께 빠진다 */
    public Map<LocalDate, Long> activeByDay(Instant from) {
        Map<LocalDate, Long> out = new HashMap<>();
        jdbc.query("SELECT day AS d, count(*) AS n FROM member_active_day WHERE day >= ? GROUP BY day",
                (RowCallbackHandler) rs -> out.put(rs.getObject("d", LocalDate.class), rs.getLong("n")), LocalDate.ofInstant(from, Counts.KST));
        return out;
    }

    /** 기간 [from, to] 동안 하루라도 활동한 회원 수 (066) */
    public long activeBetween(LocalDate from, LocalDate to) {
        return Counts.one(jdbc, "SELECT count(DISTINCT member_id) FROM member_active_day WHERE day BETWEEN ? AND ?", from, to);
    }

    public long activeSince(Instant from) {
        return Counts.one(jdbc, "SELECT count(*) FROM member WHERE deleted_at IS NULL AND last_active_at >= ?", Timestamp.from(from));
    }

    /**
     * @param q      주소·닉네임 일부 (대소문자 무시)
     * @param role   USER·MANAGER·ADMIN 또는 STAFF(매니저+관리자), 비우면 전체
     * @param status ACTIVE·SUSPENDED·WITHDRAWN, 비우면 전체
     */
    public Page page(String q, String role, String status, int page) {
        List<Object> args = new ArrayList<>();
        StringBuilder where = new StringBuilder("m.deleted_at IS NULL");
        String query = q == null ? "" : q.strip().toLowerCase(Locale.ROOT);
        if (!query.isEmpty()) {
            String like = "%" + query.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
            where.append(" AND (m.handle LIKE ? OR lower(m.nickname) LIKE ?)");
            args.add(like);
            args.add(like);
        }
        String r = role == null ? "" : role.strip().toUpperCase(Locale.ROOT);
        if (r.equals("STAFF")) where.append(" AND m.role <> 'USER'");
        else if (List.of("USER", "MANAGER", "ADMIN").contains(r)) {
            where.append(" AND m.role = ?");
            args.add(r);
        }
        String st = status == null ? "" : status.strip().toUpperCase(Locale.ROOT);
        if (List.of("ACTIVE", "SUSPENDED", "WITHDRAWN").contains(st)) {
            where.append(" AND m.status = ?");
            args.add(st);
        }
        long total = Counts.one(jdbc, "SELECT count(*) FROM member m WHERE " + where, args.toArray());
        int p = Math.max(1, page);
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(PAGE_SIZE);
        pageArgs.add((long) (p - 1) * PAGE_SIZE);
        List<Row> rows = jdbc.query(SELECT + " WHERE " + where + " ORDER BY m.created_at DESC, m.id DESC LIMIT ? OFFSET ?", (rs, i) -> row(rs), pageArgs.toArray());
        return new Page(rows, p, total, PAGE_SIZE);
    }

    public Optional<Row> byHandle(String handle) {
        return jdbc.query(SELECT + " WHERE m.handle = ? AND m.deleted_at IS NULL", (rs, i) -> row(rs), handle == null ? "" : handle.strip()).stream().findFirst();
    }

    /** 번호 → 주소·닉네임 (대시보드의 많이 쓴 회원) */
    public Map<Long, Row> byIds(List<Long> ids) {
        Map<Long, Row> out = new HashMap<>();
        if (ids.isEmpty()) return out;
        jdbc.query(SELECT + " WHERE m.id = ANY (?)", (RowCallbackHandler) rs -> out.put(rs.getLong("id"), row(rs)), (Object) ids.toArray(Long[]::new));
        return out;
    }

    private static Row row(ResultSet rs) throws SQLException {
        Timestamp active = rs.getTimestamp("last_active_at");
        return new Row(rs.getLong("id"), rs.getString("handle"), rs.getString("nickname"), rs.getString("role"), rs.getString("status"),
                rs.getString("provider"), rs.getTimestamp("created_at").toInstant(), active == null ? null : active.toInstant());
    }
}
