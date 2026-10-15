package com.team.blog.trade;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import tools.jackson.databind.JsonNode;

import com.team.blog.support.IntegrationTest;
import com.team.blog.trade.application.TradeJournal;

/**
 * #243 매매 일지: 봇이 MCP record_trade로 체결을 남기고, 회원이 날별로 보고, 메모를 남겨 요약 임시글과 쇼츠 대본을 만든다.
 */
class TradeJournalTest extends IntegrationTest {
    static final ZoneId KST = ZoneId.of("Asia/Seoul");

    @Autowired
    TradeJournal journal;

    String token(Session s) throws Exception {
        return read(s.http().perform(asJson(post("/api/me/tokens"), Map.of("name", "trade-bot", "scope", "WRITE")))
                .andExpect(status().isCreated()).andReturn()).path("secret").asString();
    }

    JsonNode rpc(String token, String method, Object params) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("jsonrpc", "2.0");
        body.put("id", 1);
        body.put("method", method);
        if (params != null) body.put("params", params);
        return read(mvc.perform(post("/api/mcp").contentType(MediaType.APPLICATION_JSON).header("Authorization", "Bearer " + token)
                .content(json.writeValueAsString(body))).andExpect(status().isOk()).andReturn()).path("result");
    }

    JsonNode call(String token, String tool, Map<String, Object> args) throws Exception {
        return rpc(token, "tools/call", Map.of("name", tool, "arguments", args));
    }

    static String text(JsonNode result) {
        return result.path("content").get(0).path("text").asString();
    }

    static String at(LocalDate day, String time) {
        return day + "T" + time + "+09:00";
    }

    JsonNode trade(String token, String id, LocalDate day, String time, String symbol, String name, String side, int qty, int price,
                   Integer pnl, String reason) throws Exception {
        Map<String, Object> a = new HashMap<>(Map.of("external_id", id, "symbol", symbol, "symbol_name", name, "side", side,
                "quantity", qty, "price", price, "fee", 150, "reason", reason, "executed_at", at(day, time)));
        if (pnl != null) a.put("realized_pnl", pnl);
        return call(token, "record_trade", a);
    }

    static final String MEMO_D1 = "삼성전자는 계획대로 2%에서 팔았는데, 카카오는 거래량만 보고 따라 샀어요. 다음에는 거래량과 함께 추세도 확인하려고 해요.";
    static final String MEMO_D2 = "카카오 손절은 아팠지만 -3% 규칙을 지켰고, SK하이닉스는 갭 상승 뒤 눌림을 기다린 덕분에 만회했어요.";

    /** 이틀 동안의 체결: 첫날 삼성전자 매수·매도와 카카오 매수, 다음 날 카카오 손절과 SK하이닉스 매수·매도 */
    void recordTwoDays(String t, LocalDate d1, LocalDate d2) throws Exception {
        trade(t, "o-1", d1, "09:05:00", "005930", "삼성전자", "BUY", 10, 71000, null, "20일 이동평균선 위로 올라섬");
        trade(t, "o-2", d1, "13:40:00", "005930", "삼성전자", "SELL", 10, 72500, 14600, "목표 수익 2% 도달");
        trade(t, "o-3", d1, "14:10:00", "035720", "카카오", "BUY", 20, 41000, null, "거래량이 평소의 3배");
        trade(t, "o-4", d2, "09:30:00", "035720", "카카오", "SELL", 20, 39800, -24300, "손절선 -3% 도달");
        trade(t, "o-5", d2, "10:15:00", "000660", "SK하이닉스", "BUY", 3, 182000, null, "갭 상승 뒤 첫 눌림");
        trade(t, "o-6", d2, "15:10:00", "000660", "SK하이닉스", "SELL", 3, 185500, 10200, "장 마감 전 정리");
    }

    void writeMemo(Session me, LocalDate d, String memo) throws Exception {
        me.http().perform(asJson(put("/api/me/trades/" + d + "/memo"), Map.of("memo", memo))).andExpect(status().isOk());
    }

    long createDraft(Session me, LocalDate d) throws Exception {
        return read(me.http().perform(post("/api/me/trades/" + d + "/draft").with(csrf())).andExpect(status().isOk()).andReturn())
                .path("postId").asLong();
    }

    long createShorts(Session me, LocalDate d) throws Exception {
        return read(me.http().perform(post("/api/me/trades/" + d + "/shorts").with(csrf())).andExpect(status().isOk()).andReturn())
                .path("shortsId").asLong();
    }

    @Test
    void 잘못된_체결은_이유를_알려_주고_남기지_않는다() throws Exception {
        Session me = signup(uniqueLogin("trade"));
        String t = token(me);
        JsonNode bad = call(t, "record_trade", Map.of("external_id", "bad-1", "symbol", "삼성", "side", "BUY", "quantity", 1, "price", 100,
                "realized_pnl", 10));
        assertThat(bad.path("isError").asBoolean()).isTrue();
        assertThat(text(bad)).contains("종목 코드", "실현 손익은 매도");
        JsonNode overview = read(me.http().perform(get("/api/me/trades?days=7")).andExpect(status().isOk()).andReturn());
        assertThat(overview.path("days")).isEmpty();
    }

    @Test
    void 봇이_남긴_체결을_날별로_요약하고_누적_손익을_낸다() throws Exception {
        Session me = signup(uniqueLogin("trade"));
        String t = token(me);
        LocalDate d1 = LocalDate.now(KST).minusDays(2);
        LocalDate d2 = LocalDate.now(KST).minusDays(1);

        // 도구가 보이고, 같은 체결은 한 번만 남는다
        assertThat(rpc(t, "tools/list", null).path("tools").findValuesAsString("name")).contains("record_trade", "get_trade_day");
        JsonNode first = trade(t, "o-1", d1, "09:05:00", "005930", "삼성전자", "BUY", 10, 71000, null, "20일 이동평균선 위로 올라섬");
        assertThat(first.path("isError").asBoolean()).as(text(first)).isFalse();
        assertThat(text(first)).contains("체결을 남겼어요", "/trades?date=" + d1);
        assertThat(text(trade(t, "o-1", d1, "09:05:00", "005930", "삼성전자", "BUY", 10, 71000, null, "같은 주문"))).contains("이미 남긴 체결");
        trade(t, "o-2", d1, "13:40:00", "005930", "삼성전자", "SELL", 10, 72500, 14600, "목표 수익 2% 도달");
        trade(t, "o-3", d1, "14:10:00", "035720", "카카오", "BUY", 20, 41000, null, "거래량이 평소의 3배");

        trade(t, "o-4", d2, "09:30:00", "035720", "카카오", "SELL", 20, 39800, -24300, "손절선 -3% 도달");
        trade(t, "o-5", d2, "10:15:00", "000660", "SK하이닉스", "BUY", 3, 182000, null, "갭 상승 뒤 첫 눌림");
        trade(t, "o-6", d2, "15:10:00", "000660", "SK하이닉스", "SELL", 3, 185500, 10200, "장 마감 전 정리");

        // 날별 요약과 누적 실현 손익
        JsonNode overview = read(me.http().perform(get("/api/me/trades?days=7")).andExpect(status().isOk()).andReturn());
        assertThat(overview.path("days")).hasSize(2);
        JsonNode latest = overview.path("days").get(0);
        assertThat(latest.path("date").asString()).isEqualTo(d2.toString());
        assertThat(latest.path("summary").path("trades").asInt()).isEqualTo(3);
        assertThat(latest.path("summary").path("realizedPnl").path("KRW").decimalValue()).isEqualByComparingTo("-14100");
        assertThat(latest.path("cumulativePnl").path("KRW").decimalValue()).isEqualByComparingTo("500");
        assertThat(overview.path("total").path("wins").asInt()).isEqualTo(2);
        assertThat(overview.path("total").path("losses").asInt()).isEqualTo(1);
        assertThat(overview.path("live").asBoolean()).isFalse();
    }

    @Test
    void 메모를_남기면_하루_요약_임시글을_하나만_만든다() throws Exception {
        Session me = signup(uniqueLogin("trade"));
        String t = token(me);
        LocalDate d1 = LocalDate.now(KST).minusDays(2);
        recordTwoDays(t, d1, LocalDate.now(KST).minusDays(1));

        // 메모가 없으면 쇼츠 대본을 만들지 않는다 (숫자만 읽는 영상은 양산형)
        me.http().perform(post("/api/me/trades/" + d1 + "/shorts").with(csrf())).andExpect(status().isConflict());

        // 요약 임시글: 고지·체결 표·메모가 들어간 비공개 임시글. 다시 눌러도 같은 글
        writeMemo(me, d1, MEMO_D1);
        long postId = createDraft(me, d1);
        assertThat(postId).isPositive();
        assertThat(read(me.http().perform(post("/api/me/trades/" + d1 + "/draft").with(csrf())).andReturn()).path("postId").asLong()).isEqualTo(postId);
        Map<String, Object> post = jdbc.queryForMap("SELECT title, content_md, status FROM post WHERE id = ?", postId);
        assertThat(post).containsEntry("status", "DRAFT");
        assertThat((String) post.get("title")).isEqualTo("매매 일지 " + d1 + " (모의투자): 실현 손익 +14,600원");
        assertThat((String) post.get("content_md")).contains("투자 권유가 아니며", "## 오늘 메모", "거래량과 함께 추세도",
                "| 09:05 | 삼성전자 | 매수 | 10 | 71,000원 |  | 20일 이동평균선 위로 올라섬 |",
                "| 13:40 | 삼성전자 | 매도 | 10 | 72,500원 | +14,600원 | 목표 수익 2% 도달 |",
                "- 지금까지 실현 손익: +14,600원");
    }

    @Test
    void 메모를_남긴_날의_쇼츠_대본은_요약_글을_잇고_양산형_검사를_통과한다() throws Exception {
        Session me = signup(uniqueLogin("trade"));
        String t = token(me);
        LocalDate d1 = LocalDate.now(KST).minusDays(2);
        recordTwoDays(t, d1, LocalDate.now(KST).minusDays(1));
        writeMemo(me, d1, MEMO_D1);
        long postId = createDraft(me, d1);

        // 쇼츠 대본: 요약 글을 원본으로 잇고, 양산형 검사를 통과한다
        long shorts1 = createShorts(me, d1);
        JsonNode item = read(me.http().perform(get("/api/me/shorts/" + shorts1)).andExpect(status().isOk()).andReturn());
        assertThat(item.path("postId").asLong()).isEqualTo(postId);
        assertThat(item.path("estimatedSeconds").asDouble()).isLessThanOrEqualTo(60);
        assertThat(item.path("scenes").findValuesAsString("narration")).anyMatch(n -> n.startsWith("제가 오늘 느낀 건,"))
                .anyMatch(n -> n.contains("투자 권유가 아니라"));
        JsonNode check1 = read(me.http().perform(post("/api/me/shorts/" + shorts1 + "/check").with(csrf())).andExpect(status().isOk()).andReturn());
        assertThat(check1.path("passed").asBoolean()).as(check1.toString()).isTrue();
    }

    @Test
    void 다음_날_대본과_AI가_읽는_하루_기록을_만들고_잘못_보낸_체결을_지운다() throws Exception {
        Session me = signup(uniqueLogin("trade"));
        String t = token(me);
        LocalDate d1 = LocalDate.now(KST).minusDays(2);
        LocalDate d2 = LocalDate.now(KST).minusDays(1);
        recordTwoDays(t, d1, d2);
        writeMemo(me, d1, MEMO_D1);
        createDraft(me, d1);
        createShorts(me, d1);

        // 다음 날 대본도 앞날 대본과 겹친다고 막히지 않는다 (메모와 체결이 다르다)
        writeMemo(me, d2, MEMO_D2);
        long shorts2 = createShorts(me, d2);
        JsonNode check2 = read(me.http().perform(post("/api/me/shorts/" + shorts2 + "/check").with(csrf())).andExpect(status().isOk()).andReturn());
        assertThat(check2.path("passed").asBoolean()).as(check2.toString()).isTrue();

        // AI가 읽는 하루 기록
        String day = text(call(t, "get_trade_day", Map.of("date", d2.toString())));
        assertThat(day).contains("체결 3건", "카카오(035720) 매도 20주 @ 39800원", "손익 -24300원", "사용자 메모: 카카오 손절은", "투자 권유");

        // 잘못 보낸 체결을 지우면 요약도 바뀐다. 남의 체결은 지울 수 없다
        long fill = read(me.http().perform(get("/api/me/trades/" + d2)).andReturn()).path("fills").get(1).path("id").asLong();
        Session other = signup(uniqueLogin("trade2"));
        other.http().perform(delete("/api/me/trades/fills/" + fill).with(csrf())).andExpect(status().isNotFound());
        assertThat(read(other.http().perform(get("/api/me/trades")).andReturn()).path("days")).isEmpty();
        me.http().perform(delete("/api/me/trades/fills/" + fill).with(csrf())).andExpect(status().isNoContent());
        assertThat(read(me.http().perform(get("/api/me/trades/" + d2)).andReturn()).path("summary").path("trades").asInt()).isEqualTo(2);
    }

    @Test
    void 코인_거래소_체결은_소수_수량과_통화를_그대로_남기고_합계를_통화별로_낸다() throws Exception {
        Session me = signup(uniqueLogin("tradecoin"));
        String t = token(me);
        LocalDate d = LocalDate.now(KST).minusDays(1);
        Map<String, Object> buy = new HashMap<>(Map.of("external_id", "bn-1", "exchange", "binance", "symbol", "BTCUSDT", "symbol_name", "비트코인",
                "side", "BUY", "quantity", "0.0012", "price", "83752.3", "currency", "usdt", "fee", "0.1005"));
        buy.put("executed_at", at(d, "09:00:00"));
        buy.put("reason", "1시간봉 20개 평균 위로");
        JsonNode r = call(t, "record_trade", buy);
        assertThat(r.path("isError").asBoolean()).as(text(r)).isFalse();
        Map<String, Object> sell = new HashMap<>(Map.of("external_id", "bn-2", "exchange", "BINANCE", "symbol", "BTCUSDT", "symbol_name", "비트코인",
                "side", "SELL", "quantity", "0.0012", "price", "84800", "currency", "USDT", "fee", "0.1018"));
        sell.put("executed_at", at(d, "15:00:00"));
        sell.put("realized_pnl", "1.0549");
        assertThat(call(t, "record_trade", sell).path("isError").asBoolean()).isFalse();
        Map<String, Object> eth = new HashMap<>(Map.of("external_id", "up-1", "exchange", "UPBIT", "symbol", "KRW-ETH", "symbol_name", "이더리움",
                "side", "SELL", "quantity", "0.05", "price", 5200000, "currency", "KRW", "fee", 130));
        eth.put("realized_pnl", -4200);
        eth.put("executed_at", at(d, "16:00:00"));
        assertThat(call(t, "record_trade", eth).path("isError").asBoolean()).isFalse();
        JsonNode badCurrency = call(t, "record_trade", Map.of("external_id", "bad-c", "symbol", "BTCUSDT", "side", "BUY", "quantity", 1,
                "price", 1, "currency", "US DOLLAR"));
        assertThat(text(badCurrency)).contains("통화(currency)");

        // 원화와 USDT를 더하지 않고 통화별로 낸다 (원화 먼저)
        JsonNode day = read(me.http().perform(get("/api/me/trades/" + d)).andExpect(status().isOk()).andReturn());
        assertThat(day.path("summary").path("realizedPnl").properties()).extracting(Map.Entry::getKey).containsExactly("KRW", "USDT");
        assertThat(day.path("summary").path("realizedPnl").path("USDT").decimalValue()).isEqualByComparingTo("1.0549");
        assertThat(day.path("summary").path("realizedPnl").path("KRW").decimalValue()).isEqualByComparingTo("-4200");
        assertThat(day.path("summary").path("fees").path("USDT").decimalValue()).isEqualByComparingTo("0.2023");
        JsonNode first = day.path("fills").get(0);
        assertThat(first.path("exchange").asString()).isEqualTo("BINANCE");
        assertThat(first.path("currency").asString()).isEqualTo("USDT");
        assertThat(first.path("quantity").decimalValue()).isEqualByComparingTo("0.0012");
        assertThat(first.path("price").decimalValue()).isEqualByComparingTo("83752.3");

        // 요약 글은 통화마다 따로 적고 코인은 수량 뒤에 코인 이름을 붙인다
        long postId = read(me.http().perform(post("/api/me/trades/" + d + "/draft").with(csrf())).andExpect(status().isOk()).andReturn())
                .path("postId").asLong();
        Map<String, Object> post = jdbc.queryForMap("SELECT title, content_md FROM post WHERE id = ?", postId);
        assertThat((String) post.get("title")).isEqualTo("매매 일지 " + d + " (모의투자): 실현 손익 -4,200원 · +1.05 USDT");
        assertThat((String) post.get("content_md")).contains(
                "| 09:00 | 비트코인 · 바이낸스 | 매수 | 0.0012 BTC | 83,752.30 USDT |  | 1시간봉 20개 평균 위로 |",
                "| 16:00 | 이더리움 · 업비트 | 매도 | 0.05 ETH | 5,200,000원 | -4,200원 |",
                "- 수수료·세금: 130원 · 0.2023 USDT");

        String forAi = text(call(t, "get_trade_day", Map.of("date", d.toString())));
        assertThat(forAi).contains("[BINANCE] 비트코인(BTCUSDT) 매수 0.0012 BTC @ 83752.3 USDT", "손익 1.0549 USDT", "실현 손익 -4200원 · 1.0549 USDT");
    }

    Map<String, Object> futures(String id, LocalDate day, String time, String position, String side, int leverage, String pnl) {
        Map<String, Object> a = new HashMap<>(Map.of("external_id", id, "exchange", "BINANCE", "symbol", "BTCUSDT", "symbol_name", "비트코인",
                "currency", "USDT", "market", "FUTURES", "position_side", position, "side", side, "quantity", "0.01", "price", 83000));
        a.put("leverage", leverage);
        a.put("interval", "15m");
        a.put("executed_at", at(day, time));
        if (pnl != null) a.put("realized_pnl", pnl);
        return a;
    }

    /** 숏 진입 SELL → 숏 청산 BUY(손익), 롱 진입 BUY → 롱 청산 SELL(손익) */
    void recordFutures(String t, LocalDate d) throws Exception {
        for (Map<String, Object> a : List.of(futures("f-1", d, "09:00:00", "SHORT", "SELL", 3, null),
                futures("f-2", d, "10:00:00", "SHORT", "BUY", 3, "12.5"),
                futures("f-3", d, "11:00:00", "LONG", "BUY", 7, null),
                futures("f-4", d, "12:00:00", "LONG", "SELL", 7, "-2.5"))) {
            JsonNode r = call(t, "record_trade", a);
            assertThat(r.path("isError").asBoolean()).as(text(r)).isFalse();
        }
    }

    @Test
    void 선물은_레버리지_1에서_7배_청산_손익_포지션_방향을_검사한다() throws Exception {
        Session me = signup(uniqueLogin("futures"));
        String t = token(me);
        LocalDate d = LocalDate.now(KST).minusDays(1);
        recordFutures(t, d);

        // 레버리지는 1~7배, 손익은 청산에만, 현물에는 포지션 방향을 적지 않는다
        JsonNode tooHigh = call(t, "record_trade", futures("f-bad", d, "13:00:00", "LONG", "BUY", 8, null));
        assertThat(tooHigh.path("isError").asBoolean()).isTrue();
        assertThat(text(tooHigh)).contains("1~7배");
        Map<String, Object> notNumber = futures("f-bad5", d, "13:00:00", "LONG", "BUY", 2, null);
        notNumber.put("leverage", "abc");
        JsonNode badLeverage = call(t, "record_trade", notNumber);
        assertThat(badLeverage.path("isError").asBoolean()).isTrue();
        assertThat(text(badLeverage)).contains("leverage는 1~7 사이 정수");
        JsonNode openWithPnl = call(t, "record_trade", futures("f-bad2", d, "13:00:00", "SHORT", "SELL", 2, "1"));
        assertThat(text(openWithPnl)).contains("청산에만");
        JsonNode noSide = call(t, "record_trade", Map.of("external_id", "f-bad3", "market", "FUTURES", "symbol", "BTCUSDT", "side", "BUY",
                "quantity", 1, "price", 1, "currency", "USDT"));
        assertThat(text(noSide)).contains("position_side");
        JsonNode spotSide = call(t, "record_trade", Map.of("external_id", "f-bad4", "symbol", "BTCUSDT", "side", "BUY", "quantity", 1,
                "price", 1, "currency", "USDT", "position_side", "LONG", "interval", "2h"));
        assertThat(text(spotSide)).contains("선물(market=FUTURES)에만", "봉(interval)");
    }

    @Test
    void 선물은_포지션_방향과_레버리지를_남기고_숏_청산_매수의_손익도_더한다() throws Exception {
        Session me = signup(uniqueLogin("futures"));
        String t = token(me);
        LocalDate d = LocalDate.now(KST).minusDays(1);
        recordFutures(t, d);

        JsonNode day = read(me.http().perform(get("/api/me/trades/" + d)).andExpect(status().isOk()).andReturn());
        assertThat(day.path("fills")).hasSize(4);
        JsonNode first = day.path("fills").get(0);
        assertThat(first.path("market").asString()).isEqualTo("FUTURES");
        assertThat(first.path("positionSide").asString()).isEqualTo("SHORT");
        assertThat(first.path("leverage").asInt()).isEqualTo(3);
        assertThat(first.path("interval").asString()).isEqualTo("15m");
        JsonNode s = day.path("summary");
        assertThat(s.path("closes").asInt()).isEqualTo(2);
        assertThat(s.path("wins").asInt()).isEqualTo(1);
        assertThat(s.path("losses").asInt()).isEqualTo(1);
        assertThat(s.path("realizedPnl").path("USDT").decimalValue()).isEqualByComparingTo("10");
    }

    @Test
    void 선물_체결은_AI_하루_기록과_요약_글에_포지션을_붙여_적는다() throws Exception {
        Session me = signup(uniqueLogin("futures"));
        String t = token(me);
        LocalDate d = LocalDate.now(KST).minusDays(1);
        recordFutures(t, d);

        assertThat(text(call(t, "get_trade_day", Map.of("date", d.toString())))).contains("[선물 숏 3배] [15m]", "숏 청산", "롱 진입", "청산 2");

        writeMemo(me, d, "숏은 계획대로, 롱은 7배라 흔들렸어요.");
        long postId = createDraft(me, d);
        String body = jdbc.queryForObject("SELECT content_md FROM post WHERE id = ?", String.class, postId);
        assertThat(body).contains("비트코인 · 바이낸스 (선물 숏 3배) | 숏 청산", "(선물 롱 7배) | 롱 진입", "이익 본 청산 1");
    }

    @Test
    void 같은_날_모의투자와_실계좌가_섞이면_합계와_요약_글을_나눠_적는다() throws Exception {
        Session me = signup(uniqueLogin("mixedmode"));
        String t = token(me);
        LocalDate d = LocalDate.now(KST).minusDays(1);

        Map<String, Object> live = futures("m-2", d, "10:00:00", "SHORT", "BUY", 3, "-4");
        live.put("mode", "LIVE");
        for (Map<String, Object> a : List.of(futures("m-1", d, "09:00:00", "LONG", "SELL", 2, "12"), live)) {
            JsonNode r = call(t, "record_trade", a);
            assertThat(r.path("isError").asBoolean()).as(text(r)).isFalse();
        }

        JsonNode s = read(me.http().perform(get("/api/me/trades/" + d)).andExpect(status().isOk()).andReturn()).path("summary");
        assertThat(s.path("realizedPnl").path("USDT").decimalValue()).isEqualByComparingTo("8");
        assertThat(s.path("modes").path("PAPER").path("realizedPnl").path("USDT").decimalValue()).isEqualByComparingTo("12");
        assertThat(s.path("modes").path("LIVE").path("realizedPnl").path("USDT").decimalValue()).isEqualByComparingTo("-4");
        assertThat(s.path("modes").path("LIVE").path("losses").asInt()).isEqualTo(1);

        long postId = read(me.http().perform(post("/api/me/trades/" + d + "/draft").with(csrf())).andExpect(status().isOk()).andReturn())
                .path("postId").asLong();
        Map<String, Object> draft = jdbc.queryForMap("SELECT title, content_md FROM post WHERE id = ?", postId);
        assertThat((String) draft.get("title")).contains("(모의투자·실계좌): 실현 손익 모의투자 +12.00 USDT · 실계좌 -4.00 USDT");
        assertThat((String) draft.get("content_md")).contains("  - 모의투자: 체결 1건, 실현 손익 +12.00 USDT (이익 본 청산 1 · 손해 본 청산 0)",
                "  - 실계좌: 체결 1건, 실현 손익 -4.00 USDT (이익 본 청산 0 · 손해 본 청산 1)");

        // 한 모드뿐인 날은 나누지 않는다
        JsonNode empty = read(me.http().perform(get("/api/me/trades/" + d.minusDays(1))).andExpect(status().isOk()).andReturn());
        assertThat(empty.path("summary").path("modes").isEmpty()).isTrue();
    }

    @Test
    void 예전_봇이_이유_앞머리로_알린_선물_체결을_열로_옮긴다() throws Exception {
        Session me = signup(uniqueLogin("legacyfut"));
        jdbc.update("""
                INSERT INTO trade (member_id, external_id, mode, exchange, symbol, side, quantity, price, currency, reason, executed_at)
                VALUES (?, 'legacy-1', 'PAPER', 'BINANCE', 'BTCUSDT', 'SELL', 0.01, 83000, 'USDT', '[선물 숏 진입 3x] RSI 과열', now()),
                       (?, 'legacy-2', 'PAPER', 'BINANCE', 'BTCUSDT', 'BUY', 0.01, 83000, 'USDT', '골든크로스', now())
                """, me.memberId(), me.memberId());
        String sql = new String(getClass().getResourceAsStream("/db/migration/V41__trade_futures.sql").readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        String update = sql.substring(sql.indexOf("UPDATE trade"), sql.indexOf(';', sql.indexOf("UPDATE trade")));
        jdbc.update(update + " AND member_id = ?", me.memberId());
        Map<String, Object> legacy = jdbc.queryForMap("SELECT market, position_side, leverage, reason FROM trade WHERE external_id = 'legacy-1' AND member_id = ?",
                me.memberId());
        assertThat(legacy).containsEntry("market", "FUTURES").containsEntry("position_side", "SHORT").containsEntry("reason", "RSI 과열");
        assertThat(((Number) legacy.get("leverage")).intValue()).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT market FROM trade WHERE external_id = 'legacy-2' AND member_id = ?", String.class, me.memberId()))
                .isEqualTo("SPOT");
    }

    @Test
    void 하루_기록_상한을_넘으면_봇을_멈추라고_알린다() throws Exception {
        Session me = signup(uniqueLogin("tradecap"));
        String t = token(me);
        LocalDate d = LocalDate.now(KST).minusDays(1);
        for (int i = 0; i < 8; i++) {
            JsonNode r = trade(t, "cap-" + i, d, "10:00:0" + i, "005930", "삼성전자", "BUY", 1, 70000, null, "");
            assertThat(r.path("isError").asBoolean()).as(text(r)).isFalse();
        }
        JsonNode over = trade(t, "cap-9", d, "10:00:09", "005930", "삼성전자", "BUY", 1, 70000, null, "");
        assertThat(over.path("isError").asBoolean()).isTrue();
        assertThat(text(over)).contains("하루 8건까지");
        // 이미 남긴 체결을 다시 보내는 것은 상한과 상관없이 알려 준다
        assertThat(text(trade(t, "cap-0", d, "10:00:00", "005930", "삼성전자", "BUY", 1, 70000, null, ""))).contains("이미 남긴 체결");
    }

    @Test
    void 동시에_몰려도_하루_상한과_요약글_하나를_지킨다() throws Exception {
        Session me = signup(uniqueLogin("tradeburst"));
        LocalDate d = LocalDate.now(KST).minusDays(1);
        ExecutorService pool = Executors.newFixedThreadPool(6);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Boolean>> results = new ArrayList<>();
            for (int i = 0; i < 12; i++) {
                String id = "burst-" + i;
                results.add(pool.submit(() -> {
                    start.await();
                    try {
                        journal.recordFill(me.memberId(), new TradeJournal.Input(id, TradeJournal.Mode.PAPER, "005930", "삼성전자",
                                TradeJournal.Side.BUY, BigDecimal.ONE, BigDecimal.valueOf(70000), null, null, "", "",
                                d.atTime(10, 0).atZone(KST).toInstant(), "", ""));
                        return true;
                    } catch (RuntimeException e) {
                        return false;
                    }
                }));
            }
            start.countDown();
            int ok = 0;
            for (Future<Boolean> f : results) if (f.get()) ok++;
            assertThat(ok).isEqualTo(8);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM trade WHERE member_id = ?", Integer.class, me.memberId())).isEqualTo(8);

            CountDownLatch again = new CountDownLatch(1);
            List<Future<Long>> drafts = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                drafts.add(pool.submit(() -> {
                    again.await();
                    return journal.createDraft(me.memberId(), d).postId();
                }));
            }
            again.countDown();
            List<Long> ids = new ArrayList<>();
            for (Future<Long> f : drafts) ids.add(f.get());
            assertThat(ids).containsOnly(ids.get(0));
            assertThat(jdbc.queryForObject("SELECT count(*) FROM post WHERE author_id = ?", Integer.class, me.memberId())).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }
}
