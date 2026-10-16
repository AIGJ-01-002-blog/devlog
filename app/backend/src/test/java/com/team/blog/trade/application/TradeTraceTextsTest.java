package com.team.blog.trade.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.team.blog.shorts.application.ShortsScript;

/** #285 판단 흔적을 요약 글·쇼츠 대본·기법별 성적에 넣는다 */
class TradeTraceTextsTest {
    static final Instant AT = Instant.parse("2026-10-15T01:00:00Z");

    static TradeTrace entry(String version, String setup) {
        return new TradeTrace(version, "d-1", TradeTrace.Basket.TRADING, "UP", setup, "BREAKOUT10", null, "", null, null, null, null, null,
                new BigDecimal("58800"), null, null, null, "", null, null, null, null);
    }

    static TradeTrace exit(String version, String setup, TradeTrace.ExitReason reason, String r) {
        return new TradeTrace(version, "d-1", null, "", setup, "", null, "", null, null, null, null, null, null, null, null, null, "", null,
                reason, r == null ? null : new BigDecimal(r), null);
    }

    static TradeJournal.Fill fill(long id, String side, BigDecimal pnl, TradeTrace t, int minutes) {
        return fill("PAPER", id, side, pnl, t, minutes);
    }

    static TradeJournal.Fill fill(String mode, long id, String side, BigDecimal pnl, TradeTrace t, int minutes) {
        return new TradeJournal.Fill(id, "x-" + id, mode, "BINANCE", "BTCUSDT", "비트코인", side, new BigDecimal("0.01"), new BigDecimal("60000"),
                "USDT", BigDecimal.ZERO, pnl, "", "눌림 뒤 돌파", AT.plusSeconds(minutes * 60L), "SPOT", null, null, "1h", t);
    }

    static TradeJournal.Day day(List<TradeJournal.Fill> fills) {
        return new TradeJournal.Day(LocalDate.of(2026, 10, 15), fills, TradeJournal.summarize(fills), Map.of("USDT", BigDecimal.TEN),
                "규칙대로 1차 익절에서 반을 덜었어요.", null, null);
    }

    @Test
    void 정리_이유와_R배수_바구니와_셋업을_짧게_적는다() {
        assertThat(TradeTexts.exitLabel(fill(1, "SELL", BigDecimal.ONE, exit("v1", "PULLBACK", TradeTrace.ExitReason.TP1, "1.8"), 0)))
                .isEqualTo("TP1 정리 +1.8R");
        assertThat(TradeTexts.exitLabel(fill(1, "SELL", BigDecimal.ONE, exit("v1", "", null, "-1"), 0))).isEqualTo("정리 -1R");
        assertThat(TradeTexts.exitLabel(fill(1, "BUY", null, entry("v1", "PULLBACK"), 0))).isEmpty();
        assertThat(TradeTexts.setupLabel(fill(1, "BUY", null, entry("v1", "PULLBACK"), 0))).isEqualTo("트레이딩 · PULLBACK");
        assertThat(TradeTexts.setupLabel(fill(1, "BUY", null, TradeTrace.NONE, 0))).isEmpty();
    }

    @Test
    void 기법_버전_셋업별로_승률과_평균_R을_낸다() {
        List<TradeJournal.Fill> fills = List.of(
                fill(1, "BUY", null, entry("v1", "PULLBACK"), 0),
                fill(2, "SELL", BigDecimal.ONE, exit("v1", "PULLBACK", TradeTrace.ExitReason.TP1, "1.8"), 10),
                fill(3, "SELL", BigDecimal.ONE.negate(), exit("v1", "PULLBACK", TradeTrace.ExitReason.STOP, "-1"), 20),
                fill(4, "SELL", BigDecimal.ONE, exit("v2", "BREAKOUT", TradeTrace.ExitReason.TRAIL, "2.5"), 30),
                fill(5, "SELL", BigDecimal.ONE, TradeTrace.NONE, 40));
        List<TradeJournal.SetupStat> stats = TradeJournal.setupStats(fills);
        assertThat(stats).containsExactly(
                new TradeJournal.SetupStat("PAPER", "v1", "PULLBACK", 2, 1, new BigDecimal("0.40")),
                new TradeJournal.SetupStat("PAPER", "v2", "BREAKOUT", 1, 1, new BigDecimal("2.50")));

        String body = TradeTexts.draftBody(day(fills));
        assertThat(body).contains("## 기법별 성적", "| v1 | PULLBACK | 2번 | 50% | +0.4R |", "[트레이딩 · PULLBACK] 눌림 뒤 돌파", "TP1 정리 +1.8R 눌림 뒤 돌파");
    }

    @Test
    void 정리_체결에_기법이_비면_진입_체결에서_잇고_실거래와_모의는_따로_센다() {
        List<TradeJournal.Fill> fills = List.of(
                fill(1, "BUY", null, entry("v1", "PULLBACK"), 0),
                fill(2, "SELL", BigDecimal.ONE, exit("", "", TradeTrace.ExitReason.TP1, "1.8"), 10),
                fill("LIVE", 3, "SELL", BigDecimal.ONE.negate(), exit("v1", "PULLBACK", TradeTrace.ExitReason.STOP, "-1"), 20));
        assertThat(TradeJournal.setupStats(fills)).containsExactlyInAnyOrder(
                new TradeJournal.SetupStat("PAPER", "v1", "PULLBACK", 1, 1, new BigDecimal("1.80")),
                new TradeJournal.SetupStat("LIVE", "v1", "PULLBACK", 1, 0, new BigDecimal("-1.00")));
        assertThat(TradeTexts.draftBody(day(fills))).contains("| v1 | PULLBACK | 1번 | 100% | +1.8R |", "| v1 (실계좌) | PULLBACK | 1번 | 0% | -1R |");
    }

    @Test
    void 기간_앞_진입_체결은_기법만_잇고_세지_않는다() {
        List<TradeJournal.Fill> earlier = List.of(fill(1, "BUY", null, entry("v1", "PULLBACK"), 0));
        List<TradeJournal.Fill> fills = List.of(fill(2, "SELL", BigDecimal.ONE, exit("", "", TradeTrace.ExitReason.TP1, "1.8"), 10));
        assertThat(TradeJournal.setupStats(fills, earlier)).containsExactly(
                new TradeJournal.SetupStat("PAPER", "v1", "PULLBACK", 1, 1, new BigDecimal("1.80")));
        assertThat(TradeJournal.setupStats(fills)).containsExactly(new TradeJournal.SetupStat("PAPER", "", "", 1, 1, new BigDecimal("1.80")));
    }

    @Test
    void 쇼츠_대본에_정리_이유와_R을_말한다() {
        List<TradeJournal.Fill> fills = List.of(fill(2, "SELL", new BigDecimal("18"), exit("v1", "PULLBACK", TradeTrace.ExitReason.TP1, "1.8"), 10));
        List<ShortsScript.Scene> scenes = TradeTexts.shortsScenes(day(fills));
        assertThat(scenes).anySatisfy(s -> {
            assertThat(s.caption()).contains("TP1 정리 +1.8R");
            assertThat(s.narration()).contains("1차 익절로 +1.8R이에요.");
        });
        // 흔적 없는 체결은 예전 문장 그대로
        assertThat(TradeTexts.draftBody(day(List.of(fill(9, "BUY", null, TradeTrace.NONE, 0))))).doesNotContain("기법별 성적", "[");
    }
}
