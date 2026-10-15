package com.team.blog.trade.application;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 매매 일지 설정 (#243). 2.0.0 릴리스 전까지 enabled=false로 화면·API·MCP 도구를 숨긴다.
 *
 * @param enabled      TRADES_ENABLED. 꺼져 있으면 /api/me/trades는 없는 주소(404)이고 MCP 도구도 보이지 않는다
 * @param dailyRecords TRADES_DAILY_LIMIT. 회원별 하루(한국 0시 기준) 체결 기록 상한. 봇이 고장 나 같은 주문을 끝없이 보내도 여기서 멈춘다
 * @param binanceUrl   자동매매 화면 차트(spec 101)의 바이낸스 현물 공개 캔들 주소 (키 없음). 시험은 가짜 서버로 바꾼다
 * @param upbitUrl     같은 차트의 업비트 공개 캔들 주소 (키 없음)
 * @param chartTtl     차트 캔들을 다시 받기 전까지 들고 있는 시간. 회원이 많아도 거래소에는 이 간격으로만 간다
 */
@ConfigurationProperties(prefix = "blog.trades")
public record TradeProperties(@DefaultValue("false") boolean enabled,
                              @DefaultValue("2000") int dailyRecords,
                              @DefaultValue("https://data-api.binance.vision") String binanceUrl,
                              @DefaultValue("https://api.upbit.com") String upbitUrl,
                              @DefaultValue("10s") Duration chartTtl) {}
