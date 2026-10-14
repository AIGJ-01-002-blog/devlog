package com.team.blog.trade.web;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.team.blog.shared.security.CurrentMember;
import com.team.blog.shared.security.MemberPrincipal;
import com.team.blog.shorts.application.ShortsService;
import com.team.blog.trade.application.TradeReplays;

/**
 * 매매 재현 쇼츠 만들기 (#265). 매매 일지와 쇼츠가 모두 켜져 있어야 하고, 아니면 404다.
 * POST /api/me/shorts/replay { tradeIds: [..], interval: "15m" | null } → 새 쇼츠 대본 (스튜디오에서 고치고 영상으로 만든다)
 */
@RestController
public class TradeReplayController {
    private final TradeReplays replays;

    public TradeReplayController(TradeReplays replays) {
        this.replays = replays;
    }

    @PostMapping("/api/me/shorts/replay")
    public ResponseEntity<ShortsService.Item> create(@CurrentMember MemberPrincipal me, @RequestBody TradeReplays.Request body) {
        return ResponseEntity.status(HttpStatus.CREATED).body(replays.create(me.id(), body));
    }
}
