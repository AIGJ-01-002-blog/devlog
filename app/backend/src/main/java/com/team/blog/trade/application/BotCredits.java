package com.team.blog.trade.application;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * 서버 모의 봇이 회원의 기본 크레딧(투자 전용 지갑)으로 사고팔게 하는 창구 (#331). 포인트 모의투자(paper)가 구현한다.
 * 매매 일지(trade)가 포인트를 직접 부르지 않게 이 인터페이스로만 주문한다.
 * 주문은 봇 바퀴와 다른 트랜잭션에서 끝나고, 모자라거나 시세가 없으면 체결하지 않고 빈 값을 돌려준다(예외를 던지지 않는다).
 */
public interface BotCredits {

    /** @param pnl 판 체결의 실현 손익(원). 산 체결은 null */
    record Filled(BigDecimal quantity, BigDecimal price, BigDecimal pnl) {}

    /** 포인트와 매매 일지가 둘 다 켜졌는지. 꺼졌으면 봇은 가상 돈으로 돈다 */
    boolean enabled();

    /** 지금 쓸 수 있는 기본 크레딧 */
    long invest(long memberId);

    /** 지금 갖고 있는 이 코인 수량(내가 손으로 산 것 포함). 없으면 0 */
    BigDecimal held(long memberId, String coin);

    /** 기본 크레딧 amount(수수료 포함)로 산다. 같은 orderId는 한 번만 체결하고, 다시 부르면 그때 체결을 돌려준다 */
    Optional<Filled> buy(long memberId, String coin, long amount, String orderId, String reason);

    /** quantity만큼 판다. 가진 것보다 많으면 가진 만큼 판다 */
    Optional<Filled> sell(long memberId, String coin, BigDecimal quantity, String orderId, String reason);
}
