package com.team.blog.telegram.infra;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import com.sun.net.httpserver.HttpServer;

import com.team.blog.telegram.application.TelegramProperties;
import com.team.blog.telegram.application.TelegramProperties.Audience;
import com.team.blog.telegram.infra.TelegramApi.SendResult;
import com.team.blog.telegram.infra.TelegramApi.Update;

/** Bot API 호출을 가짜 서버로 확인한다: 주소, 보내는 본문, 응답 해석, 실패 분류, 토큰이 로그에 남지 않음(023 FR-007). */
@ExtendWith(OutputCaptureExtension.class)
class HttpTelegramApiTest {
    static final String TOKEN = "fake-token-for-test";

    record Seen(String method, String pathAndQuery, String contentType, String body) {}
    record Reply(int status, String body) {}

    private HttpServer server;
    private final List<Seen> seen = new CopyOnWriteArrayList<>();
    private final Map<String, Reply> replies = new ConcurrentHashMap<>();

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String path = ex.getRequestURI().getPath();
            String raw = ex.getRequestURI().getRawQuery();
            seen.add(new Seen(ex.getRequestMethod(), raw == null ? path : path + "?" + raw,
                    ex.getRequestHeaders().getFirst("Content-Type"), body));
            Reply r = replies.getOrDefault(path.substring(path.lastIndexOf('/') + 1), new Reply(404, "{}"));
            byte[] out = r.body().getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(r.status(), out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private String base() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private static HttpTelegramApi api(String baseUrl, String token) {
        return new HttpTelegramApi(new TelegramProperties(true, token, baseUrl, Duration.ofSeconds(1), false,
                Duration.ofMinutes(10), 20, 4000, Audience.ADMINS));
    }

    @Test
    void botUsernameIsAskedOnceAndRemembered() {
        replies.put("getMe", new Reply(200, "{\"ok\":true,\"result\":{\"username\":\"devlog_bot\"}}"));
        HttpTelegramApi api = api(base() + "/", TOKEN);
        assertThat(api.botUsername()).contains("devlog_bot");
        assertThat(api.botUsername()).contains("devlog_bot");
        assertThat(seen).hasSize(1);
        // 끝에 붙은 / 는 한 번만 쓴다
        assertThat(seen.get(0).pathAndQuery()).isEqualTo("/bot" + TOKEN + "/getMe");
    }

    @Test
    void botUsernameIsRetriedAfterFailure() {
        replies.put("getMe", new Reply(502, "bad gateway"));
        HttpTelegramApi api = api(base(), TOKEN);
        assertThat(api.botUsername()).isEmpty();
        replies.put("getMe", new Reply(200, "{\"ok\":true,\"result\":{\"username\":\"devlog_bot\"}}"));
        assertThat(api.botUsername()).contains("devlog_bot");
        assertThat(seen).hasSize(2);
    }

    @Test
    void nothingIsCalledWithoutAToken() {
        assertThat(api(base(), "").botUsername()).isEmpty();
        assertThat(seen).isEmpty();
    }

    @Test
    void updatesAreParsedIncludingMessagesWithoutTextOrChat() {
        replies.put("getUpdates", new Reply(200, """
                {"ok":true,"result":[
                  {"update_id":7,"message":{"chat":{"id":42,"type":"private"},"from":{"first_name":"민서"},"text":"/start abc"}},
                  {"update_id":8,"message":{"chat":{"id":-5,"type":"group"},"photo":[]}},
                  {"update_id":9}
                ]}"""));
        List<Update> updates = api(base(), TOKEN).updates(7).orElseThrow();
        assertThat(updates).containsExactly(
                new Update(7, 42L, "private", "/start abc", "민서"),
                new Update(8, -5L, "group", null, null),
                new Update(9, null, null, null, null));
        assertThat(seen.get(0).pathAndQuery())
                .isEqualTo("/bot" + TOKEN + "/getUpdates?timeout=1&offset=7&allowed_updates=%5B%22message%22%5D");
    }

    @Test
    void failedUpdatesReturnEmpty() {
        HttpTelegramApi api = api(base(), TOKEN);
        replies.put("getUpdates", new Reply(200, "{\"ok\":false}"));
        assertThat(api.updates(0)).isEmpty();
        replies.put("getUpdates", new Reply(500, "oops"));
        assertThat(api.updates(0)).isEmpty();
        replies.put("getUpdates", new Reply(200, "not json"));
        assertThat(api.updates(0)).isEmpty();
    }

    @Test
    void sendPostsPlainTextWithoutLinkPreview() {
        replies.put("sendMessage", new Reply(200, "{\"ok\":true}"));
        assertThat(api(base(), TOKEN).send(42, "새 댓글: \"제목\" <b>")).isEqualTo(SendResult.OK);
        Seen s = seen.get(0);
        assertThat(s.method()).isEqualTo("POST");
        assertThat(s.pathAndQuery()).isEqualTo("/bot" + TOKEN + "/sendMessage");
        assertThat(s.contentType()).isEqualTo("application/json");
        assertThat(s.body()).isEqualTo(
                "{\"chat_id\":42,\"text\":\"새 댓글: \\\"제목\\\" <b>\",\"link_preview_options\":{\"is_disabled\":true}}");
    }

    @Test
    void blockedOrMissingChatIsGoneAndOtherErrorsFail() {
        HttpTelegramApi api = api(base(), TOKEN);
        replies.put("sendMessage", new Reply(403, "{\"ok\":false,\"description\":\"Forbidden: bot was blocked by the user\"}"));
        assertThat(api.send(1, "x")).isEqualTo(SendResult.GONE);
        replies.put("sendMessage", new Reply(400, "{\"ok\":false,\"description\":\"Bad Request: chat not found\"}"));
        assertThat(api.send(1, "x")).isEqualTo(SendResult.GONE);
        replies.put("sendMessage", new Reply(400, "{\"ok\":false,\"description\":\"Bad Request: message is too long\"}"));
        assertThat(api.send(1, "x")).isEqualTo(SendResult.FAILED);
        replies.put("sendMessage", new Reply(429, "{\"ok\":false}"));
        assertThat(api.send(1, "x")).isEqualTo(SendResult.FAILED);
    }

    @Test
    void unreachableServerFailsWithoutLeakingTheToken(CapturedOutput output) throws Exception {
        int closedPort;
        try (ServerSocket s = new ServerSocket(0)) {
            closedPort = s.getLocalPort();
        }
        HttpTelegramApi api = api("http://127.0.0.1:" + closedPort, TOKEN);
        assertThat(api.send(1, "x")).isEqualTo(SendResult.FAILED);
        assertThat(api.updates(0)).isEmpty();
        assertThat(api.botUsername()).isEmpty();
        replies.put("sendMessage", new Reply(500, TOKEN));
        assertThat(api(base(), TOKEN).send(1, "x")).isEqualTo(SendResult.FAILED);
        assertThat(output.getAll()).contains("HttpTelegramApi").doesNotContain(TOKEN);
    }

    @Test
    void malformedTokenNeverThrowsOrLogsTheToken(CapturedOutput output) {
        // 비밀값 파일 끝의 줄바꿈·공백이 그대로 들어온 경우: 주소를 만들 수 없다
        String broken = TOKEN + " \n";
        HttpTelegramApi api = api(base(), broken);
        assertThat(api.botUsername()).isEmpty();
        assertThat(api.updates(0)).isEmpty();
        assertThat(api.send(1, "x")).isEqualTo(SendResult.FAILED);
        assertThat(seen).isEmpty();
        assertThat(output.getAll()).contains("IllegalArgumentException").doesNotContain(TOKEN);
    }
}
