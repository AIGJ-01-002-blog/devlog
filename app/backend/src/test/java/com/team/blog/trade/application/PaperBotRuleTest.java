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
        /** 참이면 주문·환전이 실패한다(시세를 못 받음 등) */
        boolean fail;
        /** 이 글자로 끝나는 환전만 실패한다 (선물 반전에서 진입만 실패) */
        String failKey;
        final List<String> calls = new ArrayList<>();

        public boolean enabled() { return true; }
        public long invest(long memberId) { return invest; }
        public BigDecimal held(long memberId, String coin) { return held; }

        public Optional<Filled> buy(long memberId, String coin, long amount, String orderId, String reason) {
            calls.add("BUY " + coin + " " + amount + " " + orderId);
            if (fail) return Optional.empty();
            BigDecimal q = new BigDecimal("0.5");
            held = held.add(q);
            return Optional.of(new Filled(q, new BigDecimal("200000"), null));
        }

        public Optional<Filled> sell(long memberId, String coin, BigDecimal quantity, String orderId, String reason) {
            calls.add("SELL " + coin + " " + quantity.stripTrailingZeros().toPlainString());
            held = held.subtract(quantity);
            return Optional.of(new Filled(quantity, new BigDecimal("190000"), new BigDecimal("-5000")));
        }

        BigDecimal rate = new BigDecimal("1400");
        public Optional<BigDecimal> usdtKrw() { return Optional.ofNullable(rate); }

        public boolean exchangeOut(long memberId, long amount, String key, String memo) {
            calls.add("OUT " + amount + " " + key);
            return !fail && (failKey == null || !key.endsWith(failKey));
        }

        public void exchangeIn(long memberId, long cost, long back, String key, String memo) {
            calls.add("IN " + cost + " " + back + " " + key);
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
    void 크레딧_주문이_실패하면_그_봉을_끝냈다고_적지_않고_다음_바퀴에_다시_산다() {
        FakeCredits credits = new FakeCredits();
        credits.fail = true;
        PaperBot bot = new PaperBot(null, null, null, null, null, null, credits);
        PaperBot.Book book = PaperBot.BOOKS.getLast();
        ObjectNode acc = fresh(JsonMapper.builder().build());
        Coin c = krw(100, 99, 98, 97, 96, 95, 101, 104, 106, 99, 97, 300);
        assertThat(bot.creditTrade(7, book, acc, c)).isEmpty();
        assertThat(acc.path("seen").has("KRW-BTC")).isFalse();

        credits.fail = false;
        assertThat(bot.creditTrade(7, book, acc, c)).extracting(PaperBot.Fill::side).containsExactly("BUY");
        assertThat(credits.calls).containsExactly("BUY BTC 100000 bot-BTC-" + 11 * HOUR + "-B", "BUY BTC 100000 bot-BTC-" + 11 * HOUR + "-B");
        assertThat(acc.path("seen").path("KRW-BTC").asLong()).isEqualTo(11 * HOUR);
    }

    @Test
    void USDT_환전이_실패하면_그_봉을_다시_본다() {
        FakeCredits credits = new FakeCredits();
        credits.fail = true;
        PaperBot bot = new PaperBot(null, null, null, null, null, null, credits);
        ObjectNode acc = fresh(JsonMapper.builder().build());
        Coin c = coin(100, 99, 98, 97, 96, 95, 101, 104, 106, 99, 97, 300);
        assertThat(bot.usdtTrade(7, PaperBot.BOOKS.getFirst(), PaperBot.DEFAULTS, acc, c, new BigDecimal("1400"))).isEmpty();
        assertThat(acc.path("seen").has("BTCUSDT")).isFalse();
        credits.fail = false;
        assertThat(bot.usdtTrade(7, PaperBot.BOOKS.getFirst(), PaperBot.DEFAULTS, acc, c, new BigDecimal("1400"))).hasSize(1);
        assertThat(acc.path("seen").path("BTCUSDT").asLong()).isEqualTo(11 * HOUR);
    }

    @Test
    void 가상_돈_계좌도_회원이_고른_한_번_금액을_쓴다() {
        PaperBot bot = new PaperBot(null, null, null, null, null, null, null);
        PaperBot.Settings half = new PaperBot.Settings(PaperBot.DEFAULTS.coins(), new BigDecimal("0.03"), BigDecimal.ZERO, "1h", 50_000);
        assertThat(bot.virtualBudget(PaperBot.BOOKS.getLast(), half)).isEqualByComparingTo("50000");
        assertThat(bot.virtualBudget(PaperBot.BOOKS.getFirst(), half)).isEqualByComparingTo("50");
        assertThat(bot.virtualBudget(PaperBot.BOOKS.getFirst(), PaperBot.DEFAULTS)).isEqualByComparingTo("100");
    }

    @Test
    void 한_번_금액_범위는_설정값을_따른다() {
        PaperBotBudget narrow = new PaperBotBudget(20_000, 10_000, 30_000);
        PaperBot.Settings s = new PaperBot.Settings(PaperBot.DEFAULTS.coins(), new BigDecimal("0.03"), BigDecimal.ZERO, "1h", 50_000);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> PaperBot.checked(s, narrow)).isInstanceOf(com.team.blog.shared.error.ApiException.class);
        assertThat(PaperBot.checked(s).budget()).isEqualTo(50_000);
        // 운영 범위가 바뀌어 저장된 금액이 벗어나도 고른 코인·봉은 그대로 두고 금액만 범위 끝으로 맞춘다
        PaperBot bot = new PaperBot(null, null, null, null, null, null, null, narrow);
        PaperBot.Settings kept = bot.settings("{\"coins\":[\"ETH\"],\"stop_loss\":\"0.05\",\"interval\":\"1d\",\"budget\":5000}");
        assertThat(kept.coins()).containsExactly("ETH");
        assertThat(kept.interval()).isEqualTo("1d");
        assertThat(kept.budget()).isEqualTo(10_000);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new PaperBotBudget(1_000, 5_000, 10_000)).isInstanceOf(IllegalArgumentException.class);
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
    @Test
    void 바이낸스_계좌도_기본_크레딧으로_사고_손절은_봉_종가로_본다() {
        FakeCredits credits = new FakeCredits();
        PaperBot bot = new PaperBot(null, null, null, null, null, null, credits);
        PaperBot.Book book = PaperBot.BOOKS.getFirst(); // 바이낸스: USDT 봉
        ObjectNode acc = fresh(JsonMapper.builder().build());
        bot.creditTrade(7, book, acc, coin(100, 99, 98, 97, 96, 95, 101, 104, 106, 99, 97, 300));
        // 한 번 금액은 10만 크레딧이고, 업비트 계좌 주문과 번호가 겹치지 않는다
        assertThat(credits.calls).containsExactly("BUY BTC 100000 bot-binance-BTC-" + 11 * HOUR + "-B");
        assertThat(acc.path("positions").path("BTCUSDT").path("ref_price").asString()).isEqualTo("300");

        // 체결가는 원(200,000)이고 봉은 USDT(299)라도 손절선은 봉 종가 300 기준이라 팔지 않는다
        assertThat(bot.creditTrade(7, book, acc, coin(100, 99, 98, 97, 96, 95, 101, 104, 106, 99, 97, 300, 299))).isEmpty();
        assertThat(credits.calls).hasSize(1);
    }
    @Test
    void 익절선에_닿으면_판다() {
        PaperBot.Rule r = new PaperBot.Rule(3, new BigDecimal("0.03"), new BigDecimal("0.04"), "4h");
        String last = null;
        for (int c : new int[] {100, 101, 102, 103, 104}) last = r.onBar(BigDecimal.valueOf(c), true, BigDecimal.valueOf(100));
        assertThat(last).isEqualTo("SELL:익절선 +4% 도달");
    }

    @Test
    void 계정별_기준은_범위를_확인하고_코인을_정해진_순서로_맞춘다() {
        PaperBot.Settings s = PaperBot.checked(new PaperBot.Settings(List.of("SOL", "BTC", "SOL"), new BigDecimal("0.020"), BigDecimal.ZERO, "4h", 50_000));
        assertThat(s.coins()).containsExactly("BTC", "SOL");
        assertThat(s.stopLoss()).isEqualByComparingTo("0.02");
        assertThat(s.step()).hasHours(4);
        for (PaperBot.Settings bad : List.of(
                new PaperBot.Settings(List.of(), new BigDecimal("0.02"), BigDecimal.ZERO, "1h", 50_000),
                new PaperBot.Settings(List.of("PEPE"), new BigDecimal("0.02"), BigDecimal.ZERO, "1h", 50_000),
                new PaperBot.Settings(List.of("BTC"), new BigDecimal("0.001"), BigDecimal.ZERO, "1h", 50_000),
                new PaperBot.Settings(List.of("BTC"), new BigDecimal("0.02"), new BigDecimal("0.001"), "1h", 50_000),
                new PaperBot.Settings(List.of("BTC"), new BigDecimal("0.02"), BigDecimal.ZERO, "1m", 50_000),
                new PaperBot.Settings(List.of("BTC"), new BigDecimal("0.02"), BigDecimal.ZERO, "1h", 4_000))) {
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> PaperBot.checked(bad)).isInstanceOf(com.team.blog.shared.error.ApiException.class);
        }
        // 저장된 값이 비었거나 깨졌으면 기본값으로 돈다
        PaperBot bot = new PaperBot(null, null, null, null, null, null, null);
        assertThat(bot.settings("{}")).isEqualTo(PaperBot.DEFAULTS);
        assertThat(bot.settings("{\"stop_loss\":\"9\"}")).isEqualTo(PaperBot.DEFAULTS);
        assertThat(bot.settings("{\"coins\":[\"ETH\"],\"interval\":\"1d\",\"budget\":20000}").coins()).containsExactly("ETH");
    }

    @Test
    void 고르지_않은_코인은_새로_사지_않는다() {
        FakeCredits credits = new FakeCredits();
        PaperBot bot = new PaperBot(null, null, null, null, null, null, credits);
        PaperBot.Settings ethOnly = new PaperBot.Settings(List.of("ETH"), new BigDecimal("0.03"), BigDecimal.ZERO, "1h", 20_000);
        ObjectNode acc = fresh(JsonMapper.builder().build());
        assertThat(bot.creditTrade(7, PaperBot.BOOKS.getLast(), ethOnly, acc, krw(100, 99, 98, 97, 96, 95, 101, 104, 106, 99, 97, 300))).isEmpty();
        assertThat(bot.trade(PaperBot.BOOKS.getFirst(), ethOnly, fresh(JsonMapper.builder().build()), coin(100, 99, 98, 97, 96, 95, 101, 104, 106, 99, 97, 300)))
                .isEmpty();
        assertThat(credits.calls).isEmpty();
    }

    @Test
    void 선물은_위로_뚫으면_롱_아래로_뚫으면_롱을_정리하고_숏() {
        PaperBot.Rule r = new PaperBot.Rule(3, new BigDecimal("0.5"));
        List<String> last = List.of();
        for (int c : new int[] {100, 99, 98, 97, 105}) last = r.onBarFutures(BigDecimal.valueOf(c), null, BigDecimal.ZERO);
        assertThat(last).hasSize(1).first().asString().startsWith("LONG:");
        r = new PaperBot.Rule(3, new BigDecimal("0.5"));
        for (int c : new int[] {100, 101, 102, 103, 90}) last = r.onBarFutures(BigDecimal.valueOf(c), "LONG", BigDecimal.valueOf(100));
        assertThat(last).hasSize(2);
        assertThat(last.get(0)).startsWith("CLOSE:");
        assertThat(last.get(1)).startsWith("SHORT:");
        // 숏은 값이 올라야 손절이다
        r = new PaperBot.Rule(3, new BigDecimal("0.03"));
        for (int c : new int[] {100, 99, 98, 97, 104}) last = r.onBarFutures(BigDecimal.valueOf(c), "SHORT", BigDecimal.valueOf(100));
        assertThat(last).containsExactly("CLOSE:손절선 -3% 도달");
    }

    @Test
    void 정리하면_맡긴_돈에_손익을_더하고_수수료를_뺀다_잃어도_맡긴_돈까지만() {
        // 0.3개를 100에 롱, 110에 정리: 10 + 3 - 0.015 - 0.0165
        assertThat(PaperBot.settle(new BigDecimal("0.3"), new BigDecimal("100"), new BigDecimal("110"), BigDecimal.ONE, BigDecimal.TEN, new BigDecimal("0.0005")))
                .isEqualByComparingTo("12.9685");
        // 같은 숏은 3을 잃는다
        assertThat(PaperBot.settle(new BigDecimal("0.3"), new BigDecimal("100"), new BigDecimal("110"), BigDecimal.ONE.negate(), BigDecimal.TEN, new BigDecimal("0.0005")))
                .isEqualByComparingTo("6.9685");
        // 맡긴 돈보다 많이 잃으면 0
        assertThat(PaperBot.settle(new BigDecimal("0.3"), new BigDecimal("100"), new BigDecimal("200"), BigDecimal.ONE.negate(), BigDecimal.TEN, new BigDecimal("0.0005")))
                .isEqualByComparingTo("0");
        assertThat(PaperBot.usdt(new BigDecimal("100000"), new BigDecimal("1400"))).isEqualByComparingTo("71.42857142");
    }

    @Test
    void 선물_계좌는_크레딧을_USDT로_바꿔_롱에_들어가고_반대_신호에_되돌린_뒤_숏에_들어간다() {
        FakeCredits credits = new FakeCredits();
        PaperBot bot = new PaperBot(null, null, null, null, null, null, credits);
        PaperBot.Book book = PaperBot.FUTURES;
        PaperBot.Settings s = new PaperBot.Settings(List.of("BTC"), new BigDecimal("0.9"), BigDecimal.ZERO, "1h", 14_000);
        ObjectNode acc = fresh(JsonMapper.builder().build());
        BigDecimal rate = new BigDecimal("1400");

        List<PaperBot.Fill> in = bot.usdtTrade(7, book, s, acc, coin(100, 99, 98, 97, 96, 95, 101, 104, 106, 99, 97, 300), rate);
        assertThat(in).extracting(PaperBot.Fill::side).containsExactly("LONG");
        assertThat(credits.calls).containsExactly("OUT 14000 bot-usdt-binance_futures-BTC-" + 11 * HOUR + "-LONG");
        ObjectNode pos = (ObjectNode) acc.path("positions").path("BTCUSDT");
        assertThat(pos.path("side").asString()).isEqualTo("LONG");
        assertThat(new BigDecimal(pos.path("margin").asString())).isEqualByComparingTo("10"); // 14,000 크레딧 = 10 USDT
        assertThat(new BigDecimal(pos.path("quantity").asString())).isLessThan(new BigDecimal("0.1")); // 10×3배/300, 수수료만큼 덜

        // 같은 봉은 다시 거래하지 않는다
        assertThat(bot.usdtTrade(7, book, s, acc, coin(100, 99, 98, 97, 96, 95, 101, 104, 106, 99, 97, 300), rate)).isEmpty();

        // 아래로 뚫으면 롱을 정리해 크레딧으로 되돌리고 숏에 들어간다
        List<PaperBot.Fill> flip = bot.usdtTrade(7, book, s, acc, coin(100, 99, 98, 97, 96, 95, 101, 104, 106, 99, 97, 300, 250, 100), rate);
        assertThat(flip).extracting(PaperBot.Fill::side).containsExactly("CLOSE", "SHORT");
        assertThat(flip.getFirst().pnl()).isNegative();
        assertThat(credits.calls.get(1)).startsWith("IN 14000 ").endsWith("-CLOSE");
        assertThat(acc.path("positions").path("BTCUSDT").path("side").asString()).isEqualTo("SHORT");
    }

    @Test
    void 선물_반전에서_반대_진입만_실패하면_그_봉을_다시_보고_진입만_다시_낸다() {
        FakeCredits credits = new FakeCredits();
        PaperBot bot = new PaperBot(null, null, null, null, null, null, credits);
        PaperBot.Settings s = new PaperBot.Settings(List.of("BTC"), new BigDecimal("0.9"), BigDecimal.ZERO, "1h", 14_000);
        ObjectNode acc = fresh(JsonMapper.builder().build());
        BigDecimal rate = new BigDecimal("1400");
        bot.usdtTrade(7, PaperBot.FUTURES, s, acc, coin(100, 99, 98, 97, 96, 95, 101, 104, 106, 99, 97, 300), rate);

        credits.failKey = "-SHORT";
        Coin down = coin(100, 99, 98, 97, 96, 95, 101, 104, 106, 99, 97, 300, 250, 100);
        assertThat(bot.usdtTrade(7, PaperBot.FUTURES, s, acc, down, rate)).extracting(PaperBot.Fill::side).containsExactly("CLOSE");
        assertThat(acc.path("seen").path("BTCUSDT").asLong()).isEqualTo(11 * HOUR);

        credits.failKey = null;
        assertThat(bot.usdtTrade(7, PaperBot.FUTURES, s, acc, down, rate)).extracting(PaperBot.Fill::side).containsExactly("SHORT");
        assertThat(acc.path("seen").path("BTCUSDT").asLong()).isEqualTo(13 * HOUR);
    }

    @Test
    void 환율을_모르면_거래하지_않고_봉도_넘기지_않는다() {
        FakeCredits credits = new FakeCredits();
        PaperBot bot = new PaperBot(null, null, null, null, null, null, credits);
        ObjectNode acc = fresh(JsonMapper.builder().build());
        Coin c = coin(100, 99, 98, 97, 96, 95, 101, 104, 106, 99, 97, 300);
        assertThat(bot.usdtTrade(7, PaperBot.BOOKS.getFirst(), PaperBot.DEFAULTS, acc, c, null)).isEmpty();
        assertThat(credits.calls).isEmpty();
        // 환율이 들어오면 그 봉에서 산다 (현물은 레버리지 1배)
        assertThat(bot.usdtTrade(7, PaperBot.BOOKS.getFirst(), PaperBot.DEFAULTS, acc, c, new BigDecimal("1400")))
                .extracting(PaperBot.Fill::side).containsExactly("BUY");
        assertThat(credits.calls.getFirst()).startsWith("OUT ").endsWith("bot-usdt-binance-BTC-" + 11 * HOUR + "-BUY");
    }
}
