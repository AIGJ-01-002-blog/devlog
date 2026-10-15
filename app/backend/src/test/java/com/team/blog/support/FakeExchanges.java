package com.team.blog.support;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.StringJoiner;
import java.util.concurrent.atomic.AtomicInteger;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * 테스트용 가짜 거래소 공개 캔들 (spec 101 자동매매 차트). 바이낸스 /api/v3/klines, 업비트 /v1/candles/…, 코빗 /v2/candles를 흉내 낸다.
 * 봉은 지금부터 거꾸로 15분 간격으로 limit·count개를 만들고, 종가는 100에서 1씩 오른다. SOL은 실패(500)로 답한다.
 */
public final class FakeExchanges {
    public static final FakeExchanges INSTANCE = start();
    private static final DateTimeFormatter UTC = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);

    private final HttpServer server;
    /** 받은 요청 수 (캐시를 확인할 때) */
    public final AtomicInteger calls = new AtomicInteger();
    /** 받은 요청 경로 (원화 탭이 코빗으로 가는지 본다) */
    public final java.util.Queue<String> paths = new java.util.concurrent.ConcurrentLinkedQueue<>();

    private FakeExchanges(HttpServer server) {
        this.server = server;
    }

    private static FakeExchanges start() {
        try {
            HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            FakeExchanges f = new FakeExchanges(s);
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

    private void handle(HttpExchange ex) throws IOException {
        calls.incrementAndGet();
        String path = ex.getRequestURI().getPath();
        paths.add(path);
        String query = ex.getRequestURI().getQuery();
        int n = Integer.parseInt(param(query, path.startsWith("/v1/candles") ? "count" : "limit"));
        long step = 15 * 60_000L;
        long last = Instant.now().toEpochMilli() / step * step;
        String out;
        int status = 200;
        if (query.contains("SOL") || query.contains("sol_krw")) {
            status = 500;
            out = "{}";
        } else if (path.startsWith("/api/v3/klines")) {
            StringJoiner j = new StringJoiner(",", "[", "]");
            for (int i = 0; i < n; i++) {
                long t = last - (long) (n - 1 - i) * step;
                int c = 100 + i;
                j.add("[" + t + ",\"" + (c - 1) + "\",\"" + (c + 1) + "\",\"" + (c - 2) + "\",\"" + c + "\",\"1\"," + (t + step - 1) + "]");
            }
            out = j.toString();
        } else if (path.startsWith("/v2/candles")) {
            StringJoiner j = new StringJoiner(",", "{\"success\":true,\"data\":[", "]}");
            for (int i = 0; i < n; i++) { // 코빗은 오래된 순, 값은 문자열
                long t = last - (long) (n - 1 - i) * step;
                int c = 100 + i;
                j.add("{\"timestamp\":" + t + ",\"open\":\"" + (c - 1) + "\",\"high\":\"" + (c + 1) + "\",\"low\":\"" + (c - 2) + "\",\"close\":\"" + c + "\",\"volume\":\"1\"}");
            }
            out = j.toString();
        } else {
            StringJoiner j = new StringJoiner(",", "[", "]");
            for (int i = n - 1; i >= 0; i--) { // 업비트는 새것부터
                long t = last - (long) (n - 1 - i) * step;
                int c = 100 + i;
                j.add("{\"candle_date_time_utc\":\"" + UTC.format(Instant.ofEpochMilli(t)) + "\",\"opening_price\":" + (c - 1) + ",\"high_price\":" + (c + 1)
                        + ",\"low_price\":" + (c - 2) + ",\"trade_price\":" + c + "}");
            }
            out = j.toString();
        }
        byte[] body = out.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, body.length);
        ex.getResponseBody().write(body);
        ex.close();
    }

    private static String param(String query, String name) {
        for (String kv : query.split("&")) {
            if (kv.startsWith(name + "=")) return kv.substring(name.length() + 1);
        }
        return "10";
    }
}
