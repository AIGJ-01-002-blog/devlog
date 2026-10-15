package com.team.blog.moderation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.ResultActions;

import tools.jackson.databind.JsonNode;

import com.team.blog.moderation.application.ReportPurgeJob;
import com.team.blog.support.Browser;
import com.team.blog.support.IntegrationTest;

/** 신고·숨김·정지 (019). */
class ReportTest extends IntegrationTest {
    @Autowired ReportPurgeJob purge;

    private long publish(Session s, String title, String visibility) throws Exception {
        long id = read(s.http().perform(asJson(post("/api/posts"), Map.of("title", title, "contentMd", "본문 " + title)))
                .andExpect(status().isCreated()).andReturn()).path("id").asLong();
        s.http().perform(asJson(post("/api/posts/" + id + "/publish"), Map.of("title", title, "contentMd", "본문 " + title,
                        "visibility", visibility, "baseVersion", 0, "tags", List.of()))
                .header("Idempotency-Key", UUID.randomUUID().toString())).andExpect(status().isOk());
        return id;
    }

    private long comment(Session s, long postId, String content) throws Exception {
        return read(s.http().perform(asJson(post("/api/posts/" + postId + "/comments"), Map.of("content", content)))
                .andExpect(status().isCreated()).andReturn()).path("id").asLong();
    }

    private ResultActions report(Session s, String type, long id, String reason, String detail) throws Exception {
        Map<String, Object> body = new HashMap<>(Map.of("targetType", type, "targetId", id, "reason", reason));
        if (detail != null) body.put("detail", detail);
        return s.http().perform(asJson(post("/api/reports"), body));
    }

    private Browser admin(Session s) throws Exception {
        jdbc.update("UPDATE member SET role = 'ADMIN' WHERE id = ?", s.memberId());
        return relogin(s);
    }

    private long caseOf(String column, long id) {
        return jdbc.queryForObject("SELECT id FROM report_case WHERE " + column + " = ? ORDER BY id DESC LIMIT 1", Long.class, id);
    }

    private String code(ResultActions r, int status) throws Exception {
        return read(r.andExpect(status().is(status)).andReturn()).path("code").asString();
    }

    private JsonNode notifications(Session s) throws Exception {
        drain();
        return read(s.http().perform(get("/api/me/notifications")).andExpect(status().isOk()).andReturn()).path("items");
    }

    @Test
    void 신고는_대상마다_대기_사건_하나로_모이고_다시_신고해도_한_건이다() throws Exception {
        Session author = signup(uniqueLogin("rpa"));
        Session a = signup(uniqueLogin("rpb"));
        Session b = signup(uniqueLogin("rpc"));
        long postId = publish(author, "광고 글", "PUBLIC");
        report(a, "POST", postId, "SPAM", null).andExpect(status().isNoContent());
        report(a, "POST", postId, "ABUSE", null).andExpect(status().isNoContent());
        report(b, "POST", postId, "other", "  출처 없이 퍼 온 글이에요  ").andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM report_case WHERE post_id = ?", Integer.class, postId)).isEqualTo(1);
        long caseId = caseOf("post_id", postId);
        assertThat(jdbc.queryForList("SELECT reason || ':' || COALESCE(detail, '') FROM report WHERE case_id = ? ORDER BY id", String.class, caseId))
                .containsExactly("SPAM:", "OTHER:출처 없이 퍼 온 글이에요");
        assertThat(jdbc.queryForMap("SELECT snapshot_title, snapshot_content, target_author_id FROM report_case WHERE id = ?", caseId))
                .containsEntry("snapshot_title", "광고 글").containsEntry("snapshot_content", "본문 광고 글")
                .containsEntry("target_author_id", author.memberId());

        assertThat(code(report(author, "POST", postId, "SPAM", null), 400)).isEqualTo("CANNOT_REPORT_OWN");
        assertThat(code(report(a, "POST", postId, "OTHER", " "), 400)).isEqualTo("VALIDATION_FAILED");
        assertThat(code(report(a, "POST", postId, "NOPE", null), 400)).isEqualTo("VALIDATION_FAILED");
        long secret = publish(author, "비밀", "PRIVATE");
        assertThat(code(report(a, "POST", secret, "SPAM", null), 404)).isEqualTo("NOT_FOUND");
        assertThat(code(report(a, "POST", 99_999_999, "SPAM", null), 404)).isEqualTo("NOT_FOUND");
        browser().perform(asJson(post("/api/reports"), Map.of("targetType", "POST", "targetId", postId, "reason", "SPAM")))
                .andExpect(status().isUnauthorized());

        // 1분 5건
        resetRateLimits();
        for (int i = 0; i < 5; i++) report(b, "POST", postId, "SPAM", null).andExpect(status().isNoContent());
        assertThat(code(report(b, "POST", postId, "SPAM", null), 429)).isEqualTo("TOO_MANY_REQUESTS");
    }

