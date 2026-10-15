package com.team.blog.trade;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import tools.jackson.databind.JsonNode;

import com.team.blog.support.IntegrationTest;

/** spec 101: 봇이 report_trade_bot으로 알린 모의 계좌·체결·Gemini 판단·요약이 /trade 화면 API에 보이고, 매매 일지와 섞이지 않는다 */
class TradeBotSimTest extends IntegrationTest {
    static final ZoneId KST = ZoneId.of("Asia/Seoul");

    String token(Session s) throws Exception {
        return read(s.http().perform(asJson(post("/api/me/tokens"), Map.of("name", "trade-bot", "scope", "WRITE")))
                .andExpect(status().isCreated()).andReturn()).path("secret").asString();
    }

    JsonNode call(String token, Map<String, Object> args) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("jsonrpc", "2.0");
        body.put("id", 1);
        body.put("method", "tools/call");
        body.put("params", Map.of("name", "report_trade_bot", "arguments", args));
        return read(mvc.perform(post("/api/mcp").contentType(MediaType.APPLICATION_JSON).header("Authorization", "Bearer " + token)
                .content(json.writeValueAsString(body))).andExpect(status().isOk()).andReturn()).path("result");
    }

    static String text(JsonNode result) {
        return result.path("content").get(0).path("text").asString();
    }

    static String ago(int minutes) {
        return OffsetDateTime.now(KST).minusMinutes(minutes).truncatedTo(ChronoUnit.SECONDS).toString();
    }

    Map<String, Object> report() {
        return Map.of(
                "bot_version", "1.0.0",
                "ai", Map.of("enabled", true, "model", "gemini-2.5-flash", "calls_today", 12),
                "accounts", List.of(Map.of("account", "trade-state-binance-spot-1h.json", "exchange", "BINANCE", "market", "spot",
                        "currency", "USDT", "cash", "900.5", "unrealized_pnl", "-1.25",
                        "positions", List.of(Map.of("symbol", "BTCUSDT", "quantity", "0.0015", "avg_price", "66000", "price", "65200",
                                "unrealized_pnl", "-1.2")),
                        "rules", Map.of("전략", "20봉 이동평균 돌파", "봉", "1h", "손절", "3%"))),
                "events", List.of(
                        Map.of("id", "f-1", "kind", "FILL", "at", ago(30), "account", "trade-state-binance-spot-1h.json", "symbol", "BTCUSDT",
                                "side", "BUY", "quantity", "0.0015", "price", "66000", "currency", "USDT", "text", "종가가 20봉 평균 위로"),
                        Map.of("id", "ai-1", "kind", "AI", "at", ago(31), "symbol", "BTCUSDT", "verdict", "ALLOW", "text", "거래량이 받쳐 줘요"),
                        Map.of("id", "ai-2", "kind", "AI", "at", ago(20), "symbol", "ETHUSDT", "verdict", "BLOCK", "text", "급등 직후라 쉬어요"),
                        Map.of("id", "sum-1", "kind", "SUMMARY", "at", ago(5), "text", "오늘 매수 1건, 막은 진입 1건")));
    }

    @Test
    void 봇이_알린_모의_상태가_화면에_보인다() throws Exception {
        Session me = signup(uniqueLogin("tbot"));
        String token = token(me);
        JsonNode r = call(token, report());
        assertThat(r.path("isError").asBoolean()).isFalse();
        assertThat(text(r)).contains("모의 계좌 1개", "새 사건 4건", "/trade");

        // 다시 보내도 사건은 한 번만
        assertThat(text(call(token, report()))).contains("새 사건 0건", "이미 있던 4건");

        JsonNode v = read(me.http().perform(get("/api/me/trade-bot")).andExpect(status().isOk()).andReturn());
        assertThat(v.path("status").path("aiEnabled").asBoolean()).isTrue();
        assertThat(v.path("status").path("aiModel").asString()).isEqualTo("gemini-2.5-flash");
        JsonNode acc = v.path("accounts").get(0);
        assertThat(acc.path("currency").asString()).isEqualTo("USDT");
        assertThat(acc.path("market").asString()).isEqualTo("SPOT");
        assertThat(acc.path("positions").get(0).path("avgPrice").decimalValue()).isEqualByComparingTo("66000");
        assertThat(acc.path("rules").findValuesAsString("label")).containsExactlyInAnyOrder("전략", "봉", "손절");
        assertThat(v.path("fills").size()).isEqualTo(1);
        assertThat(v.path("fills").get(0).path("text").asString()).isEqualTo("종가가 20봉 평균 위로");
        assertThat(v.path("decisions").findValuesAsString("verdict")).containsExactly("BLOCK", "ALLOW");
        assertThat(v.path("summaries").get(0).path("text").asString()).contains("막은 진입 1건");

        // 매매 일지에는 섞이지 않는다
        Long journal = jdbc.queryForObject("SELECT count(*) FROM trade t JOIN member m ON m.id = t.member_id WHERE m.id = "
                + "(SELECT member_id FROM trade_bot_status ORDER BY reported_at DESC LIMIT 1)", Long.class);
        assertThat(journal).isZero();

        // 계좌 목록을 빼면 계좌는 그대로, 빈 목록이면 지운다
        call(token, Map.of("events", List.of()));
        assertThat(read(me.http().perform(get("/api/me/trade-bot")).andReturn()).path("accounts").size()).isEqualTo(1);
        call(token, Map.of("accounts", List.of()));
        assertThat(read(me.http().perform(get("/api/me/trade-bot")).andReturn()).path("accounts").size()).isZero();
    }

    @Test
    void 실계좌와_잘못된_값은_받지_않는다() throws Exception {
        Session me = signup(uniqueLogin("tbotx"));
        String token = token(me);
        JsonNode live = call(token, Map.of("accounts", List.of(Map.of("account", "trade-live-binance-spot-1h.json", "currency", "USDT", "cash", 1))));
        assertThat(live.path("isError").asBoolean()).isTrue();
        assertThat(text(live)).contains("실계좌");

        JsonNode badVerdict = call(token, Map.of("events", List.of(Map.of("id", "x", "kind", "AI", "verdict", "MAYBE"))));
        assertThat(badVerdict.path("isError").asBoolean()).isTrue();

        // 잘못된 사건이 하나라도 있으면 아무것도 남기지 않는다
        call(token, Map.of("events", List.of(
                Map.of("id", "ok-1", "kind", "SUMMARY", "text", "요약"),
                Map.of("id", "bad", "kind", "FILL"))));
        assertThat(read(me.http().perform(get("/api/me/trade-bot")).andReturn()).path("summaries").size()).isZero();

        // 한 번도 알리지 않은 회원은 빈 화면
        Session other = signup(uniqueLogin("tbote"));
        JsonNode empty = read(other.http().perform(get("/api/me/trade-bot")).andExpect(status().isOk()).andReturn());
        assertThat(empty.path("status").path("reportedAt").isNull()).isTrue();
        assertThat(empty.path("accounts").size()).isZero();
    }
}
