package com.team.blog.paper.infra;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import com.team.blog.paper.application.PaperCoins;
import com.team.blog.paper.application.PaperProperties;
import com.team.blog.paper.application.Quotes;
import com.team.blog.shared.time.Times;

/**
 * 원화 거래소 공개 시세 (#267). 키 없이 시세 주소 하나만 부르고, 다섯 코인을 한 번에 받아 잠깐(기본 5초) 들고 있는다.
 * 회원이 많이 눌러도 거래소에는 5초에 한 번만 간다(공개 API 초당 제한 안).
 * <p>거래소는 blog.paper.source로 고른다. 학교 서버에서 업비트로 나가는 연결이 막혀 있어 기본은 코빗(/v2/tickers)이다(v2.1.3).
 */
@Component
class KrwQuotes implements Quotes {
    private static final Logger log = LoggerFactory.getLogger(KrwQuotes.class);
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private final PaperProperties props;
    private final Clock clock;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
    private final JsonMapper json = JsonMapper.builder().build();
    private final AtomicReference<Map<String, Quote>> cached = new AtomicReference<>(Map.of());
    private volatile Instant cachedAt = Instant.EPOCH;
    /** 마지막으로 거래소가 실패한 시각. 캐시 시간 동안은 다시 부르지 않고 바로 실패를 돌려준다(줄 서서 5초씩 기다리지 않게). */
    private volatile Instant failedAt = Instant.EPOCH;

    KrwQuotes(PaperProperties props, Clock clock) {
        this.props = props;
        this.clock = clock;
    }

    @Override
    public Map<String, Quote> latest() {
        Instant now = Times.now(clock);
        Map<String, Quote> hit = cached.get();
        if (!hit.isEmpty() && cachedAt.plus(props.cacheTtl()).isAfter(now)) return hit;
        synchronized (this) {
            Instant attempt = Times.now(clock);
            hit = cached.get();
            if (!hit.isEmpty() && cachedAt.plus(props.cacheTtl()).isAfter(attempt)) return hit;
            if (failedAt.plus(props.cacheTtl()).isAfter(attempt)) throw new QuotesUnavailable("시세 서버가 잠시 응답하지 않습니다");
            try {
                Map<String, Quote> fresh = fetch(attempt);
                cached.set(fresh);
                cachedAt = attempt;
                failedAt = Instant.EPOCH;
                return fresh;
            } catch (RuntimeException e) {
                failedAt = Times.now(clock);
                throw e;
            }
        }
    }