    /** 두 사람이 스팸으로 신고한 글 하나와 그 대기 사건 */
    private record Reported(Session author, Session r1, Session r2, Session adminSession, Browser admin, long postId, long caseId) {}

    private Reported reportedPost() throws Exception {
        Session author = signup(uniqueLogin("hda"));
        Session r1 = signup(uniqueLogin("hdb"));
        Session r2 = signup(uniqueLogin("hdc"));
        Session adminSession = signup(uniqueLogin("hdadm"));
        Browser admin = admin(adminSession);
        long postId = publish(author, "숨길 글", "PUBLIC");
        report(r1, "POST", postId, "SPAM", null).andExpect(status().isNoContent());
        report(r2, "POST", postId, "SPAM", null).andExpect(status().isNoContent());
        return new Reported(author, r1, r2, adminSession, admin, postId, caseOf("post_id", postId));
    }

    private void hide(Reported t) throws Exception {
        t.admin().perform(asJson(post("/api/admin/reports/" + t.caseId() + "/resolve"), Map.of("action", "HIDE", "reason", "SPAM")))
                .andExpect(status().isOk());
    }

    @Test
    void 관리자만_신고_목록을_본다() throws Exception {
        Reported t = reportedPost();
        Session r1 = t.r1();
        long caseId = t.caseId();

        r1.http().perform(get("/api/admin/reports")).andExpect(status().isNotFound());
        browser().perform(get("/api/admin/reports")).andExpect(status().isUnauthorized());
        browser().perform(get("/api/admin/nothing-here")).andExpect(status().isUnauthorized());

        JsonNode list = read(t.admin().perform(get("/api/admin/reports")).andExpect(status().isOk()).andReturn()).path("items");
        JsonNode row = null;
        for (JsonNode n : list) if (n.path("caseId").asLong() == caseId) row = n;
        assertThat(row).isNotNull();
        assertThat(row.path("reportCount").asInt()).isEqualTo(2);
        assertThat(row.path("reasons").path("SPAM").asInt()).isEqualTo(2);
        assertThat(row.path("preview").asString()).isEqualTo("숨길 글");
    }

    @Test
    void 신고_뒤_비공개로_바꿔도_대기는_그대로이고_관리자는_스냅샷만_본다() throws Exception {
        Reported t = reportedPost();
        Session author = t.author();
        Browser admin = t.admin();
        long postId = t.postId();

        author.http().perform(asJson(patch("/api/posts/" + postId + "/visibility"), Map.of("visibility", "PRIVATE"))).andExpect(status().isOk());
        JsonNode detail = read(admin.perform(get("/api/admin/reports/" + t.caseId())).andExpect(status().isOk()).andReturn());
        assertThat(detail.path("targetState").asString()).isEqualTo("PRIVATE");
        assertThat(detail.path("snapshotContent").asString()).isEqualTo("본문 숨길 글");
        assertThat(detail.path("author").path("handle").asString()).isEqualTo(author.handle());
        assertThat(detail.toString()).doesNotContain(t.r1().handle()).doesNotContain("reporter");
        admin.perform(get("/api/posts/" + postId)).andExpect(status().isNotFound());
    }

