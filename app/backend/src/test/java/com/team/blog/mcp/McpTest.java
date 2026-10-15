package com.team.blog.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import tools.jackson.databind.JsonNode;

import com.team.blog.account.application.WithdrawalPurgeStep;
import com.team.blog.support.IntegrationTest;

/** spec 052: 개인 접근 토큰과 MCP 서버. AI는 임시글·발행 대기까지만 만들고, 볼 수 있는 글만 읽는다. */
class McpTest extends IntegrationTest {
    @Autowired Map<String, WithdrawalPurgeStep> purgeSteps;

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
        var req = post("/api/mcp").contentType(MediaType.APPLICATION_JSON).accept(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM)
                .content(json.writeValueAsString(body));
        if (token != null) req.header("Authorization", "Bearer " + token);
        return mvc.perform(req);
    }

    JsonNode call(String token, String tool, Map<String, Object> args) throws Exception {
        return read(rpc(token, "tools/call", Map.of("name", tool, "arguments", args)).andExpect(status().isOk()).andReturn()).path("result");
    }

    static String text(JsonNode result) {
        return result.path("content").get(0).path("text").asString();
    }

    long publish(Session s, String title, String visibility) throws Exception {
        long id = read(s.http().perform(asJson(post("/api/posts"), Map.of("title", title, "contentMd", "")))
                .andExpect(status().isCreated()).andReturn()).path("id").asLong();
        Map<String, Object> body = new HashMap<>(Map.of("title", title, "contentMd", "비밀 본문 " + title, "visibility", visibility,
                "tags", List.of(), "baseVersion", 0));
        s.http().perform(asJson(post("/api/posts/" + id + "/publish"), body).header("Idempotency-Key", UUID.randomUUID().toString()))
                .andExpect(status().isOk());
        return id;
    }

    @Test
    void 토큰을_만들면_원문은_한_번만_보이고_목록에는_앞부분만_남는다() throws Exception {
        Session me = signup(uniqueLogin("tok"));
        JsonNode issued = read(me.http().perform(asJson(post("/api/me/tokens"), Map.of("name", "노트북", "scope", "READ", "expiresInDays", 30)))
                .andExpect(status().isCreated()).andReturn());
        String secret = issued.path("secret").asString();
        assertThat(secret).startsWith("dvl_").hasSizeGreaterThan(40);
        JsonNode list = read(me.http().perform(get("/api/me/tokens")).andExpect(status().isOk()).andReturn());
        assertThat(list).hasSize(1);
        assertThat(list.get(0).path("prefix").asString()).isEqualTo(secret.substring(0, 8));
        assertThat(list.get(0).path("scope").asString()).isEqualTo("READ");
        assertThat(list.toString()).doesNotContain(secret);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM personal_access_token WHERE token_hash = ?", Integer.class, secret)).isZero();

        // 이름·기간 검증, 남의 토큰은 폐기할 수 없다
        me.http().perform(asJson(post("/api/me/tokens"), Map.of("name", " "))).andExpect(status().isBadRequest());
        me.http().perform(asJson(post("/api/me/tokens"), Map.of("name", "x", "expiresInDays", 7))).andExpect(status().isBadRequest());
        Session other = signup(uniqueLogin("tok2"));
        long id = list.get(0).path("id").asLong();
        other.http().perform(delete("/api/me/tokens/" + id).with(csrf()))
                .andExpect(status().isNotFound());

        // 폐기하면 바로 막힌다
        rpc(secret, "ping", null).andExpect(status().isOk());
        me.http().perform(delete("/api/me/tokens/" + id).with(csrf()))
                .andExpect(status().isNoContent());
        rpc(secret, "ping", null).andExpect(status().isUnauthorized()).andExpect(header().string("WWW-Authenticate", org.hamcrest.Matchers.startsWith("Bearer")));
        assertThat(read(me.http().perform(get("/api/me/tokens")).andReturn())).isEmpty();
    }

    @Test
    void 토큰은_10개까지_만료된_토큰은_쓸_수_없다() throws Exception {
        Session me = signup(uniqueLogin("toklim"));
        String first = token(me, "WRITE");
        for (int i = 1; i < 10; i++) token(me, "READ");
        me.http().perform(asJson(post("/api/me/tokens"), Map.of("name", "열한 번째"))).andExpect(status().isConflict());
        jdbc.update("UPDATE personal_access_token SET expires_at = now() - interval '1 minute' WHERE member_id = ?", me.memberId());
        rpc(first, "ping", null).andExpect(status().isUnauthorized());
        assertThat(read(me.http().perform(get("/api/me/tokens")).andReturn()).get(0).path("expired").asBoolean()).isTrue();
        // 만료된 토큰은 개수에 세지 않는다
        token(me, "READ");
    }

    @Test
    void 토큰_없이는_401이고_세션_쿠키로는_들어올_수_없다() throws Exception {
        rpc(null, "tools/list", null).andExpect(status().isUnauthorized());
        rpc("dvl_doesnotexist", "tools/list", null).andExpect(status().isUnauthorized());
        Session me = signup(uniqueLogin("tokcookie"));
        me.http().perform(post("/api/mcp").contentType(MediaType.APPLICATION_JSON)
                .content("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/mcp")).andExpect(status().isMethodNotAllowed());
    }

    @Test
    void 연결하면_서버_정보와_발행_도구를_뺀_도구_목록을_돌려준다() throws Exception {
        Session me = signup(uniqueLogin("devlogi"));
        String t = token(me, "WRITE");

        JsonNode init = read(rpc(t, "initialize", Map.of("protocolVersion", "2025-06-18", "capabilities", Map.of(),
                "clientInfo", Map.of("name", "test", "version", "1"))).andExpect(status().isOk()).andReturn());
        assertThat(init.path("result").path("protocolVersion").asString()).isEqualTo("2025-06-18");
        assertThat(init.path("result").path("serverInfo").path("name").asString()).isEqualTo("devlog");
        mvc.perform(post("/api/mcp").header("Authorization", "Bearer " + t).contentType(MediaType.APPLICATION_JSON)
                .content("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}")).andExpect(status().isAccepted());

        JsonNode tools = read(rpc(t, "tools/list", null).andReturn()).path("result").path("tools");
        assertThat(tools.findValuesAsString("name")).contains("write_devlog", "request_publish", "get_post", "list_tags")
                .doesNotContain("publish_post", "delete_post");
    }

    /** write_devlog로 임시글을 만들고 그 글 번호를 돌려준다 */
    long writeDevlog(Session me, String t) throws Exception {
        JsonNode r = call(t, "write_devlog", Map.of("title", "MCP 서버 붙인 날", "content_md", "## 오늘 한 일\n토큰 인증",
                "tags", List.of("Spring", "MCP", "mcp")));
        assertThat(r.path("isError").asBoolean()).isFalse();
        long id = jdbc.queryForObject("SELECT id FROM post WHERE author_id = ? ORDER BY id DESC LIMIT 1", Long.class, me.memberId());
        assertThat(text(r)).contains("/write/" + id).contains("공개되지 않았어요");
        return id;
    }

    @Test
    void 연결하고_개발_일지를_쓰면_임시글이_되고_발행은_사람이_한다() throws Exception {
        Session me = signup(uniqueLogin("devlog"));
        String t = token(me, "WRITE");
        long id = writeDevlog(me, t);
        assertThat(jdbc.queryForObject("SELECT status FROM post WHERE id = ?", String.class, id)).isEqualTo("DRAFT");

        // 편집 화면이 태그 제안을 받는다
        JsonNode hint = read(me.http().perform(get("/api/posts/" + id + "/ai-hint")).andExpect(status().isOk()).andReturn());
        assertThat(hint.path("tags")).hasSize(2);
        assertThat(hint.path("publishRequestedAt").isNull()).isTrue();

        assertThat(call(t, "update_draft", Map.of("post_id", id, "title", "MCP 서버 붙인 날 (수정)")).path("isError").asBoolean()).isFalse();
        JsonNode req = call(t, "request_publish", Map.of("post_id", id));
        assertThat(req.path("isError").asBoolean()).isFalse();
        assertThat(jdbc.queryForObject("SELECT status FROM post WHERE id = ?", String.class, id)).isEqualTo("DRAFT");
        assertThat(read(me.http().perform(get("/api/posts/" + id + "/ai-hint")).andReturn()).path("publishRequestedAt").isNull()).isFalse();
        assertThat(text(call(t, "list_my_posts", Map.of()))).contains("MCP 서버 붙인 날 (수정)");
        assertThat(text(call(t, "get_post", Map.of("post_id", id)))).contains("토큰 인증");
    }

    @Test
    void 다른_회원은_AI가_쓴_임시글과_글_제안을_볼_수_없다() throws Exception {
        Session me = signup(uniqueLogin("devlogo"));
        long id = writeDevlog(me, token(me, "WRITE"));

        // 다른 회원의 글 제안은 볼 수 없다
        Session other = signup(uniqueLogin("devlog2"));
        other.http().perform(get("/api/posts/" + id + "/ai-hint")).andExpect(status().isNotFound());
        String ot = token(other, "WRITE");
        assertThat(call(ot, "update_draft", Map.of("post_id", id, "title", "남의 글")).path("isError").asBoolean()).isTrue();
        assertThat(call(ot, "get_post", Map.of("post_id", id)).path("isError").asBoolean()).isTrue();
    }

    @Test
    void 읽기_전용_토큰은_쓰지_못하고_볼_수_없는_글은_읽지_못한다() throws Exception {
        Session author = signup(uniqueLogin("mcpau"));
        long pub = publish(author, "공개 글", "PUBLIC");
        long priv = publish(author, "비공개 글", "PRIVATE");
        Session reader = signup(uniqueLogin("mcprd"));
        String t = token(reader, "READ");

        JsonNode denied = call(t, "write_devlog", Map.of("title", "x", "content_md", "y"));
        assertThat(denied.path("isError").asBoolean()).isTrue();
        assertThat(text(denied)).contains("읽기 전용");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM post WHERE author_id = ?", Integer.class, reader.memberId())).isZero();

        assertThat(text(call(t, "get_post", Map.of("post_id", pub)))).contains("비밀 본문 공개 글");
        JsonNode hidden = call(t, "get_post", Map.of("post_id", priv));
        assertThat(hidden.path("isError").asBoolean()).isTrue();
        assertThat(text(hidden)).doesNotContain("비밀 본문");
        assertThat(call(t, "nope", Map.of()).path("isError").asBoolean()).isTrue();
        assertThat(read(rpc(t, "resources/list", null).andReturn()).path("error").path("code").asInt()).isEqualTo(-32601);
    }

    @Test
    void 정지된_계정은_403이고_탈퇴하면_토큰이_지워진다() throws Exception {
        Session me = signup(uniqueLogin("mcpsus"));
        String t = token(me, "WRITE");
        jdbc.update("UPDATE member SET status = 'SUSPENDED' WHERE id = ?", me.memberId());
        rpc(t, "ping", null).andExpect(status().isForbidden());
        jdbc.update("UPDATE member SET status = 'ACTIVE' WHERE id = ?", me.memberId());
        rpc(t, "ping", null).andExpect(status().isOk());
        purgeSteps.get("accessTokenWithdrawalPurgeStep").purge(me.memberId());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM personal_access_token WHERE member_id = ?", Long.class, me.memberId())).isZero();
        rpc(t, "ping", null).andExpect(status().isUnauthorized());
    }
}