    private Map<String, Quote> fetch(Instant now) {
        boolean korbit = PaperProperties.KORBIT.equals(props.source());
        String base = props.baseUrl().endsWith("/") ? props.baseUrl().substring(0, props.baseUrl().length() - 1) : props.baseUrl();
        String url = korbit
                ? base + "/v2/tickers?symbol=" + PaperCoins.ALL.stream().map(c -> c.symbol().toLowerCase(Locale.ROOT) + "_krw").collect(Collectors.joining(","))
                : base + "/v1/ticker?markets=" + PaperCoins.ALL.stream().map(c -> "KRW-" + c.symbol()).collect(Collectors.joining(","));
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url)).timeout(TIMEOUT)
                    .header("Accept", "application/json").GET().build();
            HttpResponse<String> res = client.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (res.statusCode() != 200) throw new QuotesUnavailable("HTTP " + res.statusCode());
            Map<String, Quote> out = new LinkedHashMap<>();
            JsonNode root = json.readTree(res.body());
            for (JsonNode n : korbit ? root.path("data") : root) {
                (korbit ? korbitQuote(n, now) : upbitQuote(n, now)).ifPresent(q -> out.put(q.symbol(), q));
            }
            if (out.size() != PaperCoins.ALL.size()) throw new QuotesUnavailable("코인 " + out.size() + "개만 받음");
            return Map.copyOf(out);
        } catch (QuotesUnavailable e) {
            throw unavailable(e.getMessage());
        } catch (IOException | RuntimeException e) {
            throw unavailable(e.getClass().getSimpleName());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new QuotesUnavailable("interrupted");
        }
    }

    /** 업비트 /v1/ticker 한 줄. 원화 시장의 다섯 코인 가운데 값이 있는 것만 시세로 바꾼다 */
    private static Optional<Quote> upbitQuote(JsonNode n, Instant now) {
        String market = n.path("market").asString("");
        if (!market.startsWith("KRW-")) return Optional.empty();
        String symbol = market.substring(4);
        BigDecimal price = n.path("trade_price").decimalValue();
        if (PaperCoins.of(symbol).isEmpty() || price == null || price.signum() <= 0) return Optional.empty();
        BigDecimal change = n.path("signed_change_rate").isNumber() ? n.path("signed_change_rate").decimalValue() : BigDecimal.ZERO;
        return Optional.of(new Quote(symbol, price, change, now));
    }

    /** 코빗 /v2/tickers 한 줄({"symbol":"btc_krw","close":"111155000","priceChangePercent":"0.09"}). 값은 문자열이고 등락률은 %다 */
    private static Optional<Quote> korbitQuote(JsonNode n, Instant now) {
        String market = n.path("symbol").asString("");
        if (!market.endsWith("_krw")) return Optional.empty();
        String symbol = market.substring(0, market.length() - 4).toUpperCase(Locale.ROOT);
        BigDecimal price = decimal(n.path("close"));
        if (PaperCoins.of(symbol).isEmpty() || price == null || price.signum() <= 0) return Optional.empty();
        BigDecimal percent = decimal(n.path("priceChangePercent"));
        BigDecimal change = percent == null ? BigDecimal.ZERO : percent.movePointLeft(2);
        return Optional.of(new Quote(symbol, price, change, now));
    }

    /** USDT 환율은 천천히 움직여서 1분씩 들고 있는다. 실패해도 1분 동안은 다시 부르지 않는다 */
    private static final Duration USDT_TTL = Duration.ofMinutes(1);
    private volatile BigDecimal usdt;
    private volatile Instant usdtAt = Instant.EPOCH;

    @Override
    public synchronized Optional<BigDecimal> usdtKrw() {
        Instant now = Times.now(clock);
        if (usdtAt.plus(USDT_TTL).isAfter(now)) return Optional.ofNullable(usdt);
        usdtAt = now;
        boolean korbit = PaperProperties.KORBIT.equals(props.source());
        String base = props.baseUrl().endsWith("/") ? props.baseUrl().substring(0, props.baseUrl().length() - 1) : props.baseUrl();
        String url = korbit ? base + "/v2/tickers?symbol=usdt_krw" : base + "/v1/ticker?markets=KRW-USDT";
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url)).timeout(TIMEOUT).header("Accept", "application/json").GET().build();
            HttpResponse<String> res = client.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (res.statusCode() != 200) throw new QuotesUnavailable("HTTP " + res.statusCode());
            JsonNode root = json.readTree(res.body());
            JsonNode row = korbit ? root.path("data").path(0) : root.path(0);
            BigDecimal price = decimal(korbit ? row.path("close") : row.path("trade_price"));
            if (price == null || price.signum() <= 0) throw new QuotesUnavailable("USDT 시세 없음");
            usdt = price;
        } catch (IOException | RuntimeException e) {
            log.warn("USDT 환율을 받지 못했습니다 (지난 값을 써요): {}", e.getClass().getSimpleName());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return Optional.ofNullable(usdt);
    }

    /** 숫자나 숫자 문자열을 읽는다. 없거나 숫자가 아니면 null */
    private static BigDecimal decimal(JsonNode n) {
        if (n.isNumber()) return n.decimalValue();
        if (!n.isString()) return null;
        try {
            return new BigDecimal(n.asString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 실패 이유를 남기고 시세 없음으로 돌려준다 */
    private QuotesUnavailable unavailable(String reason) {
        String source = PaperProperties.KORBIT.equals(props.source()) ? "코빗" : "업비트";
        log.warn("{} 시세를 받지 못했습니다: {}", source, reason);
        return new QuotesUnavailable(reason);
    }
}
