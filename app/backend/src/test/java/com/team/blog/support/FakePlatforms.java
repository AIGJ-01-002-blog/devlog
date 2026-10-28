package com.team.blog.support;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * 테스트용 가짜 Google OAuth·YouTube Data API·Instagram API (spec 108). 진짜 계정에는 아무것도 보내지 않는다.
 * 기본 응답은 성공 흐름이고, {@link #once}로 한 번만 다른 응답을 줄 수 있다. 받은 요청은 {@link #seen}에 남는다.
 */
public final class FakePlatforms {
    public record Seen(String method, String path, Map<String, String> query, Map<String, String> form, String authorization,
                       String contentType, String uploadLength, String body, int bytes) {}

    public record Reply(int status, String body, Map<String, String> headers) {
        public Reply(int status, String body) {
            this(status, body, Map.of());
        }
    }

    public static final String IG_USER = "17841400000000001";
    public static final String VIDEO_ID = "abcDEF12345";

    private final HttpServer server;
    public final List<Seen> seen = new CopyOnWriteArrayList<>();
    private final Map<String, Deque<Reply>> overrides = new ConcurrentHashMap<>();
    /** 컨테이너 상태 (GET /v24.0/{container}) */
    public volatile String containerStatus = "FINISHED";
    /** 영상 바이트(PUT /upload-session/1)를 받은 뒤 응답하기 전에 부른다. 시험이 업로드 도중을 붙잡을 때 쓴다 */
    public volatile Runnable onVideoPut;

    private FakePlatforms(HttpServer server) {
        this.server = server;
    }

    public static FakePlatforms start() {
        try {
            HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            FakePlatforms f = new FakePlatforms(s);
            s.createContext("/", f::handle);
            s.start();
            return f;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public void stop() {
        server.stop(0);
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    public void reset() {
        seen.clear();
        overrides.clear();
        containerStatus = "FINISHED";
        onVideoPut = null;
    }

    /** "POST /youtube/v3/playlistItems"처럼 방법과 경로로 한 번만 다른 응답 */
    public void once(String methodAndPath, Reply reply) {
        overrides.computeIfAbsent(methodAndPath, k -> new ArrayDeque<>()).add(reply);
    }

    public List<Seen> calls(String methodAndPath) {
        return seen.stream().filter(s -> (s.method() + " " + s.path()).equals(methodAndPath)).toList();
    }

    private void handle(HttpExchange ex) throws IOException {
        byte[] raw = ex.getRequestBody().readAllBytes();
        String method = ex.getRequestMethod();
        String path = ex.getRequestURI().getPath();
        String ct = ex.getRequestHeaders().getFirst("Content-Type");
        boolean form = ct != null && ct.startsWith("application/x-www-form-urlencoded");
        boolean video = ct != null && ct.startsWith("video/");
        String body = video ? "" : new String(raw, StandardCharsets.UTF_8);
        seen.add(new Seen(method, path, parse(ex.getRequestURI().getRawQuery()), form ? parse(body) : Map.of(),
                ex.getRequestHeaders().getFirst("Authorization"), ct, ex.getRequestHeaders().getFirst("X-Upload-Content-Length"), body, raw.length));
        String key = method + " " + path;
        Runnable hook = onVideoPut;
        if (hook != null && key.equals("PUT /upload-session/1")) hook.run();
        Deque<Reply> q = overrides.get(key);
        Reply r = q == null ? null : q.poll();
        if (r == null) r = defaults(method, path);
        r.headers().forEach((k, v) -> ex.getResponseHeaders().add(k, v));
        ex.getResponseHeaders().add("Content-Type", "application/json");
        byte[] out = r.body().getBytes(StandardCharsets.UTF_8);
        ex.sendResponseHeaders(r.status(), out.length == 0 ? -1 : out.length);
        if (out.length > 0) ex.getResponseBody().write(out);
        ex.close();
    }

    private Reply defaults(String method, String path) {
        String key = method + " " + path;
        return switch (key) {
            case "POST /token" -> seen.getLast().form().get("grant_type").equals("refresh_token")
                    ? new Reply(200, "{\"access_token\":\"yt-access-2\",\"expires_in\":3599,\"token_type\":\"Bearer\"}")
                    : new Reply(200, "{\"access_token\":\"yt-access-1\",\"refresh_token\":\"yt-refresh-1\",\"expires_in\":3599}");
            case "GET /youtube/v3/channels" -> new Reply(200, "{\"items\":[{\"id\":\"UCtest\",\"snippet\":{\"title\":\"devlog 채널\"}}]}");
            case "POST /upload/youtube/v3/videos" -> new Reply(200, "", Map.of("Location", baseUrl() + "/upload-session/1"));
            case "PUT /upload-session/1" -> new Reply(200, "{\"id\":\"" + VIDEO_ID + "\",\"status\":{\"privacyStatus\":\"private\"}}");
            case "GET /youtube/v3/playlists" -> new Reply(200,
                    "{\"items\":[{\"id\":\"PLother\",\"snippet\":{\"title\":\"다른 목록\"}},{\"id\":\"PLai\",\"snippet\":{\"title\":\"[백엔드 면접] AI협업\"}}]}");
            case "POST /youtube/v3/playlistItems" -> new Reply(200, "{\"id\":\"item1\"}");
            case "POST /oauth/access_token" -> new Reply(200, "{\"access_token\":\"ig-short\",\"user_id\":\"" + IG_USER + "\",\"permissions\":\"instagram_business_basic,instagram_business_content_publish\"}");
            case "GET /access_token" -> new Reply(200, "{\"access_token\":\"ig-long-1\",\"token_type\":\"bearer\",\"expires_in\":5184000}");
            case "GET /refresh_access_token" -> new Reply(200, "{\"access_token\":\"ig-long-2\",\"token_type\":\"bearer\",\"expires_in\":5184000}");
            case "GET /v24.0/me" -> new Reply(200, "{\"user_id\":\"" + IG_USER + "\",\"username\":\"devlog.k\"}");
            case "POST /v24.0/" + IG_USER + "/media" -> new Reply(200, "{\"id\":\"container1\"}");
            case "GET /v24.0/container1" -> new Reply(200, "{\"status_code\":\"" + containerStatus + "\"}");
            case "POST /v24.0/" + IG_USER + "/media_publish" -> new Reply(200, "{\"id\":\"media1\"}");
            case "GET /v24.0/media1" -> new Reply(200, "{\"permalink\":\"https://www.instagram.com/reel/DAbc123/\",\"id\":\"media1\"}");
            default -> new Reply(404, "{\"error\":{\"message\":\"no route " + key + "\"}}");
        };
    }

    private static Map<String, String> parse(String raw) {
        Map<String, String> m = new LinkedHashMap<>();
        if (raw == null || raw.isEmpty()) return m;
        for (String pair : raw.split("&")) {
            int i = pair.indexOf('=');
            String k = URLDecoder.decode(i < 0 ? pair : pair.substring(0, i), StandardCharsets.UTF_8);
            String v = i < 0 ? "" : URLDecoder.decode(pair.substring(i + 1), StandardCharsets.UTF_8);
            m.put(k, v);
        }
        return m;
    }
}
