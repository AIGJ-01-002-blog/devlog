package com.team.blog.paper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;

import com.team.blog.support.FakeUpbit;
import com.team.blog.support.IntegrationTest;

/**
 * #267 포인트 모의투자: 투자 전용 포인트로 먼저 사고, 팔면 원금은 낸 지갑으로 돌아가고 수익은 전부 크레딧(상점 지갑)이 된다.
 * 체결은 매매 일지에 PAPER로 남는다.
 */
class PaperTradingTest extends IntegrationTest {
    final FakeUpbit upbit = FakeUpbit.INSTANCE;

    @BeforeEach
    @AfterEach
    void resetQuotes() {
        upbit.reset();
    }

    static String orderId() {
        return UUID.randomUUID().toString();
    }

    JsonNode order(Session s, Map<String, Object> body, int expect) throws Exception {
        return read(s.http().perform(asJson(post("/api/me/paper/orders"), body)).andExpect(status().is(expect)).andReturn());
    }

    static Map<String, Object> buy(String id, String symbol, long amount, boolean useShop) {
        Map<String, Object> m = new HashMap<>();
        m.put("clientOrderId", id);
        m.put("symbol", symbol);
        m.put("side", "BUY");
        m.put("amount", amount);
        m.put("useShop", useShop);
        m.put("reason", "시험 매수");
        return m;
    }

    static Map<String, Object> sellAll(String id, String symbol) {
        return Map.of("clientOrderId", id, "symbol", symbol, "side", "SELL", "all", true);
    }

    long wallet(Session s, String wallet) {
        return jdbc.queryForObject("SELECT COALESCE(SUM(amount), 0) FROM point_ledger WHERE member_id = ? AND wallet = ?", Long.class,
                s.memberId(), wallet);
    }

    JsonNode limit(Session s, Map<String, Object> body, int expect) throws Exception {
        return read(s.http().perform(asJson(post("/api/me/paper/limit-orders"), body)).andExpect(status().is(expect)).andReturn());
    }

    static Map<String, Object> limitBody(String id, String symbol, String side, String price, String quantity) {
        Map<String, Object> m = new HashMap<>();
        m.put("clientOrderId", id);
        m.put("symbol", symbol);
        m.put("side", side);
        m.put("price", new BigDecimal(price));
        m.put("quantity", new BigDecimal(quantity));
        return m;
    }

    JsonNode state(Session s) throws Exception {
        return read(s.http().perform(get("/api/me/paper")).andExpect(status().isOk()).andReturn());
    }

    /** #332 지정가 매수: 낼 크레딧(수수료 포함)을 묶고, 시세가 지정가 아래로 오면 더 싼 값에 사고 남은 몫을 돌려준다 */
    @Test
    void 지정가_매수는_크레딧을_묶고_시세가_닿으면_싼_값에_체결한다() throws Exception {
        Session me = signup(uniqueLogin("pplimbuy"));
        state(me);
        // BTC 1억일 때 9천만 원에 0.01개: 900,000 + 수수료 450 = 900,450을 묶는다
        String id = orderId();
        JsonNode placed = limit(me, limitBody(id, "BTC", "BUY", "90000000", "0.01"), 200);
        assertThat(placed.path("order").path("status").asString()).isEqualTo("OPEN");
        assertThat(placed.path("order").path("locked").asLong()).isEqualTo(900_450);
        assertThat(placed.path("state").path("locked").asLong()).isEqualTo(900_450);
        assertThat(wallet(me, "INVEST_ONLY")).isEqualTo(9_099_550L);
        // 같은 주문 번호는 한 번만 묶는다
        assertThat(limit(me, limitBody(id, "BTC", "BUY", "90000000", "0.01"), 200).path("duplicate").asBoolean()).isTrue();
        assertThat(wallet(me, "INVEST_ONLY")).isEqualTo(9_099_550L);

        // 8천9백만으로 내려오면 화면을 열 때 체결: 890,000 + 수수료 445, 남은 10,005는 돌려준다
        upbit.prices.put("BTC", new BigDecimal("89000000"));
        JsonNode s = state(me);
        JsonNode order = s.path("orders").get(0);
        assertThat(order.path("status").asString()).isEqualTo("FILLED");
        assertThat(order.path("filledPrice").decimalValue()).isEqualByComparingTo("89000000");
        assertThat(s.path("locked").asLong()).isZero();
        assertThat(s.path("positions").get(0).path("quantity").decimalValue()).isEqualByComparingTo("0.01");
        assertThat(s.path("positions").get(0).path("cost").asLong()).isEqualTo(890_445);
        assertThat(wallet(me, "INVEST_ONLY")).isEqualTo(10_000_000L - 890_445);
        assertThat(s.path("fills").get(0).path("side").asString()).isEqualTo("BUY");
        assertThat(s.path("fills").get(0).path("bot").asBoolean()).isFalse();
        Map<String, Object> trade = jdbc.queryForMap("SELECT strategy, price FROM trade WHERE id = ?", s.path("fills").get(0).path("tradeId").asLong());
        assertThat(trade).containsEntry("strategy", "포인트 모의투자");

        // 한 번 더 열어도 다시 체결하지 않는다
        assertThat(state(me).path("positions").get(0).path("quantity").decimalValue()).isEqualByComparingTo("0.01");
    }

