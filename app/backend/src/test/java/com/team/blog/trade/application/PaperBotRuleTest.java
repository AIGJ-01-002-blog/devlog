package com.team.blog.trade.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

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
        PaperBot bot = new PaperBot(null, null, null, null, null, null);
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
}
