package com.team.blog.mcp.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import tools.jackson.databind.JsonNode;

import com.team.blog.shared.config.BlogProperties;
import com.team.blog.trade.application.TradeBotSim;
import com.team.blog.trade.application.TradeJournal;
import com.team.blog.trade.application.TradeTexts;
import com.team.blog.trade.application.TradeTrace;

/**
 * 매매 일지 MCP 도구 (#243). record_trade는 집 컴퓨터의 매매 봇이 쓰기 토큰으로 체결마다 부르고,
 * report_trade_bot은 봇이 모의 계좌 상태·체결·Gemini 판단을 자동매매 화면(/trade, spec 101)에 알릴 때 쓴다.
 * get_trade_day는 사용자 쪽 AI가 그날 기록을 읽고 메모·글을 도울 때 쓴다. 주문을 내는 도구는 없다.
 * 코인 거래소(#254)는 exchange·currency를 함께 보내고, 금액은 그 통화로 읽는다.
 * 선물(#260)은 market=FUTURES와 position_side·leverage를 보낸다. 롱 진입 BUY, 롱 청산 SELL, 숏 진입 SELL, 숏 청산 BUY.
 */
@Component
class TradeTools {
    static final String RECORD_SCHEMA = """
            {"type":"object","properties":{
              "external_id":{"type":"string","description":"봇 쪽 주문·체결 번호 (80자까지). 같은 번호로 다시 보내면 한 번만 남는다"},
              "mode":{"type":"string","enum":["PAPER","LIVE"],"default":"PAPER","description":"PAPER 모의투자, LIVE 실계좌"},
              "exchange":{"type":"string","description":"거래소 코드 (BINANCE, UPBIT, BYBIT 등). 증권사면 비워도 된다"},
              "symbol":{"type":"string","description":"종목·마켓 코드 (영문·숫자 20자까지, 예: BTCUSDT, KRW-BTC, 005930)"},
              "symbol_name":{"type":"string","description":"종목 이름 (예: 비트코인, 삼성전자)"},
              "market":{"type":"string","enum":["SPOT","FUTURES"],"default":"SPOT","description":"SPOT 현물, FUTURES 선물"},
              "position_side":{"type":"string","enum":["LONG","SHORT"],"description":"선물 포지션 방향. 선물에만 (필수)"},
              "leverage":{"type":"integer","minimum":1,"maximum":7,"description":"선물 레버리지 배수. 선물에만, 없으면 1"},
              "interval":{"type":"string","enum":["1m","5m","15m","1h","4h","1d"],"description":"봇이 판단한 봉"},
              "side":{"type":"string","enum":["BUY","SELL"],"description":"현물은 매수 BUY·매도 SELL. 선물은 롱 진입 BUY, 롱 청산 SELL, 숏 진입 SELL, 숏 청산 BUY"},
              "quantity":{"type":"number","exclusiveMinimum":0,"description":"수량. 코인은 소수 10자리까지 (예: 0.00012345)"},
              "price":{"type":"number","minimum":0,"description":"체결 가격 (currency 통화)"},
              "currency":{"type":"string","default":"KRW","description":"가격·수수료·손익의 통화 (KRW, USDT 등)"},
              "fee":{"type":"number","minimum":0,"description":"수수료·세금 (currency 통화)"},
              "realized_pnl":{"type":"number","description":"청산으로 확정된 손익 (currency 통화, 수수료·세금·펀딩비를 반영한 값). 현물 매도와 선물 청산에만"},
              "strategy":{"type":"string","description":"전략 이름 (60자까지)"},
              "reason":{"type":"string","description":"주문을 낸 신호·이유 (2000자까지, 판단 이유 JSON도 된다). 비밀값이 들어 있으면 남기지 않는다"},
              "executed_at":{"type":"string","description":"체결 시각 ISO-8601 (예: 2026-10-14T10:03:00+09:00). 없으면 지금"},
              "strategy_version":{"type":"string","description":"판단 흔적: 기법 버전 (예: ladder-v0.1, 40자까지)"},
              "decision_id":{"type":"string","description":"판단 흔적: 봇 로컬 판단 기록과 잇는 키 (예: 20261015T09-BTC-1h, 80자까지). 진입·분할 익절·정리 체결에 같은 키를 보내면 한 포지션으로 묶인다"},
              "basket":{"type":"string","enum":["CORE","TRADING"],"description":"판단 흔적: CORE 코어 적립, TRADING 트레이딩"},
              "regime":{"type":"string","description":"세 겹 거름망 1단 장세 (예: UP)"},
              "setup":{"type":"string","description":"세 겹 거름망 2단 셋업 (예: PULLBACK)"},
              "trigger":{"type":"string","description":"세 겹 거름망 3단 방아쇠 (예: BREAKOUT10)"},
              "news_score":{"type":"integer","minimum":-10,"maximum":10,"description":"뉴스 점수"},
              "news_flags":{"type":"string","description":"뉴스 표시, 쉼표로 이어서 (예: MACRO_EVENT)"},
              "risk_pct":{"type":"number","minimum":0,"maximum":1,"description":"이번 거래에 건 위험 (잔고 대비, 예: 0.005)"},
              "kelly_raw":{"type":"number","minimum":0,"maximum":1,"description":"계산한 켈리 비율"},
              "kelly_used":{"type":"number","minimum":0,"maximum":1,"description":"실제로 쓴 켈리 비율"},
              "ai_conf":{"type":"number","minimum":0,"maximum":1,"description":"AI 확신도"},
              "equity_at_entry":{"type":"number","minimum":0,"description":"크기 계산에 쓴 아침 잔고 (currency 통화)"},
              "stop_price":{"type":"number","minimum":0,"description":"진입 때 정한 손절가"},
              "tp1_price":{"type":"number","minimum":0,"description":"진입 때 정한 1차 익절가"},
              "tp2_price":{"type":"number","minimum":0,"description":"진입 때 정한 2차 익절가"},
              "liquidation_price":{"type":"number","minimum":0,"description":"청산가. 선물에만"},
              "ai_model":{"type":"string","description":"판단에 쓴 AI 모델 (예: gemini-2.5-flash-lite)"},
              "ai_cost":{"type":"number","minimum":0,"description":"판단에 든 AI 비용"},
              "exit_reason":{"type":"string","enum":["STOP","TP1","TP2","TRAIL","TIME","NEWS","MANUAL"],"description":"정리 이유. 포지션을 닫는 체결(현물 매도, 선물 청산)에만"},
              "r_multiple":{"type":"number","description":"정리 손익 ÷ 처음 위험 (예: 1.8, 손해는 -1). 포지션을 닫는 체결에만"},
              "funding":{"type":"number","description":"선물 펀딩비 (currency 통화, 낸 돈은 음수). 선물에만. 수수료는 fee에 적는다"}},
             "required":["external_id","symbol","side","quantity","price"]}""";

