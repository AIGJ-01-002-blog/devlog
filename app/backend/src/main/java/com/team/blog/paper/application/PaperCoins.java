package com.team.blog.paper.application;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/** 포인트로 모의매매하는 다섯 코인 (#267, 블로그 주인 결정: 비트코인·이더리움·리플·솔라나·도지) */
public final class PaperCoins {
    public record Coin(String symbol, String name, int scale) {}

    /** scale은 수량 소수 자리. 비싼 코인은 잘게, 싼 코인은 덜 잘게 */
    public static final List<Coin> ALL = List.of(
            new Coin("BTC", "비트코인", 8),
            new Coin("ETH", "이더리움", 8),
            new Coin("XRP", "리플", 4),
            new Coin("SOL", "솔라나", 6),
            new Coin("DOGE", "도지코인", 2));

    private PaperCoins() {}

    public static Optional<Coin> of(String symbol) {
        if (symbol == null) return Optional.empty();
        String s = symbol.strip().toUpperCase(Locale.ROOT);
        return ALL.stream().filter(c -> c.symbol().equals(s)).findFirst();
    }
}