    /** #332 아직 닿지 않은 지정가 매수는 취소하면 묶은 크레딧이 그대로 돌아오고, 남의 주문은 없는 주문이다 */
    @Test
    void 닿지_않은_지정가_매수를_취소하면_묶은_크레딧이_돌아온다() throws Exception {
        Session me = signup(uniqueLogin("pplimcan"));
        state(me);
        JsonNode open = limit(me, limitBody(orderId(), "BTC", "BUY", "50000000", "0.02"), 200);
        assertThat(open.path("order").path("locked").asLong()).isEqualTo(1_000_500);
        assertThat(wallet(me, "INVEST_ONLY")).isEqualTo(10_000_000L - 1_000_500);
        long id2 = open.path("order").path("id").asLong();
        JsonNode canceled = read(me.http().perform(delete("/api/me/paper/limit-orders/" + id2).with(csrf())).andExpect(status().isOk()).andReturn());
        assertThat(canceled.path("locked").asLong()).isZero();
        assertThat(wallet(me, "INVEST_ONLY")).isEqualTo(10_000_000L);
        assertThat(read(me.http().perform(delete("/api/me/paper/limit-orders/" + id2).with(csrf())).andExpect(status().isConflict()).andReturn())
                .path("code").asString()).isEqualTo("CLOSED");
        // 남의 주문은 없는 주문이다
        Session other = signup(uniqueLogin("pplimoth"));
        other.http().perform(delete("/api/me/paper/limit-orders/" + id2).with(csrf())).andExpect(status().isNotFound());
    }

    /** #332 지정가 매도: 수량을 묶어 시장가로는 묶인 만큼 못 팔고, 시세가 지정가 위로 오면 높은 값에 팔아 수익을 크레딧으로 넣는다 */
    @Test
    void 지정가_매도는_수량을_묶고_시세가_닿으면_팔아_수익을_크레딧으로_넣는다() throws Exception {
        Session me = signup(uniqueLogin("pplimsell"));
        order(me, buy(orderId(), "BTC", 1_000_000, false), 200); // 0.009995개, 원금 1,000,000
        JsonNode placed = limit(me, limitBody(orderId(), "BTC", "SELL", "110000000", "0.005"), 200);
        assertThat(placed.path("order").path("status").asString()).isEqualTo("OPEN");
        assertThat(placed.path("state").path("positions").get(0).path("available").decimalValue()).isEqualByComparingTo("0.004995");
        // 묶인 수량까지 시장가로 팔 수는 없다
        Map<String, Object> sell = Map.of("clientOrderId", orderId(), "symbol", "BTC", "side", "SELL", "quantity", new BigDecimal("0.006"));
        assertThat(order(me, sell, 409).path("code").asString()).isEqualTo("NOT_ENOUGH");
        assertThat(limit(me, limitBody(orderId(), "BTC", "SELL", "110000000", "0.006"), 409).path("code").asString()).isEqualTo("NOT_ENOUGH");

        // 1억 2천이 되면 지정가(1억 1천)보다 높은 값에 판다: 600,000 - 수수료 300 = 599,700, 원금 500,250(비율대로) → 수익 99,450
        upbit.prices.put("BTC", new BigDecimal("120000000"));
        JsonNode s = state(me);
        assertThat(s.path("orders").get(0).path("status").asString()).isEqualTo("FILLED");
        assertThat(s.path("orders").get(0).path("filledPrice").decimalValue()).isEqualByComparingTo("120000000");
        assertThat(s.path("positions").get(0).path("quantity").decimalValue()).isEqualByComparingTo("0.004995");
        assertThat(s.path("fills").get(0).path("side").asString()).isEqualTo("SELL");
        assertThat(s.path("fills").get(0).path("pnl").decimalValue()).isEqualByComparingTo("99450");
        assertThat(wallet(me, "SHOP")).isEqualTo(99_450L);
    }