    static final String REPORT_SCHEMA = """
            {"type":"object","properties":{
              "bot_version":{"type":"string","description":"봇 버전 (예: 1.0.0)"},
              "ai":{"type":"object","description":"AI(Gemini) 진입 필터 상태","properties":{
                "enabled":{"type":"boolean"},"model":{"type":"string","description":"예: gemini-2.5-flash"},
                "calls_today":{"type":"integer","minimum":0}}},
              "accounts":{"type":"array","maxItems":20,"description":"모의 계좌 전체. 주면 이 목록으로 통째로 바뀌고(빠진 계좌는 지워진다), 빼면 그대로 둔다",
                "items":{"type":"object","properties":{
                  "account":{"type":"string","description":"상태 파일 이름 (예: trade-state-binance-spot-1h.json). trade-live-…는 받지 않는다"},
                  "exchange":{"type":"string"},"market":{"type":"string","enum":["spot","futures"]},"currency":{"type":"string"},
                  "cash":{"type":"number"},"unrealized_pnl":{"type":"number"},
                  "positions":{"type":"array","items":{"type":"object","properties":{
                    "symbol":{"type":"string"},"side":{"type":"string","enum":["LONG","SHORT"],"description":"선물만"},
                    "quantity":{"type":"number"},"avg_price":{"type":"number","description":"현물 평균 단가 (선물은 entry)"},
                    "entry":{"type":"number"},"price":{"type":"number"},"unrealized_pnl":{"type":"number"}},"required":["symbol","quantity"]}},
                  "rules":{"type":"object","description":"지금 적용 중인 기준. 이름→값 (예: 전략→20봉 이동평균 돌파, 봉→1h, 손절→3%), 20개까지"}},
                 "required":["account","currency","cash"]}},
              "events":{"type":"array","maxItems":200,"description":"지난 알림 뒤에 생긴 사건",
                "items":{"type":"object","properties":{
                  "id":{"type":"string","description":"사건 번호 (빈칸 없이 120자까지, 예: fill:paper-…, ai:계좌:심볼:봉시각, sum:2026-10-15). 다시 보내도 한 번만 남는다"},
                  "kind":{"type":"string","enum":["FILL","AI","SUMMARY"],"description":"FILL 모의 체결, AI Gemini 진입 판단, SUMMARY 하루 요약"},
                  "at":{"type":"string","description":"ISO-8601 (예: 2026-10-15T13:05:00+09:00)"},
                  "account":{"type":"string"},"symbol":{"type":"string"},
                  "side":{"type":"string","enum":["BUY","SELL","LONG","SHORT","CLOSE"]},
                  "quantity":{"type":"number"},"price":{"type":"number"},"currency":{"type":"string"},"realized_pnl":{"type":"number"},
                  "verdict":{"type":"string","enum":["ALLOW","BLOCK"],"description":"AI만: 진입 허락·막음"},
                  "text":{"type":"string","description":"체결 이유, Gemini 판단 이유, 하루 요약 본문 (1000자까지)"}},
                 "required":["id","kind"]}}}}""";

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm").withZone(KST);

