package com.team.blog.trade.application;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import com.team.blog.shared.error.ApiException;
import com.team.blog.shared.error.NotFoundException;
import com.team.blog.shared.time.Times;

/**
 * 자동매매 화면의 차트 탭 (spec 101). 코인 5종을 거래소(바이낸스 현물·업비트 원화)별로 공개 캔들(키 없음)로 그리고,
 * 봇이 알린 모의 체결(trade_bot_event FILL)을 그 위에 표시한다.
 * <p>브라우저가 거래소를 바로 부르지 않고 서버가 받아 준다. 사이트 CSP(connect-src 'self')를 넓히지 않고,
 * 회원이 많아도 거래소에는 (거래소, 봉) 조합마다 chartTtl(기본 10초)에 한 번만 가게 하려는 것이다.
 */
@Service
public class TradeCharts {
    private static final Logger log = LoggerFactory.getLogger(TradeCharts.class);
    private static final Duration TIMEOUT = Duration.ofSeconds(5);
    /** 차트 한 장의 봉 수. 99개 이동평균선이 차트 절반 넘게 그려지도록 거래소 한 번 요청의 최대(코빗·업비트 200)까지 받는다 (#354) */
    public static final int CANDLES = 200;
    public static final List<String> COINS = List.of("BTC", "ETH", "XRP", "SOL", "DOGE");
    private static final Map<String, String> NAMES = Map.of("BTC", "비트코인", "ETH", "이더리움", "XRP", "리플", "SOL", "솔라나", "DOGE", "도지코인");
    /** 봉 간격 → 업비트 분봉 단위 (일봉은 따로) */
    private static final Map<String, String> UPBIT_UNITS = Map.of("1s", "seconds", "1m", "minutes/1", "5m", "minutes/5", "15m", "minutes/15",
            "1h", "minutes/60", "4h", "minutes/240", "1d", "days", "1w", "weeks");
    /** 봉 간격 → 코빗 /v2/candles interval (분 단위, 일봉은 1D). 코빗은 1초 봉이 없다 */
    private static final Map<String, String> KORBIT_UNITS = Map.of("1m", "1", "5m", "5", "15m", "15", "1h", "60", "4h", "240", "1d", "1D", "1w", "1W");
    public static final List<String> INTERVALS = List.of("1s", "1m", "5m", "15m", "1h", "4h", "1d", "1w");

    public enum Exchange { BINANCE, UPBIT }

    /**
     * @param t 봉이 열린 시각(밀리초)
     * @param v 거래량(코인 개수). 거래소가 주지 않으면 0
     */
    public record Candle(long t, BigDecimal o, BigDecimal h, BigDecimal l, BigDecimal c, BigDecimal v) {
        public Candle(long t, BigDecimal o, BigDecimal h, BigDecimal l, BigDecimal c) {
            this(t, o, h, l, c, BigDecimal.ZERO);
        }
    }

    /** 최근 24시간 고가·저가·거래량(코인)·거래대금(코인 × 종가 합). 1시간 봉 24개로 센다 (#354) */
    public record Day(BigDecimal high, BigDecimal low, BigDecimal volume, BigDecimal quoteVolume) {}

    /** 봇이 알린 모의 체결 */
    public static final String BY_BOT = "BOT";
    /** 회원이 포인트 모의투자에서 직접 낸 체결 (#324) */
    public static final String BY_ME = "ME";

    /** 차트 위에 찍을 체결. by는 누가 냈는지(BOT·ME) */
    public record Mark(long t, String side, BigDecimal price, String text, String by) {}

    /**
     * @param error 이 코인 캔들을 받지 못했으면 이유, 받았으면 null
     * @param day 최근 24시간 요약. 1시간 봉을 받지 못했으면 null
     */
    public record Coin(String coin, String name, String symbol, List<Candle> candles, List<Mark> marks, String error, Day day) {
        public Coin(String coin, String name, String symbol, List<Candle> candles, List<Mark> marks, String error) {
            this(coin, name, symbol, candles, marks, error, null);
        }
    }

    public record Chart(String exchange, String interval, String currency, List<Coin> coins, Instant fetchedAt) {}

    private record Cached(Instant at, List<Coin> coins) {}

    private final TradeProperties props;
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
    private final JsonMapper json = JsonMapper.builder().build();
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();
    /** 캐시 키(거래소:간격)마다 따로 잠근다. 다른 간격·24시간 요약을 받는 요청끼리 서로 기다리지 않는다 (#354) */
    private final Map<String, Object> locks = new ConcurrentHashMap<>();