    @Test
    void 잘못된_지정가는_거절한다() throws Exception {
        Session me = signup(uniqueLogin("pplimbad"));
        Map<String, Object> noPrice = limitBody(orderId(), "BTC", "BUY", "1", "0.01");
        noPrice.remove("price");
        limit(me, noPrice, 400);
        // 최소 주문 5,000 크레딧보다 작다
        limit(me, limitBody(orderId(), "XRP", "BUY", "800", "1"), 400);
        // 기본 크레딧 1,000만보다 많이 묶을 수 없다
        assertThat(limit(me, limitBody(orderId(), "BTC", "BUY", "100000000", "1"), 409).path("code").asString()).isEqualTo("POINTS_SHORT");
        // 갖고 있지 않은 코인은 지정가로도 못 판다
        assertThat(limit(me, limitBody(orderId(), "ETH", "SELL", "5000000", "1"), 409).path("code").asString()).isEqualTo("NO_POSITION");
        // 수량×가격이 long을 넘으면 묶는 크레딧이 잘려 거의 공짜로 살 수 있었다: 금액·수량 상한으로 거절한다
        limit(me, limitBody(orderId(), "BTC", "BUY", "100", "184467440737095517"), 400);
        limit(me, limitBody(orderId(), "BTC", "BUY", "10000000000000", "0.2"), 400);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM paper_limit_order WHERE member_id = ?", Long.class, me.memberId())).isZero();
    }

    /** #324 모의투자 차트: 원화 캔들 위에 내가 포인트로 낸 체결이 ME로 찍히고, 봇 체결은 BOT으로 따로 찍힌다 */
    @Test
    void 모의투자_차트에_내_체결과_봇_체결이_함께_찍힌다() throws Exception {
        Session me = signup(uniqueLogin("ppchart"));
        order(me, buy(orderId(), "BTC", 1_000_000, false), 200);
        jdbc.update("""
                INSERT INTO trade_bot_event (member_id, external_id, kind, account, symbol, side, quantity, price, currency, verdict, text, occurred_at, created_at)
                VALUES (?, ?, 'FILL', 'server-paper-upbit-spot-1h', 'KRW-BTC', 'SELL', 0.01, 101, 'KRW', '', '봇 매도', now(), now())
                """, me.memberId(), orderId());

        JsonNode chart = read(me.http().perform(get("/api/me/paper/chart").param("interval", "15m")).andExpect(status().isOk()).andReturn());
        assertThat(chart.path("currency").asString()).isEqualTo("KRW");
        JsonNode btc = chart.path("coins").get(0);
        assertThat(btc.path("symbol").asString()).isEqualTo("KRW-BTC");
        assertThat(btc.path("candles").size()).isEqualTo(com.team.blog.trade.application.TradeCharts.CANDLES);
        assertThat(btc.path("marks").findValuesAsString("by")).containsExactlyInAnyOrder("ME", "BOT");
        assertThat(btc.path("marks").findValuesAsString("text")).contains("시험 매수", "봇 매도");
        // 다른 코인에는 찍히지 않는다
        assertThat(chart.path("coins").get(1).path("marks").size()).isZero();

        me.http().perform(get("/api/me/paper/chart").param("interval", "2m")).andExpect(status().isBadRequest());
    }

    @Test
    void 투자_전용_포인트로_사고_팔면_원금은_투자로_수익은_전부_크레딧으로() throws Exception {
        Session me = signup(uniqueLogin("ppbuy"));
        JsonNode state = read(me.http().perform(get("/api/me/paper")).andExpect(status().isOk()).andReturn());
        assertThat(state.path("invest").asLong()).isEqualTo(10_000_000L);
        assertThat(state.path("quotes").findValuesAsString("symbol")).containsExactly("BTC", "ETH", "XRP", "SOL", "DOGE");
        assertThat(state.path("quotesError").isNull()).isTrue();
        assertThat(state.path("quotesSource").asString()).isIn("KORBIT", "UPBIT");

        // 1억 원짜리 BTC를 100만 P어치: 수수료 500P, 수량 0.009995
        String id = orderId();
        JsonNode filled = order(me, buy(id, "BTC", 1_000_000, false), 200);
        assertThat(filled.path("fee").asLong()).isEqualTo(500);
        assertThat(filled.path("quantity").decimalValue()).isEqualByComparingTo("0.009995");
        assertThat(filled.path("state").path("invest").asLong()).isEqualTo(9_000_000L);
        assertThat(filled.path("state").path("positions").get(0).path("cost").asLong()).isEqualTo(1_000_000L);

        // 같은 주문 번호로 다시 보내면 한 번만 체결된다
        JsonNode again = order(me, buy(id, "BTC", 1_000_000, false), 200);
        assertThat(again.path("duplicate").asBoolean()).isTrue();
        assertThat(again.path("tradeId").asLong()).isEqualTo(filled.path("tradeId").asLong());
        assertThat(wallet(me, "INVEST_ONLY")).isEqualTo(9_000_000L);

        // 매매 일지에 PAPER·UPBIT·KRW로 남는다
        Map<String, Object> trade = jdbc.queryForMap("SELECT mode, exchange, currency, side, strategy FROM trade WHERE id = ?", filled.path("tradeId").asLong());
        assertThat(trade).containsEntry("mode", "PAPER").containsEntry("exchange", "UPBIT").containsEntry("currency", "KRW")
                .containsEntry("side", "BUY").containsEntry("strategy", "포인트 모의투자");

        // 20% 올라 모두 팔면: 받는 돈 1,199,400 - 수수료 600 = 1,198,800, 수익 198,800
        upbit.prices.put("BTC", new java.math.BigDecimal("120000000"));
        JsonNode sold = order(me, sellAll(orderId(), "BTC"), 200);
        assertThat(sold.path("pnl").asLong()).isEqualTo(198_800);
        assertThat(sold.path("reward").asLong()).isEqualTo(198_800);
        assertThat(sold.path("state").path("positions").size()).isZero();
        assertThat(wallet(me, "INVEST_ONLY")).isEqualTo(10_000_000L);
        assertThat(wallet(me, "SHOP")).isEqualTo(198_800L);
        // 내 포인트 화면을 열어도 봇 수익으로 다시 정산되지 않는다(같은 체결)
        me.http().perform(get("/api/me/points")).andExpect(status().isOk());
        assertThat(wallet(me, "SHOP")).isEqualTo(198_800L);
        // 가진 게 없으면 못 판다
        assertThat(order(me, sellAll(orderId(), "BTC"), 409).path("code").asString()).isEqualTo("NO_POSITION");
    }

