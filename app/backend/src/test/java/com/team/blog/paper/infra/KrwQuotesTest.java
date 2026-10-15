package com.team.blog.paper.infra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import com.team.blog.paper.application.PaperProperties;
import com.team.blog.paper.application.Quotes.Quote;
import com.team.blog.paper.application.Quotes.QuotesUnavailable;
import com.sun.net.httpserver.HttpServer;

/** 거래소가 실패하면 캐시 시간 동안은 다시 부르지 않고 바로 실패한다 (#267, 요청이 줄 서서 기다리지 않게). */
class KrwQuotesTest {

    /** 테스트에서 시간을 앞으로 돌리는 시계. */
    static final class MovableClock extends Clock {
        Instant now = Instant.parse("2026-10-14T00:00:00Z");
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    @Test
    void 실패한_뒤_캐시_시간_동안은_업비트를_다시_부르지_않는다() {
        MovableClock clock = new MovableClock();
        // 1번 포트는 닫혀 있어 바로 연결이 거부된다
        KrwQuotes quotes = new KrwQuotes(new PaperProperties("http://127.0.0.1:1", new BigDecimal("0.0005"), 5000, Duration.ofSeconds(5), "upbit"), clock);

        assertThatThrownBy(quotes::latest).isInstanceOf(QuotesUnavailable.class).hasMessageNotContaining("잠시");
        clock.now = clock.now.plusSeconds(2);
        assertThatThrownBy(quotes::latest).isInstanceOf(QuotesUnavailable.class).hasMessageContaining("잠시");
        clock.now = clock.now.plusSeconds(4);
        assertThatThrownBy(quotes::latest).isInstanceOf(QuotesUnavailable.class).hasMessageNotContaining("잠시");
    }

    /** 원화 시장의 다섯 코인만 받고(다른 시장·값 없는 줄은 뺀다), 캐시 시간 동안은 다시 부르지 않는다 */
    @Test
    void 원화_다섯_코인을_받아_캐시_시간_동안_들고_있는다() throws IOException {
        String body = """
                [{"market":"KRW-BTC","trade_price":100000000,"signed_change_rate":0.01},
                 {"market":"KRW-ETH","trade_price":5000000},
                 {"market":"KRW-XRP","trade_price":800,"signed_change_rate":-0.02},
                 {"market":"KRW-SOL","trade_price":200000,"signed_change_rate":0},
                 {"market":"KRW-DOGE","trade_price":300,"signed_change_rate":0.5},
                 {"market":"USDT-BTC","trade_price":70000},
                 {"market":"KRW-ADA","trade_price":1000},
                 {"market":"KRW-BTC2","trade_price":0}]
                """;
        AtomicInteger calls = new AtomicInteger();
        HttpServer server = serve(200, body, calls);
        try {
            MovableClock clock = new MovableClock();
            KrwQuotes quotes = new KrwQuotes(properties(server), clock);
            Map<String, Quote> got = quotes.latest();
            assertThat(got).containsOnlyKeys("BTC", "ETH", "XRP", "SOL", "DOGE");
            assertThat(got.get("BTC").price()).isEqualByComparingTo("100000000");
            assertThat(got.get("BTC").changeRate()).isEqualByComparingTo("0.01");
            assertThat(got.get("ETH").changeRate()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(got.get("XRP").at()).isEqualTo(clock.now);

            clock.now = clock.now.plusSeconds(4);
            assertThat(quotes.latest()).isSameAs(got);
            assertThat(calls.get()).isEqualTo(1);
            clock.now = clock.now.plusSeconds(2);
            assertThat(quotes.latest().get("BTC").at()).isEqualTo(clock.now);
            assertThat(calls.get()).isEqualTo(2);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void 코인이_모자라거나_HTTP_오류면_시세_없음이다() throws IOException {
        HttpServer few = serve(200, "[{\"market\":\"KRW-BTC\",\"trade_price\":1}]", new AtomicInteger());
        HttpServer down = serve(500, "", new AtomicInteger());
        try {
            assertThatThrownBy(new KrwQuotes(properties(few), new MovableClock())::latest).isInstanceOf(QuotesUnavailable.class)
                    .hasMessage("코인 1개만 받음");
            assertThatThrownBy(new KrwQuotes(properties(down), new MovableClock())::latest).isInstanceOf(QuotesUnavailable.class)
                    .hasMessage("HTTP 500");
        } finally {
            few.stop(0);
            down.stop(0);
        }
    }

    static PaperProperties properties(HttpServer server) {
        return properties(server, "upbit");
    }

    static PaperProperties properties(HttpServer server, String source) {
        return new PaperProperties("http://127.0.0.1:" + server.getAddress().getPort() + "/", new BigDecimal("0.0005"), 5000, Duration.ofSeconds(5), source);
    }

    /** 코빗은 /v2/tickers에 소문자 심볼(btc_krw)을 쉼표로 묶어 묻고, 값은 문자열·등락률은 %로 준다 (v2.1.3) */
    @Test
    void 코빗에서_원화_다섯_코인을_받는다() throws IOException {
        String body = """
                {"success":true,"data":[
                 {"symbol":"btc_krw","close":"111155000","priceChangePercent":"0.09"},
                 {"symbol":"eth_krw","close":"5000000"},
                 {"symbol":"xrp_krw","close":"800","priceChangePercent":"-2.5"},
                 {"symbol":"sol_krw","close":"200000","priceChangePercent":"0"},
                 {"symbol":"doge_krw","close":"109.1","priceChangePercent":"-2.59"},
                 {"symbol":"btc_usdt","close":"70000"},
                 {"symbol":"ada_krw","close":"1000"}]}
                """;
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<String> query = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v2/tickers", ex -> {
            calls.incrementAndGet();
            query.set(ex.getRequestURI().getQuery());
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, bytes.length);
            try (OutputStream out = ex.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.start();
        try {
            Map<String, Quote> got = new KrwQuotes(properties(server, "korbit"), new MovableClock()).latest();
            assertThat(query.get()).isEqualTo("symbol=btc_krw,eth_krw,xrp_krw,sol_krw,doge_krw");
            assertThat(got).containsOnlyKeys("BTC", "ETH", "XRP", "SOL", "DOGE");
            assertThat(got.get("BTC").price()).isEqualByComparingTo("111155000");
            assertThat(got.get("BTC").changeRate()).isEqualByComparingTo("0.0009");
            assertThat(got.get("XRP").changeRate()).isEqualByComparingTo("-0.025");
            assertThat(got.get("ETH").changeRate()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(got.get("DOGE").price()).isEqualByComparingTo("109.1");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void 시세_거래소는_코빗이나_업비트만_된다() {
        assertThatThrownBy(() -> new PaperProperties("http://x", new BigDecimal("0.0005"), 5000, Duration.ofSeconds(5), "bithumb"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    static HttpServer serve(int status, String body, AtomicInteger calls) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/ticker", ex -> {
            calls.incrementAndGet();
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
            try (OutputStream out = ex.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.start();
        return server;
    }
}
