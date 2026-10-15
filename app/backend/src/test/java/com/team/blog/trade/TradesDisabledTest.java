package com.team.blog.trade;

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

/** #243: TRADES_ENABLED가 꺼져 있으면(2.0.0 전 운영 기본) 화면 메뉴·API·MCP 도구가 모두 없는 것과 같다 */
// 기능을 끈 시험 셋은 같은 설정으로 Spring 문맥 하나를 함께 쓴다(문맥마다 DB 연결 풀이 생겨 시험 DB 연결 수를 넘지 않게, #266)
@TestPropertySource(properties = {"blog.shorts.enabled=false", "blog.trades.enabled=false", "blog.points.enabled=false"})
class TradesDisabledTest extends IntegrationTest {

    @Test
    void 꺼져_있으면_어디에도_보이지_않는다() throws Exception {
        Session me = signup(uniqueLogin("troff"));
        assertThat(read(me.http().perform(get("/api/auth/me")).andReturn()).path("features").path("trades").asBoolean()).isFalse();
        me.http().perform(get("/api/me/trades")).andExpect(status().isNotFound());
        me.http().perform(get("/api/me/trades/2026-10-14")).andExpect(status().isNotFound());
        me.http().perform(get("/api/me/trade-bot")).andExpect(status().isNotFound());

        String token = read(me.http().perform(asJson(post("/api/me/tokens"), Map.of("name", "trade-bot", "scope", "WRITE")))
                .andExpect(status().isCreated()).andReturn()).path("secret").asString();
        Map<String, Object> body = new HashMap<>();
        body.put("jsonrpc", "2.0");
        body.put("id", 1);
        body.put("method", "tools/list");
        var tools = read(mvc.perform(post("/api/mcp").contentType(MediaType.APPLICATION_JSON).header("Authorization", "Bearer " + token)
                .content(json.writeValueAsString(body))).andReturn()).path("result").path("tools").findValuesAsString("name");
        assertThat(tools).contains("write_devlog").doesNotContain("record_trade", "get_trade_day", "report_trade_bot");
        body.put("method", "tools/call");
        body.put("params", Map.of("name", "record_trade", "arguments", Map.of()));
        var called = read(mvc.perform(post("/api/mcp").contentType(MediaType.APPLICATION_JSON).header("Authorization", "Bearer " + token)
                .content(json.writeValueAsString(body))).andReturn()).path("result");
        assertThat(called.path("isError").asBoolean()).isTrue();
        assertThat(called.path("content").get(0).path("text").asString()).contains("없는 도구예요");
    }
}