    @Test
    void 상점_포인트는_고른_때만_쓰고_손실은_낸_비율대로_돌려준다() throws Exception {
        Session me = signup(uniqueLogin("ppshop"));
        me.http().perform(get("/api/me/paper")).andExpect(status().isOk());
        jdbc.update("INSERT INTO point_ledger (member_id, wallet, amount, reason, request_key) VALUES (?, 'SHOP', 50000, 'ADMIN', 'test:shop')", me.memberId());

        // 투자 전용 1,000만보다 많이 사려면 상점 포인트를 쓰겠다고 골라야 한다
        assertThat(order(me, buy(orderId(), "ETH", 10_020_000, false), 409).path("code").asString()).isEqualTo("POINTS_SHORT");
        JsonNode filled = order(me, buy(orderId(), "ETH", 10_020_000, true), 200);
        JsonNode pos = filled.path("state").path("positions").get(0);
        assertThat(pos.path("costInvest").asLong()).isEqualTo(10_000_000L);
        assertThat(pos.path("costShop").asLong()).isEqualTo(20_000L);
        assertThat(wallet(me, "INVEST_ONLY")).isZero();
        assertThat(wallet(me, "SHOP")).isEqualTo(30_000L);

        // 반값에 모두 팔면 손실: 돌려받은 돈을 원금 비율대로 나누고, 상점 적립은 없다
        upbit.prices.put("ETH", new java.math.BigDecimal("2500000"));
        JsonNode sold = order(me, sellAll(orderId(), "ETH"), 200);
        long proceeds = sold.path("pnl").asLong() + 10_020_000L;
        assertThat(sold.path("pnl").asLong()).isNegative();
        assertThat(sold.path("reward").asLong()).isZero();
        long backShop = wallet(me, "SHOP") - 30_000L;
        assertThat(backShop).isEqualTo(20_000L * proceeds / 10_020_000L);
        assertThat(wallet(me, "INVEST_ONLY") + backShop).isEqualTo(proceeds);
    }

    @Test
    void 시세를_못_받으면_주문을_막고_잘못된_주문은_거절한다() throws Exception {
        Session me = signup(uniqueLogin("ppbad"));
        assertThat(order(me, buy(orderId(), "PEPE", 10_000, false), 400).path("code").asString()).isEqualTo("VALIDATION_FAILED");
        assertThat(order(me, buy(orderId(), "BTC", 1_000, false), 400).path("code").asString()).isEqualTo("VALIDATION_FAILED");

        upbit.down = true;
        JsonNode state = read(me.http().perform(get("/api/me/paper")).andExpect(status().isOk()).andReturn());
        assertThat(state.path("quotesError").asString()).contains("시세");
        assertThat(order(me, buy(orderId(), "BTC", 10_000, false), 503).path("code").asString()).isEqualTo("QUOTES_UNAVAILABLE");
        assertThat(wallet(me, "INVEST_ONLY")).isEqualTo(10_000_000L);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM paper_order WHERE member_id = ?", Integer.class, me.memberId())).isZero();
    }
}
