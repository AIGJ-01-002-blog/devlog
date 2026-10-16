package com.team.blog.trade.application;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 서버 모의 봇의 한 번 금액 정책 (#340, v2.2.0 리뷰). 단위는 기본 크레딧(1크레딧 = 1원).
 *
 * @param budget 회원이 고르지 않았을 때의 한 번 금액
 * @param min    고를 수 있는 가장 작은 금액. 포인트 모의투자 최소 주문과 같다
 * @param max    고를 수 있는 가장 큰 금액
 */
@ConfigurationProperties(prefix = "blog.trades.bot-budget")
public record PaperBotBudget(@DefaultValue("100000") long budget, @DefaultValue("5000") long min, @DefaultValue("1000000") long max) {
    public static final PaperBotBudget DEFAULT = new PaperBotBudget(100_000, 5_000, 1_000_000);

    public PaperBotBudget {
        if (min <= 0 || min > budget || budget > max) {
            throw new IllegalArgumentException("blog.trades.bot-budget은 0 < min <= budget <= max여야 합니다: " + min + ", " + budget + ", " + max);
        }
    }
}
