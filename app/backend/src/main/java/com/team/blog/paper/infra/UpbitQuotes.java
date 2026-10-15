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
 * 업비트 공개 시세 (#267). 키 없이 /v1/ticker 하나만 부르고, 다섯 코인을 한 번에 받아 잠깐(기본 5초) 들고 있는다.
 * 회원이 많이 눌러도 업비트에는 5초에 한 번만 간다(공개 API 초당 제한 안).
 */
@Component
class UpbitQuotes implements Quotes {
    private static final Logger log = LoggerFactory.getLogger(UpbitQuotes.class);
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private final PaperProperties props;
    private final Clock clock;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
    private final JsonMapper json = JsonMapper.builder().build();
    private final AtomicReference<Map<String, Quote>> cached = new AtomicReference<>(Map.of());
    private volatile Instant cachedAt = Instant.EPOCH;
    /** 마지막으로 업비트가 실패한 시각. 캐시 시간 동안은 다시 부르지 않고 바로 실패를 돌려준다(줄 서서 5초씩 기다리지 않게). */
    private volatile Instant failedAt = Instant.EPOCH;

    UpbitQuotes(PaperProperties props, Clock clock) {
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
        String markets = PaperCoins.ALL.stream().map(c -> "KRW-" + c.symbol()).collect(Collectors.joining(","));
        String base = props.baseUrl().endsWith("/") ? props.baseUrl().substring(0, props.baseUrl().length() - 1) : props.baseUrl();
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(base + "/v1/ticker?markets=" + markets)).timeout(TIMEOUT)
                    .header("Accept", "application/json").GET().build();
            HttpResponse<String> res = client.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (res.statusCode() != 200) throw new QuotesUnavailable("HTTP " + res.statusCode());
            Map<String, Quote> out = new LinkedHashMap<>();
            for (JsonNode n : json.readTree(res.body())) {
                quoteOf(n, now).ifPresent(q -> out.put(q.symbol(), q));
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

    /** 원화 시장의 다섯 코인 가운데 값이 있는 것만 시세로 바꾼다 */
    private static Optional<Quote> quoteOf(JsonNode n, Instant now) {
        String market = n.path("market").asString("");
        if (!market.startsWith("KRW-")) return Optional.empty();
        String symbol = market.substring(4);
        BigDecimal price = n.path("trade_price").decimalValue();
        if (PaperCoins.of(symbol).isEmpty() || price == null || price.signum() <= 0) return Optional.empty();
        BigDecimal change = n.path("signed_change_rate").isNumber() ? n.path("signed_change_rate").decimalValue() : BigDecimal.ZERO;
        return Optional.of(new Quote(symbol, price, change, now));
    }

    /** 실패 이유를 남기고 시세 없음으로 돌려준다 */
    private static QuotesUnavailable unavailable(String reason) {
        log.warn("업비트 시세를 받지 못했습니다: {}", reason);
        return new QuotesUnavailable(reason);
    }
}
