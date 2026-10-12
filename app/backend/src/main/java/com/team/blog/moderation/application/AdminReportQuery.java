package com.team.blog.moderation.application;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.team.blog.account.infra.MemberSuspensionRepository;
import com.team.blog.shared.cursor.CursorCodec;
import com.team.blog.shared.error.NotFoundException;
import com.team.blog.shared.stats.Counts;
import com.team.blog.shared.time.Times;

/**
 * 관리자 신고 목록·처리 화면 (019 FR-011~FR-014). 관리자는 신고 시점 스냅샷만 보고, 지금 비공개·휴지통인 원문은 열지 않는다.
 * 신고자는 화면에 내보내지 않는다(사유·설명만).
 */
@Service
public class AdminReportQuery {
    static final int PAGE = 20;
    private static final int PREVIEW = 80;
    private static final String COL_STATUS = "status";
    private static final String COL_HANDLE = "handle";
    private static final String COL_CREATED_AT = "created_at";

    private final JdbcTemplate jdbc;
    private final CursorCodec cursors;
    private final MemberSuspensionRepository suspensions;

    public AdminReportQuery(JdbcTemplate jdbc, CursorCodec cursors, MemberSuspensionRepository suspensions) {
        this.jdbc = jdbc;
        this.cursors = cursors;
        this.suspensions = suspensions;
    }

    public enum Tab { PENDING, HANDLED }

    /** 대상의 지금 상태 (FR-013) */
    public enum TargetState { PUBLIC, FRIENDS, PRIVATE, DRAFT, TRASH, HIDDEN, DELETED_COMMENT, AUTHOR_WITHDRAWN, GONE }

    public record Row(long caseId, ReportTarget targetType, String status, String preview, String authorHandle, int reportCount,
                      Map<ReportReason, Integer> reasons, Instant latestAt, Instant handledAt, boolean hiddenNow) {}

    public record Page(List<Row> items, String nextCursor) {}

    public record ReportLine(ReportReason reason, String detail, Instant at) {}

    public record Suspension(long id, String reason, Instant startedAt, Instant endsAt, Instant liftedAt) {}

    /**
     * @param admin 관리자·매니저면 정지할 수 없다 (FR-035, 062)
     * @param role  USER·MANAGER·ADMIN (062 권한 바꾸기)
     */
    public record AuthorInfo(String handle, String nickname, Instant joinedAt, long hiddenCount, boolean suspended, boolean admin,
                             List<Suspension> suspensions, String role) {}

    /**
     * @param link       대상 주소 (지금 볼 수 있든 없든 화면이 링크로 보여 준다. 관리자도 볼 수 없으면 404)
     * @param mine       관리자 자신의 콘텐츠면 true (처리할 수 없음, FR-018)
     */
    public record Detail(long caseId, ReportTarget targetType, Long targetId, String status, TargetState targetState, String link,
                         String snapshotTitle, String snapshotContent, Map<ReportReason, Integer> reasons, List<ReportLine> reports,
                         ReportReason hiddenReason, Instant createdAt, Instant handledAt, AuthorInfo author, boolean mine) {}

    public Page list(Tab tab, String cursor) {
        String list = "admin-reports:" + tab;
        List<Object> args = new ArrayList<>();
        String after = "";
        if (tab == Tab.PENDING) {
            long[] k = cursors.decode(cursor, list, 3);
            if (k != null) {
                after = " WHERE (report_count, latest_at, id) < (?, ?, ?)";
                args.add(k[0]);
                args.add(Timestamp.from(Times.fromEpochMicros(k[1])));
                args.add(k[2]);
            }
        } else {
            long[] k = cursors.decode(cursor, list, 2);
            if (k != null) {
                after = " WHERE (handled_at, id) < (?, ?)";
                args.add(Timestamp.from(Times.fromEpochMicros(k[0])));
                args.add(k[1]);
            }
        }
        args.add(PAGE + 1);
        String where = tab == Tab.PENDING ? "rc.status = 'PENDING'" : "rc.status <> 'PENDING'";
        String order = tab == Tab.PENDING ? "report_count DESC, latest_at DESC, id DESC" : "handled_at DESC, id DESC";
        List<Row> rows = jdbc.query("""
                SELECT * FROM (
                    SELECT rc.id, rc.target_type, rc.status, rc.snapshot_title, rc.snapshot_content, rc.handled_at, m.handle,
                           count(r.id) AS report_count, COALESCE(max(r.created_at), rc.created_at) AS latest_at,
                           count(*) FILTER (WHERE r.reason = 'SPAM') AS spam, count(*) FILTER (WHERE r.reason = 'ABUSE') AS abuse,
                           count(*) FILTER (WHERE r.reason = 'SEXUAL') AS sexual, count(*) FILTER (WHERE r.reason = 'PRIVACY') AS privacy,
                           count(*) FILTER (WHERE r.reason = 'COPYRIGHT') AS copyright, count(*) FILTER (WHERE r.reason = 'OTHER') AS other,
                           COALESCE(p.hidden_at, c.hidden_at) IS NOT NULL AS hidden_now
                    FROM report_case rc
                    LEFT JOIN report r ON r.case_id = rc.id
                    JOIN member m ON m.id = rc.target_author_id
                    LEFT JOIN post p ON p.id = rc.post_id
                    LEFT JOIN comment c ON c.id = rc.comment_id
                    WHERE""" + " " + where + """

                    GROUP BY rc.id, m.handle, p.hidden_at, c.hidden_at
                ) x""" + after + " ORDER BY " + order + " LIMIT ?", this::row, args.toArray());
        CursorCodec.Page<Row> page = CursorCodec.page(rows, PAGE, r -> tab == Tab.PENDING
                ? cursors.encode(list, r.reportCount(), Times.toEpochMicros(r.latestAt()), r.caseId())
                : cursors.encode(list, Times.toEpochMicros(r.handledAt()), r.caseId()));
        return new Page(page.items(), page.nextCursor());
    }

