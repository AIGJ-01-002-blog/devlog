package com.team.blog.trade.application;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 매매 일지 설정 (#243). 2.0.0 릴리스 전까지 enabled=false로 화면·API·MCP 도구를 숨긴다.
 *
 * @param enabled      TRADES_ENABLED. 꺼져 있으면 /api/me/trades는 없는 주소(404)이고 MCP 도구도 보이지 않는다
 * @param dailyRecords 회원별 하루(한국 0시 기준) 체결 기록 상한. 봇이 고장 나 같은 주문을 끝없이 보내도 여기서 멈춘다
 */
@ConfigurationProperties(prefix = "blog.trades")
public record TradeProperties(@DefaultValue("false") boolean enabled,
                              @DefaultValue("500") int dailyRecords) {}
