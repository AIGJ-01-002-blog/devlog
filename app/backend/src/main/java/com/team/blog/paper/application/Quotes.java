package com.team.blog.paper.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

/** 코인 시세 (#267). 원화 가격(1P = 1원) */
public interface Quotes {
    /** @param changeRate 전날 종가 대비 등락률 (0.0123 = +1.23%) */
    record Quote(String symbol, BigDecimal price, BigDecimal changeRate, Instant at) {}

    /**
     * 심볼(BTC 등)마다 지금 시세. 받지 못하면 빈 Map이 아니라 예외를 던진다.
     *
     * @throws QuotesUnavailable 거래소가 답하지 않거나 이상한 값을 줄 때
     */
    Map<String, Quote> latest();

    /**
     * 1 USDT가 몇 원인지 (서버 모의 봇이 기본 크레딧을 USDT로 바꿀 때 쓴다, 1크레딧 = 1원). 같은 원화 거래소의 USDT 마켓 종가.
     * 받지 못하면 빈 값(예외를 던지지 않는다).
     */
    default Optional<BigDecimal> usdtKrw() {
        return Optional.empty();
    }

    class QuotesUnavailable extends RuntimeException {
        public QuotesUnavailable(String message) {
            super(message);
        }
    }
}
