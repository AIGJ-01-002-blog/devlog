package com.team.blog.trade.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import com.team.blog.trade.application.TradeCharts.Candle;
import com.team.blog.trade.application.TradeCharts.Coin;

/** spec 102: 서버 모의 봇의 매매 규칙이 trade-bot의 이동평균 교차(strategy.py)와 같게 사고팔고, 같은 봉을 두 번 거래하지 않는다 */
class PaperBotRuleTest {
    static final long HOUR = 3_600_000L;

    static Coin coin(int... closes) {
        List<Candle> cs = new ArrayList<>();
        for (int i = 0; i < closes.length; i++) {
            BigDecimal c = BigDecimal.valueOf(closes[i]);
            cs.add(new Candle(i * HOUR, c, c, c, c));
        }
        return new Coin("BTC", "비트코인", "BTCUSDT", cs, List.of(), null);
    }

    static ObjectNode fresh(JsonMapper json) {
        ObjectNode acc = json.createObjectNode();
        acc.put("cash", "1000");
        acc.set("positions", json.createObjectNode());
        acc.set("seen", json.createObjectNode());
        return acc;
    }

    @Test
    void 위로_뚫으면_사고_아래로_내려가면_판다() {
        PaperBot.Rule r = new PaperBot.Rule(3, new BigDecimal("0.03"));
        String last = null;
        for (int c : new int[] {100, 99, 98, 97, 105}) last = r.onBar(BigDecimal.valueOf(c), false, BigDecimal.ZERO);
        assertThat(last).isEqualTo("BUY");
        r = new PaperBot.Rule(3, new BigDecimal("0.03"));
        for (int c : new int[] {100, 101, 102, 103, 90}) last = r.onBar(BigDecimal.valueOf(c), true, BigDecimal.valueOf(80));
        assertThat(last).startsWith("SELL:").contains("이동평균선 아래로");
    }

    @Test
    void 손절이_추세보다_먼저다() {
        PaperBot.Rule r = new PaperBot.Rule(3, new BigDecimal("0.03"));
        String last = null;
        for (int c : new int[] {100, 101, 102, 103, 104}) last = r.onBar(BigDecimal.valueOf(c), true, BigDecimal.valueOf(108));
        assertThat(last).isEqualTo("SELL:손절선 -3% 도달");
    }

    @Test
    void 장부에_체결을_남기고_같은_봉은_다시_거래하지_않는다() {
        JsonMapper json = JsonMapper.builder().build();
        PaperBot bot = new PaperBot(null, null, null, null, null, null, null);
        PaperBot.Book book = PaperBot.BOOKS.getFirst();
        ObjectNode acc = fresh(json);
        Coin c = coin(100, 99, 98, 97, 96, 95, 101, 104, 106, 99, 97, 300);
        List<PaperBot.Fill> fills = bot.trade(book, acc, c);
        assertThat(fills).extracting(PaperBot.Fill::side).containsExactly("BUY", "SELL", "BUY");
        PaperBot.Fill sell = fills.get(1);
        assertThat(sell.pnl()).isNotNull();
        assertThat(sell.at().toEpochMilli()).isEqualTo(10 * HOUR); // 9번째 봉(99)이 닫힌 시각
        assertThat(acc.path("positions").has("BTCUSDT")).isTrue();
        assertThat(new BigDecimal(acc.path("cash").asString())).isLessThan(new BigDecimal("1000"));

        // 같은 봉을 다시 받아도 체결은 없다
        assertThat(bot.trade(book, acc, c)).isEmpty();
    }

    /** 기본 크레딧 창구 흉내: 부른 주문을 적고, 산 만큼 갖고 있다고 답한다 */
    static final class FakeCredits implements BotCredits {
        long invest = 1_000_000;
        BigDecimal held = BigDecimal.ZERO;
        final List<String> calls = new ArrayList<>();

        public boolean enabled() { return true; }
        public long invest(long memberId) { return invest; }
        public BigDecimal held(long memberId, String coin) { return held; }

