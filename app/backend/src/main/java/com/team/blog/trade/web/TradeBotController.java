package com.team.blog.trade.web;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.team.blog.shared.security.CurrentMember;
import com.team.blog.shared.security.MemberPrincipal;
import com.team.blog.trade.application.TradeBotSim;

/**
 * 자동매매 모의 화면 API (spec 101). 로그인한 회원 본인 것만. TRADES_ENABLED가 꺼져 있으면 404다.
 * 기록은 매매 봇이 MCP 도구 report_trade_bot으로 보낸다(여기에는 받는 주소도, 주문을 내는 주소도 없다).
 */
@RestController
public class TradeBotController {
    private final TradeBotSim bot;

    public TradeBotController(TradeBotSim bot) {
        this.bot = bot;
    }

    @GetMapping("/api/me/trade-bot")
    public ResponseEntity<TradeBotSim.View> view(@CurrentMember MemberPrincipal me) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(bot.view(me.id()));
    }
}
