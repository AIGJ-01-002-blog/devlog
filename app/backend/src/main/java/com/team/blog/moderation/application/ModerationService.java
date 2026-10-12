package com.team.blog.moderation.application;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.team.blog.shared.error.ApiException;
import com.team.blog.shared.error.FieldErrorItem;
import com.team.blog.shared.error.NotFoundException;
import com.team.blog.shared.time.Times;

/**
 * 관리자 신고 처리와 숨김 해제 (019 US2·US3, docs/43 §3~§4). 한 대상의 대기 사건 하나를 숨김 또는 반려로 닫는다(FR-015).
 * 자동 숨김은 없다(FR-016). 관리자는 자기 콘텐츠의 사건을 처리하거나 자기 것을 숨길 수 없다(FR-018).
 * 알림은 커밋 뒤 사건으로 만든다(FR-042).
 */
@Service
public class ModerationService {
    private static final String CANNOT_HANDLE_OWN = "CANNOT_HANDLE_OWN";
    private final JdbcTemplate jdbc;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    public ModerationService(JdbcTemplate jdbc, ApplicationEventPublisher events, Clock clock) {
        this.jdbc = jdbc;
        this.events = events;
        this.clock = clock;
    }

    public enum Action { HIDE, REJECT }

    @Transactional
    public void resolve(long adminId, long caseId, Action action, String hideReason) {
        if (action == null) throw invalid("action", "INVALID_ACTION", "처리 방법을 골라 주세요.");
        ReportReason reason = action == Action.HIDE
                ? ReportReason.parse(hideReason).orElseThrow(() -> invalid("reason", "INVALID_REASON", "숨기는 사유를 골라 주세요."))
                : null;
        CaseRow c = lock(caseId);
        if (!"PENDING".equals(c.status())) throw ApiException.conflict("CASE_ALREADY_HANDLED", "이미 처리된 신고예요.");
        if (c.authorId() == adminId) throw ApiException.badRequest(CANNOT_HANDLE_OWN, "자기 글·댓글의 신고는 처리할 수 없어요.");
        Instant now = Times.now(clock);
        Timestamp ts = Timestamp.from(now);
        if (action == Action.HIDE) {
            if (c.targetId() == null) throw ApiException.conflict("TARGET_GONE", "신고 대상이 이미 없어요.");
            // 이미 숨겨져 있으면(다른 사건으로) 처음 숨긴 기록을 그대로 둔다
            jdbc.update("UPDATE " + c.table() + " SET hidden_at = ?, hidden_by = ?, hidden_reason = ? WHERE id = ? AND hidden_at IS NULL",
                    ts, adminId, reason.name(), c.targetId());
        }
        jdbc.update("UPDATE report_case SET status = ?, handled_by = ?, handled_at = ? WHERE id = ?",
                action == Action.HIDE ? "HIDDEN" : "REJECTED", adminId, ts, caseId);
        events.publishEvent(new ModerationEvents.ReportsResolved(caseId, action == Action.HIDE, now));
        if (action == Action.HIDE) {
            events.publishEvent(new ModerationEvents.ContentHidden(caseId, c.type(), c.targetId(), c.authorId(), c.postId(), now));
        }
    }

    /** 처리됨 탭의 [숨김 해제] (FR-026·FR-027). 목록 위치는 처음 공개된 시각 그대로라 따로 고칠 것이 없다. 작성자에게 알리지 않는다. */
    @Transactional
    public void unhide(long adminId, long caseId) {
        CaseRow c = lock(caseId);
        if (!"HIDDEN".equals(c.status()) || c.targetId() == null) throw new NotFoundException();
        if (c.authorId() == adminId) throw ApiException.badRequest(CANNOT_HANDLE_OWN, "자기 글·댓글의 숨김은 바꿀 수 없어요.");
        int n = jdbc.update("UPDATE " + c.table() + " SET hidden_at = NULL, hidden_by = NULL, hidden_reason = NULL WHERE id = ? AND hidden_at IS NOT NULL",
                c.targetId());
        if (n == 0) throw ApiException.conflict("NOT_HIDDEN", "이미 숨김이 풀려 있어요.");
        events.publishEvent(new ModerationEvents.ContentUnhidden(c.type(), c.targetId(), c.authorId(), Times.now(clock)));
    }