    private final TradeJournal trades;
    private final TradeBotSim bot;
    private final String baseUrl;

    TradeTools(TradeJournal trades, TradeBotSim bot, BlogProperties props) {
        this.trades = trades;
        this.bot = bot;
        this.baseUrl = props.site().baseUrl();
    }

    boolean enabled() {
        return trades.enabled();
    }

    McpTools.Result recordTrade(AccessTokens.Caller caller, JsonNode a) {
        TradeJournal.Side side = enumOf(TradeJournal.Side.class, a.path("side").asString(""));
        String modeRaw = a.path("mode").asString("");
        TradeJournal.Mode mode = modeRaw.isBlank() ? TradeJournal.Mode.PAPER : enumOf(TradeJournal.Mode.class, modeRaw);
        if (!modeRaw.isBlank() && mode == null) return McpTools.Result.fail("mode는 PAPER나 LIVE예요.");
        String marketRaw = a.path("market").asString("");
        TradeJournal.Market market = marketRaw.isBlank() ? TradeJournal.Market.SPOT : enumOf(TradeJournal.Market.class, marketRaw);
        if (market == null) return McpTools.Result.fail("market은 SPOT이나 FUTURES예요.");
        String positionRaw = a.path("position_side").asString("");
        TradeJournal.PositionSide position = enumOf(TradeJournal.PositionSide.class, positionRaw);
        if (!positionRaw.isBlank() && position == null) return McpTools.Result.fail("position_side는 LONG이나 SHORT예요.");
        BigDecimal leverageRaw = number(a, "leverage");
        if (a.hasNonNull("leverage") && leverageRaw == null) {
            return McpTools.Result.fail("leverage는 1~" + TradeJournal.LEVERAGE_MAX + " 사이 정수예요.");
        }
        Integer leverage;
        try {
            leverage = leverageRaw == null ? null : leverageRaw.intValueExact();
        } catch (ArithmeticException e) {
            return McpTools.Result.fail("leverage는 1~" + TradeJournal.LEVERAGE_MAX + " 사이 정수예요.");
        }
        String reason = a.path("reason").asString("");
        List<String> secrets = SecretScanner.find(reason);
        if (!secrets.isEmpty()) return McpTools.Result.fail(SecretScanner.warning(secrets) + " 이유(reason)에서 빼고 다시 보내 주세요. 남기지 않았어요.");
        TradeTrace trace;
        try {
            trace = trace(a);
        } catch (IllegalArgumentException e) {
            return McpTools.Result.fail(e.getMessage());
        }
        Instant at;
        try {
            at = a.hasNonNull("executed_at") ? OffsetDateTime.parse(a.path("executed_at").asString()).toInstant() : null;
        } catch (DateTimeParseException e) {
            return McpTools.Result.fail("executed_at은 2026-10-14T10:03:00+09:00처럼 시간대를 붙여 적어 주세요.");
        }
        TradeJournal.Recorded r = trades.recordFill(caller.memberId(), new TradeJournal.Input(a.path("external_id").asString(""), mode,
                a.path("symbol").asString(""), a.path("symbol_name").asString(""), side, number(a, "quantity"), number(a, "price"),
                number(a, "fee"), number(a, "realized_pnl"), a.path("strategy").asString(""), reason, at,
                a.path("exchange").asString(""), a.path("currency").asString(""), market, position, leverage, a.path("interval").asString(""), trace));
        String link = baseUrl + "/trades?date=" + r.day();
        return McpTools.Result.ok(r.duplicate()
                ? "이미 남긴 체결이에요 (체결 번호 " + r.id() + "). 다시 남기지 않았어요. 매매 일지: " + link
                : "체결을 남겼어요 (체결 번호 " + r.id() + "). 매매 일지: " + link);
    }

