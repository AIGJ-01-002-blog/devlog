package com.team.blog.trade.web;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.team.blog.shared.security.CurrentMember;
import com.team.blog.shared.security.MemberPrincipal;
import com.team.blog.trade.application.PaperBot;
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
    private final PaperBot paper;

    public TradeBotController(TradeBotSim bot, TradeCharts charts, PaperBot paper) {
        this.bot = bot;
        this.charts = charts;
        this.paper = paper;
    }

    /** 서버가 돌리는 모의 봇(spec 102)이 켜져 있는지와 내가 고른 기준(#340) */
    @GetMapping("/api/me/trade-bot/paper")
    public ResponseEntity<PaperBot.Switch> paper(@CurrentMember MemberPrincipal me) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(paper.get(me.id()));
    }

    /** 켜면 바로 한 바퀴 돌고, 그 뒤로 5분마다 돈다. 끄면 다음 바퀴부터 멈추고 기록은 남는다. 기준(#340)은 보낸 것만 바꾼다 */
    @PutMapping("/api/me/trade-bot/paper")
    public PaperBot.Switch setPaper(@CurrentMember MemberPrincipal me, @RequestBody PaperBot.Change body) {
        return paper.set(me.id(), body);
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
