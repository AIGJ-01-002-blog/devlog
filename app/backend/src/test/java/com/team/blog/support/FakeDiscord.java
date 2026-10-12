package com.team.blog.support;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 테스트용 가짜 디스코드 웹훅 API (078). 웹훅 조회(GET)·보내기(POST)만 흉내 내고 보낸 메시지를 남긴다.
 * deleted에 넣은 웹훅 번호는 404(지운 웹훅)로, limitedOnce에 넣은 번호는 한 번 429로 답한다.
 */
public final class FakeDiscord {
    public static final String NAME = "블로그 알림";

    /** @param body 보낸 요청 본문 전체 (멘션·플래그 확인용) */
    public record Sent(String webhookId, String text, JsonNode body) {}

    public static final FakeDiscord INSTANCE = start();

    private final HttpServer server;
    private final JsonMapper json = JsonMapper.builder().build();
    public final List<Sent> sent = new CopyOnWriteArrayList<>();
    public final Set<String> deleted = ConcurrentHashMap.newKeySet();
    /** 여기 넣은 웹훅은 다음 보내기 한 번을 429(잠깐 기다리라)로 답한다 */
    public final Set<String> limitedOnce = ConcurrentHashMap.newKeySet();
    /** 보내기 요청에 wait=true가 붙어 왔는지 */
    public final List<Boolean> waited = new CopyOnWriteArrayList<>();

    private FakeDiscord(HttpServer server) {
        this.server = server;
    }

    private static FakeDiscord start() {
        try {
            HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            FakeDiscord f = new FakeDiscord(s);
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

    public List<String> to(String webhookId) {
        return sent.stream().filter(m -> m.webhookId().equals(webhookId)).map(Sent::text).toList();
    }

    /** 이 웹훅으로 n번째 메시지가 올 때까지 기다린다 (보내기는 따로 도는 실행기에서 한다) */
    public String await(String webhookId, int count) throws InterruptedException {
        long until = System.currentTimeMillis() + 10_000;
        while (to(webhookId).size() < count) {
            if (System.currentTimeMillis() > until) throw new AssertionError("디스코드 메시지가 오지 않았습니다: " + to(webhookId));
            Thread.sleep(10);
        }
        return to(webhookId).get(count - 1);
    }

    private void handle(HttpExchange ex) throws IOException {
        String[] parts = ex.getRequestURI().getPath().split("/");
        // 웹훅 주소는 /api/webhooks/{id}/{token} 모양만 받는다
        String id = parts.length >= 5 ? parts[3] : "";
        int status;
        String out = "";
        if (parts.length != 5 || !"webhooks".equals(parts[2])) {
            status = 400;
        } else if (deleted.contains(id)) {
            status = 404;
            out = "{\"message\":\"Unknown Webhook\",\"code\":10015}";
        } else if ("GET".equals(ex.getRequestMethod())) {
            status = 200;
            out = "{\"id\":\"" + id + "\",\"type\":1,\"name\":\"" + NAME + "\",\"channel_id\":\"1\"}";
        } else if (limitedOnce.remove(id)) {
            status = 429;
            out = "{\"message\":\"You are being rate limited.\",\"retry_after\":0.2,\"global\":false}";
        } else {
            waited.add("wait=true".equals(ex.getRequestURI().getQuery()));
            JsonNode body = json.readTree(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            sent.add(new Sent(id, body.path("content").asString(), body));
            status = 200;
            out = "{\"id\":\"1\",\"channel_id\":\"1\"}";
        }
        byte[] b = out.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, b.length == 0 ? -1 : b.length);
        if (b.length > 0) ex.getResponseBody().write(b);
        ex.close();
    }
}
