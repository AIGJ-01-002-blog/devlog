package com.team.blog.paper.application;

import java.math.BigDecimal;
import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 포인트 모의투자 설정 (#267). 포인트(POINTS_ENABLED)와 매매 일지(TRADES_ENABLED)가 둘 다 켜져야 쓴다.
 *
 * @param baseUrl  시세를 받는 거래소 공개 API 주소 (키 없음). 시험은 가짜 서버로 바꾼다
 * @param feeRate  거래 수수료율. 업비트 원화 시장과 같은 0.05%
 * @param minOrder 한 번에 사고팔 수 있는 가장 작은 금액(포인트). 업비트 최소 주문 5,000원과 같다
 * @param cacheTtl 시세를 다시 받기 전까지 쓰는 시간
 * @param source   시세를 받는 거래소. korbit(기본) 또는 upbit. 학교 서버에서 업비트가 막혀 있어 코빗을 쓴다(v2.1.3)
 */
@ConfigurationProperties(prefix = "blog.paper")
public record PaperProperties(@DefaultValue("https://api.korbit.co.kr") String baseUrl,
                              @DefaultValue("0.0005") BigDecimal feeRate,
                              @DefaultValue("5000") long minOrder,
                              @DefaultValue("5s") Duration cacheTtl,
                              @DefaultValue("korbit") String source) {
    public static final String KORBIT = "korbit";
    public static final String UPBIT = "upbit";

    public PaperProperties {
        if (!KORBIT.equals(source) && !UPBIT.equals(source)) {
            throw new IllegalArgumentException("blog.paper.source는 korbit 또는 upbit여야 합니다: " + source);
        }
    }
}
