package com.team.blog.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import tools.jackson.databind.JsonNode;

import com.team.blog.support.IntegrationTest;

/** spec 052: ChatGPT처럼 토큰을 붙여 넣을 수 없는 앱은 OAuth(동적 등록 + PKCE)로 연결하고, 회원이 동의해야 토큰이 나온다. */
class McpOAuthTest extends IntegrationTest {
    static final String REDIRECT = "https://chatgpt.com/connector_platform_oauth_redirect";
    static final String VERIFIER = "verifier-" + "a".repeat(50);

    static String challenge(String verifier) throws Exception {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
                MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));
    }

    String register() throws Exception {
        return read(mvc.perform(post("/api/oauth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("client_name", "ChatGPT", "redirect_uris", List.of(REDIRECT)))))
                .andExpect(status().isCreated()).andReturn()).path("client_id").asString();
    }

    Map<String, Object> authorize(String clientId, boolean approve) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("clientId", clientId);
        body.put("redirectUri", REDIRECT);
        body.put("responseType", "code");
        body.put("codeChallenge", challenge(VERIFIER));
        body.put("codeChallengeMethod", "S256");
        body.put("scope", "devlog.read devlog.write");
        body.put("state", "xyz");
        body.put("resource", "http://localhost:8080/api/mcp");
        body.put("approve", approve);
        return body;
    }

    static Map<String, String> query(String url) {
        Map<String, String> out = new HashMap<>();
        for (String kv : URI.create(url).getRawQuery().split("&")) {
            String[] p = kv.split("=", 2);
            out.put(p[0], URLDecoder.decode(p[1], StandardCharsets.UTF_8));
        }
        return out;
    }

    ResultActions token(Map<String, String> form) throws Exception {
        MockHttpServletRequestBuilder b = post("/api/oauth/token").contentType(MediaType.APPLICATION_FORM_URLENCODED);
        form.forEach(b::param);
        return mvc.perform(b);
    }

    ResultActions ping(String token) throws Exception {
        return mvc.perform(post("/api/mcp").header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON)
                .content("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}"));
    }

    @Test
    void 메타데이터로_로그인_방법을_알린다() throws Exception {
        mvc.perform(post("/api/mcp").contentType(MediaType.APPLICATION_JSON).content("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", Matchers.containsString("resource_metadata=\"http://localhost:8080/.well-known/oauth-protected-resource\"")));
        JsonNode pr = read(mvc.perform(get("/.well-known/oauth-protected-resource")).andExpect(status().isOk()).andReturn());
        assertThat(pr.path("resource").asString()).isEqualTo("http://localhost:8080/api/mcp");
        assertThat(pr.path("authorization_servers").get(0).asString()).isEqualTo("http://localhost:8080");
        JsonNode as = read(mvc.perform(get("/.well-known/oauth-authorization-server")).andExpect(status().isOk()).andReturn());
        assertThat(as.path("issuer").asString()).isEqualTo("http://localhost:8080");
        assertThat(as.path("code_challenge_methods_supported").toString()).contains("S256");
        assertThat(as.path("registration_endpoint").asString()).endsWith("/api/oauth/register");
        // 동의 화면은 로그인 껍데기로
        mvc.perform(get("/oauth/authorize")).andExpect(status().isOk());
    }

    /** 동의 화면 정보를 읽는다. scope가 null이면 범위를 고르지 않은 요청 */
    JsonNode consentView(Session me, String clientId, String scope) throws Exception {
        var req = get("/api/oauth/authorize").param("client_id", clientId).param("redirect_uri", REDIRECT)
                .param("response_type", "code").param("code_challenge", challenge(VERIFIER)).param("code_challenge_method", "S256");
        if (scope != null) req.param("scope", scope);
        return read(me.http().perform(req).andExpect(status().isOk()).andReturn());
    }

    /** [허용]을 눌러 받은 코드 */
    String approvedCode(Session me, String clientId) throws Exception {
        return query(read(me.http().perform(asJson(post("/api/oauth/authorize"), authorize(clientId, true))).andReturn())
                .path("redirect").asString()).get("code");
    }

    ResultActions exchange(String code, String clientId, String verifier) throws Exception {
        return token(Map.of("grant_type", "authorization_code", "code", code, "client_id", clientId, "redirect_uri", REDIRECT,
                "code_verifier", verifier));
    }

    @Test
    void 동의_화면은_앱과_돌아갈_곳과_범위를_알리고_로그인해야_허용한다() throws Exception {
        String clientId = register();
        Session me = signup(uniqueLogin("oauth"));

        // 동의 화면 정보
        JsonNode view = consentView(me, clientId, null);
        assertThat(view.path("clientName").asString()).isEqualTo("ChatGPT");
        assertThat(view.path("redirectHost").asString()).isEqualTo("chatgpt.com");
        // 범위를 고르지 않으면 읽기만, devlog.write를 요청해야 쓰기까지
        assertThat(view.path("scope").asString()).isEqualTo("READ");
        JsonNode writeView = consentView(me, clientId, "devlog.write");
        assertThat(writeView.path("scope").asString()).isEqualTo("WRITE");

        // 로그인하지 않으면 허용할 수 없다
        browser().perform(asJson(post("/api/oauth/authorize"), authorize(clientId, true))).andExpect(status().isUnauthorized());
    }

    @Test
    void 코드는_verifier가_맞아야_바꾸고_한_번_꺼내면_사라진다() throws Exception {
        String clientId = register();
        Session me = signup(uniqueLogin("oauthc"));

        String redirect = read(me.http().perform(asJson(post("/api/oauth/authorize"), authorize(clientId, true)))
                .andExpect(status().isOk()).andReturn()).path("redirect").asString();
        assertThat(redirect).startsWith(REDIRECT + "?code=");
        Map<String, String> q = query(redirect);
        assertThat(q).containsEntry("state", "xyz").containsEntry("iss", "http://localhost:8080");

        // verifier 길이가 RFC 7636(43~128자)에 맞지 않으면 코드를 꺼내기 전에 거절한다
        exchange(q.get("code"), clientId, "short").andExpect(status().isBadRequest());
        // verifier가 틀리면 거절하고, 코드는 한 번 꺼내면 사라진다
        exchange(q.get("code"), clientId, "wrong-" + "b".repeat(50)).andExpect(status().isBadRequest());
        exchange(q.get("code"), clientId, VERIFIER).andExpect(status().isBadRequest());
    }

    @Test
    void 동의하면_코드를_토큰으로_바꾸고_MCP를_쓰며_갱신하고_폐기할_수_있다() throws Exception {
        String clientId = register();
        Session me = signup(uniqueLogin("oautht"));

        String code = approvedCode(me, clientId);
        JsonNode t = read(exchange(code, clientId, VERIFIER).andExpect(status().isOk()).andReturn());
        assertThat(t.path("token_type").asString()).isEqualTo("Bearer");
        assertThat(t.path("scope").asString()).contains("devlog.write");
        String access = t.path("access_token").asString();
        ping(access).andExpect(status().isOk());

        // 설정 화면에 연결로 보인다
        JsonNode list = read(me.http().perform(get("/api/me/tokens")).andReturn());
        assertThat(list).hasSize(1);
        assertThat(list.get(0).path("name").asString()).isEqualTo("ChatGPT");
        assertThat(list.get(0).path("oauth").asBoolean()).isTrue();

        // 갱신하면 새 한 쌍이 나오고 이전 것은 못 쓴다
        String refresh = t.path("refresh_token").asString();
        JsonNode t2 = read(token(Map.of("grant_type", "refresh_token", "refresh_token", refresh, "client_id", clientId))
                .andExpect(status().isOk()).andReturn());
        ping(access).andExpect(status().isUnauthorized());
        ping(t2.path("access_token").asString()).andExpect(status().isOk());
        token(Map.of("grant_type", "refresh_token", "refresh_token", refresh, "client_id", clientId)).andExpect(status().isBadRequest());

        // 폐기하면 갱신도 막힌다
        me.http().perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/me/tokens/" + list.get(0).path("id").asLong())
                .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf())).andExpect(status().isNoContent());
        ping(t2.path("access_token").asString()).andExpect(status().isUnauthorized());
        token(Map.of("grant_type", "refresh_token", "refresh_token", t2.path("refresh_token").asString(), "client_id", clientId))
                .andExpect(status().isBadRequest());
    }

    @Test
    void ChatGPT처럼_등록하고_Basic으로_client_id를_보내도_연결된다() throws Exception {
        // ChatGPT 새 커넥터는 커넥터마다 다른 돌아갈 주소를 쓰고, 등록 본문에 표준 필드를 더 담는다
        String cb = "https://chatgpt.com/connector/oauth/cb_" + "x".repeat(20);
        Map<String, Object> reg = new HashMap<>();
        reg.put("client_name", "ChatGPT");
        reg.put("redirect_uris", List.of(cb));
        reg.put("grant_types", List.of("authorization_code", "refresh_token"));
        reg.put("response_types", List.of("code"));
        reg.put("token_endpoint_auth_method", "client_secret_basic");
        reg.put("scope", "devlog.read devlog.write");
        JsonNode r = read(mvc.perform(post("/api/oauth/register").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(reg)))
                .andExpect(status().isCreated()).andReturn());
        // 공개 클라이언트로 답한다(비밀값 없음)
        assertThat(r.path("token_endpoint_auth_method").asString()).isEqualTo("none");
        assertThat(r.has("client_secret")).isFalse();
        String clientId = r.path("client_id").asString();

        Session me = signup(uniqueLogin("oauthgpt"));
        Map<String, Object> body = authorize(clientId, true);
        body.put("redirectUri", cb);
        String code = query(read(me.http().perform(asJson(post("/api/oauth/authorize"), body)).andExpect(status().isOk()).andReturn())
                .path("redirect").asString()).get("code");

        String basic = "Basic " + Base64.getEncoder().encodeToString((clientId + ":").getBytes(StandardCharsets.UTF_8));
        JsonNode t = read(mvc.perform(post("/api/oauth/token").contentType(MediaType.APPLICATION_FORM_URLENCODED).header("Authorization", basic)
                        .param("grant_type", "authorization_code").param("code", code).param("redirect_uri", cb).param("code_verifier", VERIFIER)
                        .param("resource", "http://localhost:8080/api/mcp"))
                .andExpect(status().isOk()).andReturn());
        ping(t.path("access_token").asString()).andExpect(status().isOk());
        // 갱신도 Basic으로
        mvc.perform(post("/api/oauth/token").contentType(MediaType.APPLICATION_FORM_URLENCODED).header("Authorization", basic)
                        .param("grant_type", "refresh_token").param("refresh_token", t.path("refresh_token").asString()))
                .andExpect(status().isOk());
    }

    @Test
    void 거부하거나_요청이_틀리면_토큰이_나오지_않는다() throws Exception {
        String clientId = register();
        Session me = signup(uniqueLogin("oauthno"));
        String denied = read(me.http().perform(asJson(post("/api/oauth/authorize"), authorize(clientId, false))).andReturn()).path("redirect").asString();
        assertThat(query(denied)).containsEntry("error", "access_denied").containsEntry("state", "xyz");

        // 등록하지 않은 돌아갈 주소·앱, PKCE 없음
        Map<String, Object> bad = authorize(clientId, true);
        bad.put("redirectUri", "https://evil.example.com/cb");
        me.http().perform(asJson(post("/api/oauth/authorize"), bad)).andExpect(status().isBadRequest());
        bad = authorize("dvc_none", true);
        me.http().perform(asJson(post("/api/oauth/authorize"), bad)).andExpect(status().isBadRequest());
        bad = authorize(clientId, true);
        bad.put("codeChallengeMethod", "plain");
        assertThat(read(me.http().perform(asJson(post("/api/oauth/authorize"), bad)).andExpect(status().isOk()).andReturn())
                .path("redirect").asString()).contains("error=invalid_request");

        // http 돌아갈 주소는 내 컴퓨터만
        mvc.perform(post("/api/oauth/register").contentType(MediaType.APPLICATION_JSON)
                .content("{\"client_name\":\"x\",\"redirect_uris\":[\"http://evil.example.com/cb\"]}")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/oauth/register").contentType(MediaType.APPLICATION_JSON)
                .content("{\"client_name\":\"x\",\"redirect_uris\":[\"http://127.0.0.1:8976/cb\"]}")).andExpect(status().isCreated());
        token(Map.of("grant_type", "password")).andExpect(status().isBadRequest());
        assertThat(read(me.http().perform(get("/api/me/tokens")).andReturn())).isEmpty();
    }
}