    /**
     * 관리자 페이지 글 관리에서 바로 숨기기 (062). 신고 없이 처리된 사건 하나를 남겨, 숨김 알림·처리됨 탭·숨김 해제가 신고로 숨긴 글과 같게 돈다.
     * 발행한 글만 숨길 수 있고, 자기 글은 숨길 수 없다(FR-018). 이미 숨겨져 있으면 409.
     * @return 만든 사건 번호
     */
    @Transactional
    public long hidePost(long adminId, long postId, String hideReason) {
        ReportReason reason = ReportReason.parse(hideReason).orElseThrow(() -> invalid("reason", "INVALID_REASON", "숨기는 사유를 골라 주세요."));
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT author_id, title, content_md, status, hidden_at IS NOT NULL AS hidden FROM post WHERE id = ? AND deleted_at IS NULL FOR UPDATE",
                postId);
        if (rows.isEmpty() || !"PUBLISHED".equals(rows.get(0).get("status"))) throw new NotFoundException();
        Map<String, Object> r = rows.get(0);
        long authorId = ((Number) r.get("author_id")).longValue();
        if (authorId == adminId) throw ApiException.badRequest(CANNOT_HANDLE_OWN, "자기 글은 숨길 수 없어요.");
        if (Boolean.TRUE.equals(r.get("hidden"))) throw ApiException.conflict("ALREADY_HIDDEN", "이미 숨겨진 글이에요.");
        Instant now = Times.now(clock);
        Timestamp ts = Timestamp.from(now);
        // 대기 중인 신고 사건이 있으면 그 사건을 숨김으로 닫는다 (대상마다 대기 사건은 하나)
        Long pending = jdbc.query("SELECT id FROM report_case WHERE post_id = ? AND status = 'PENDING'", (rs, i) -> rs.getLong(1), postId)
                .stream().findFirst().orElse(null);
        if (pending != null) {
            resolve(adminId, pending, Action.HIDE, reason.name());
            return pending;
        }
        long caseId = Objects.requireNonNull(jdbc.queryForObject("""
                INSERT INTO report_case (target_type, post_id, target_author_id, snapshot_title, snapshot_content, status, handled_by, handled_at)
                VALUES ('POST', ?, ?, ?, ?, 'HIDDEN', ?, ?) RETURNING id
                """, Long.class, postId, authorId, r.get("title"), ReportService.cut((String) r.get("content_md")), adminId, ts));
        jdbc.update("UPDATE post SET hidden_at = ?, hidden_by = ?, hidden_reason = ? WHERE id = ?", ts, adminId, reason.name(), postId);
        events.publishEvent(new ModerationEvents.ContentHidden(caseId, ReportTarget.POST, postId, authorId, postId, now));
        return caseId;
    }

    /** 관리자 페이지 글 관리의 [숨김 해제] (062). 마지막으로 숨긴 사건을 풀어 처리됨 탭과 같은 결과를 낸다. */
    @Transactional
    public void unhidePost(long adminId, long postId) {
        Long caseId = jdbc.query("SELECT id FROM report_case WHERE post_id = ? AND status = 'HIDDEN' ORDER BY handled_at DESC, id DESC LIMIT 1",
                (rs, i) -> rs.getLong(1), postId).stream().findFirst().orElse(null);
        if (caseId != null) {
            unhide(adminId, caseId);
            return;
        }
        // 사건 없이 숨겨진 글(예전 데이터)도 풀 수 있게 한다
        Long authorId = jdbc.query("SELECT author_id FROM post WHERE id = ? AND hidden_at IS NOT NULL FOR UPDATE", (rs, i) -> rs.getLong(1), postId)
                .stream().findFirst().orElseThrow(() -> ApiException.conflict("NOT_HIDDEN", "이미 숨김이 풀려 있어요."));
        if (authorId == adminId) throw ApiException.badRequest(CANNOT_HANDLE_OWN, "자기 글·댓글의 숨김은 바꿀 수 없어요.");
        jdbc.update("UPDATE post SET hidden_at = NULL, hidden_by = NULL, hidden_reason = NULL WHERE id = ?", postId);
        events.publishEvent(new ModerationEvents.ContentUnhidden(ReportTarget.POST, postId, authorId, Times.now(clock)));
    }

    /** postId는 댓글이면 그 댓글의 글 */
    record CaseRow(long id, ReportTarget type, Long targetId, long authorId, String status, long postId) {
        String table() {
            return type == ReportTarget.POST ? "post" : "comment";
        }
    }

    private CaseRow lock(long caseId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT rc.id, rc.target_type, COALESCE(rc.post_id, rc.comment_id) AS target_id, rc.target_author_id, rc.status,
                       COALESCE(rc.post_id, c.post_id, 0) AS post_id
                FROM report_case rc LEFT JOIN comment c ON c.id = rc.comment_id
                WHERE rc.id = ? FOR UPDATE OF rc
                """, caseId);
        if (rows.isEmpty()) throw new NotFoundException();
        Map<String, Object> r = rows.get(0);
        Number target = (Number) r.get("target_id");
        return new CaseRow(caseId, ReportTarget.valueOf((String) r.get("target_type")), target == null ? null : target.longValue(),
                ((Number) r.get("target_author_id")).longValue(), (String) r.get("status"), ((Number) r.get("post_id")).longValue());
    }

    private static ApiException invalid(String field, String code, String message) {
        return ApiException.validation(List.of(new FieldErrorItem(field, code, message)));
    }

}
