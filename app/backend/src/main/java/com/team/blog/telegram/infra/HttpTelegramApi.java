package com.team.blog.telegram.infra;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import com.team.blog.telegram.application.TelegramProperties;

/**
 * Bot API를 직접 부른다(라이브러리 없이 세 가지만). 주소에 토큰이 들어가므로 주소·응답 본문은 로그에 남기지 않는다(FR-007).
 * 메시지는 서식 없이 보낸다: 제목·닉네임에 든 특수 문자를 이스케이프할 일이 없다.
 */
@Component
class HttpTelegramApi implements TelegramApi {
    private static final Logger log = LoggerFactory.getLogger(HttpTelegramApi.class);
    private static final Duration SEND_TIMEOUT = Duration.ofSeconds(10);

    private final TelegramProperties props;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final JsonMapper json = JsonMapper.builder().build();
    private volatile String username;

    HttpTelegramApi(TelegramProperties props) {
        this.props = props;
    }

    private String url(String method) {
        String base = props.baseUrl().endsWith("/") ? props.baseUrl().substring(0, props.baseUrl().length() - 1) : props.baseUrl();
        return base + "/bot" + props.botToken() + "/" + method;
    }

    @Override
    public Optional<String> botUsername() {
        if (username != null) return Optional.of(username);
        if (!props.available()) return Optional.empty();
        JsonNode r = get("getMe", "", SEND_TIMEOUT);
        String name = r == null ? null : r.path("result").path("username").asString(null);
        if (name != null && !name.isBlank()) username = name;
        return Optional.ofNullable(username);
    }

    @Override
    public Optional<List<Update>> updates(long offset) {
        String q = "?timeout=" + props.pollTimeout().toSeconds() + "&offset=" + offset
                + "&allowed_updates=" + URLEncoder.encode("[\"message\"]", StandardCharsets.UTF_8);
        JsonNode r = get("getUpdates", q, props.pollTimeout().plusSeconds(10));
        if (r == null || !r.path("ok").asBoolean(false)) return Optional.empty();
        List<Update> out = new ArrayList<>();
        for (JsonNode u : r.path("result")) {
            JsonNode m = u.path("message");
            JsonNode chat = m.path("chat");
            out.add(new Update(u.path("update_id").asLong(),
                    chat.has("id") ? chat.path("id").asLong() : null,
                    chat.path("type").asString(null),
                    m.has("text") ? m.path("text").asString() : null,
                    m.path("from").path("first_name").asString(null)));
        }
        return Optional.of(out);
    }

    @Override
    public SendResult send(long chatId, String text) {
        ObjectNode body = json.createObjectNode();
        body.put("chat_id", chatId);
        body.put("text", text);
        body.putObject("link_preview_options").put("is_disabled", true);
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url("sendMessage"))).timeout(SEND_TIMEOUT)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body), StandardCharsets.UTF_8)).build();
            HttpResponse<String> res = client.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (res.statusCode() == 200) return SendResult.OK;
            // 403: 봇 차단·탈퇴한 사용자, 400 chat not found: 대화가 없다
            if (res.statusCode() == 403 || (res.statusCode() == 400 && res.body().contains("chat not found"))) return SendResult.GONE;
            log.warn("텔레그램 메시지를 보내지 못했습니다 (HTTP {})", res.statusCode());
            return SendResult.FAILED;
        } catch (IOException | RuntimeException e) {
            // 예외 메시지에는 토큰이 든 주소가 실릴 수 있어(설정한 토큰에 공백 등) 종류만 남긴다
            log.warn("텔레그램 메시지를 보내지 못했습니다: {}", e.getClass().getSimpleName());
            return SendResult.FAILED;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return SendResult.FAILED;
        }
    }

    /** 주소 만들기도 try 안에서 한다: 잘못된 토큰·주소의 예외 메시지에 토큰이 실려 로그로 나가지 않게 (FR-007) */
    private JsonNode get(String method, String query, Duration timeout) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url(method) + query)).timeout(timeout).GET().build();
            HttpResponse<String> res = client.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (res.statusCode() != 200) {
                log.warn("텔레그램 API 호출 실패 (HTTP {})", res.statusCode());
                return null;
            }
            return json.readTree(res.body());
        } catch (IOException | RuntimeException e) {
            log.warn("텔레그램 API 호출 실패: {}", e.getClass().getSimpleName());
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }
}
