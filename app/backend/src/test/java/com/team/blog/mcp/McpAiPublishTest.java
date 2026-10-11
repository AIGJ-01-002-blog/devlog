package com.team.blog.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import tools.jackson.databind.JsonNode;

import com.team.blog.support.IntegrationTest;

/**
 * spec 053: AI 발행·삭제 허용. 기본은 꺼짐이고, 켜면 publish_post·delete_post가 열린다.
 * 설정은 로그인한 웹 화면에서만 바꾸며, 접근 토큰으로는 바꿀 수 없다.
 */
class McpAiPublishTest extends IntegrationTest {

    String token(Session s, String scope) throws Exception {
        return read(s.http().perform(asJson(post("/api/me/tokens"), Map.of("name", "Claude Code", "scope", scope)))
                .andExpect(status().isCreated()).andReturn()).path("secret").asString();
    }

    ResultActions rpc(String token, String method, Object params) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("jsonrpc", "2.0");
        body.put("id", 1);
        body.put("method", method);
        if (params != null) body.put("params", params);
        return mvc.perform(post("/api/mcp").contentType(MediaType.APPLICATION_JSON).header("Authorization", "Bearer " + token)
                .content(json.writeValueAsString(body)));
    }

    JsonNode call(String token, String tool, Map<String, Object> args) throws Exception {
        return read(rpc(token, "tools/call", Map.of("name", tool, "arguments", args)).andExpect(status().isOk()).andReturn()).path("result");
    }

    List<String> toolNames(String token) throws Exception {
        return read(rpc(token, "tools/list", null).andExpect(status().isOk()).andReturn()).path("result").path("tools").findValuesAsString("name");
    }

    static String text(JsonNode result) {
        return result.path("content").get(0).path("text").asString();
    }

    void allow(Session s, boolean on) throws Exception {
        JsonNode r = read(s.http().perform(asJson(put("/api/me/ai-publish"), Map.of("allowed", on))).andExpect(status().isOk()).andReturn());
        assertThat(r.path("allowed").asBoolean()).isEqualTo(on);
    }

    long draft(String token, String title) throws Exception {
        JsonNode r = call(token, "create_draft", Map.of("title", title, "content_md", "## 오늘 한 일\n" + title, "tags", List.of("Spring", "MCP")));
        assertThat(r.path("isError").asBoolean()).isFalse();
        return Long.parseLong(text(r).replaceAll("(?s).*글 번호 (\\d+).*", "$1"));
    }

    String postStatus(long id) {
        return jdbc.queryForObject("SELECT status FROM post WHERE id = ?", String.class, id);
    }

    @Test
    void 꺼져_있으면_발행_삭제_도구가_보이지_않고_불러도_거절된다() throws Exception {
        Session me = signup(uniqueLogin("aipoff"));
        assertThat(read(me.http().perform(get("/api/me/ai-publish")).andExpect(status().isOk()).andReturn()).path("allowed").asBoolean()).isFalse();
        String t = token(me, "WRITE");
        assertThat(toolNames(t)).contains("write_devlog", "request_publish").doesNotContain("publish_post", "delete_post");
        assertThat(read(rpc(t, "initialize", Map.of("protocolVersion", "2025-06-18")).andReturn()).path("result").path("instructions").asString())
                .doesNotContain("publish_post");

        long id = draft(t, "꺼진 상태");
        JsonNode pub = call(t, "publish_post", Map.of("post_id", id));
        assertThat(pub.path("isError").asBoolean()).isTrue();
        assertThat(text(pub)).contains("설정 › AI 연결");
        JsonNode del = call(t, "delete_post", Map.of("post_id", id));
        assertThat(del.path("isError").asBoolean()).isTrue();
        assertThat(text(del)).contains("설정 › AI 연결");
        assertThat(postStatus(id)).isEqualTo("DRAFT");
        assertThat(jdbc.queryForObject("SELECT deleted_at IS NULL FROM post WHERE id = ?", Boolean.class, id)).isTrue();
    }

    @Test
    void 켜면_쓰기_토큰으로_발행하고_삭제한다_웹과_같은_규칙이다() throws Exception {
        Session me = signup(uniqueLogin("aipon"));
        String t = token(me, "WRITE");
        long id = draft(t, "AI가 발행한 글");
        allow(me, true);
        assertThat(toolNames(t)).contains("publish_post", "delete_post");
        assertThat(read(rpc(t, "initialize", Map.of("protocolVersion", "2025-06-18")).andReturn()).path("result").path("instructions").asString())
                .contains("publish_post");

        // 잘못된 공개 범위는 거절, 태그를 주지 않으면 AI 제안 태그로 발행된다
        assertThat(call(t, "publish_post", Map.of("post_id", id, "visibility", "EVERYONE")).path("isError").asBoolean()).isTrue();
        JsonNode pub = call(t, "publish_post", Map.of("post_id", id, "visibility", "PRIVATE"));
        assertThat(pub.path("isError").asBoolean()).as(text(pub)).isFalse();
        assertThat(text(pub)).contains("/@" + me.handle() + "/posts/" + id).contains("비공개").contains("태그: spring, mcp");
        assertThat(postStatus(id)).isEqualTo("PUBLISHED");
        assertThat(jdbc.queryForObject("SELECT visibility FROM post WHERE id = ?", String.class, id)).isEqualTo("PRIVATE");
        assertThat(jdbc.queryForList("SELECT t.name FROM post_tag pt JOIN tag t ON t.id = pt.tag_id WHERE pt.post_id = ? ORDER BY pt.position",
                String.class, id)).containsExactly("spring", "mcp");
        // 발행되면 AI 제안 기록은 웹 발행처럼 지워진다
        assertThat(jdbc.queryForObject("SELECT count(*) FROM post_ai_hint WHERE post_id = ?", Integer.class, id)).isZero();
        // 이미 발행한 글은 다시 발행하지 않는다
        assertThat(call(t, "publish_post", Map.of("post_id", id)).path("isError").asBoolean()).isTrue();

        // 웹 발행과 같은 검증: 태그 규칙을 어기면 발행되지 않는다
        long bad = draft(t, "태그가 이상한 글");
        JsonNode badTags = call(t, "publish_post", Map.of("post_id", bad, "tags", List.of("a".repeat(100))));
        assertThat(badTags.path("isError").asBoolean()).isTrue();
        assertThat(postStatus(bad)).isEqualTo("DRAFT");
        // tags는 문자열 배열이어야 한다: 문자열 하나나 숫자가 섞이면 조용히 무시하지 않고 거절한다
        assertThat(call(t, "publish_post", Map.of("post_id", bad, "tags", "Spring")).path("isError").asBoolean()).isTrue();
        assertThat(call(t, "publish_post", Map.of("post_id", bad, "tags", List.of("Spring", 1))).path("isError").asBoolean()).isTrue();
        assertThat(postStatus(bad)).isEqualTo("DRAFT");

        // 삭제는 웹의 [삭제]처럼 휴지통으로 옮긴다
        JsonNode del = call(t, "delete_post", Map.of("post_id", id));
        assertThat(del.path("isError").asBoolean()).as(text(del)).isFalse();
        assertThat(text(del)).contains("휴지통");
        assertThat(jdbc.queryForObject("SELECT deleted_at IS NOT NULL FROM post WHERE id = ?", Boolean.class, id)).isTrue();
        assertThat(text(call(t, "delete_post", Map.of("post_id", id)))).contains("이미 휴지통");

        // 다시 끄면 바로 막힌다
        allow(me, false);
        assertThat(toolNames(t)).doesNotContain("publish_post", "delete_post");
        assertThat(call(t, "delete_post", Map.of("post_id", bad)).path("isError").asBoolean()).isTrue();
        assertThat(jdbc.queryForObject("SELECT deleted_at IS NULL FROM post WHERE id = ?", Boolean.class, bad)).isTrue();
    }

    @Test
    void 비밀값이_보이는_글은_임시글로_만들되_AI가_발행하지_못한다() throws Exception {
        Session me = signup(uniqueLogin("aipsec"));
        String t = token(me, "WRITE");
        allow(me, true);
        String key = "sk-ant-" + "b".repeat(40);
        JsonNode r = call(t, "create_draft", Map.of("title", "키 설정기", "content_md", "환경 변수에 " + key + " 를 넣었어요"));
        assertThat(r.path("isError").asBoolean()).isFalse();
        assertThat(text(r)).contains("주의").contains("AI API 키").doesNotContain(key);
        long id = Long.parseLong(text(r).replaceAll("(?s).*글 번호 (\\d+).*", "$1"));

        JsonNode pub = call(t, "publish_post", Map.of("post_id", id));
        assertThat(pub.path("isError").asBoolean()).isTrue();
        assertThat(text(pub)).contains("발행하지 않았어요").doesNotContain(key);
        assertThat(postStatus(id)).isEqualTo("DRAFT");

        // 빼고 나면 발행된다
        assertThat(call(t, "update_draft", Map.of("post_id", id, "content_md", "환경 변수에 키를 넣었어요")).path("isError").asBoolean()).isFalse();
        JsonNode ok = call(t, "publish_post", Map.of("post_id", id, "visibility", "PRIVATE"));
        assertThat(ok.path("isError").asBoolean()).as(text(ok)).isFalse();
        assertThat(postStatus(id)).isEqualTo("PUBLISHED");
    }

    @Test
    void 읽기_토큰과_남의_글은_켜져_있어도_거절된다() throws Exception {
        Session owner = signup(uniqueLogin("aipown"));
        String ot = token(owner, "WRITE");
        long id = draft(ot, "남의 글");

        Session me = signup(uniqueLogin("aipme"));
        allow(me, true);
        String readOnly = token(me, "READ");
        JsonNode denied = call(readOnly, "publish_post", Map.of("post_id", id));
        assertThat(denied.path("isError").asBoolean()).isTrue();
        assertThat(text(denied)).contains("읽기 전용");
        assertThat(call(readOnly, "delete_post", Map.of("post_id", id)).path("isError").asBoolean()).isTrue();

        String write = token(me, "WRITE");
        JsonNode pub = call(write, "publish_post", Map.of("post_id", id));
        assertThat(pub.path("isError").asBoolean()).isTrue();
        assertThat(text(pub)).contains("찾을 수 없어요");
        assertThat(call(write, "delete_post", Map.of("post_id", id)).path("isError").asBoolean()).isTrue();
        assertThat(postStatus(id)).isEqualTo("DRAFT");
        assertThat(jdbc.queryForObject("SELECT deleted_at IS NULL FROM post WHERE id = ?", Boolean.class, id)).isTrue();

        // 이메일 인증 전이면 켜져 있어도 쓰기 도구처럼 막힌다
        jdbc.update("UPDATE auth_identity SET email_verified_at = NULL WHERE member_id = ?", me.memberId());
        assertThat(text(call(write, "delete_post", Map.of("post_id", id)))).contains("이메일 인증");
    }

    @Test
    void 설정은_로그인_세션으로만_바꾸고_토큰으로는_바꿀_수_없다() throws Exception {
        Session me = signup(uniqueLogin("aipset"));
        String t = token(me, "WRITE");
        String body = json.writeValueAsString(Map.of("allowed", true));
        // 세션 없이 Bearer 토큰만: 웹 API는 접근 토큰을 보지 않는다
        mvc.perform(put("/api/me/ai-publish").header("Authorization", "Bearer " + t).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(r -> assertThat(r.getResponse().getStatus()).isIn(401, 403));
        mvc.perform(get("/api/me/ai-publish").header("Authorization", "Bearer " + t)).andExpect(status().isUnauthorized());
        // 로그인 세션이어도 CSRF 토큰이 없으면 안 된다
        me.http().perform(put("/api/me/ai-publish").contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden());
        // MCP에는 설정을 바꾸는 도구가 없다
        assertThat(toolNames(t)).noneMatch(n -> n.contains("setting") || n.contains("allow"));
        assertThat(jdbc.queryForObject("SELECT ai_publish_allowed FROM member WHERE id = ?", Boolean.class, me.memberId())).isFalse();

        me.http().perform(asJson(put("/api/me/ai-publish"), Map.of())).andExpect(status().isBadRequest());
        allow(me, true);
        assertThat(read(me.http().perform(get("/api/me/ai-publish")).andReturn()).path("allowed").asBoolean()).isTrue();
    }
}