    private Row row(ResultSet rs, int i) throws SQLException {
        String title = rs.getString("snapshot_title");
        String content = rs.getString("snapshot_content");
        String preview = title != null ? title : preview(content);
        Timestamp handled = rs.getTimestamp("handled_at");
        return new Row(rs.getLong("id"), ReportTarget.valueOf(rs.getString("target_type")), rs.getString(COL_STATUS), preview,
                rs.getString(COL_HANDLE), rs.getInt("report_count"), reasons(rs), rs.getTimestamp("latest_at").toInstant(),
                handled == null ? null : handled.toInstant(), rs.getBoolean("hidden_now"));
    }

    private static Map<ReportReason, Integer> reasons(ResultSet rs) throws SQLException {
        Map<ReportReason, Integer> m = new EnumMap<>(ReportReason.class);
        for (ReportReason r : ReportReason.values()) {
            int n = rs.getInt(r.name().toLowerCase(java.util.Locale.ROOT));
            if (n > 0) m.put(r, n);
        }
        return m;
    }

    public Detail detail(long caseId, long adminId) {
        List<Detail> found = jdbc.query("""
                SELECT rc.*, m.id AS author_id, m.handle, m.nickname, m.created_at AS joined_at, m.status AS member_status, m.role AS member_role,
                       m.withdrawn_at IS NOT NULL OR m.deleted_at IS NOT NULL AS withdrawn,
                       p.status AS post_status, p.visibility AS post_visibility, p.deleted_at IS NOT NULL AS post_deleted,
                       p.hidden_at IS NOT NULL AS post_hidden, p.hidden_reason AS post_hidden_reason,
                       c.post_id AS comment_post_id, c.deleted_at IS NOT NULL AS comment_deleted,
                       c.hidden_at IS NOT NULL AS comment_hidden, c.hidden_reason AS comment_hidden_reason,
                       cp.deleted_at IS NOT NULL AS comment_post_deleted, cpm.handle AS comment_post_handle,
                       (SELECT count(*) FROM post x WHERE x.author_id = m.id AND x.hidden_at IS NOT NULL)
                       + (SELECT count(*) FROM comment y WHERE y.author_id = m.id AND y.hidden_at IS NOT NULL) AS hidden_count
                FROM report_case rc
                JOIN member m ON m.id = rc.target_author_id
                LEFT JOIN post p ON p.id = rc.post_id
                LEFT JOIN comment c ON c.id = rc.comment_id
                LEFT JOIN post cp ON cp.id = c.post_id
                LEFT JOIN member cpm ON cpm.id = cp.author_id
                WHERE rc.id = ?
                """, (rs, i) -> detail(rs, adminId), caseId);
        if (found.isEmpty()) throw new NotFoundException();
        return found.get(0);
    }

