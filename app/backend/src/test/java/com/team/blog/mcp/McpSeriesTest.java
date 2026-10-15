package com.team.blog.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import tools.jackson.databind.JsonNode;

import com.team.blog.support.IntegrationTest;

/**
 * spec 073: MCP로 시리즈 만들기·글 넣기(자리 지정)·포트폴리오 프로젝트 칸 쓰기. 웹의 시리즈 화면과 같은 규칙을 따른다.
 */
class McpSeriesTest extends IntegrationTest {

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

    static String text(JsonNode result) {
        return result.path("content").get(0).path("text").asString();
    }

    long publish(Session s, String title, String visibility) throws Exception {
        long id = read(s.http().perform(asJson(post("/api/posts"), Map.of("title", title, "contentMd", "")))
                .andExpect(status().isCreated()).andReturn()).path("id").asLong();
        Map<String, Object> body = new HashMap<>(Map.of("title", title, "contentMd", "본문 " + title, "visibility", visibility,
                "tags", List.of("spring"), "baseVersion", 0));
        s.http().perform(asJson(post("/api/posts/" + id + "/publish"), body).header("Idempotency-Key", UUID.randomUUID().toString()))
                .andExpect(status().isOk());
        return id;
    }


    long seriesId(String text) {
        return Long.parseLong(text.replaceAll("(?s).*시리즈 번호 (\\d+).*", "$1"));
    }

    List<Long> order(long seriesId) {
        return jdbc.queryForList("SELECT post_id FROM series_post WHERE series_id = ? ORDER BY position", Long.class, seriesId);
    }

    /** 글 세 편(a, b, c)과 시리즈 하나("devlog 만들기")를 만든 상태 */
    record Shelf(Session me, String token, long a, long b, long c, long seriesId) {}

    Shelf shelf(String login) throws Exception {
        Session me = signup(uniqueLogin(login));
        String t = token(me, "WRITE");
        long a = publish(me, "첫 글", "PUBLIC");
        long b = publish(me, "둘째 글", "PUBLIC");
        long c = publish(me, "셋째 글", "PUBLIC");

        assertThat(text(call(t, "list_series", Map.of()))).contains("아직 시리즈가 없어요");
        JsonNode made = call(t, "create_series", Map.of("series_name", "devlog 만들기"));
        assertThat(made.path("isError").asBoolean()).as(text(made)).isFalse();
        return new Shelf(me, t, a, b, c, seriesId(text(made)));
    }

    @Test
    void 시리즈를_만들고_글을_원하는_자리에_넣는다() throws Exception {
        Shelf f = shelf("mcpser");
        String t = f.token();
        long a = f.a();
        long b = f.b();
        long c = f.c();
        long sid = f.seriesId();

        assertThat(text(call(t, "add_to_series", Map.of("post_id", a, "series_id", sid)))).contains("1편");
        assertThat(text(call(t, "add_to_series", Map.of("post_id", c, "series_id", sid)))).contains("2편");
        // 가운데에 끼워 넣는다
        assertThat(text(call(t, "add_to_series", Map.of("post_id", b, "series_id", sid, "position", 2)))).contains("2편");
        assertThat(order(sid)).containsExactly(a, b, c);
        // 이미 든 글은 자리만 옮긴다
        call(t, "add_to_series", Map.of("post_id", c, "series_id", sid, "position", 1));
        assertThat(order(sid)).containsExactly(c, a, b);
        assertThat(text(call(t, "list_series", Map.of()))).contains(sid + "번 devlog 만들기 (글 3편)");

        // 임시글은 맨 뒤에 들어가고 순서는 매기지 않는다
        long draft = read(f.me().http().perform(asJson(post("/api/posts"), Map.of("title", "임시", "contentMd", "")))
                .andExpect(status().isCreated()).andReturn()).path("id").asLong();
        assertThat(text(call(t, "add_to_series", Map.of("post_id", draft, "series_id", sid, "position", 1)))).contains("발행 전");
        assertThat(order(sid)).containsExactly(c, a, b, draft);
    }

