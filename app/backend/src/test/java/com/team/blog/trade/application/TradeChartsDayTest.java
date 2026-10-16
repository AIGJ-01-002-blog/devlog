package com.team.blog.trade.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.team.blog.trade.application.TradeCharts.Candle;

/** #354: 차트 머리말의 24시간 고가·저가·거래량은 마지막 1시간 봉 24개로 센다 */
class TradeChartsDayTest {

    @Test
    void 마지막_24개_봉만_센다() {
        List<Candle> hourly = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            BigDecimal c = BigDecimal.valueOf(100 + i);
            // 앞쪽 6개는 24시간 밖이라 아주 높은 고가가 있어도 세지 않는다
            BigDecimal h = i < 6 ? BigDecimal.valueOf(9999) : c.add(BigDecimal.ONE);
            hourly.add(new Candle(i, c, h, c.subtract(BigDecimal.ONE), c, BigDecimal.valueOf(2)));
        }
        TradeCharts.Day d = TradeCharts.day(hourly);
        assertThat(d.high()).isEqualByComparingTo("130");
        assertThat(d.low()).isEqualByComparingTo("105");
        assertThat(d.volume()).isEqualByComparingTo("48");
        // 거래대금 = 2 × (106 + … + 129)
        assertThat(d.quoteVolume()).isEqualByComparingTo(String.valueOf(2 * (106 + 129) * 24 / 2));
        assertThat(TradeCharts.day(List.of())).isNull();
    }
}
