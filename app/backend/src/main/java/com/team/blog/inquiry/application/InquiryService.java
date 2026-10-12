package com.team.blog.inquiry.application;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.team.blog.notification.application.NotificationService;
import com.team.blog.release.ReleaseNotes;
import com.team.blog.shared.error.ApiException;
import com.team.blog.shared.error.FieldErrorItem;
import com.team.blog.shared.error.NotFoundException;
import com.team.blog.shared.text.TextCleaner;
import com.team.blog.shared.time.Times;
import com.team.blog.shared.web.RateLimiter;

/**
 * 문의·신고 접수와 처리 (054). 회원이 웹에서, 연결한 AI가 MCP report_bug로 남긴다. 내용은 접수한 회원과 관리자만 본다.
 * 사용자 글이 공개 저장소에 그대로 나가지 않도록 GitHub 이슈는 만들지 않는다. 관리자(또는 관리자 토큰으로 연결한 AI)가
 * 상태·답변·고친 버전을 적고, 답변이 새로 붙거나 바뀌면 회원에게 알림(INQUIRY_ANSWERED)을 보낸다.
 */
@Service
public class InquiryService {
    public static final int TITLE_MAX = 200;
    public static final int CONTENT_MAX = 20_000;
    public static final int ANSWER_MAX = 5_000;
    static final int PAGE_URL_MAX = 500;
    static final int TOOL_MAX = 60;
    static final int CLIENT_MAX = 80;
    static final int PER_HOUR = 10;
    static final int PER_DAY = 30;
    static final int MINE_MAX = 50;
    static final int ADMIN_PAGE = 30;
    private static final String UP_TO = "자까지 쓸 수 있어요.";
    private static final String TITLE = "title";
    private static final String CONTENT = "content";
    private static final String UPDATE_HANDLING = """
            UPDATE inquiry SET status = COALESCE(?, status),
                   answer = CASE WHEN ? THEN answer WHEN ? = '' THEN NULL ELSE ? END,
                   answered_at = CASE WHEN ? THEN answered_at WHEN ? = '' THEN NULL WHEN ? THEN ? ELSE answered_at END,
                   fixed_version = CASE WHEN ? THEN fixed_version WHEN ? = '' THEN NULL ELSE ? END,
                   handled_by = ?, updated_at = ?
            WHERE id = ?
            """;
    private static final Pattern VERSION = Pattern.compile("^\\d{1,3}\\.\\d{1,3}\\.\\d{1,4}$");

    public enum Source { WEB, MCP }

    /** 웹 접수. pageUrl은 문제가 난 화면 주소(사이트 안 경로)이고 없어도 된다 */
    public record Submit(String category, String title, String content, String pageUrl) {}

    /** MCP 접수. tool은 문제가 난 devlog 도구 이름, client는 AI 앱 또는 토큰 이름 */
    public record AiReport(String title, String content, String tool, String client) {}

    public record Item(long id, InquiryCategory category, Source source, String title, String content, String pageUrl,
                       String toolName, String clientName, String appVersion, InquiryStatus status, String answer,
                       Instant answeredAt, String fixedVersion, Instant createdAt, Instant updatedAt,
                       String memberHandle, String memberNickname) {}

    public record Page(List<Item> items, Long nextBefore) {}

    /** 관리자 처리. 비운 칸은 그대로 둔다. answer를 ""로 주면 답변을 지운다(알림은 그대로 남는다) */
    public record Update(String status, String answer, String fixedVersion) {}

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final RateLimiter rateLimiter;
    private final NotificationService notifications;
    private final ReleaseNotes releases;
    private final Clock clock;

