package com.team.blog.trade;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import tools.jackson.databind.JsonNode;

import com.team.blog.support.IntegrationTest;

/**
 * #265 매매 재현 쇼츠: 매매 일지에서 고른 체결로 스튜디오에 대본을 만들고, AI가 get_shorts로 체결·메모를 읽고,
 * 워커는 작업에서 체결과 차트 구간을 받는다.
 */
class TradeReplayTest extends IntegrationTest {
    static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final String TOKEN = "test-render-token";

    @BeforeEach
    void emptyQueue() {
        jdbc.update("UPDATE shorts SET status = 'FAILED', claim_token = NULL WHERE status IN ('QUEUED', 'RENDERING')");
    }

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

    long fill(String token, String id, String exchange, String symbol, String currency, LocalDate day, String time, String side, String qty,
              String price, String pnl, String reason) throws Exception {
        Map<String, Object> a = new HashMap<>(Map.of("external_id", id, "exchange", exchange, "symbol", symbol, "symbol_name", "비트코인",
                "currency", currency, "side", side, "quantity", qty, "price", price, "reason", reason, "executed_at", day + "T" + time + "+09:00"));
        if (pnl != null) a.put("realized_pnl", pnl);
        JsonNode r = call(token, "record_trade", a);
        assertThat(r.path("isError").asBoolean()).as(text(r)).isFalse();
        return jdbc.queryForObject("SELECT id FROM trade WHERE external_id = ?", Long.class, id);
    }

    /** 하루에 남긴 체결들: 바이낸스 BTCUSDT 매수 두 번·매도 한 번, 업비트 매수, 주식 매수 */
    record Fills(long buy, long add, long sell, long upbit, long stock) {}

    Fills recordFills(String t, LocalDate d) throws Exception {
        String p = "r" + System.nanoTime() + "-";
        long buy = fill(t, p + "1", "BINANCE", "BTCUSDT", "USDT", d, "10:15:00", "BUY", "0.01", "92100.5", null, "15분봉 20일선 위로 거래량 두 배");
        long add = fill(t, p + "2", "BINANCE", "BTCUSDT", "USDT", d, "11:00:00", "BUY", "0.005", "92300", null, "눌림에서 지지 확인");
        long sell = fill(t, p + "3", "BINANCE", "BTCUSDT", "USDT", d, "13:30:00", "SELL", "0.015", "93050", "13.2", "목표가 1% 도달");
        long upbit = fill(t, p + "4", "UPBIT", "KRW-BTC", "KRW", d, "14:00:00", "BUY", "0.001", "128000000", null, "업비트 따로 매수");
        long stock = fill(t, p + "5", "", "005930", "KRW", d, "14:10:00", "BUY", "1", "71000", null, "주식");
        return new Fills(buy, add, sell, upbit, stock);
    }

    void writeMemo(Session me, LocalDate d) throws Exception {
        me.http().perform(asJson(put("/api/me/trades/" + d + "/memo"), Map.of("memo", "거래량이 붙은 돌파는 따라가도 됐지만, 두 번째 매수는 너무 서둘렀어요.")))
                .andExpect(status().isOk());
    }

    /** 만든다: 체결은 일부러 순서를 섞어 고른다 */
    JsonNode createReplay(Session me, Fills f) throws Exception {
        return read(me.http().perform(asJson(post("/api/me/shorts/replay"), Map.of("tradeIds", List.of(f.sell(), f.buy(), f.add()))))
                .andExpect(status().isCreated()).andReturn());
    }

    @Test
    void 메모가_없거나_고를_수_없는_체결이면_재현_대본을_만들지_않는다() throws Exception {
        Session me = signup(uniqueLogin("replay"));
        String t = token(me);
        LocalDate d = LocalDate.now(KST).minusDays(1);
        Fills f = recordFills(t, d);

        // 메모가 없는 날은 만들지 않는다 (메모를 남긴 날만)
        JsonNode noMemo = read(me.http().perform(asJson(post("/api/me/shorts/replay"), Map.of("tradeIds", List.of(f.buy(), f.sell()))))
                .andExpect(status().isConflict()).andReturn());
        assertThat(noMemo.path("code").asString()).isEqualTo("TRADE_MEMO_REQUIRED");
        writeMemo(me, d);

        // 다른 종목·거래소를 섞거나, 공개 캔들이 없는 거래소, 너무 많은 체결은 막는다
        assertBad(me, Map.of("tradeIds", List.of(f.buy(), f.upbit())), "MIXED_SYMBOL");
        assertBad(me, Map.of("tradeIds", List.of(f.stock())), "EXCHANGE_UNSUPPORTED");
        assertBad(me, Map.of("tradeIds", List.of(1L, 2L, 3L, 4L, 5L, 6L, 7L)), "TOO_MANY");
        assertBad(me, Map.of("tradeIds", List.of(f.buy(), f.sell()), "interval", "1m"), "TOO_LONG");
        assertBad(me, Map.of("tradeIds", List.of(f.buy(), f.sell()), "interval", "2h"), "INVALID");

        // 남의 체결은 고를 수 없다
        Session other = signup(uniqueLogin("replay2"));
        assertBad(other, Map.of("tradeIds", List.of(f.buy())), "NOT_FOUND");
    }