    @Test
    void 남의_시리즈와_남의_글에는_넣을_수_없고_읽기_토큰과_같은_이름은_거절한다() throws Exception {
        Shelf f = shelf("mcpserx");
        String t = f.token();
        long sid = f.seriesId();
        call(t, "add_to_series", Map.of("post_id", f.a(), "series_id", sid));

        // 남의 시리즈·남의 글에는 넣을 수 없고, 읽기 토큰은 만들 수 없다
        Session other = signup(uniqueLogin("mcpser2"));
        String ot = token(other, "WRITE");
        assertThat(call(ot, "add_to_series", Map.of("post_id", f.a(), "series_id", sid)).path("isError").asBoolean()).isTrue();
        long theirs = publish(other, "남의 글", "PUBLIC");
        assertThat(call(t, "add_to_series", Map.of("post_id", theirs, "series_id", sid)).path("isError").asBoolean()).isTrue();
        assertThat(call(token(f.me(), "READ"), "create_series", Map.of("series_name", "읽기")).path("isError").asBoolean()).isTrue();
        // 같은 이름은 거절한다
        assertThat(text(call(t, "create_series", Map.of("series_name", "devlog 만들기")))).contains("이미 있어요");
    }

    @Test
    void 프로젝트_칸은_AI_발행을_허용했을_때만_쓰고_주지_않은_칸은_그대로_둔다() throws Exception {
        Session me = signup(uniqueLogin("mcpprj"));
        String t = token(me, "WRITE");
        long post = publish(me, "공개 글", "PUBLIC");
        long sid = seriesId(text(call(t, "create_series", Map.of("series_name", "포트폴리오"))));
        call(t, "add_to_series", Map.of("post_id", post, "series_id", sid));

        Map<String, Object> fields = Map.of("series_id", sid, "portfolio", true, "period", "2026.09 ~ 2026.10",
                "summary", "개발 블로그", "tech", List.of("Spring Boot", "React", "Spring Boot"),
                "team_work", "팀이 기획과 설계를 함께 했다", "my_role", "검색과 MCP를 맡았다");
        // 허용 전에는 목록에 없고 불러도 막힌다
        assertThat(read(rpc(t, "tools/list", null).andReturn()).path("result").path("tools").findValuesAsString("name"))
                .contains("list_series", "create_series", "add_to_series").doesNotContain("set_series_project");
        JsonNode off = call(t, "set_series_project", fields);
        assertThat(off.path("isError").asBoolean()).isTrue();
        assertThat(text(off)).contains("AI가 발행·삭제하도록 허용");

        jdbc.update("UPDATE member SET ai_publish_allowed = true WHERE id = ?", me.memberId());
        JsonNode on = call(t, "set_series_project", fields);
        assertThat(on.path("isError").asBoolean()).as(text(on)).isFalse();
        assertThat(text(on)).contains("/@" + me.handle() + "/portfolio");

        JsonNode portfolio = read(mvc.perform(get("/api/members/" + me.handle() + "/portfolio")).andReturn());
        JsonNode project = portfolio.path("projects").get(0);
        assertThat(project.path("summary").asString()).isEqualTo("개발 블로그");
        assertThat(project.path("tech").size()).isEqualTo(2);
        assertThat(project.path("myRole").asString()).isEqualTo("검색과 MCP를 맡았다");
        assertThat(project.path("posts").get(0).path("id").asLong()).isEqualTo(post);

        // 한 칸만 고치면 나머지는 그대로, 빈 문자열은 지운다
        call(t, "set_series_project", Map.of("series_id", sid, "summary", ""));
        JsonNode after = read(mvc.perform(get("/api/members/" + me.handle() + "/portfolio")).andReturn()).path("projects").get(0);
        assertThat(after.path("summary").isNull() || after.path("summary").isMissingNode()).isTrue();
        assertThat(after.path("myRole").asString()).isEqualTo("검색과 MCP를 맡았다");
        assertThat(text(call(t, "list_series", Map.of()))).contains("포트폴리오 프로젝트").contains("제 역할: 검색과 MCP를 맡았다")
                .contains("쓴 기술: Spring Boot, React").doesNotContain("한 줄 설명");

        // 남의 시리즈는 없는 것과 같다
        Session other = signup(uniqueLogin("mcpprj2"));
        jdbc.update("UPDATE member SET ai_publish_allowed = true WHERE id = ?", other.memberId());
        assertThat(call(token(other, "WRITE"), "set_series_project", Map.of("series_id", sid, "portfolio", false))
                .path("isError").asBoolean()).isTrue();
    }
}