    /** 판단 흔적 (#283·#284). 범위는 TradeJournal이 보고, 여기서는 글자를 열거·숫자로 읽기만 한다 */
    private static TradeTrace trace(JsonNode a) {
        String basketRaw = a.path("basket").asString("");
        TradeTrace.Basket basket = enumOf(TradeTrace.Basket.class, basketRaw);
        if (!basketRaw.isBlank() && basket == null) throw new IllegalArgumentException("basket은 CORE나 TRADING이에요.");
        String exitRaw = a.path("exit_reason").asString("");
        TradeTrace.ExitReason exit = enumOf(TradeTrace.ExitReason.class, exitRaw);
        if (!exitRaw.isBlank() && exit == null) throw new IllegalArgumentException("exit_reason은 STOP, TP1, TP2, TRAIL, TIME, NEWS, MANUAL 가운데 하나예요.");
        Integer newsScore = null;
        if (a.hasNonNull("news_score")) {
            BigDecimal n = number(a, "news_score");
            try {
                newsScore = n == null ? null : n.intValueExact();
            } catch (ArithmeticException e) {
                newsScore = null;
            }
            if (newsScore == null) throw new IllegalArgumentException("news_score는 -10~10 사이 정수예요.");
        }
        for (String f : List.of("risk_pct", "kelly_raw", "kelly_used", "ai_conf", "equity_at_entry", "stop_price", "tp1_price", "tp2_price",
                "liquidation_price", "ai_cost", "r_multiple", "funding")) {
            if (a.hasNonNull(f) && number(a, f) == null) throw new IllegalArgumentException(f + "는 숫자예요.");
        }
        return new TradeTrace(a.path("strategy_version").asString(""), a.path("decision_id").asString(""), basket, a.path("regime").asString(""),
                a.path("setup").asString(""), a.path("trigger").asString(""), newsScore, a.path("news_flags").asString(""), number(a, "risk_pct"),
                number(a, "kelly_raw"), number(a, "kelly_used"), number(a, "ai_conf"), number(a, "equity_at_entry"), number(a, "stop_price"),
                number(a, "tp1_price"), number(a, "tp2_price"), number(a, "liquidation_price"), a.path("ai_model").asString(""),
                number(a, "ai_cost"), exit, number(a, "r_multiple"), number(a, "funding"));
    }

