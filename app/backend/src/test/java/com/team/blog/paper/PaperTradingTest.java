package com.team.blog.paper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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

    @Test
    void 투자_전용_포인트로_사고_팔면_원금은_투자로_수익은_전부_크레딧으로() throws Exception {
        Session me = signup(uniqueLogin("ppbuy"));
        JsonNode state = read(me.http().perform(get("/api/me/paper")).andExpect(status().isOk()).andReturn());
        assertThat(state.path("invest").asLong()).isEqualTo(10_000_000L);
        assertThat(state.path("quotes").findValuesAsString("symbol")).containsExactly("BTC", "ETH", "XRP", "SOL", "DOGE");
        assertThat(state.path("quotesError").isNull()).isTrue();

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