    @Test
    void 고른_체결로_재현_대본을_만든다() throws Exception {
        Session me = signup(uniqueLogin("replay"));
        String t = token(me);
        LocalDate d = LocalDate.now(KST).minusDays(1);
        Fills f = recordFills(t, d);
        writeMemo(me, d);

        // 만든다: 체결은 시간 순으로 복사되고, 봉은 체결 사이가 60개 안에 드는 가장 짧은 봉(3시간 15분 → 5분봉)
        JsonNode item = createReplay(me, f);
        JsonNode replay = item.path("replay");
        assertThat(replay.path("exchange").asString()).isEqualTo("BINANCE");
        assertThat(replay.path("symbol").asString()).isEqualTo("BTCUSDT");
        assertThat(replay.path("interval").asString()).isEqualTo("5m");
        assertThat(replay.path("fills").findValuesAsString("side")).containsExactly("BUY", "BUY", "SELL");
        assertThat(replay.path("fills").get(0).path("tradeId").asLong()).isEqualTo(f.buy());
        assertThat(item.path("title").asString()).contains("비트코인", "매매 다시 보기");
        List<String> narrations = item.path("scenes").findValuesAsString("narration");
        assertThat(narrations).hasSize(3 + 3);
        assertThat(narrations.get(0)).endsWith("돌려 볼게요.").contains("왜");
        assertThat(narrations.get(1)).startsWith("10시 15분,").contains("거래량 두 배", "92,100.50 USDT에 매수했어요");
        assertThat(narrations.get(3)).contains("+13.20 USDT");
        assertThat(narrations.getLast()).contains("두 번째 매수는 너무 서둘렀어요");
        assertThat(narrations).noneMatch(n -> n.contains("투자 권유"));
        assertThat(item.path("estimatedSeconds").asDouble()).isLessThanOrEqualTo(60);
    }

    @Test
    void 재현_대본의_체결과_구간을_AI와_워커가_받는다() throws Exception {
        Session me = signup(uniqueLogin("replay"));
        String t = token(me);
        LocalDate d = LocalDate.now(KST).minusDays(1);
        Fills f = recordFills(t, d);
        writeMemo(me, d);
        long id = createReplay(me, f).path("id").asLong();

        // AI는 get_shorts로 고른 체결·메모·다시 쓰기 규칙을 읽는다
        String got = text(call(t, "get_shorts", Map.of("shorts_id", id)));
        assertThat(got).contains("매매 재현", "바이낸스", "5분봉", "목표가 1% 도달", "두 번째 매수는 너무 서둘렀어요", "지금 사는 이유", "update_shorts_script");
        assertThat(text(call(t, "list_shorts", Map.of()))).contains("매매 재현(체결 3개)");

        // 대본을 고쳐도 체결·구간은 그대로
        JsonNode edited = read(me.http().perform(asJson(put("/api/me/shorts/" + id), Map.of("title", "돌파 매수 복기"))).andExpect(status().isOk()).andReturn());
        assertThat(edited.path("replay").path("fills")).hasSize(3);

        // 워커는 작업에서 체결과 차트 구간을 받는다 (체결을 지워도 복사본은 남는다)
        me.http().perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/me/trades/fills/" + f.add())
                .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf())).andExpect(status().isNoContent());
        jdbc.update("UPDATE shorts SET status = 'QUEUED', queued_at = now() WHERE id = ?", id);
        JsonNode job = read(mvc.perform(post("/api/render/jobs/claim").header("X-Render-Token", TOKEN)).andExpect(status().isOk()).andReturn());
        assertThat(job.path("id").asLong()).isEqualTo(id);
        assertThat(job.path("replay").path("interval").asString()).isEqualTo("5m");
        assertThat(job.path("replay").path("fills")).hasSize(3);
        assertThat(job.path("replay").path("from").asString()).isNotBlank();
    }

    @Test
    void 글_쇼츠는_replay가_비어_있다() throws Exception {
        Session me = signup(uniqueLogin("replay"));
        JsonNode plain = read(me.http().perform(asJson(post("/api/me/shorts"), Map.of("title", "글 쇼츠", "scenes", List.of())))
                .andExpect(status().isCreated()).andReturn());
        assertThat(plain.path("replay").isNull()).isTrue();
        assertThat(read(me.http().perform(get("/api/me/shorts/" + plain.path("id").asLong())).andReturn()).path("replay").isNull()).isTrue();
    }

    private void assertBad(Session s, Map<String, Object> body, String code) throws Exception {
        JsonNode r = read(s.http().perform(asJson(post("/api/me/shorts/replay"), body)).andExpect(status().isBadRequest()).andReturn());
        assertThat(r.path("errors").findValuesAsString("code")).as(r.toString()).contains(code);
    }
}
