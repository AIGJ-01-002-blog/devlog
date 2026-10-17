package com.team.blog.inquiry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import tools.jackson.databind.JsonNode;

import com.team.blog.support.Browser;
import com.team.blog.support.IntegrationTest;

/**
 * spec 054: 문의·신고 접수와 AI 버그 신고. 회원은 웹에서, 연결한 AI는 MCP report_bug로 남기고, 관리자만 읽고 처리한다.
 * 답변이 붙으면 회원에게 알림이 가고, 고친 버전은 릴리스 노트로 이어진다.
 */
class InquiryTest extends IntegrationTest {

    private Browser admin(Session s) throws Exception {
        jdbc.update("UPDATE member SET role = 'ADMIN' WHERE id = ?", s.memberId());
        return relogin(s);
    }

    private ResultActions submit(Browser b, String category, String title, String content, String pageUrl) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("category", category);
        body.put("title", title);
        body.put("content", content);
        if (pageUrl != null) body.put("pageUrl", pageUrl);
        return b.perform(asJson(post("/api/inquiries"), body));
    }

    private String token(Session s, String scope) throws Exception {
        return token(s, scope, false);
    }

    private String token(Session s, String scope, boolean adminTools) throws Exception {
        return read(s.http().perform(asJson(post("/api/me/tokens"), Map.of("name", "Claude Code", "scope", scope, "adminTools", adminTools)))
                .andExpect(status().isCreated()).andReturn()).path("secret").asString();
    }

    private JsonNode rpc(String token, String method, Object params) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("jsonrpc", "2.0");
        body.put("id", 1);
        body.put("method", method);
        if (params != null) body.put("params", params);
        return read(mvc.perform(post("/api/mcp").contentType(MediaType.APPLICATION_JSON).header("Authorization", "Bearer " + token)
                .content(json.writeValueAsString(body))).andExpect(status().isOk()).andReturn()).path("result");
    }

    private JsonNode call(String token, String tool, Map<String, Object> args) throws Exception {
        return rpc(token, "tools/call", Map.of("name", tool, "arguments", args));
    }

    private static String text(JsonNode result) {
        return result.path("content").get(0).path("text").asString();
    }

    private List<String> toolNames(String token) throws Exception {
        return rpc(token, "tools/list", null).path("tools").findValuesAsString("name");
    }

    @Test
    void 회원이_남긴_문의는_내_문의에_보이고_관리자가_답하면_알림이_간다() throws Exception {
        Session me = signup(uniqueLogin("inqme"));
        long id = read(submit(me.http(), "QUESTION", "  태그는 몇 개까지 붙이나요?  ", "글 하나에 태그를 몇 개까지 붙일 수 있나요?", "/write")
                .andExpect(status().isCreated()).andReturn()).path("id").asLong();

        JsonNode mine = read(me.http().perform(get("/api/me/inquiries")).andExpect(status().isOk()).andReturn()).path("items");
        assertThat(mine.get(0).path("id").asLong()).isEqualTo(id);
        assertThat(mine.get(0).path("title").asString()).isEqualTo("태그는 몇 개까지 붙이나요?");
        assertThat(mine.get(0).path("status").asString()).isEqualTo("RECEIVED");
        assertThat(mine.get(0).path("pageUrl").asString()).isEqualTo("/write");
        assertThat(mine.get(0).path("source").asString()).isEqualTo("WEB");

        // 일반 회원에게 관리 API는 없는 주소다
        me.http().perform(get("/api/admin/inquiries")).andExpect(status().isNotFound());

        Browser admin = admin(signup(uniqueLogin("inqadm")));
        JsonNode open = read(admin.perform(get("/api/admin/inquiries")).andExpect(status().isOk()).andReturn()).path("items");
        assertThat(open.findValuesAsString("title")).contains("태그는 몇 개까지 붙이나요?");

        JsonNode done = read(admin.perform(asJson(patch("/api/admin/inquiries/" + id), Map.of("status", "CLOSED", "answer", "10개까지예요.")))
                .andExpect(status().isOk()).andReturn());
        assertThat(done.path("status").asString()).isEqualTo("CLOSED");
        assertThat(done.path("answer").asString()).isEqualTo("10개까지예요.");
        assertThat(done.path("answeredAt").isNull()).isFalse();

        drain();
        JsonNode items = read(me.http().perform(get("/api/me/notifications")).andExpect(status().isOk()).andReturn()).path("items");
        assertThat(items.get(0).path("type").asString()).isEqualTo("INQUIRY_ANSWERED");
        assertThat(items.get(0).path("link").asString()).isEqualTo("/support?id=" + id);
        assertThat(items.get(0).path("inquiry").path("title").asString()).isEqualTo("태그는 몇 개까지 붙이나요?");

        // 같은 답변으로 다시 저장하면 알림을 또 보내지 않는다
        admin.perform(asJson(patch("/api/admin/inquiries/" + id), Map.of("answer", "10개까지예요."))).andExpect(status().isOk());
        drain();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notification WHERE receiver_id = ? AND type = 'INQUIRY_ANSWERED'",
                Integer.class, me.memberId())).isEqualTo(1);
        assertThat(read(admin.perform(get("/api/admin/inquiries?tab=done")).andReturn()).path("items").findValuesAsString("title"))
                .contains("태그는 몇 개까지 붙이나요?");
    }

    @Test
    void 종류와_제목_내용이_없으면_거절하고_다른_사이트_주소는_남기지_않는다() throws Exception {
        Session me = signup(uniqueLogin("inqval"));
        submit(me.http(), "NOPE", "제목", "내용", null).andExpect(status().isBadRequest());
        submit(me.http(), "BUG", " ", "내용", null).andExpect(status().isBadRequest());
        submit(me.http(), "BUG", "제목", "", null).andExpect(status().isBadRequest());
        submit(me.http(), "BUG", "x".repeat(201), "내용", null).andExpect(status().isBadRequest());
        long id = read(submit(me.http(), "BUG", "버튼이 안 눌려요", "발행 버튼이 안 눌려요", "https://evil.example/x")
                .andExpect(status().isCreated()).andReturn()).path("id").asLong();
        assertThat(jdbc.queryForObject("SELECT page_url FROM inquiry WHERE id = ?", String.class, id)).isNull();
        submit(browser(), "BUG", "비회원", "비회원", null).andExpect(status().isUnauthorized());
    }

    @Test
    void AI는_읽기_토큰으로도_버그를_신고하고_관리자_AI는_읽고_고친_버전을_적는다() throws Exception {
        Session user = signup(uniqueLogin("inqai"));
        String readToken = token(user, "READ");
        assertThat(toolNames(readToken)).contains("report_bug").doesNotContain("list_inquiries", "get_inquiry", "update_inquiry");

        JsonNode r = call(readToken, "report_bug", Map.of("title", "publish_post가 태그를 버려요",
                "content", "## 요약\n태그가 사라져요\n## 재현 방법\npublish_post(post_id=1, tags=[\"Spring\"])", "tool", "publish_post"));
        assertThat(r.path("isError").asBoolean()).isFalse();
        long id = Long.parseLong(text(r).replaceAll("(?s).*접수 번호 (\\d+).*", "$1"));
        Map<String, Object> row = jdbc.queryForMap("SELECT category, source, tool_name, client_name, member_id FROM inquiry WHERE id = ?", id);
        assertThat(row).containsEntry("category", "BUG").containsEntry("source", "MCP").containsEntry("tool_name", "publish_post")
                .containsEntry("client_name", "Claude Code").containsEntry("member_id", user.memberId());

        // 관리자 도구는 일반 회원이 이름으로 불러도 없는 도구다
        assertThat(text(call(readToken, "get_inquiry", Map.of("inquiry_id", id)))).contains("없는 도구");

        Session adminSession = signup(uniqueLogin("inqaiadm"));
        jdbc.update("UPDATE member SET role = 'ADMIN' WHERE id = ?", adminSession.memberId());
        // 관리자라도 관리자 도구를 따로 켜지 않은 토큰에는 보이지 않는다 (#378)
        String plainToken = token(adminSession, "WRITE");
        assertThat(toolNames(plainToken)).contains("report_bug").doesNotContain("list_inquiries", "get_inquiry", "update_inquiry");
        assertThat(text(call(plainToken, "get_inquiry", Map.of("inquiry_id", id)))).contains("없는 도구");
        assertThat(rpc(plainToken, "initialize", Map.of("protocolVersion", "2025-06-18")).path("instructions").asString()).doesNotContain("list_inquiries");

        String adminToken = token(adminSession, "WRITE", true);
        assertThat(toolNames(adminToken)).contains("report_bug", "list_inquiries", "get_inquiry", "update_inquiry");
        assertThat(rpc(adminToken, "initialize", Map.of("protocolVersion", "2025-06-18")).path("instructions").asString()).contains("list_inquiries");

        assertThat(text(call(adminToken, "list_inquiries", Map.of("category", "BUG")))).contains("[" + id + "]", "publish_post가 태그를 버려요");
        String detail = text(call(adminToken, "get_inquiry", Map.of("inquiry_id", id)));
        assertThat(detail).contains("<user_content>", "태그가 사라져요", "문제가 난 도구: publish_post", "AI 신고, Claude Code");

        JsonNode up = call(adminToken, "update_inquiry", Map.of("inquiry_id", id, "status", "RESOLVED", "fixed_version", "v1.29.0",
                "answer", "1.29.0에서 고쳤어요."));
        assertThat(up.path("isError").asBoolean()).isFalse();
        assertThat(jdbc.queryForMap("SELECT status, fixed_version, handled_by FROM inquiry WHERE id = ?", id))
                .containsEntry("status", "RESOLVED").containsEntry("fixed_version", "1.29.0").containsEntry("handled_by", adminSession.memberId());
        assertThat(text(call(adminToken, "update_inquiry", Map.of("inquiry_id", id, "fixed_version", "다음 버전")))).contains("1.29.0처럼");

        JsonNode mine = read(user.http().perform(get("/api/me/inquiries")).andReturn()).path("items");
        assertThat(mine.get(0).path("fixedVersion").asString()).isEqualTo("1.29.0");
        assertThat(mine.get(0).path("clientName").isNull()).isTrue();
    }

    @Test
    void 릴리스_노트는_누구나_읽고_맨_위가_지금_버전이다() throws Exception {
        JsonNode notes = read(mvc.perform(get("/api/release-notes")).andExpect(status().isOk()).andReturn());
        JsonNode releases = notes.path("releases");
        assertThat(releases.size()).isGreaterThan(1);
        assertThat(notes.path("current").asString()).isEqualTo(releases.get(0).path("version").asString());
        assertThat(releases.get(0).path("html").asString()).contains("<li>");
        mvc.perform(get("/releases")).andExpect(status().isOk());
    }

    @Test
    void 관리자_도구는_관리자만_토큰에_켤_수_있고_역할이_빠지면_바로_사라진다() throws Exception {
        Session user = signup(uniqueLogin("inqtok"));
        user.http().perform(asJson(post("/api/me/tokens"), Map.of("name", "Claude Code", "scope", "READ", "adminTools", true)))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ADMIN_ONLY"));

        jdbc.update("UPDATE member SET role = 'ADMIN' WHERE id = ?", user.memberId());
        String adminToken = token(user, "READ", true);
        assertThat(read(user.http().perform(get("/api/me/tokens")).andReturn()).get(0).path("adminTools").asBoolean()).isTrue();
        assertThat(toolNames(adminToken)).contains("list_inquiries");

        jdbc.update("UPDATE member SET role = 'USER' WHERE id = ?", user.memberId());
        assertThat(toolNames(adminToken)).doesNotContain("list_inquiries");
    }
}
