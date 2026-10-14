package com.team.blog.point.application;

/**
 * 포인트 적립 규칙 (#266). 기본값은 여기 두고, 관리자가 바꾼 값은 point_rule 테이블에 남는다.
 * 포인트 1점은 모의투자에서 1원으로 센다(매매 봇 모의 계좌 기본 현금 1,000만 원과 같은 크기).
 */
public enum PointRule {
    WELCOME("가입 첫 지급 (투자 전용)", 10_000_000, 1_000_000_000),
    ATTENDANCE("출석 한 번", 100, 100_000),
    STREAK_DAYS("연속 출석 보너스 주기 (일)", 7, 365),
    STREAK_BONUS("연속 출석 보너스", 700, 1_000_000),
    POST("글 발행 한 편", 300, 100_000),
    POST_DAILY_MAX("글 발행 적립 하루 상한 (편)", 3, 50),
    POST_MIN_CHARS("적립되는 글의 최소 글자 수", 300, 20_000),
    TRADE_PROFIT_PERCENT("모의투자 실현 수익의 적립 비율 (%)", 1, 100),
    TRADE_PROFIT_DAILY_MAX("모의투자 수익 적립 하루 상한", 3_000, 10_000_000);

    private final String label;
    private final long defaultValue;
    private final long max;

    PointRule(String label, long defaultValue, long max) {
        this.label = label;
        this.defaultValue = defaultValue;
        this.max = max;
    }

    public String label() {
        return label;
    }

    public long defaultValue() {
        return defaultValue;
    }

    /** 관리자가 넣을 수 있는 가장 큰 값 (오타로 0이 하나 더 붙는 것을 막는다) */
    public long max() {
        return max;
    }
}
