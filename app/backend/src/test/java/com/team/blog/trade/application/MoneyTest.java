package com.team.blog.trade.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.Map;

import org.junit.jupiter.api.Test;

/** #254 통화별 금액 표기와 코인 수량 단위 */
class MoneyTest {
    @Test
    void 원화는_원_단위_다른_통화는_소수_둘째_자리까지_적는다() {
        assertThat(Money.format(new BigDecimal("14600"), "KRW", true)).isEqualTo("+14,600원");
        assertThat(Money.format(new BigDecimal("-24300.4"), "KRW", true)).isEqualTo("-24,300원");
        assertThat(Money.format(new BigDecimal("83752.3"), "USDT", false)).isEqualTo("83,752.30 USDT");
        assertThat(Money.format(new BigDecimal("1.0549"), "USDT", true)).isEqualTo("+1.05 USDT");
        assertThat(Money.format(new BigDecimal("0.000123"), "USDT", false)).isEqualTo("0.000123 USDT");
        assertThat(Money.format(BigDecimal.ZERO, "USDT", true)).isEqualTo("0.00 USDT");
    }

    @Test
    void 여러_통화는_원화를_먼저_잇고_부호는_모두_같을_때만_정한다() {
        Map<String, BigDecimal> m = Money.empty();
        Money.add(m, "USDT", new BigDecimal("2"));
        Money.add(m, "KRW", new BigDecimal("-100"));
        assertThat(Money.format(m, "KRW", true)).isEqualTo("-100원 · +2.00 USDT");
        assertThat(Money.signum(m)).isZero();
        assertThat(Money.format(Money.empty(), "USDT", true)).isEqualTo("0.00 USDT");
        assertThat(Money.signum(Money.plus(Map.of("USDT", BigDecimal.ONE), Map.of("KRW", BigDecimal.TEN)))).isEqualTo(1);
    }

    @Test
    void 코인은_마켓_코드에서_코인_이름을_읽는다() {
        assertThat(Money.unit("BINANCE", "BTCUSDT", "USDT")).isEqualTo("BTC");
        assertThat(Money.unit("UPBIT", "KRW-BTC", "KRW")).isEqualTo("BTC");
        assertThat(Money.unit("BYBIT", "ETH-USDT", "USDT")).isEqualTo("ETH");
        assertThat(Money.unit("", "005930", "KRW")).isEqualTo("주");
        assertThat(Money.exchangeName("UPBIT")).isEqualTo("업비트");
    }
}
