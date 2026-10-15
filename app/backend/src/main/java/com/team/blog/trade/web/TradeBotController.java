package com.team.blog.trade.web;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.team.blog.shared.security.CurrentMember;
import com.team.blog.shared.security.MemberPrincipal;
import com.team.blog.trade.application.TradeBotSim;
import com.team.blog.trade.application.TradeCharts;

/**
 * 자동매매 모의 화면 API (spec 101). 로그인한 회원 본인 것만. TRADES_ENABLED가 꺼져 있으면 404다.
 * 기록은 매매 봇이 MCP 도구 report_trade_bot으로 보낸다(여기에는 받는 주소도, 주문을 내는 주소도 없다).
 */
@RestController
public class TradeBotController {
    private final TradeBotSim bot;
    private final TradeCharts charts;

    public TradeBotController(TradeBotSim bot, TradeCharts charts) {
        this.bot = bot;
        this.charts = charts;
    }

    @GetMapping("/api/me/trade-bot")
    public ResponseEntity<TradeBotSim.View> view(@CurrentMember MemberPrincipal me) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(bot.view(me.id()));
    }

    /** 차트 탭: 코인 5종의 공개 캔들(서버가 10초씩 들고 있음)과 그 위에 찍을 내 봇의 모의 체결 */
    @GetMapping("/api/me/trade-bot/charts")
    public ResponseEntity<TradeCharts.Chart> charts(@CurrentMember MemberPrincipal me, @RequestParam(defaultValue = "binance") String exchange,
                                                    @RequestParam(defaultValue = "15m") String interval) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(charts.chart(me.id(), exchange, interval));
    }
}