    private Detail detail(ResultSet rs, long adminId) throws SQLException {
        long caseId = rs.getLong("id");
        ReportTarget type = ReportTarget.valueOf(rs.getString("target_type"));
        Long targetId = type == ReportTarget.POST ? nullableLong(rs, "post_id") : nullableLong(rs, "comment_id");
        boolean withdrawn = rs.getBoolean("withdrawn");
        TargetState state;
        String link = null;
        String hiddenReason;
        if (targetId == null) {
            state = TargetState.GONE;
            hiddenReason = null;
        } else if (type == ReportTarget.POST) {
            hiddenReason = rs.getString("post_hidden_reason");
            state = withdrawn ? TargetState.AUTHOR_WITHDRAWN : postState(rs);
            link = "/@" + rs.getString(COL_HANDLE) + "/posts/" + targetId;
        } else {
            hiddenReason = rs.getString("comment_hidden_reason");
            state = withdrawn ? TargetState.AUTHOR_WITHDRAWN : commentState(rs);
            long postId = rs.getLong("comment_post_id");
            link = "/@" + rs.getString("comment_post_handle") + "/posts/" + postId + "?comment=" + targetId + "#comment-" + targetId;
        }
        long authorId = rs.getLong("author_id");
        List<ReportLine> reports = jdbc.query("SELECT reason, detail, created_at FROM report WHERE case_id = ? ORDER BY created_at, id",
                (r, i) -> new ReportLine(ReportReason.valueOf(r.getString("reason")), r.getString("detail"), r.getTimestamp(COL_CREATED_AT).toInstant()),
                caseId);
        Map<ReportReason, Integer> reasons = new EnumMap<>(ReportReason.class);
        reports.forEach(l -> reasons.merge(l.reason(), 1, Integer::sum));
        List<Suspension> history = suspensions.findByMemberIdOrderByStartedAtDesc(authorId).stream()
                .map(s -> new Suspension(s.getId(), s.getReason(), s.getStartedAt(), s.getEndsAt(), s.getLiftedAt())).toList();
        AuthorInfo author = new AuthorInfo(rs.getString(COL_HANDLE), withdrawn ? null : rs.getString("nickname"),
                rs.getTimestamp("joined_at").toInstant(), rs.getLong("hidden_count"), "SUSPENDED".equals(rs.getString("member_status")),
                !"USER".equals(rs.getString("member_role")), history, rs.getString("member_role"));
        Timestamp handled = rs.getTimestamp("handled_at");
        return new Detail(caseId, type, targetId, rs.getString(COL_STATUS), state, link, rs.getString("snapshot_title"),
                rs.getString("snapshot_content"), reasons, reports, hiddenReason == null ? null : ReportReason.valueOf(hiddenReason),
                rs.getTimestamp(COL_CREATED_AT).toInstant(), handled == null ? null : handled.toInstant(), author, authorId == adminId);
    }

    private static TargetState postState(ResultSet rs) throws SQLException {
        if (rs.getBoolean("post_deleted")) return TargetState.TRASH;
        if (rs.getBoolean("post_hidden")) return TargetState.HIDDEN;
        if ("DRAFT".equals(rs.getString("post_status"))) return TargetState.DRAFT;
        return TargetState.valueOf(rs.getString("post_visibility"));
    }

    private static TargetState commentState(ResultSet rs) throws SQLException {
        if (rs.getBoolean("comment_deleted") || rs.getBoolean("comment_post_deleted")) return TargetState.DELETED_COMMENT;
        return rs.getBoolean("comment_hidden") ? TargetState.HIDDEN : TargetState.PUBLIC;
    }

    /** 관리자 대시보드(062): 처리를 기다리는 신고 사건 수 */
    public long pendingCount() {
        Long n = jdbc.queryForObject("SELECT count(*) FROM report_case WHERE status = 'PENDING'", Long.class);
        return n == null ? 0 : n;
    }

    /** 관리자 대시보드(062): 날짜별 접수된 신고 수 */
    public Map<LocalDate, Long> reportsByDay(Instant from) {
        return Counts.byDay(jdbc, """
                SELECT (created_at AT TIME ZONE 'Asia/Seoul')::date AS d, count(*) AS n
                FROM report WHERE created_at >= ? GROUP BY d
                """, from);
    }

    /** 회원 화면 `/admin/members/{handle}`: 가입일·숨겨진 콘텐츠 수·정지 이력 */
    public AuthorInfo member(String handle) {
        List<AuthorInfo> found = jdbc.query("""
                SELECT m.id, m.handle, m.nickname, m.created_at, m.status, m.role,
                       (SELECT count(*) FROM post x WHERE x.author_id = m.id AND x.hidden_at IS NOT NULL)
                       + (SELECT count(*) FROM comment y WHERE y.author_id = m.id AND y.hidden_at IS NOT NULL) AS hidden_count
                FROM member m WHERE m.handle = ? AND m.deleted_at IS NULL
                """, (rs, i) -> new AuthorInfo(rs.getString(COL_HANDLE), rs.getString("nickname"), rs.getTimestamp(COL_CREATED_AT).toInstant(),
                rs.getLong("hidden_count"), "SUSPENDED".equals(rs.getString(COL_STATUS)), !"USER".equals(rs.getString("role")),
                suspensions.findByMemberIdOrderByStartedAtDesc(rs.getLong("id")).stream()
                        .map(s -> new Suspension(s.getId(), s.getReason(), s.getStartedAt(), s.getEndsAt(), s.getLiftedAt())).toList(),
                rs.getString("role")),
                handle);
        if (found.isEmpty()) throw new NotFoundException();
        return found.get(0);
    }

    static String preview(String content) {
        if (content == null) return null;
        String s = content.replaceAll("\\s+", " ").strip();
        return s.codePointCount(0, s.length()) <= PREVIEW ? s : s.substring(0, s.offsetByCodePoints(0, PREVIEW)) + "…";
    }

    private static Long nullableLong(ResultSet rs, String col) throws SQLException {
        long v = rs.getLong(col);
        return rs.wasNull() ? null : v;
    }
}