    @Test
    void 숨기면_작성자_외에는_사라지고_작성자는_사유와_함께_본다() throws Exception {
        Reported t = reportedPost();
        Session author = t.author();
        Browser admin = t.admin();
        long postId = t.postId();
        long caseId = t.caseId();

        assertThat(code(admin.perform(asJson(post("/api/admin/reports/" + caseId + "/resolve"), Map.of("action", "HIDE"))), 400))
                .isEqualTo("VALIDATION_FAILED");
        hide(t);
        assertThat(code(admin.perform(asJson(post("/api/admin/reports/" + caseId + "/resolve"), Map.of("action", "REJECT"))), 409))
                .isEqualTo("CASE_ALREADY_HANDLED");
        assertThat(jdbc.queryForMap("SELECT hidden_reason, hidden_by FROM post WHERE id = ?", postId))
                .containsEntry("hidden_reason", "SPAM").containsEntry("hidden_by", t.adminSession().memberId());

        // 작성자 외에는 없는 글, 작성자는 사유와 함께 본다
        t.r1().http().perform(get("/api/posts/" + postId)).andExpect(status().isNotFound());
        JsonNode mine = read(author.http().perform(get("/api/posts/" + postId)).andExpect(status().isOk()).andReturn());
        assertThat(mine.path("owner").path("hidden").asBoolean()).isTrue();
        assertThat(mine.path("owner").path("hiddenReason").asString()).isEqualTo("SPAM");
        // 작성자가 공개 범위를 바꿔도 숨김은 풀리지 않는다
        author.http().perform(asJson(patch("/api/posts/" + postId + "/visibility"), Map.of("visibility", "PUBLIC"))).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT hidden_at IS NOT NULL FROM post WHERE id = ?", Boolean.class, postId)).isTrue();
    }

    @Test
    void 숨기면_신고자와_작성자에게_알리고_숨김을_풀면_다시_보인다() throws Exception {
        Reported t = reportedPost();
        Session author = t.author();
        Session r1 = t.r1();
        hide(t);

        // 신고자마다 처리 결과, 작성자에게 숨김 (신고자 정보 없음)
        for (Session r : List.of(r1, t.r2())) {
            JsonNode n = notifications(r).get(0);
            assertThat(n.path("type").asString()).isEqualTo("REPORT_RESOLVED");
            assertThat(n.path("result").asString()).isEqualTo("ACTION_TAKEN");
            assertThat(n.path("post").isNull()).isTrue();
            assertThat(n.path("link").isNull()).isTrue();
        }
        JsonNode hidden = notifications(author).get(0);
        assertThat(hidden.path("type").asString()).isEqualTo("CONTENT_HIDDEN");
        assertThat(hidden.path("hidden").path("reason").asString()).isEqualTo("SPAM");
        assertThat(hidden.path("post").path("title").asString()).isEqualTo("숨길 글");
        assertThat(hidden.toString()).doesNotContain(r1.handle()).doesNotContain(t.adminSession().handle());

        // 숨김 해제: 다시 보이고, 알림은 "지금은 다시 보여요"
        t.admin().perform(post("/api/admin/reports/" + t.caseId() + "/unhide").with(csrf())).andExpect(status().isOk());
        r1.http().perform(get("/api/posts/" + t.postId())).andExpect(status().isOk());
        JsonNode after = notifications(author).get(0);
        assertThat(after.path("hidden").path("stillHidden").asBoolean()).isFalse();
        assertThat(after.path("hidden").path("reason").isNull()).isTrue();
        assertThat(notifications(author).size()).isEqualTo(1);
    }

