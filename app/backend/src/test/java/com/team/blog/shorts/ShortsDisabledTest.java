package com.team.blog.shorts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;

import com.team.blog.support.IntegrationTest;

/** spec 099: SHORTS_ENABLED가 꺼져 있으면(2.0.0 전 운영 기본) 화면 메뉴·API·워커 API·MCP 도구가 모두 없는 것과 같다 */
@TestPropertySource(properties = "blog.shorts.enabled=false")
class ShortsDisabledTest extends IntegrationTest {

    @Test
    void 꺼져_있으면_어디에도_보이지_않는다() throws Exception {
        Session me = signup(uniqueLogin("shoff"));
        assertThat(read(me.http().perform(get("/api/auth/me")).andReturn()).path("features").path("shorts").asBoolean()).isFalse();
        me.http().perform(get("/api/me/shorts")).andExpect(status().isNotFound());
        me.http().perform(asJson(post("/api/me/shorts"), Map.of("title", "x", "scenes", java.util.List.of()))).andExpect(status().isNotFound());
        mvc.perform(post("/api/render/jobs/claim").header("X-Render-Token", "test-render-token")).andExpect(status().isNotFound());

        String token = read(me.http().perform(asJson(post("/api/me/tokens"), Map.of("name", "Claude Code", "scope", "WRITE")))
                .andExpect(status().isCreated()).andReturn()).path("secret").asString();
        Map<String, Object> body = new HashMap<>();
        body.put("jsonrpc", "2.0");
        body.put("id", 1);
        body.put("method", "tools/list");
        var list = read(mvc.perform(post("/api/mcp").contentType(MediaType.APPLICATION_JSON).header("Authorization", "Bearer " + token)
                .content(json.writeValueAsString(body))).andReturn()).path("result").path("tools");
        var tools = list.findValuesAsString("name");
        assertThat(tools).contains("write_devlog").doesNotContain("create_shorts_script", "list_shorts", "list_shorts_candidates");
        // 쇼츠가 꺼진 서버에서는 일반 도구 설명에도 쇼츠 안내가 없다 (#240)
        assertThat(list.findValuesAsString("description")).noneMatch(d -> d.contains("쇼츠"));
    }
}
