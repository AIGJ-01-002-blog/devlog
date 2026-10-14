package com.team.blog.mcp.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import tools.jackson.databind.JsonNode;

import com.team.blog.shared.config.BlogProperties;
import com.team.blog.trade.application.TradeJournal;
import com.team.blog.trade.application.TradeTexts;

/**
 * 매매 일지 MCP 도구 (#243). record_trade는 집 컴퓨터의 매매 봇이 쓰기 토큰으로 체결마다 부르고,
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
              "reason":{"type":"string","description":"주문을 낸 신호·이유 (300자까지)"},
              "executed_at":{"type":"string","description":"체결 시각 ISO-8601 (예: 2026-10-14T10:03:00+09:00). 없으면 지금"}},
             "required":["external_id","symbol","side","quantity","price"]}""";

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm").withZone(KST);

    private final TradeJournal trades;
    private final String baseUrl;

    TradeTools(TradeJournal trades, BlogProperties props) {
        this.trades = trades;
        this.baseUrl = props.site().baseUrl();
    }

    boolean enabled() {
        return trades.enabled();
    }

    McpTools.Result record(AccessTokens.Caller caller, JsonNode a) {
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
        Instant at;
        try {
            at = a.hasNonNull("executed_at") ? OffsetDateTime.parse(a.path("executed_at").asString()).toInstant() : null;
        } catch (DateTimeParseException e) {
            return McpTools.Result.fail("executed_at은 2026-10-14T10:03:00+09:00처럼 시간대를 붙여 적어 주세요.");
        }
        TradeJournal.Recorded r = trades.record(caller.memberId(), new TradeJournal.Input(a.path("external_id").asString(""), mode,
                a.path("symbol").asString(""), a.path("symbol_name").asString(""), side, number(a, "quantity"), number(a, "price"),
                number(a, "fee"), number(a, "realized_pnl"), a.path("strategy").asString(""), a.path("reason").asString(""), at,
                a.path("exchange").asString(""), a.path("currency").asString(""), market, position, leverage, a.path("interval").asString("")));
        String link = baseUrl + "/trades?date=" + r.day();
        return McpTools.Result.ok(r.duplicate()
                ? "이미 남긴 체결이에요 (체결 번호 " + r.id() + "). 다시 남기지 않았어요. 매매 일지: " + link
                : "체결을 남겼어요 (체결 번호 " + r.id() + "). 매매 일지: " + link);
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
        for (TradeJournal.Fill f : d.fills()) {
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
        sb.append(d.memo().isBlank() ? "사용자 메모: 아직 없음\n" : "사용자 메모: " + d.memo() + "\n");
        sb.append("메모·요약 글·쇼츠는 사용자가 이 화면에서 정해요: ").append(baseUrl).append("/trades?date=").append(date)
                .append("\n투자 권유로 읽히는 표현(추천·확실한 수익)은 쓰지 마세요.");
        return McpTools.Result.ok(sb.toString());
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
