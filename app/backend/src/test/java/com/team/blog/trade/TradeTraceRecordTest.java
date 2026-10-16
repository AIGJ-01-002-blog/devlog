package com.team.blog.trade;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import tools.jackson.databind.JsonNode;

import com.team.blog.support.IntegrationTest;

/**
 * #283·#284 봇 판단 흔적: 진입 체결에 기법 버전·거름망·비중·손절익절을, 정리 체결에 정리 이유·R배수·펀딩비를 남긴다.
 * 흔적이 없는 옛 호출은 지금처럼 남는다.
 */
class TradeTraceRecordTest extends IntegrationTest {
    static final ZoneId KST = ZoneId.of("Asia/Seoul");

    String token(Session s) throws Exception {
        return read(s.http().perform(asJson(post("/api/me/tokens"), Map.of("name", "trade-bot", "scope", "WRITE")))
                .andExpect(status().isCreated()).andReturn()).path("secret").asString();
    }

    JsonNode call(String token, String tool, Map<String, Object> args) throws Exception {
        Map<String, Object> body = Map.of("jsonrpc", "2.0", "id", 1, "method", "tools/call", "params", Map.of("name", tool, "arguments", args));
        return read(mvc.perform(post("/api/mcp").contentType(MediaType.APPLICATION_JSON).header("Authorization", "Bearer " + token)
                .content(json.writeValueAsString(body))).andExpect(status().isOk()).andReturn()).path("result");
    }

    static String text(JsonNode result) {
        return result.path("content").get(0).path("text").asString();
    }

    static String at(LocalDate day, String time) {
        return day + "T" + time + "+09:00";
    }

    Map<String, Object> entry(String id, LocalDate d) {
        Map<String, Object> a = new HashMap<>(Map.of("external_id", id, "exchange", "BINANCE", "currency", "USDT", "market", "FUTURES",
                "position_side", "LONG", "leverage", 3, "symbol", "BTCUSDT", "side", "BUY", "quantity", "0.01", "price", 60000));
        a.put("executed_at", at(d, "09:00:00"));
        a.putAll(Map.of("strategy_version", "ladder-v0.1", "decision_id", "20261015T09-BTC-1h", "basket", "TRADING", "regime", "up",
                "setup", "PULLBACK", "trigger", "BREAKOUT10", "news_score", -1, "news_flags", "MACRO_EVENT", "risk_pct", 0.005, "kelly_raw", 0.08));
        a.putAll(Map.of("kelly_used", 0.02, "ai_conf", 0.75, "equity_at_entry", 300000, "stop_price", 58800, "tp1_price", 61800,
                "tp2_price", 63000, "liquidation_price", 40500, "ai_model", "gemini-2.5-flash-lite", "ai_cost", 0.3));
        a.put("reason", "{\"filter\":\"UP>PULLBACK>BREAKOUT10\",\"note\":\"" + "눌림 뒤 돌파 ".repeat(40) + "\"}");
        return a;
    }

