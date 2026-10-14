package com.team.blog.point.application;

/** 포인트 지갑 (#266). 가입 첫 지급은 모의투자에만, 활동으로 번 포인트는 상점에서도 쓴다 */
public enum Wallet {
    /** 가입 첫 지급. 모의투자에만 쓰고 상점에서는 못 쓴다 */
    INVEST_ONLY,
    /** 출석·연속 출석·글 발행·모의투자 수익으로 번 포인트. 상점에서 꾸미기 아이템을 산다 */
    SHOP
}