    public InquiryService(JdbcTemplate jdbc, PlatformTransactionManager txManager, RateLimiter rateLimiter,
                          NotificationService notifications, ReleaseNotes releases, Clock clock) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(txManager);
        this.rateLimiter = rateLimiter;
        this.notifications = notifications;
        this.releases = releases;
        this.clock = clock;
    }

    public long submit(long memberId, Submit cmd) {
        InquiryCategory category = InquiryCategory.parse(cmd.category())
                .orElseThrow(() -> invalid("category", "INVALID_CATEGORY", "종류를 골라 주세요."));
        String title = title(cmd.title());
        String content = content(cmd.content());
        limit(memberId);
        return insert(memberId, category, Source.WEB, title, content, pageUrl(cmd.pageUrl()), null, null);
    }

    /** 연결한 AI의 버그 신고. 종류는 늘 BUG다. 실패는 웹과 같은 예외로 알린다(MCP가 문장으로 바꾼다) */
    public long reportFromAi(long memberId, AiReport r) {
        String title = title(r.title());
        String content = content(r.content());
        limit(memberId);
        return insert(memberId, InquiryCategory.BUG, Source.MCP, title, content, null, cut(TextCleaner.cleanLine(r.tool()), TOOL_MAX),
                cut(TextCleaner.cleanLine(r.client()), CLIENT_MAX));
    }

    /** 내가 남긴 문의 (최근 50개). 관리자 이름은 싣지 않는다 */
    public List<Item> mine(long memberId) {
        return jdbc.query(SELECT + " WHERE i.member_id = ? ORDER BY i.id DESC LIMIT ?", this::row, memberId, MINE_MAX).stream()
                .map(i -> new Item(i.id(), i.category(), i.source(), i.title(), i.content(), i.pageUrl(), i.toolName(), null,
                        i.appVersion(), i.status(), i.answer(), i.answeredAt(), i.fixedVersion(), i.createdAt(), i.updatedAt(), null, null))
                .toList();
    }

    /**
     * 관리자 목록. open이면 접수·처리 중(오래된 것부터 처리하도록 번호 순), 아니면 해결·닫힘(최근 순).
     * @param category 비우면 전체
     * @param before 다음 쪽: 앞 쪽 마지막 번호
     */
    public Page list(boolean open, InquiryCategory category, Long before) {
        List<Object> args = new ArrayList<>();
        StringBuilder where = new StringBuilder(open ? " WHERE i.status IN ('RECEIVED', 'IN_PROGRESS')" : " WHERE i.status IN ('RESOLVED', 'CLOSED')");
        if (category != null) {
            where.append(" AND i.category = ?");
            args.add(category.name());
        }
        if (before != null) {
            where.append(open ? " AND i.id > ?" : " AND i.id < ?");
            args.add(before);
        }
        args.add(ADMIN_PAGE + 1);
        List<Item> rows = jdbc.query(SELECT + where + (open ? " ORDER BY i.id ASC" : " ORDER BY i.id DESC") + " LIMIT ?", this::row, args.toArray());
        boolean more = rows.size() > ADMIN_PAGE;
        List<Item> items = more ? rows.subList(0, ADMIN_PAGE) : rows;
        return new Page(List.copyOf(items), more ? items.getLast().id() : null);
    }

    /** 관리자 대시보드(062): 아직 해결하지 않은 문의 수 */
    public long openCount() {
        Long n = jdbc.queryForObject("SELECT count(*) FROM inquiry WHERE status IN ('RECEIVED', 'IN_PROGRESS')", Long.class);
        return n == null ? 0 : n;
    }

    public Item detail(long id) {
        return find(id).orElseThrow(NotFoundException::new);
    }

    public Optional<Item> find(long id) {
        return jdbc.query(SELECT + " WHERE i.id = ?", this::row, id).stream().findFirst();
    }

    /** 상태·답변·고친 버전 적기. 답변이 새로 붙거나 바뀌면 커밋 뒤 회원에게 알린다 */
    public Item update(long adminId, long id, Update u) {
        InquiryStatus status = u.status() == null || u.status().isBlank() ? null
                : InquiryStatus.parse(u.status()).orElseThrow(() -> invalid("status", "INVALID_STATUS", "상태를 골라 주세요."));
        String answer = u.answer() == null ? null : TextCleaner.cleanMultiline(u.answer());
        if (answer != null && answer.codePointCount(0, answer.length()) > ANSWER_MAX) {
            throw invalid("answer", "ANSWER_TOO_LONG", "답변은 " + ANSWER_MAX + UP_TO);
        }
        String fixed = u.fixedVersion() == null ? null : TextCleaner.cleanLine(u.fixedVersion()).replaceFirst("^[vV]", "");
        if (fixed != null && !fixed.isEmpty() && !VERSION.matcher(fixed).matches()) {
            throw invalid("fixedVersion", "INVALID_VERSION", "고친 버전은 1.29.0처럼 적어 주세요.");
        }
        Instant now = Times.now(clock);
        Boolean answered = tx.execute(s -> {
            List<String> old = jdbc.query("SELECT answer FROM inquiry WHERE id = ? FOR UPDATE", (rs, i) -> Objects.toString(rs.getString(1), ""), id);
            if (old.isEmpty()) throw new NotFoundException();
            boolean changed = answer != null && !answer.isEmpty() && !answer.equals(old.getFirst());
            jdbc.update(UPDATE_HANDLING, status == null ? null : status.name(),
                    answer == null, answer, answer,
                    answer == null, answer, changed, Timestamp.from(now),
                    fixed == null, fixed, fixed,
                    adminId, Timestamp.from(now), id);
            return changed;
        });
        if (Boolean.TRUE.equals(answered)) {
            long memberId = Objects.requireNonNull(jdbc.queryForObject("SELECT member_id FROM inquiry WHERE id = ?", Long.class, id));
            notifications.inquiryAnswered(id, memberId, now);
        }
        return detail(id);
    }

    /** 탈퇴 정리: 회원이 남긴 문의를 지운다(답변 알림은 FK로 함께 지워진다) */
    public void deleteAll(long memberId) {
        jdbc.update("DELETE FROM inquiry WHERE member_id = ?", memberId);
    }

    private long insert(long memberId, InquiryCategory category, Source source, String title, String content, String pageUrl,
                        String tool, String client) {
        Timestamp now = Timestamp.from(Times.now(clock));
        return Objects.requireNonNull(jdbc.queryForObject("""
                INSERT INTO inquiry (member_id, category, source, title, content, page_url, tool_name, client_name, app_version, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id
                """, Long.class, memberId, category.name(), source.name(), title, content, pageUrl, blankToNull(tool), blankToNull(client),
                releases.currentVersion(), now, now));
    }

    private void limit(long memberId) {
        rateLimiter.check("inquiry-hour:" + memberId, PER_HOUR, Duration.ofHours(1));
        rateLimiter.check("inquiry-day:" + memberId, PER_DAY, Duration.ofDays(1));
    }

    static String title(String raw) {
        String t = TextCleaner.cleanLine(raw);
        if (t.isEmpty()) throw invalid(TITLE, "TITLE_REQUIRED", "제목을 적어 주세요.");
        if (t.codePointCount(0, t.length()) > TITLE_MAX) throw invalid(TITLE, "TITLE_TOO_LONG", "제목은 " + TITLE_MAX + UP_TO);
        return t;
    }

    static String content(String raw) {
        String c = TextCleaner.cleanMultiline(raw);
        if (c.isEmpty()) throw invalid(CONTENT, "CONTENT_REQUIRED", "내용을 적어 주세요.");
        if (c.codePointCount(0, c.length()) > CONTENT_MAX) throw invalid(CONTENT, "CONTENT_TOO_LONG", "내용은 " + CONTENT_MAX + UP_TO);
        return c;
    }

    /** 사이트 안 경로만 남긴다 ("/@me/posts/3?x=1"). 다른 주소·너무 긴 주소는 버린다 */
    static String pageUrl(String raw) {
        String p = TextCleaner.cleanLine(raw);
        if (p.isEmpty() || !p.startsWith("/") || p.startsWith("//") || p.length() > PAGE_URL_MAX) return null;
        return p;
    }

    private static String cut(String s, int max) {
        if (s == null) return null;
        return s.codePointCount(0, s.length()) <= max ? s : s.substring(0, s.offsetByCodePoints(0, max));
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    private static final String SELECT = """
            SELECT i.id, i.category, i.source, i.title, i.content, i.page_url, i.tool_name, i.client_name, i.app_version, i.status,
                   i.answer, i.answered_at, i.fixed_version, i.created_at, i.updated_at, m.handle, m.nickname
            FROM inquiry i JOIN member m ON m.id = i.member_id""";

    private Item row(ResultSet rs, int i) throws SQLException {
        return new Item(rs.getLong("id"), InquiryCategory.valueOf(rs.getString("category")), Source.valueOf(rs.getString("source")),
                rs.getString(TITLE), rs.getString(CONTENT), rs.getString("page_url"), rs.getString("tool_name"),
                rs.getString("client_name"), rs.getString("app_version"), InquiryStatus.valueOf(rs.getString("status")),
                rs.getString("answer"), instant(rs.getTimestamp("answered_at")), rs.getString("fixed_version"),
                instant(rs.getTimestamp("created_at")), instant(rs.getTimestamp("updated_at")), rs.getString("handle"), rs.getString("nickname"));
    }

    private static Instant instant(Timestamp t) {
        return t == null ? null : t.toInstant();
    }

    private static ApiException invalid(String field, String code, String message) {
        return ApiException.validation(List.of(new FieldErrorItem(field, code, message)));
    }
}
