package com.team.blog.trade.application;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import com.team.blog.shared.error.FieldErrorItem;

/**
 * 봇이 체결과 함께 보내는 판단 흔적 (#283 진입, #284 정리, 목표 v2.3.0 #344). 모두 선택 값이다.
 * 진입 값(기법 버전·판단 키·바구니·거름망·비중·손절익절)은 어느 체결에나 붙일 수 있고,
 * 정리 값(정리 이유·R배수)은 포지션을 닫는 체결에만, 청산가·펀딩비는 선물에만 받는다.
 * 관망 기록·뉴스 원문·AI 입력 같은 판단 원본은 봇 로컬 DB에만 두고 여기에는 요약 값만 남긴다.
 *
 * @param basket CORE(코어 적립) | TRADING(트레이딩). null이면 알리지 않은 것
 * @param regime·setup·trigger 세 겹 거름망 결과 (예: UP, PULLBACK, BREAKOUT10)
 * @param riskPct·kellyRaw·kellyUsed·aiConf 0~1 비율
 * @param funding 선물 펀딩비 (낸 돈은 음수). 수수료는 체결의 fee에 적는다
 */
public record TradeTrace(String strategyVersion, String decisionId, Basket basket, String regime, String setup, String trigger,
                         Integer newsScore, String newsFlags, BigDecimal riskPct, BigDecimal kellyRaw, BigDecimal kellyUsed,
                         BigDecimal aiConf, BigDecimal equityAtEntry, BigDecimal stopPrice, BigDecimal tp1Price, BigDecimal tp2Price,
                         BigDecimal liquidationPrice, String aiModel, BigDecimal aiCost, ExitReason exitReason, BigDecimal rMultiple,
                         BigDecimal funding) {

    public static final TradeTrace NONE = new TradeTrace("", "", null, "", "", "", null, "", null, null, null, null, null, null, null,
            null, null, "", null, null, null, null);

    public static final int STRATEGY_VERSION_MAX = 40;
    public static final int DECISION_ID_MAX = 80;
    public static final int STAGE_MAX = 30;
    public static final int NEWS_FLAGS_MAX = 120;
    public static final int AI_MODEL_MAX = 60;
    public static final int NEWS_SCORE_MAX = 10;
    /** numeric(12, 4)에 넉넉히 들어가는 R배수 */
    private static final BigDecimal R_MAX = new BigDecimal("99999999");
    private static final BigDecimal AMOUNT_MAX = new BigDecimal("99999999999999");
    /** ai_cost는 numeric(18, 8)이라 정수 10자리까지만 들어가요. */
    private static final BigDecimal AI_COST_MAX = new BigDecimal("9999999999.99999999");
    private static final Pattern KEY = Pattern.compile("[A-Za-z0-9._:+-]*");
    private static final Pattern STAGE = Pattern.compile("[A-Z0-9_]{0," + STAGE_MAX + "}");
    private static final Pattern FLAGS = Pattern.compile("[A-Z0-9_,]{0," + NEWS_FLAGS_MAX + "}");
    private static final String INVALID = "INVALID";
    private static final String TOO_LONG = "TOO_LONG";

    public enum Basket { CORE, TRADING }

    public enum ExitReason { STOP, TP1, TP2, TRAIL, TIME, NEWS, MANUAL }

    /** 판단 흔적이 하나라도 있는가 */
    public boolean present() {
        return !equals(NONE);
    }

    /**
     * 값을 다듬고 범위를 본다. 틀린 값은 errors에 더하고, 맞으면 다듬은 흔적을 돌려준다.
     * @param futures 선물 체결인가 (청산가·펀딩비)
     * @param closes 포지션을 닫는 체결인가 (정리 이유·R배수)
     */
    TradeTrace validate(List<FieldErrorItem> errors, boolean futures, boolean closes) {
        String version = trim(strategyVersion);
        String decision = trim(decisionId);
        String reg = trim(regime).toUpperCase(Locale.ROOT);
        String set = trim(setup).toUpperCase(Locale.ROOT);
        String trg = trim(trigger).toUpperCase(Locale.ROOT);
        String flags = trim(newsFlags).toUpperCase(Locale.ROOT).replaceAll("\\s+", "");
        String model = trim(aiModel);
        key(errors, "strategy_version", version, STRATEGY_VERSION_MAX, "기법 버전");
        key(errors, "decision_id", decision, DECISION_ID_MAX, "판단 키");
        stage(errors, "regime", reg);
        stage(errors, "setup", set);
        stage(errors, "trigger", trg);
        if (!FLAGS.matcher(flags).matches()) {
            errors.add(new FieldErrorItem("news_flags", INVALID, "뉴스 표시(news_flags)는 MACRO_EVENT처럼 영문 대문자를 쉼표로 이어 " + NEWS_FLAGS_MAX + "자까지예요."));
        }
        if (newsScore != null && Math.abs(newsScore) > NEWS_SCORE_MAX) {
            errors.add(new FieldErrorItem("news_score", INVALID, "뉴스 점수(news_score)는 -" + NEWS_SCORE_MAX + "~" + NEWS_SCORE_MAX + "예요."));
        }
        fraction(errors, "risk_pct", riskPct);
        fraction(errors, "kelly_raw", kellyRaw);
        fraction(errors, "kelly_used", kellyUsed);
        fraction(errors, "ai_conf", aiConf);
        for (var p : List.of(new Named("equity_at_entry", equityAtEntry), new Named("stop_price", stopPrice), new Named("tp1_price", tp1Price),
                new Named("tp2_price", tp2Price), new Named("liquidation_price", liquidationPrice))) {
            if (p.value() != null && (p.value().signum() < 0 || p.value().compareTo(AMOUNT_MAX) > 0)) {
                errors.add(new FieldErrorItem(p.name(), INVALID, p.name() + "은 0 이상이어야 해요."));
            }
        }
        if (aiCost != null && (aiCost.signum() < 0 || aiCost.compareTo(AI_COST_MAX) > 0)) {
            errors.add(new FieldErrorItem("ai_cost", INVALID, "ai_cost는 0 이상 " + AI_COST_MAX.toPlainString() + " 이하여야 해요."));
        }
        if (model.length() > AI_MODEL_MAX) errors.add(new FieldErrorItem("ai_model", TOO_LONG, "AI 모델 이름은 " + AI_MODEL_MAX + "자까지예요."));
        if (!futures && liquidationPrice != null) {
            errors.add(new FieldErrorItem("liquidation_price", INVALID, "청산가(liquidation_price)는 선물(market=FUTURES)에만 적어요."));
        }
        if (!futures && funding != null) errors.add(new FieldErrorItem("funding", INVALID, "펀딩비(funding)는 선물(market=FUTURES)에만 적어요."));
        if (funding != null && funding.abs().compareTo(AMOUNT_MAX) > 0) errors.add(new FieldErrorItem("funding", INVALID, "펀딩비가 너무 커요."));
        if (!closes && exitReason != null) {
            errors.add(new FieldErrorItem("exit_reason", INVALID, "정리 이유(exit_reason)는 포지션을 닫는 체결(현물 매도, 선물 청산)에만 적어요."));
        }
        if (!closes && rMultiple != null) {
            errors.add(new FieldErrorItem("r_multiple", INVALID, "R배수(r_multiple)는 포지션을 닫는 체결(현물 매도, 선물 청산)에만 적어요."));
        }
        if (rMultiple != null && rMultiple.abs().compareTo(R_MAX) > 0) errors.add(new FieldErrorItem("r_multiple", INVALID, "R배수가 너무 커요."));
        return new TradeTrace(version, decision, basket, reg, set, trg, newsScore, flags, riskPct, kellyRaw, kellyUsed, aiConf, equityAtEntry,
                stopPrice, tp1Price, tp2Price, liquidationPrice, model, aiCost, exitReason, rMultiple, funding);
    }

    private record Named(String name, BigDecimal value) {}

    private static void key(List<FieldErrorItem> errors, String field, String value, int max, String label) {
        if (value.length() > max) errors.add(new FieldErrorItem(field, TOO_LONG, label + "(" + field + ")는 " + max + "자까지예요."));
        else if (!KEY.matcher(value).matches()) {
            errors.add(new FieldErrorItem(field, INVALID, label + "(" + field + ")는 영문·숫자와 . _ : + -만 써요."));
        }
    }

    private static void stage(List<FieldErrorItem> errors, String field, String value) {
        if (!STAGE.matcher(value).matches()) {
            errors.add(new FieldErrorItem(field, INVALID, field + "는 UP, PULLBACK처럼 영문 대문자·숫자·_로 " + STAGE_MAX + "자까지예요."));
        }
    }

    private static void fraction(List<FieldErrorItem> errors, String field, BigDecimal value) {
        if (value != null && (value.signum() < 0 || value.compareTo(BigDecimal.ONE) > 0)) {
            errors.add(new FieldErrorItem(field, INVALID, field + "는 0~1 사이 비율이에요 (예: 0.005)."));
        }
    }

    private static String trim(String s) {
        return s == null ? "" : s.strip();
    }
}
