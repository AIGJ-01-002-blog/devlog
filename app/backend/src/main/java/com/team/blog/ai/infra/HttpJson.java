package com.team.blog.ai.infra;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

import com.team.blog.ai.application.TagModel.ModelException;

/** 공급자 호출 공통: JSON POST 한 번, 시간 제한 안에 끝나지 않으면 FAILURE. */
final class HttpJson {
    private static final HttpClient CLIENT = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    private HttpJson() {}

    record Response(int status, String body) {}

    static Response post(String url, Map<String, String> headers, String body, Duration timeout) throws ModelException {
        HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(url)).timeout(timeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        headers.forEach(req::header);
        try {
            HttpResponse<String> res = CLIENT.send(req.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return new Response(res.statusCode(), res.body());
        } catch (IOException | IllegalArgumentException e) {
            // 시간 초과(HttpTimeoutException)도 IOException이라 함께 실패로 본다
            throw new ModelException(ModelException.Kind.FAILURE);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ModelException(ModelException.Kind.FAILURE);
        }
    }

    static String trimSlash(String base) {
        return base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
    }
}