        public Optional<Filled> buy(long memberId, String coin, long amount, String orderId, String reason) {
            calls.add("BUY " + coin + " " + amount + " " + orderId);
            BigDecimal q = new BigDecimal("0.5");
            held = held.add(q);
            return Optional.of(new Filled(q, new BigDecimal("200000"), null));
        }

        public Optional<Filled> sell(long memberId, String coin, BigDecimal quantity, String orderId, String reason) {
            calls.add("SELL " + coin + " " + quantity.stripTrailingZeros().toPlainString());
            held = held.subtract(quantity);
            return Optional.of(new Filled(quantity, new BigDecimal("190000"), new BigDecimal("-5000")));
        }
    }

    static Coin krw(int... closes) {
        Coin c = coin(closes);
        return new Coin("BTC", "비트코인", "KRW-BTC", c.candles(), List.of(), null);
    }

    @Test
    void 크레딧_계좌는_지난_봉을_되짚지_않고_마지막_봉에서만_기본_크레딧으로_산다() {
        JsonMapper json = JsonMapper.builder().build();
        FakeCredits credits = new FakeCredits();
        credits.invest = 30_000;
        PaperBot bot = new PaperBot(null, null, null, null, null, null, credits);
        PaperBot.Book book = PaperBot.BOOKS.getLast();
        ObjectNode acc = fresh(json);
        // 앞쪽에도 사고팔 신호가 있지만 크레딧으로는 마지막 봉(위로 뚫음)만 거래한다
        List<PaperBot.Fill> fills = bot.creditTrade(7, book, acc, krw(100, 99, 98, 97, 96, 95, 101, 104, 106, 99, 97, 300));
        assertThat(credits.calls).containsExactly("BUY BTC 30000 bot-BTC-" + 11 * HOUR + "-B");
        assertThat(fills).extracting(PaperBot.Fill::side).containsExactly("BUY");
        assertThat(acc.path("positions").path("KRW-BTC").path("quantity").asString()).isEqualTo("0.5");

        // 같은 봉을 다시 받아도 다시 사지 않는다
        assertThat(bot.creditTrade(7, book, acc, krw(100, 99, 98, 97, 96, 95, 101, 104, 106, 99, 97, 300))).isEmpty();
        assertThat(credits.calls).hasSize(1);
    }

    @Test
    void 봇은_자기가_산_만큼만_팔고_내가_다_팔았으면_보유분을_지운다() {
        JsonMapper json = JsonMapper.builder().build();
        FakeCredits credits = new FakeCredits();
        PaperBot bot = new PaperBot(null, null, null, null, null, null, credits);
        PaperBot.Book book = PaperBot.BOOKS.getLast();
        ObjectNode acc = fresh(json);
        bot.creditTrade(7, book, acc, krw(100, 99, 98, 97, 96, 95, 101, 104, 106, 99, 97, 300));
        credits.held = new BigDecimal("2.5"); // 내가 손으로 2개 더 샀다
        List<PaperBot.Fill> sold = bot.creditTrade(7, book, acc, krw(100, 99, 98, 97, 96, 95, 101, 104, 106, 99, 97, 300, 100));
        assertThat(credits.calls).last().isEqualTo("SELL BTC 0.5");
        assertThat(sold.getFirst().pnl()).isEqualByComparingTo("-5000");
        assertThat(acc.path("positions").has("KRW-BTC")).isFalse();

        bot.creditTrade(7, book, acc, krw(100, 99, 98, 97, 96, 95, 101, 104, 106, 99, 97, 300, 100, 50, 40, 30, 20, 400));
        assertThat(acc.path("positions").has("KRW-BTC")).isTrue();
        credits.held = BigDecimal.ZERO; // 봇이 산 것을 내가 손으로 다 팔았다
        int before = credits.calls.size();
        assertThat(bot.creditTrade(7, book, acc, krw(100, 99, 98, 97, 96, 95, 101, 104, 106, 99, 97, 300, 100, 50, 40, 30, 20, 400, 10))).isEmpty();
        assertThat(credits.calls).hasSize(before);
        assertThat(acc.path("positions").has("KRW-BTC")).isFalse();
    }
}