    @Test
    void 진입과_정리_체결에_판단_흔적을_남기고_같은_판단_키로_읽는다() throws Exception {
        Session me = signup(uniqueLogin("trace"));
        String t = token(me);
        LocalDate d = LocalDate.now(KST).minusDays(1);

        JsonNode in = call(t, "record_trade", entry("tr-1", d));
        assertThat(in.path("isError").asBoolean()).as(text(in)).isFalse();
        Map<String, Object> out = new HashMap<>(Map.of("external_id", "tr-2", "exchange", "BINANCE", "currency", "USDT", "market", "FUTURES",
                "position_side", "LONG", "leverage", 3, "symbol", "BTCUSDT", "side", "SELL", "quantity", "0.01", "price", 61800));
        out.putAll(Map.of("executed_at", at(d, "15:00:00"), "realized_pnl", 18, "decision_id", "20261015T09-BTC-1h", "exit_reason", "TP1",
                "r_multiple", 1.8, "funding", -0.12, "fee", 0.5));
        JsonNode exit = call(t, "record_trade", out);
        assertThat(exit.path("isError").asBoolean()).as(text(exit)).isFalse();
        // 흔적 없는 옛 호출도 그대로 남는다
        assertThat(call(t, "record_trade", Map.of("external_id", "tr-3", "symbol", "005930", "side", "BUY", "quantity", 1, "price", 70000,
                "executed_at", at(d, "16:00:00"))).path("isError").asBoolean()).isFalse();

        JsonNode fills = read(me.http().perform(get("/api/me/trades/" + d)).andExpect(status().isOk()).andReturn()).path("fills");
        JsonNode a = fills.get(0).path("trace");
        assertThat(a.path("strategyVersion").asString()).isEqualTo("ladder-v0.1");
        assertThat(a.path("basket").asString()).isEqualTo("TRADING");
        assertThat(a.path("regime").asString()).isEqualTo("UP");
        assertThat(a.path("newsScore").asInt()).isEqualTo(-1);
        assertThat(a.path("riskPct").decimalValue()).isEqualByComparingTo("0.005");
        assertThat(a.path("stopPrice").decimalValue()).isEqualByComparingTo("58800");
        assertThat(a.path("liquidationPrice").decimalValue()).isEqualByComparingTo("40500");
        assertThat(fills.get(0).path("reason").asString()).hasSizeGreaterThan(300);
        JsonNode b = fills.get(1).path("trace");
        assertThat(b.path("decisionId").asString()).isEqualTo(a.path("decisionId").asString());
        assertThat(b.path("exitReason").asString()).isEqualTo("TP1");
        assertThat(b.path("rMultiple").decimalValue()).isEqualByComparingTo("1.8");
        assertThat(b.path("funding").decimalValue()).isEqualByComparingTo("-0.12");
        // 기간 성적과 AI가 읽는 하루 기록에도 정리 이유·R이 보인다 (#285)
        JsonNode setups = read(me.http().perform(get("/api/me/trades?days=7")).andExpect(status().isOk()).andReturn()).path("setups");
        assertThat(setups).hasSize(1);
        // 정리 체결에 기법이 비어 있어도 같은 decision_id의 진입 체결 기법으로 센다
        assertThat(setups.get(0).path("mode").asString()).isEqualTo("PAPER");
        assertThat(setups.get(0).path("strategyVersion").asString()).isEqualTo("ladder-v0.1");
        assertThat(setups.get(0).path("setup").asString()).isEqualTo("PULLBACK");
        assertThat(setups.get(0).path("samples").asInt()).isEqualTo(1);
        assertThat(setups.get(0).path("avgR").decimalValue()).isEqualByComparingTo("1.8");
        assertThat(text(call(t, "get_trade_day", Map.of("date", d.toString())))).contains("트레이딩 · PULLBACK", "TP1 정리 +1.8R");
        JsonNode c = fills.get(2).path("trace");
        assertThat(c.path("strategyVersion").asString()).isEmpty();
        assertThat(c.path("basket").isNull()).isTrue();

        // 진입이 기간 앞(어제)에 있어도 오늘 정리 체결은 그 진입의 기법으로 센다
        Map<String, Object> late = new HashMap<>(out);
        late.putAll(Map.of("external_id", "tr-4", "executed_at", at(d.plusDays(1), "00:00:01"), "r_multiple", -1, "exit_reason", "STOP"));
        assertThat(call(t, "record_trade", late).path("isError").asBoolean()).isFalse();
        JsonNode today = read(me.http().perform(get("/api/me/trades?days=1")).andExpect(status().isOk()).andReturn()).path("setups");
        assertThat(today).hasSize(1);
        assertThat(today.get(0).path("strategyVersion").asString()).isEqualTo("ladder-v0.1");
        assertThat(today.get(0).path("samples").asInt()).isEqualTo(1);
        // 하루 응답에는 기간 앞 진입 체결을 싣지 않는다
        JsonNode day = read(me.http().perform(get("/api/me/trades/" + d.plusDays(1))).andExpect(status().isOk()).andReturn());
        assertThat(day.has("earlier")).isFalse();
        assertThat(day.path("fills")).hasSize(1);
    }

    @Test
    void 판단_흔적의_범위와_자리를_검사한다() throws Exception {
        Session me = signup(uniqueLogin("trace"));
        String t = token(me);
        LocalDate d = LocalDate.now(KST).minusDays(1);

        Map<String, Object> risky = entry("bad-1", d);
        risky.put("risk_pct", 1.5);
        risky.put("ai_conf", -0.1);
        assertThat(text(call(t, "record_trade", risky))).contains("risk_pct", "ai_conf");

        Map<String, Object> spot = new HashMap<>(Map.of("external_id", "bad-2", "symbol", "BTCUSDT", "side", "BUY", "quantity", 1, "price", 100,
                "liquidation_price", 50, "funding", 1, "exit_reason", "STOP", "r_multiple", -1));
        JsonNode r = call(t, "record_trade", spot);
        assertThat(r.path("isError").asBoolean()).isTrue();
        assertThat(text(r)).contains("청산가", "펀딩비", "정리 이유", "R배수");

        Map<String, Object> badEnum = new HashMap<>(entry("bad-3", d));
        badEnum.put("basket", "SWING");
        assertThat(text(call(t, "record_trade", badEnum))).contains("basket");

        Map<String, Object> secret = new HashMap<>(entry("bad-4", d));
        secret.put("reason", "키 sk-" + "a".repeat(30) + " 로 주문");
        JsonNode s = call(t, "record_trade", secret);
        assertThat(s.path("isError").asBoolean()).isTrue();
        assertThat(text(s)).contains("남기지 않았어요");

        JsonNode overview = read(me.http().perform(get("/api/me/trades?days=7")).andExpect(status().isOk()).andReturn());
        assertThat(overview.path("days")).isEmpty();
    }
}