    @Test
    void 반려하면_문제없음으로_알리고_다음_신고는_새_사건이다() throws Exception {
        Session author = signup(uniqueLogin("rja"));
        Session r1 = signup(uniqueLogin("rjb"));
        Browser admin = admin(signup(uniqueLogin("rjadm")));
        long postId = publish(author, "괜찮은 글", "PUBLIC");
        report(r1, "POST", postId, "ABUSE", null).andExpect(status().isNoContent());
        long first = caseOf("post_id", postId);
        admin.perform(asJson(post("/api/admin/reports/" + first + "/resolve"), Map.of("action", "REJECT"))).andExpect(status().isOk());
        assertThat(notifications(r1).get(0).path("result").asString()).isEqualTo("NO_VIOLATION");
        assertThat(notifications(author).size()).isZero();
        report(r1, "POST", postId, "ABUSE", null).andExpect(status().isNoContent());
        assertThat(caseOf("post_id", postId)).isNotEqualTo(first);

        JsonNode handled = read(admin.perform(get("/api/admin/reports?tab=handled")).andExpect(status().isOk()).andReturn()).path("items");
        boolean found = false;
        for (JsonNode n : handled) if (n.path("caseId").asLong() == first) found = n.path("status").asString().equals("REJECTED");
        assertThat(found).isTrue();
    }

    @Test
    void 댓글을_숨기면_자리만_남고_그_댓글_알림이_지워진다() throws Exception {
        Session author = signup(uniqueLogin("cha"));
        Session writer = signup(uniqueLogin("chb"));
        Session reporter = signup(uniqueLogin("chc"));
        Browser admin = admin(signup(uniqueLogin("chadm")));
        long postId = publish(author, "댓글 글", "PUBLIC");
        long commentId = comment(writer, postId, "나쁜 댓글");
        assertThat(notifications(author).size()).isEqualTo(1);
        report(reporter, "COMMENT", commentId, "ABUSE", null).andExpect(status().isNoContent());
        long caseId = caseOf("comment_id", commentId);
        JsonNode detail = read(admin.perform(get("/api/admin/reports/" + caseId)).andExpect(status().isOk()).andReturn());
        assertThat(detail.path("snapshotContent").asString()).isEqualTo("나쁜 댓글");
        assertThat(detail.path("link").asString()).endsWith("#comment-" + commentId);
        admin.perform(asJson(post("/api/admin/reports/" + caseId + "/resolve"), Map.of("action", "HIDE", "reason", "ABUSE")))
                .andExpect(status().isOk());
        assertThat(notifications(author).size()).isZero();
        JsonNode n = notifications(writer).get(0);
        assertThat(n.path("type").asString()).isEqualTo("CONTENT_HIDDEN");
        assertThat(n.path("hidden").path("targetType").asString()).isEqualTo("COMMENT");
        assertThat(n.path("post").isNull()).isTrue();
        assertThat(n.path("link").asString()).endsWith("#comment-" + commentId);
        // 숨긴 댓글에는 아무도 답글을 달 수 없다
        author.http().perform(asJson(post("/api/posts/" + postId + "/comments"), Map.of("content", "답글", "replyToCommentId", commentId)))
                .andExpect(status().is4xxClientError());
    }

    @Test
    void 관리자는_자기_콘텐츠를_처리하지_못하고_다른_관리자나_자신을_정지하지_못한다() throws Exception {
        Session adminSession = signup(uniqueLogin("ownadm"));
        Session other = signup(uniqueLogin("ownadm2"));
        Browser admin = admin(adminSession);
        admin(other);
        Session reporter = signup(uniqueLogin("ownrep"));
        long postId = publish(adminSession, "관리자 글", "PUBLIC");
        report(reporter, "POST", postId, "SPAM", null).andExpect(status().isNoContent());
        long caseId = caseOf("post_id", postId);
        assertThat(read(admin.perform(get("/api/admin/reports/" + caseId)).andReturn()).path("mine").asBoolean()).isTrue();
        assertThat(code(admin.perform(asJson(post("/api/admin/reports/" + caseId + "/resolve"), Map.of("action", "REJECT"))), 400))
                .isEqualTo("CANNOT_HANDLE_OWN");
        for (String h : List.of(adminSession.handle(), other.handle())) {
            assertThat(code(admin.perform(asJson(post("/api/admin/members/" + h + "/suspension"), Map.of("days", 7, "reason", "x"))), 400))
                    .isEqualTo("CANNOT_SUSPEND_ADMIN");
        }
    }

