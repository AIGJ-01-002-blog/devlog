package com.team.blog.paper.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

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

    class QuotesUnavailable extends RuntimeException {
        public QuotesUnavailable(String message) {
            super(message);
        }
    }
}
