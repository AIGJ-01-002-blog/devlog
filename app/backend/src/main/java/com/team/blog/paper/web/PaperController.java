package com.team.blog.paper.web;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.team.blog.paper.application.PaperTrading;
import com.team.blog.shared.error.NotFoundException;
import com.team.blog.trade.application.TradeCharts;
import com.team.blog.shared.security.CurrentMember;
import com.team.blog.shared.security.MemberPrincipal;

/**
 * 포인트 모의투자 API (#267). 포인트·매매 일지 둘 중 하나라도 꺼져 있으면 404다.
 * <ul>
 *   <li>GET /api/me/paper: 다섯 코인 시세, 내 보유분, 지갑 잔액</li>
 *   <li>POST /api/me/paper/orders: 시장가로 사기·팔기. 같은 clientOrderId는 한 번만 체결</li>
 *   <li>GET /api/me/paper/chart?interval=15m: 원화 캔들과 내 체결·봇 체결 표시 (#324)</li>
 * </ul>
 */
@RestController
public class PaperController {
    private final PaperTrading paper;
    private final TradeCharts charts;

    public PaperController(PaperTrading paper, TradeCharts charts) {
        this.paper = paper;
        this.charts = charts;
    }

    @GetMapping("/api/me/paper")
    public ResponseEntity<PaperTrading.State> state(@CurrentMember MemberPrincipal me) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(paper.state(me.id()));
    }

    @GetMapping("/api/me/paper/chart")
    public ResponseEntity<TradeCharts.Chart> chart(@CurrentMember MemberPrincipal me, @RequestParam(required = false) String interval) {
        if (!paper.enabled()) throw new NotFoundException();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(charts.krw(me.id(), interval, PaperTrading.STRATEGY));
    }

    @PostMapping("/api/me/paper/orders")
    public PaperTrading.Filled order(@CurrentMember MemberPrincipal me, @RequestBody(required = false) PaperTrading.Order order) {
        return paper.order(me.id(), order);
    }
}