    @Test
    void 정지하면_모든_기기에서_로그아웃되고_해제하면_다시_쓸_수_있다() throws Exception {
        Session target = signup(uniqueLogin("susa"));
        Browser second = relogin(target);
        Browser admin = admin(signup(uniqueLogin("susadm")));
        assertThat(code(admin.perform(asJson(post("/api/admin/members/" + target.handle() + "/suspension"), Map.of("days", 3, "reason", "x"))), 400))
                .isEqualTo("VALIDATION_FAILED");
        assertThat(code(admin.perform(asJson(post("/api/admin/members/" + target.handle() + "/suspension"), Map.of("days", 7, "reason", " "))), 400))
                .isEqualTo("VALIDATION_FAILED");
        JsonNode info = read(admin.perform(asJson(post("/api/admin/members/" + target.handle() + "/suspension"),
                Map.of("days", 7, "reason", "반복 광고"))).andExpect(status().isOk()).andReturn());
        assertThat(info.path("suspended").asBoolean()).isTrue();
        assertThat(info.path("suspensions").get(0).path("reason").asString()).isEqualTo("반복 광고");
        assertThat(info.path("suspensions").get(0).path("endsAt").isNull()).isFalse();
        for (Browser b : List.of(target.http(), second)) {
            assertThat(read(b.perform(get("/api/auth/me")).andReturn()).path("authenticated").asBoolean()).isFalse();
        }
        assertThat(code(admin.perform(asJson(post("/api/admin/members/" + target.handle() + "/suspension"), Map.of("reason", "영구"))), 409))
                .isEqualTo("ALREADY_SUSPENDED");

        info = read(admin.perform(delete("/api/admin/members/" + target.handle() + "/suspension").with(csrf())).andExpect(status().isOk()).andReturn());
        assertThat(info.path("suspended").asBoolean()).isFalse();
        assertThat(jdbc.queryForObject("SELECT lifted_by IS NOT NULL FROM member_suspension WHERE member_id = ?", Boolean.class, target.memberId()))
                .isTrue();
        Browser back = relogin(target);
        assertThat(read(back.perform(get("/api/auth/me")).andReturn()).path("authenticated").asBoolean()).isTrue();
        // 영구 정지
        info = read(admin.perform(asJson(post("/api/admin/members/" + target.handle() + "/suspension"), Map.of("reason", "영구")))
                .andExpect(status().isOk()).andReturn());
        assertThat(info.path("suspensions").get(0).path("endsAt").isNull()).isTrue();
        admin.perform(get("/api/admin/members/nobody-" + target.memberId())).andExpect(status().isNotFound());
    }

    @Test
    void 처리된_지_30일_지난_사건은_스냅샷과_설명을_비운다() throws Exception {
        Session author = signup(uniqueLogin("pga"));
        Session r1 = signup(uniqueLogin("pgb"));
        Browser admin = admin(signup(uniqueLogin("pgadm")));
        long postId = publish(author, "오래된 신고", "PUBLIC");
        report(r1, "POST", postId, "OTHER", "설명").andExpect(status().isNoContent());
        long caseId = caseOf("post_id", postId);
        admin.perform(asJson(post("/api/admin/reports/" + caseId + "/resolve"), Map.of("action", "REJECT"))).andExpect(status().isOk());
        purge.run();
        assertThat(jdbc.queryForObject("SELECT snapshot_content FROM report_case WHERE id = ?", String.class, caseId)).isNotNull();
        jdbc.update("UPDATE report_case SET handled_at = now() - interval '31 days' WHERE id = ?", caseId);
        assertThat(purge.run()).isPositive();
        assertThat(jdbc.queryForMap("SELECT snapshot_title, snapshot_content, status FROM report_case WHERE id = ?", caseId))
                .containsEntry("snapshot_title", null).containsEntry("snapshot_content", null).containsEntry("status", "REJECTED");
        assertThat(jdbc.queryForMap("SELECT reason, detail FROM report WHERE case_id = ?", caseId))
                .containsEntry("reason", "OTHER").containsEntry("detail", null);
    }
}
