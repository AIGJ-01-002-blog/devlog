package com.team.blog.support;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * 테스트용 가짜 업비트 공개 시세 API (#267). /v1/ticker만 흉내 내고, 시험이 정한 값을 돌려준다.
 * down을 켜면 500으로 답한다.
 */
public final class FakeUpbit {
    public static final FakeUpbit INSTANCE = start();

    private final HttpServer server;
    public final Map<String, BigDecimal> prices = new ConcurrentHashMap<>();
    public volatile boolean down;

    private FakeUpbit(HttpServer server) {
        this.server = server;
        reset();
    }

    private static FakeUpbit start() {
        try {
            HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            FakeUpbit f = new FakeUpbit(s);
            s.createContext("/", f::handle);
            s.start();
            return f;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    /** 기본 시세로 되돌린다 */
    public void reset() {
        down = false;
        prices.put("BTC", new BigDecimal("100000000"));
        prices.put("ETH", new BigDecimal("5000000"));
        prices.put("XRP", new BigDecimal("800"));
        prices.put("SOL", new BigDecimal("250000"));
        prices.put("DOGE", new BigDecimal("300"));
        prices.put("USDT", new BigDecimal("1400")); // 바이낸스 모의 봇의 환율 (#352)
    }

    private void handle(HttpExchange ex) throws IOException {
        String query = ex.getRequestURI().getQuery();
        int status = 200;
        String out;
        if (down || !ex.getRequestURI().getPath().equals("/v1/ticker") || query == null || !query.startsWith("markets=")) {
            status = down ? 500 : 404;
            out = "{\"error\":{\"name\":\"fake\"}}";
        } else {
            out = java.util.Arrays.stream(query.substring("markets=".length()).split(","))
                    .filter(m -> prices.containsKey(m.replace("KRW-", "")))
                    .map(m -> "{\"market\":\"" + m + "\",\"trade_price\":" + prices.get(m.replace("KRW-", "")).toPlainString()
                            + ",\"signed_change_rate\":0.0123}")
                    .collect(Collectors.joining(",", "[", "]"));
        }
        byte[] bytes = out.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length);
        ex.getResponseBody().write(bytes);
        ex.close();
    }
}
