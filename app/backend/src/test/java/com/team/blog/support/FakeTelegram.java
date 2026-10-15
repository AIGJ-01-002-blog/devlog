package com.team.blog.support;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 테스트용 가짜 텔레그램 Bot API (023). getMe·sendMessage만 흉내 내고 보낸 메시지를 남긴다.
 * blocked에 넣은 대화로 보내면 403(봇 차단)으로 답한다.
 */
public final class FakeTelegram {
    public static final String BOT = "devlog_test_bot";

    public record Sent(long chatId, String text) {}

    public static final FakeTelegram INSTANCE = start();

    private final HttpServer server;
    private final JsonMapper json = JsonMapper.builder().build();
    public final List<Sent> sent = new CopyOnWriteArrayList<>();
    public final Set<Long> blocked = ConcurrentHashMap.newKeySet();

    private FakeTelegram(HttpServer server) {
        this.server = server;
    }

    private static FakeTelegram start() {
        try {
            HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            FakeTelegram f = new FakeTelegram(s);
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

    /** 이 대화로 보낸 메시지 */
    public List<String> to(long chatId) {
        return sent.stream().filter(m -> m.chatId() == chatId).map(Sent::text).toList();
    }

    /** 이 대화로 n번째 메시지가 올 때까지 기다린다 (보내기는 따로 도는 실행기에서 한다) */
    public String await(long chatId, int count) throws InterruptedException {
        if (!Waits.until(() -> to(chatId).size() >= count, Duration.ofSeconds(10))) {
            throw new AssertionError("텔레그램 메시지가 오지 않았습니다: " + to(chatId));
        }
        return to(chatId).get(count - 1);
    }

    private void handle(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        int status = 200;
        String out;
        if (path.endsWith("/getMe")) {
            out = "{\"ok\":true,\"result\":{\"id\":1,\"is_bot\":true,\"username\":\"" + BOT + "\"}}";
        } else if (path.endsWith("/sendMessage")) {
            JsonNode body = json.readTree(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            long chat = body.path("chat_id").asLong();
            if (blocked.contains(chat)) {
                status = 403;
                out = "{\"ok\":false,\"error_code\":403,\"description\":\"Forbidden: bot was blocked by the user\"}";
            } else {
                sent.add(new Sent(chat, body.path("text").asString()));
                out = "{\"ok\":true,\"result\":{}}";
            }
        } else {
            out = "{\"ok\":true,\"result\":[]}";
        }
        byte[] b = out.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, b.length);
        ex.getResponseBody().write(b);
        ex.close();
    }
}
