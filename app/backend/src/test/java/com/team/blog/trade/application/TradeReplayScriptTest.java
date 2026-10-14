package com.team.blog.trade.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.team.blog.shorts.application.OriginalityCheck;
import com.team.blog.shorts.application.ShortsScript;
import com.team.blog.shorts.application.TradeReplay;

/** #265 매매 재현 쇼츠의 처음 대본: 장면 순서(도입 → 체결마다 하나 → 결과·메모·고지)와 길이 */
class TradeReplayScriptTest {
    static TradeReplay replay(int fills, String reason) {
        List<TradeReplay.Fill> list = new ArrayList<>();
        Instant t = Instant.parse("2026-10-13T01:15:00Z");
        for (int i = 0; i < fills; i++) {
            boolean sell = i % 2 == 1;
            list.add(new TradeReplay.Fill(i + 1, sell ? "SELL" : "BUY", new BigDecimal(92100 + i * 37), new BigDecimal("0.01"),
                    sell ? new BigDecimal("4.5") : null, t.plusSeconds(i * 1700L), reason.isEmpty() ? "" : reason + " " + i));
        }
        return new TradeReplay("BINANCE", "BTCUSDT", "비트코인", "USDT", "PAPER", "15m", t.minusSeconds(3600), t.plusSeconds(20_000), list);
    }

    @Test
    void 체결마다_장면을_두고_고지로_끝난다() {
        List<ShortsScript.Scene> scenes = TradeReplays.scenes(replay(2, "20일선 위로 거래량 두 배"), "두 번째 매수는 서둘렀어요.");
        assertThat(scenes).hasSize(2 + 4);
        assertThat(scenes.get(0).narration()).startsWith("그때 저는 왜 여기서 매수했을까요?");
        assertThat(scenes.get(1).caption()).isEqualTo("10:15 매수 92,100.00 USDT");
        assertThat(scenes.get(1).narration()).isEqualTo("10시 15분, 20일선 위로 거래량 두 배 0 그래서 92,100.00 USDT에 매수했어요.");
        assertThat(scenes.get(2).narration()).contains("+4.50 USDT이 확정됐어요");
        assertThat(scenes.get(3).caption()).isEqualTo("결과 +4.50 USDT");
        assertThat(scenes.get(4).narration()).endsWith("두 번째 매수는 서둘렀어요.");
        assertThat(scenes.getLast().narration()).contains("모의투자", "투자 권유가 아니에요");
        assertThat(ShortsScript.checkRender(TradeReplays.title(replay(2, "")), scenes)).isEmpty();
        assertThat(OriginalityCheck.check(scenes, null, List.of()).passed()).isTrue();
    }

    @Test
    void 체결이_많고_이유가_길면_60초_안으로_줄인다() {
        String reason = "가".repeat(200);
        List<ShortsScript.Scene> scenes = TradeReplays.scenes(replay(TradeReplay.FILLS_MAX, reason), "메".repeat(500));
        assertThat(scenes).hasSize(ShortsScript.SCENES_MAX);
        assertThat(ShortsScript.estimateSeconds(scenes)).isLessThanOrEqualTo(ShortsScript.SECONDS_MAX);
        assertThat(ShortsScript.checkSave("t", scenes)).isEmpty();
    }

    @Test
    void 체결_줄은_AI가_읽을_수_있게_시각_수량_가격_이유를_담는다() {
        assertThat(TradeReplays.fillLines(replay(2, "돌파"))).containsExactly(
                "10월 13일 10:15 매수 0.01 BTC @ 92,100.00 USDT · 이유: 돌파 0",
                "10월 13일 10:43 매도 0.01 BTC @ 92,137.00 USDT · 손익 +4.50 USDT · 이유: 돌파 1");
    }
}
