package com.team.blog.trade.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.team.blog.shared.error.FieldErrorItem;

/** #283·#284 판단 흔적 다듬기와 범위 검사 */
class TradeTraceTest {

    static TradeTrace trace(BigDecimal risk, BigDecimal liquidation, TradeTrace.ExitReason exit, BigDecimal r, BigDecimal funding) {
        return new TradeTrace(" ladder-v0.1 ", "20261015T09-BTC-1h", TradeTrace.Basket.CORE, "up", "pullback", "breakout10", 2, "macro_event, fomc",
                risk, null, null, null, null, null, null, null, liquidation, " gemini ", null, exit, r, funding);
    }

    static List<String> fields(List<FieldErrorItem> errors) {
        return errors.stream().map(FieldErrorItem::field).toList();
    }

    @Test
    void 글자를_다듬고_맞는_값은_그대로_둔다() {
        List<FieldErrorItem> errors = new ArrayList<>();
        TradeTrace t = trace(new BigDecimal("0.005"), BigDecimal.TEN, TradeTrace.ExitReason.TP1, new BigDecimal("1.8"), BigDecimal.ONE)
                .validate(errors, true, true);
        assertThat(errors).isEmpty();
        assertThat(t.strategyVersion()).isEqualTo("ladder-v0.1");
        assertThat(t.regime()).isEqualTo("UP");
        assertThat(t.trigger()).isEqualTo("BREAKOUT10");
        assertThat(t.newsFlags()).isEqualTo("MACRO_EVENT,FOMC");
        assertThat(t.aiModel()).isEqualTo("gemini");
        assertThat(t.present()).isTrue();
        assertThat(TradeTrace.NONE.present()).isFalse();
    }

    @Test
    void 비율_선물_전용_정리_전용_값의_자리를_본다() {
        List<FieldErrorItem> errors = new ArrayList<>();
        trace(new BigDecimal("1.2"), BigDecimal.TEN, TradeTrace.ExitReason.STOP, BigDecimal.ONE.negate(), BigDecimal.ONE)
                .validate(errors, false, false);
        assertThat(fields(errors)).containsExactlyInAnyOrder("risk_pct", "liquidation_price", "funding", "exit_reason", "r_multiple");
    }

    @Test
    void 키와_거름망_글자_길이와_모양을_본다() {
        List<FieldErrorItem> errors = new ArrayList<>();
        new TradeTrace("v".repeat(41), "a b", null, "up-trend", "", "", 11, "bad flag!", null, null, null, null, new BigDecimal("-1"), null,
                null, null, null, "m".repeat(61), null, null, new BigDecimal("1E9"), null).validate(errors, true, true);
        assertThat(fields(errors)).containsExactlyInAnyOrder("strategy_version", "decision_id", "regime", "news_score", "news_flags",
                "equity_at_entry", "ai_model", "r_multiple");
    }

    @Test
    void AI_비용은_numeric_18_8_범위까지만_받는다() {
        List<FieldErrorItem> ok = new ArrayList<>();
        new TradeTrace(null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, new BigDecimal("9999999999.99999999"), null, null, null).validate(ok, false, false);
        assertThat(ok).isEmpty();
        List<FieldErrorItem> errors = new ArrayList<>();
        new TradeTrace(null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, new BigDecimal("10000000000"), null, null, null).validate(errors, false, false);
        assertThat(fields(errors)).containsExactly("ai_cost");
    }

    @Test
    void 뉴스_점수_최솟값과_저장할_수_없는_자릿수의_비율은_막는다() {
        List<FieldErrorItem> errors = new ArrayList<>();
        new TradeTrace(null, null, null, null, null, null, Integer.MIN_VALUE, null, new BigDecimal("0.000000001"), new BigDecimal("0.123456789"),
                new BigDecimal("0.500000001"), new BigDecimal("0.999999999"), null, null, null, null, null, null, null, null, null, null)
                .validate(errors, false, false);
        assertThat(fields(errors)).containsExactlyInAnyOrder("news_score", "risk_pct", "kelly_raw", "kelly_used", "ai_conf");
        List<FieldErrorItem> ok = new ArrayList<>();
        new TradeTrace(null, null, null, null, null, null, -TradeTrace.NEWS_SCORE_MAX, null, new BigDecimal("0.00000001"), new BigDecimal("0.50000000000"),
                BigDecimal.ONE, new BigDecimal("0.12345678"), null, null, null, null, null, null, null, null, null, null)
                .validate(ok, false, false);
        assertThat(ok).isEmpty();
    }
}