    McpTools.Result day(AccessTokens.Caller caller, JsonNode a) {
        LocalDate date;
        try {
            date = a.hasNonNull("date") ? LocalDate.parse(a.path("date").asString()) : trades.today();
        } catch (DateTimeParseException e) {
            return McpTools.Result.fail("date는 2026-10-14처럼 적어 주세요.");
        }
        TradeJournal.Day d = trades.day(caller.memberId(), date);
        if (d.fills().isEmpty() && d.memo().isBlank()) return McpTools.Result.ok(date + "에는 체결 기록이 없어요.");
        TradeJournal.Summary s = d.summary();
        StringBuilder sb = new StringBuilder(date + " 매매 기록\n");
        sb.append("체결 ").append(s.trades()).append("건 (매수 ").append(s.buys()).append(" · 매도 ").append(s.sells()).append(" · 청산 ").append(s.closes()).append(")")
                .append(" · 실현 손익 ").append(plain(s.realizedPnl())).append(" (이익 ").append(s.wins()).append(" · 손해 ").append(s.losses()).append(")")
                .append(" · 수수료·세금 ").append(plain(s.fees())).append(" · 누적 실현 손익 ").append(plain(d.cumulativePnl())).append('\n');
        for (TradeJournal.Fill f : d.fills()) appendFill(sb, f);
        sb.append(d.memo().isBlank() ? "사용자 메모: 아직 없음\n" : "사용자 메모: " + d.memo() + "\n");
        sb.append("메모·요약 글·쇼츠는 사용자가 이 화면에서 정해요: ").append(baseUrl).append("/trades?date=").append(date)
                .append("\n투자 권유로 읽히는 표현(추천·확실한 수익)은 쓰지 마세요.");
        return McpTools.Result.ok(sb.toString());
    }

    /** 체결 한 줄: 시각 [실계좌] [거래소] [포지션] [봉] 종목 매수·매도 수량 @ 가격 · 손익 · 이유(없으면 전략) */
    private static void appendFill(StringBuilder sb, TradeJournal.Fill f) {
        String unit = TradeJournal.unit(f);
        sb.append("- ").append(TIME.format(f.executedAt())).append(' ').append(f.mode().equals("LIVE") ? "[실계좌] " : "")
                .append(f.exchange().isBlank() ? "" : "[" + f.exchange() + "] ")
                .append(f.market().equals("FUTURES") ? "[" + TradeTexts.positionLabel(f) + "] " : "")
                .append(f.interval().isBlank() ? "" : "[" + f.interval() + "] ")
                .append(f.symbolName().isBlank() ? f.symbol() : f.symbolName() + "(" + f.symbol() + ")")
                .append(' ').append(TradeTexts.actionLabel(f)).append(' ').append(plain(f.quantity()))
                .append(unit.equals("주") ? "주" : " " + unit).append(" @ ").append(plain(f.price(), f.currency()));
        if (f.realizedPnl() != null) sb.append(" · 손익 ").append(plain(f.realizedPnl(), f.currency()));
        if (!f.reason().isBlank()) sb.append(" · 이유: ").append(f.reason());
        else if (!f.strategy().isBlank()) sb.append(" · 전략: ").append(f.strategy());
        sb.append('\n');
    }

    McpTools.Result report(AccessTokens.Caller caller, JsonNode a) {
        TradeBotSim.Reported r = bot.report(caller.memberId(), a);
        return McpTools.Result.ok((r.accounts() < 0 ? "" : "모의 계좌 " + r.accounts() + "개, ") + "새 사건 " + r.added() + "건을 남겼어요"
                + (r.duplicates() > 0 ? " (이미 있던 " + r.duplicates() + "건은 건너뜀)" : "") + ". 자동매매 화면: " + baseUrl + "/trade");
    }

    private static BigDecimal number(JsonNode a, String field) {
        JsonNode n = a.path(field);
        if (n.isNumber()) return n.decimalValue();
        if (n.isString() && !n.asString().isBlank()) {
            try {
                return new BigDecimal(n.asString().strip());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    private static String plain(BigDecimal v) {
        return v == null ? "0" : v.stripTrailingZeros().toPlainString();
    }

    /** 원화는 39800원, 그 밖의 통화는 83752.3 USDT */
    private static String plain(BigDecimal v, String currency) {
        return plain(v) + ("KRW".equals(currency) ? "원" : " " + currency);
    }

    /** 통화별 금액: 14600원 · 12.5 USDT. 없으면 0 */
    private static String plain(Map<String, BigDecimal> amounts) {
        if (amounts.isEmpty()) return "0";
        return amounts.entrySet().stream().map(e -> plain(e.getValue(), e.getKey())).collect(Collectors.joining(" · "));
    }

    private static <E extends Enum<E>> E enumOf(Class<E> type, String raw) {
        try {
            return raw == null || raw.isBlank() ? null : Enum.valueOf(type, raw.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
