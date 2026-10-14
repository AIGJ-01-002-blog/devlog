package com.team.blog.trade.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * 통화별 금액 (#254). 원화와 USDT를 더하지 않도록 합계·손익은 통화마다 따로 둔다.
 * 원화는 원 단위(1,234원), 그 밖의 통화는 소수 둘째 자리(12.34 USDT), 1보다 작은 값은 여덟째 자리까지 보인다.
 */
public final class Money {
    public static final String KRW = "KRW";
    /** 원화를 먼저, 나머지는 이름 순 */
    static final Comparator<String> ORDER = Comparator.comparing((String c) -> !c.equals(KRW)).thenComparing(Comparator.naturalOrder());
    /** 코인 거래소. 수량을 "주" 대신 코인 이름으로 읽는다 */
    static final Set<String> CRYPTO_EXCHANGES = Set.of("BINANCE", "UPBIT", "BYBIT", "BITHUMB", "OKX", "COINBASE", "KRAKEN", "BITGET", "KORBIT", "COINONE");
    private static final Map<String, String> EXCHANGE_NAMES = Map.of("BINANCE", "바이낸스", "UPBIT", "업비트", "BYBIT", "바이비트", "BITHUMB", "빗썸",
            "KIS", "한국투자증권");

    private Money() {}

    public static Map<String, BigDecimal> empty() {
        return new TreeMap<>(ORDER);
    }

    static void add(Map<String, BigDecimal> into, String currency, BigDecimal amount) {
        into.merge(currency, amount == null ? BigDecimal.ZERO : amount, BigDecimal::add);
    }

    static Map<String, BigDecimal> plus(Map<String, BigDecimal> a, Map<String, BigDecimal> b) {
        Map<String, BigDecimal> out = empty();
        out.putAll(a);
        b.forEach((c, v) -> add(out, c, v));
        return out;
    }

    /** 금액 하나. sign이면 0보다 큰 값에 +를 붙인다 (색만으로 손익을 구분하지 않게) */
    static String format(BigDecimal amount, String currency, boolean sign) {
        BigDecimal v = amount == null ? BigDecimal.ZERO : amount;
        String body;
        if (KRW.equals(currency)) {
            v = v.setScale(0, RoundingMode.HALF_UP);
            body = new DecimalFormat("#,##0").format(v.abs()) + "원";
        } else {
            v = v.abs().compareTo(BigDecimal.ONE) >= 0 || v.signum() == 0 ? v.setScale(2, RoundingMode.HALF_UP) : v.setScale(8, RoundingMode.HALF_UP);
            BigDecimal shown = v.abs().stripTrailingZeros();
            if (shown.scale() < 2) shown = shown.setScale(2, RoundingMode.UNNECESSARY);
            DecimalFormat f = new DecimalFormat("#,##0");
            f.setMinimumFractionDigits(shown.scale());
            f.setMaximumFractionDigits(shown.scale());
            body = f.format(shown) + " " + currency;
        }
        if (v.signum() < 0) return "-" + body;
        return sign && v.signum() > 0 ? "+" + body : body;
    }

    /** 통화가 여럿이면 " · "로 잇는다. 비어 있으면 fallback 통화의 0 */
    static String format(Map<String, BigDecimal> amounts, String fallback, boolean sign) {
        if (amounts.isEmpty()) return format(BigDecimal.ZERO, fallback, sign);
        return amounts.entrySet().stream().map(e -> format(e.getValue(), e.getKey(), sign)).collect(Collectors.joining(" · "));
    }

    /** 통화들의 부호를 하나로: 모두 0 이상이고 하나라도 양수면 1, 모두 0 이하이고 하나라도 음수면 -1, 아니면 0 */
    static int signum(Map<String, BigDecimal> amounts) {
        boolean up = amounts.values().stream().anyMatch(v -> v.signum() > 0);
        boolean down = amounts.values().stream().anyMatch(v -> v.signum() < 0);
        return up == down ? 0 : up ? 1 : -1;
    }

    static boolean crypto(String exchange) {
        return CRYPTO_EXCHANGES.contains(exchange);
    }

    static String exchangeName(String exchange) {
        return EXCHANGE_NAMES.getOrDefault(exchange, exchange);
    }

    /**
     * 수량 단위. 주식은 "주", 코인은 코인 이름(BTC).
     * 업비트 KRW-BTC는 통화가 앞, BTC-USDT 꼴은 뒤, 바이낸스·바이비트 BTCUSDT는 통화를 뗀 앞부분이 코인이다.
     */
    static String unit(String exchange, String symbol, String currency) {
        if (!crypto(exchange)) return "주";
        String s = symbol.toUpperCase(Locale.ROOT);
        if (s.contains("-")) {
            List<String> parts = List.of(s.split("-", 2));
            return parts.get(0).equals(currency) ? parts.get(1) : parts.get(0);
        }
        return s.endsWith(currency) && s.length() > currency.length() ? s.substring(0, s.length() - currency.length()) : s;
    }
}
