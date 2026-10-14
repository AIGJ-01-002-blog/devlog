package com.team.blog.shorts.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * 매매 재현 쇼츠 (#265). 회원이 매매 일지에서 고른 체결을 담아 두고, 렌더 워커가 그 구간의 공개 캔들을 받아
 * 캔들이 한 개씩 흘러가다 체결 시점에 매수·매도 표시가 찍히는 9:16 영상을 만든다.
 * 고른 체결은 만들 때 그대로 복사해 둔다(나중에 체결을 지워도 대본·영상이 어긋나지 않게).
 *
 * @param exchange 공개 캔들을 받을 거래소 (BINANCE·UPBIT)
 * @param from     차트 첫 캔들의 시작 시각 (봉 길이에 맞춘 UTC)
 * @param to       차트 마지막 캔들이 끝나는 시각 (이 시각 앞까지)
 */
public record TradeReplay(String exchange, String symbol, String symbolName, String currency, String mode, String interval,
                          Instant from, Instant to, List<Fill> fills) {

    /** 한 영상에 담는 체결 수. 도입 1 + 체결마다 1 + 결과·메모 2 = 장면 9개 (고지는 마지막 장면 끝에 붙는다) */
    public static final int FILLS_MAX = 6;
    /** 체결 사이 구간이 이 봉 수 안에 들어오는 가장 짧은 봉을 고른다 */
    static final int SPAN_CANDLES_AUTO = 60;
    /** 회원이 봉을 직접 고르면 체결 사이 구간을 이 봉 수까지 받는다 */
    static final int SPAN_CANDLES_MAX = 90;
    static final int BEFORE_MIN = 20;
    static final int AFTER_MIN = 10;
    /** 공개 캔들을 받을 수 있는 거래소. 바이비트는 봇이 붙으면 더한다 */
    public static final List<String> EXCHANGES = List.of("BINANCE", "UPBIT");

    /** 체결 하나. 가격·수량·손익은 체결 통화 그대로 */
    public record Fill(long tradeId, String side, BigDecimal price, BigDecimal quantity, BigDecimal realizedPnl, Instant executedAt,
                       String reason) {}

    /** 고를 수 있는 봉. code는 화면·워커·바이낸스가 함께 쓰는 이름 */
    public enum Interval {
        M1("1m", 60, "1분봉"), M5("5m", 300, "5분봉"), M15("15m", 900, "15분봉"),
        H1("1h", 3600, "1시간봉"), H4("4h", 14_400, "4시간봉"), D1("1d", 86_400, "일봉");

        public final String code;
        public final long seconds;
        public final String label;

        Interval(String code, long seconds, String label) {
            this.code = code;
            this.seconds = seconds;
            this.label = label;
        }

        public static Optional<Interval> parse(String raw) {
            if (raw == null) return Optional.empty();
            return Arrays.stream(values()).filter(i -> i.code.equalsIgnoreCase(raw.strip())).findFirst();
        }
    }

    /** 차트 구간. 실패하면 화면에 그대로 보일 이유를 담는다 */
    public sealed interface Window permits Window.Ok, Window.TooLong {
        record Ok(Interval interval, Instant from, Instant to) implements Window {}

        record TooLong(String message) implements Window {}
    }

    /**
     * 체결 시각들로 차트 구간을 정한다. 앞에는 흐름을 볼 만큼(구간의 1/3, 최소 20봉), 뒤에는 결과를 볼 만큼(1/6, 최소 10봉) 붙인다.
     * 봉을 고르지 않으면 체결 사이가 60봉 안에 들어오는 가장 짧은 봉을 쓴다. 아직 오지 않은 시간의 캔들은 없으니 끝은 지금 봉까지로 자른다.
     *
     * @param requested 회원이 고른 봉. 비어 있으면 자동
     */
    public static Window window(Instant first, Instant last, Optional<Interval> requested, Instant now) {
        long span = Math.max(0, last.getEpochSecond() - first.getEpochSecond());
        Interval interval;
        if (requested.isPresent()) {
            interval = requested.get();
            if (candles(span, interval) > SPAN_CANDLES_MAX) {
                Optional<Interval> fit = Arrays.stream(Interval.values()).filter(i -> candles(span, i) <= SPAN_CANDLES_MAX).findFirst();
                return new Window.TooLong(fit
                        .map(i -> "고른 체결 사이가 " + interval.label + " " + candles(span, interval) + "개라 영상에 담기 너무 길어요. " + i.label + " 이상을 골라 주세요.")
                        .orElse(tooFar()));
            }
        } else {
            Optional<Interval> fit = Arrays.stream(Interval.values()).filter(i -> candles(span, i) <= SPAN_CANDLES_AUTO).findFirst();
            if (fit.isEmpty()) return new Window.TooLong(tooFar());
            interval = fit.get();
        }
        long sec = interval.seconds;
        long spanCandles = candles(span, interval);
        long before = Math.max(BEFORE_MIN, spanCandles / 3);
        long after = Math.max(AFTER_MIN, spanCandles / 6);
        long from = floor(first.getEpochSecond(), sec) - before * sec;
        long to = floor(last.getEpochSecond(), sec) + (after + 1) * sec;
        long nowEnd = floor(now.getEpochSecond(), sec) + sec;
        return new Window.Ok(interval, Instant.ofEpochSecond(from), Instant.ofEpochSecond(Math.min(to, nowEnd)));
    }

    private static String tooFar() {
        return "고른 체결 사이가 너무 멀어요. 일봉 " + SPAN_CANDLES_MAX + "개(약 석 달) 안의 체결만 골라 주세요.";
    }

    static long candles(long spanSeconds, Interval i) {
        return (spanSeconds + i.seconds - 1) / i.seconds;
    }

    private static long floor(long epochSecond, long sec) {
        return Math.floorDiv(epochSecond, sec) * sec;
    }
}