    public TradeCharts(TradeProperties props, JdbcTemplate jdbc, Clock clock) {
        this.props = props;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public Chart chart(long memberId, String exchangeRaw, String intervalRaw) {
        if (!props.enabled()) throw new NotFoundException();
        Exchange exchange;
        try {
            exchange = Exchange.valueOf(exchangeRaw.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("INVALID_EXCHANGE", "거래소는 binance나 upbit예요.");
        }
        String interval = interval(exchange, intervalRaw.strip());
        Cached c = candles(exchange, interval);
        Map<String, Day> days = days(exchange);
        List<Coin> coins = new ArrayList<>();
        for (Coin coin : c.coins()) {
            long from = coin.candles().isEmpty() ? Long.MAX_VALUE : coin.candles().getFirst().t();
            coins.add(new Coin(coin.coin(), coin.name(), coin.symbol(), coin.candles(), marks(memberId, coin.symbol(), from), coin.error(), days.get(coin.coin())));
        }
        return new Chart(exchange.name(), interval, exchange == Exchange.UPBIT ? "KRW" : "USDT", coins, c.at());
    }

    /**
     * 포인트 모의투자 화면의 원화 차트 (#324). 자동매매 차트의 원화 캔들을 함께 쓰고, 봇 체결과 회원이 직접 낸 포인트 체결을 함께 찍는다.
     *
     * @param myStrategy 회원 체결을 고르는 매매 일지 전략 이름(포인트 모의투자). trade 패키지가 paper를 몰라도 되게 부르는 쪽이 넘긴다
     */
    public Chart krw(long memberId, String intervalRaw, String myStrategy) {
        if (!props.enabled()) throw new NotFoundException();
        String interval = interval(Exchange.UPBIT, intervalRaw == null ? "15m" : intervalRaw.strip());
        Cached c = candles(Exchange.UPBIT, interval);
        Map<String, Day> days = days(Exchange.UPBIT);
        List<Coin> coins = new ArrayList<>();
        for (Coin coin : c.coins()) {
            long from = coin.candles().isEmpty() ? Long.MAX_VALUE : coin.candles().getFirst().t();
            List<Mark> marks = new ArrayList<>(marks(memberId, coin.symbol(), from));
            marks.addAll(myFills(memberId, coin.coin(), myStrategy, from));
            marks.sort(Comparator.comparingLong(Mark::t));
            coins.add(new Coin(coin.coin(), coin.name(), coin.symbol(), coin.candles(), marks, coin.error(), days.get(coin.coin())));
        }
        return new Chart(Exchange.UPBIT.name(), interval, "KRW", coins, c.at());
    }

    /** 고른 봉 간격을 확인한다. 원화를 코빗에서 받으면 1초 봉은 없다 */
    private String interval(Exchange exchange, String interval) {
        if (!INTERVALS.contains(interval)) throw ApiException.badRequest("INVALID_INTERVAL", "봉은 " + String.join(", ", INTERVALS) + " 중 하나예요.");
        if (exchange == Exchange.UPBIT && props.korbit() && !KORBIT_UNITS.containsKey(interval)) {
            throw ApiException.badRequest("INVALID_INTERVAL", "원화 차트는 " + interval + " 봉이 없어요.");
        }
        return interval;
    }

    /** 코인마다 최근 24시간 요약. 1시간 봉 캐시를 함께 써서 거래소 호출이 늘지 않는다 */
    private Map<String, Day> days(Exchange exchange) {
        Map<String, Day> out = new java.util.HashMap<>();
        for (Coin coin : candles(exchange, "1h").coins()) {
            Day d = day(coin.candles());
            if (d != null) out.put(coin.coin(), d);
        }
        return out;
    }

    /** 마지막 1시간 봉 24개(지금 열린 봉 포함)로 24시간 고가·저가·거래량을 센다. 봉이 없으면 null */
    static Day day(List<Candle> hourly) {
        if (hourly.isEmpty()) return null;
        List<Candle> last = hourly.subList(Math.max(0, hourly.size() - 24), hourly.size());
        BigDecimal high = last.getFirst().h();
        BigDecimal low = last.getFirst().l();
        BigDecimal volume = BigDecimal.ZERO;
        BigDecimal quote = BigDecimal.ZERO;
        for (Candle c : last) {
            high = high.max(c.h());
            low = low.min(c.l());
            volume = volume.add(c.v());
            quote = quote.add(c.v().multiply(c.c()));
        }
        return new Day(high, low, volume.stripTrailingZeros(), quote.setScale(0, java.math.RoundingMode.HALF_UP));
    }

    /**
     * 서버 모의 봇(spec 102)이 쓰는 닫힌 봉. 차트와 같은 캐시를 함께 써서 회원 수가 늘어도 거래소 호출은 늘지 않는다.
     * 거래소가 주는 마지막 봉은 아직 열려 있을 수 있어, 닫힌 시각(열린 시각 + 봉 간격)이 지난 봉만 돌려준다.
     */
    public List<Coin> closed(Exchange exchange, String interval, Duration step) {
        long now = Times.now(clock).toEpochMilli();
        List<Coin> out = new ArrayList<>();
        for (Coin coin : candles(exchange, interval).coins()) {
            List<Candle> done = coin.candles().stream().filter(c -> c.t() + step.toMillis() <= now).toList();
            out.add(new Coin(coin.coin(), coin.name(), coin.symbol(), done, List.of(), coin.error()));
        }
        return out;
    }

    /** 모든 회원이 함께 쓰는 캔들. 체결 표시는 회원마다 따로 붙인다 */
    private Cached candles(Exchange exchange, String interval) {
        String key = exchange + ":" + interval;
        Instant now = Times.now(clock);
        Cached hit = cache.get(key);
        if (hit != null && hit.at().plus(props.chartTtl()).isAfter(now)) return hit;
        synchronized (locks.computeIfAbsent(key, k -> new Object())) {
            hit = cache.get(key);
            Instant attempt = Times.now(clock);
            if (hit != null && hit.at().plus(props.chartTtl()).isAfter(attempt)) return hit;
            List<CompletableFuture<Coin>> calls = COINS.stream().map(coin -> fetch(exchange, interval, coin)).toList();
            List<Coin> coins = calls.stream().map(CompletableFuture::join).toList();
            Cached fresh = new Cached(attempt, coins);
            cache.put(key, fresh);
            return fresh;
        }
    }

    private CompletableFuture<Coin> fetch(Exchange exchange, String interval, String coin) {
        String symbol = exchange == Exchange.UPBIT ? "KRW-" + coin : coin + "USDT";
        // 원화(UPBIT 탭)는 기본으로 코빗에서 받는다. 봇 체결 표시는 그대로 업비트 심볼(KRW-BTC)로 맞춘다 (#325)
        boolean korbit = exchange == Exchange.UPBIT && props.korbit();
        String url = candlesUrl(exchange, korbit, interval, coin, symbol);
        HttpRequest req = HttpRequest.newBuilder(URI.create(url)).timeout(TIMEOUT).header("Accept", "application/json").GET().build();
        String name = NAMES.get(coin);
        return client.sendAsync(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                .thenApply(res -> {
                    if (res.statusCode() != 200) throw new IllegalStateException("HTTP " + res.statusCode());
                    JsonNode body = json.readTree(res.body());
                    List<Candle> candles = parse(exchange, korbit, body);
                    return new Coin(coin, name, symbol, candles, List.of(), null);
                })
                .exceptionally(e -> {
                    log.warn("차트 캔들을 받지 못함 {} {} {}: {}", exchange, symbol, interval, e.getMessage());
                    return new Coin(coin, name, symbol, List.of(), List.of(), "시세를 받지 못했어요. 잠시 뒤 다시 받아요.");
                });
    }

    private String candlesUrl(Exchange exchange, boolean korbit, String interval, String coin, String symbol) {
        if (exchange == Exchange.BINANCE) {
            return base(props.binanceUrl()) + "/api/v3/klines?symbol=" + symbol + "&interval=" + interval + "&limit=" + CANDLES;
        }
        if (korbit) {
            return base(props.korbitUrl()) + "/v2/candles?symbol=" + coin.toLowerCase(Locale.ROOT) + "_krw&interval=" + KORBIT_UNITS.get(interval) + "&limit=" + CANDLES;
        }
        return base(props.upbitUrl()) + "/v1/candles/" + UPBIT_UNITS.get(interval) + "?market=" + symbol + "&count=" + CANDLES;
    }

    private static List<Candle> parse(Exchange exchange, boolean korbit, JsonNode body) {
        if (exchange == Exchange.BINANCE) return binance(body);
        return korbit ? korbit(body) : upbit(body);
    }

    /** 바이낸스 klines: [열린 시각, 시가, 고가, 저가, 종가, …], 오래된 순 */
    private static List<Candle> binance(JsonNode body) {
        List<Candle> out = new ArrayList<>();
        for (JsonNode k : body) {
            out.add(new Candle(k.get(0).asLong(), dec(k.get(1)), dec(k.get(2)), dec(k.get(3)), dec(k.get(4)), volume(k.get(5))));
        }
        return out;
    }

    /** 코빗 /v2/candles: {"success":true,"data":[{"timestamp":열린 시각(밀리초),"open":"…",…}]}, 오래된 순. 실패면 success=false */
    private static List<Candle> korbit(JsonNode body) {
        if (!body.path("success").asBoolean(false)) throw new IllegalStateException("korbit " + body.path("error").path("description").asString(""));
        List<Candle> out = new ArrayList<>();
        for (JsonNode k : body.path("data")) {
            out.add(new Candle(k.path("timestamp").asLong(), dec(k.path("open")), dec(k.path("high")), dec(k.path("low")), dec(k.path("close")),
                    volume(k.path("volume"))));
        }
        out.sort(Comparator.comparingLong(Candle::t));
        return out;
    }

    /** 업비트 candles: 새것부터 온다. candle_date_time_utc는 시간대 없는 UTC */
    private static List<Candle> upbit(JsonNode body) {
        List<Candle> out = new ArrayList<>();
        for (JsonNode k : body) {
            long t = LocalDateTime.parse(k.path("candle_date_time_utc").asString()).toInstant(ZoneOffset.UTC).toEpochMilli();
            out.add(new Candle(t, dec(k.path("opening_price")), dec(k.path("high_price")), dec(k.path("low_price")), dec(k.path("trade_price")),
                    volume(k.path("candle_acc_trade_volume"))));
        }
        out.sort(Comparator.comparingLong(Candle::t));
        return out;
    }

    private List<Mark> marks(long memberId, String symbol, long fromMillis) {
        if (fromMillis == Long.MAX_VALUE) return List.of();
        return jdbc.query("""
                SELECT occurred_at, side, price, text FROM trade_bot_event
                WHERE member_id = ? AND kind = 'FILL' AND symbol = ? AND occurred_at >= ? AND price IS NOT NULL
                ORDER BY occurred_at, id LIMIT 200
                """, (rs, i) -> new Mark(rs.getTimestamp(1).toInstant().toEpochMilli(), rs.getString(2), rs.getBigDecimal(3).stripTrailingZeros(),
                rs.getString(4), BY_BOT), memberId, symbol, Timestamp.from(Instant.ofEpochMilli(fromMillis)));
    }

    /** 회원이 포인트로 직접 낸 원화 모의 체결. 매매 일지(trade)에 PAPER·UPBIT·이 전략으로 남아 있다 */
    private List<Mark> myFills(long memberId, String coin, String strategy, long fromMillis) {
        if (fromMillis == Long.MAX_VALUE) return List.of();
        // 많이 사고판 날에도 방금 낸 체결이 보이게 최신 200건을 고른다. 차트에 넣을 때 시간순으로 다시 줄 세운다
        return jdbc.query("""
                SELECT executed_at, side, price, reason FROM trade
                WHERE member_id = ? AND mode = 'PAPER' AND exchange = 'UPBIT' AND strategy = ? AND symbol = ? AND executed_at >= ?
                ORDER BY executed_at DESC, id DESC LIMIT 200
                """, (rs, i) -> new Mark(rs.getTimestamp(1).toInstant().toEpochMilli(), rs.getString(2), rs.getBigDecimal(3).stripTrailingZeros(),
                rs.getString(4), BY_ME), memberId, strategy, coin, Timestamp.from(Instant.ofEpochMilli(fromMillis)));
    }

    /** 거래량 칸. 없거나 숫자가 아니면 0 (거래량이 없어도 캔들은 그린다) */
    private static BigDecimal volume(JsonNode n) {
        if (n == null || n.isMissingNode() || n.isNull()) return BigDecimal.ZERO;
        try {
            return dec(n);
        } catch (NumberFormatException e) {
            return BigDecimal.ZERO;
        }
    }

    private static BigDecimal dec(JsonNode n) {
        return n.isNumber() ? n.decimalValue() : new BigDecimal(n.asString());
    }

    private static String base(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
