package com.team.blog.shorts.autoupload;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.StringJoiner;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 플랫폼 API 호출 도우미. 요청 주소·본문에 토큰이 들어가므로 주소·본문·예외 메시지를 로그나 오류 문구에 싣지 않는다(텔레그램 FR-007과 같다).
 */
final class HttpJson {
    record Response(int status, JsonNode body, HttpResponse<byte[]> raw) {
        boolean ok() {
            return status >= 200 && status < 300;
        }

        String header(String name) {
            return raw.headers().firstValue(name).orElse(null);
        }
    }

    static final JsonMapper JSON = JsonMapper.builder().build();
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER).build();
    private final String platformName;

    HttpJson(String platformName) {
        this.platformName = platformName;
    }

    static String form(Map<String, String> params) {
        StringJoiner j = new StringJoiner("&");
        params.forEach((k, v) -> {
            if (v != null) j.add(enc(k) + "=" + enc(v));
        });
        return j.toString();
    }

    static Map<String, String> params(String... kv) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
        return m;
    }

    static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    Response send(HttpRequest.Builder builder, Duration timeout) {
        try {
            HttpResponse<byte[]> res = client.send(builder.timeout(timeout).build(), HttpResponse.BodyHandlers.ofByteArray());
            return new Response(res.statusCode(), parse(res.body()), res);
        } catch (IOException | RuntimeException e) {
            throw PlatformException.network(platformName);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw PlatformException.network(platformName);
        }
    }

    Response get(String url, String bearer) {
        HttpRequest.Builder b = request(url).GET();
        if (bearer != null) b.header("Authorization", "Bearer " + bearer);
        return send(b, Duration.ofSeconds(20));
    }

    Response postForm(String url, Map<String, String> params) {
        return send(request(url).header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form(params), StandardCharsets.UTF_8)), Duration.ofSeconds(20));
    }

    Response postJson(String url, String bearer, Object body, Map<String, String> headers) {
        HttpRequest.Builder b = request(url).header("Authorization", "Bearer " + bearer)
                .header("Content-Type", "application/json; charset=UTF-8")
                .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body), StandardCharsets.UTF_8));
        headers.forEach(b::header);
        return send(b, Duration.ofSeconds(30));
    }

    /** 주소가 잘못돼도(설정 오타) 예외 메시지에 주소가 실리지 않게 여기서 감싼다 */
    HttpRequest.Builder request(String url) {
        try {
            return HttpRequest.newBuilder(URI.create(url));
        } catch (RuntimeException e) {
            throw new PlatformException(PlatformException.Kind.PERMANENT, platformName + " 주소 설정이 잘못됐어요.");
        }
    }

    private static JsonNode parse(byte[] body) {
        if (body == null || body.length == 0) return JSON.createObjectNode();
        try {
            return JSON.readTree(body);
        } catch (RuntimeException e) {
            return JSON.createObjectNode();
        }
    }

    static String trimBase(String base) {
        return base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
    }

    /** 플랫폼이 준 오류 문구는 짧게만 싣는다 */
    static String brief(String s) {
        if (s == null || s.isBlank()) return "";
        String one = s.replaceAll("\\s+", " ").strip();
        return one.length() > 160 ? one.substring(0, 160) + "…" : one;
    }
}
