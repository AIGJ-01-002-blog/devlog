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
    /** 차트 한 장의 봉 수 */
    public static final int CANDLES = 60;
    public static final List<String> COINS = List.of("BTC", "ETH", "XRP", "SOL", "DOGE");
    private static final Map<String, String> NAMES = Map.of("BTC", "비트코인", "ETH", "이더리움", "XRP", "리플", "SOL", "솔라나", "DOGE", "도지코인");
    /** 봉 간격 → 업비트 분봉 단위 (일봉은 따로) */
    private static final Map<String, String> UPBIT_UNITS = Map.of("1m", "minutes/1", "5m", "minutes/5", "15m", "minutes/15", "1h", "minutes/60",
            "4h", "minutes/240", "1d", "days");
    /** 봉 간격 → 코빗 /v2/candles interval (분 단위, 일봉은 1D) */
    private static final Map<String, String> KORBIT_UNITS = Map.of("1m", "1", "5m", "5", "15m", "15", "1h", "60", "4h", "240", "1d", "1D");
    public static final List<String> INTERVALS = List.of("1m", "5m", "15m", "1h", "4h", "1d");

    public enum Exchange { BINANCE, UPBIT }

    /** @param t 봉이 열린 시각(밀리초) */
    public record Candle(long t, BigDecimal o, BigDecimal h, BigDecimal l, BigDecimal c) {}

    /** 차트 위에 찍을 봇의 모의 체결 */
    public record Mark(long t, String side, BigDecimal price, String text) {}

    /** @param error 이 코인 캔들을 받지 못했으면 이유, 받았으면 null */
    public record Coin(String coin, String name, String symbol, List<Candle> candles, List<Mark> marks, String error) {}

    public record Chart(String exchange, String interval, String currency, List<Coin> coins, Instant fetchedAt) {}

    private record Cached(Instant at, List<Coin> coins) {}

    private final TradeProperties props;
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
    private final JsonMapper json = JsonMapper.builder().build();
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

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
        String interval = intervalRaw.strip();
        if (!INTERVALS.contains(interval)) throw ApiException.badRequest("INVALID_INTERVAL", "봉은 " + String.join(", ", INTERVALS) + " 중 하나예요.");
        Cached c = candles(exchange, interval);
        List<Coin> coins = new ArrayList<>();
        for (Coin coin : c.coins()) {
            long from = coin.candles().isEmpty() ? Long.MAX_VALUE : coin.candles().getFirst().t();
            coins.add(new Coin(coin.coin(), coin.name(), coin.symbol(), coin.candles(), marks(memberId, coin.symbol(), from), coin.error()));
        }
        return new Chart(exchange.name(), interval, exchange == Exchange.UPBIT ? "KRW" : "USDT", coins, c.at());
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
        synchronized (cache) {
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
        String url = exchange == Exchange.BINANCE
                ? base(props.binanceUrl()) + "/api/v3/klines?symbol=" + symbol + "&interval=" + interval + "&limit=" + CANDLES
                : korbit
                ? base(props.korbitUrl()) + "/v2/candles?symbol=" + coin.toLowerCase(Locale.ROOT) + "_krw&interval=" + KORBIT_UNITS.get(interval) + "&limit=" + CANDLES
                : base(props.upbitUrl()) + "/v1/candles/" + UPBIT_UNITS.get(interval) + "?market=" + symbol + "&count=" + CANDLES;
        HttpRequest req = HttpRequest.newBuilder(URI.create(url)).timeout(TIMEOUT).header("Accept", "application/json").GET().build();
        String name = NAMES.get(coin);
        return client.sendAsync(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                .thenApply(res -> {
                    if (res.statusCode() != 200) throw new IllegalStateException("HTTP " + res.statusCode());
                    JsonNode body = json.readTree(res.body());
                    List<Candle> candles = exchange == Exchange.BINANCE ? binance(body) : korbit ? korbit(body) : upbit(body);
                    return new Coin(coin, name, symbol, candles, List.of(), null);
                })
                .exceptionally(e -> {
                    log.warn("차트 캔들을 받지 못함 {} {} {}: {}", exchange, symbol, interval, e.getMessage());
                    return new Coin(coin, name, symbol, List.of(), List.of(), "시세를 받지 못했어요. 잠시 뒤 다시 받아요.");
                });
    }

    /** 바이낸스 klines: [열린 시각, 시가, 고가, 저가, 종가, …], 오래된 순 */
    private static List<Candle> binance(JsonNode body) {
        List<Candle> out = new ArrayList<>();
        for (JsonNode k : body) {
            out.add(new Candle(k.get(0).asLong(), dec(k.get(1)), dec(k.get(2)), dec(k.get(3)), dec(k.get(4))));
        }
        return out;
    }

    /** 코빗 /v2/candles: {"success":true,"data":[{"timestamp":열린 시각(밀리초),"open":"…",…}]}, 오래된 순. 실패면 success=false */
    private static List<Candle> korbit(JsonNode body) {
        if (!body.path("success").asBoolean(false)) throw new IllegalStateException("korbit " + body.path("error").path("description").asString(""));
        List<Candle> out = new ArrayList<>();
        for (JsonNode k : body.path("data")) {
            out.add(new Candle(k.path("timestamp").asLong(), dec(k.path("open")), dec(k.path("high")), dec(k.path("low")), dec(k.path("close"))));
        }
        out.sort(Comparator.comparingLong(Candle::t));
        return out;
    }

    /** 업비트 candles: 새것부터 온다. candle_date_time_utc는 시간대 없는 UTC */
    private static List<Candle> upbit(JsonNode body) {
        List<Candle> out = new ArrayList<>();
        for (JsonNode k : body) {
            long t = LocalDateTime.parse(k.path("candle_date_time_utc").asString()).toInstant(ZoneOffset.UTC).toEpochMilli();
            out.add(new Candle(t, dec(k.path("opening_price")), dec(k.path("high_price")), dec(k.path("low_price")), dec(k.path("trade_price"))));
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
                rs.getString(4)), memberId, symbol, Timestamp.from(Instant.ofEpochMilli(fromMillis)));
    }

    private static BigDecimal dec(JsonNode n) {
        return n.isNumber() ? n.decimalValue() : new BigDecimal(n.asString());
    }

    private static String base(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
