package com.team.blog.shorts.autoupload;

import java.net.http.HttpRequest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Component;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import com.team.blog.shorts.autoupload.PlatformException.Kind;

/**
 * Google OAuth + YouTube Data API v3를 직접 부른다(라이브러리 없이 여섯 가지만).
 * 올리기는 재개 가능 업로드(resumable): 메타데이터로 세션 주소를 받고 그 주소에 영상 바이트를 한 번에 PUT한다. 영상은 20MB 이하다.
 * videos.insert 1회는 할당량 "Video Uploads" 묶음에서 1단위(하루 100번)다.
 */
@Component
class HttpYouTubeApi implements YouTubeApi {
    static final String NAME = "YouTube";
    private final AutoUploadProperties.YouTube props;
    private final Clock clock;
    private final HttpJson http = new HttpJson(NAME);

    HttpYouTubeApi(AutoUploadProperties props, Clock clock) {
        this.props = props.youtube();
        this.clock = clock;
    }

    @Override
    public String authorizeUrl(String redirectUri, String state) {
        return props.authUrl() + "?" + HttpJson.form(HttpJson.params(
                "client_id", props.clientId(), "redirect_uri", redirectUri, "response_type", "code",
                "scope", String.join(" ", SCOPES), "access_type", "offline", "prompt", "consent",
                "include_granted_scopes", "true", "state", state));
    }

    @Override
    public Tokens exchange(String code, String redirectUri) {
        return tokens(http.postForm(props.tokenUrl(), HttpJson.params("code", code, "client_id", props.clientId(),
                "client_secret", props.clientSecret(), "redirect_uri", redirectUri, "grant_type", "authorization_code")));
    }

    @Override
    public Tokens refresh(String refreshToken) {
        return tokens(http.postForm(props.tokenUrl(), HttpJson.params("refresh_token", refreshToken, "client_id", props.clientId(),
                "client_secret", props.clientSecret(), "grant_type", "refresh_token")));
    }

    private Tokens tokens(HttpJson.Response res) {
        if (!res.ok()) {
            String error = res.body().path("error").asString("");
            // invalid_grant: 갱신 토큰이 끊김(비밀번호 변경·권한 취소·동의 화면 "테스트"면 7일 뒤 만료)
            if (error.equals("invalid_grant") || res.status() == 401) throw PlatformException.auth(NAME);
            throw fail(res);
        }
        JsonNode b = res.body();
        String access = b.path("access_token").asString(null);
        if (access == null) throw new PlatformException(Kind.RETRYABLE, "Google이 토큰을 주지 않았어요. 잠시 뒤 다시 해 볼게요.");
        long expires = b.path("expires_in").asLong(3600);
        return new Tokens(access, b.path("refresh_token").asString(null), Instant.now(clock).plusSeconds(expires));
    }

    @Override
    public Channel channel(String accessToken) {
        HttpJson.Response res = http.get(api("/youtube/v3/channels?part=snippet&mine=true"), accessToken);
        if (!res.ok()) throw fail(res);
        JsonNode item = res.body().path("items").path(0);
        if (item.isMissingNode()) {
            throw new PlatformException(Kind.PERMANENT, "이 Google 계정에는 YouTube 채널이 없어요. 채널을 만든 뒤 다시 연결해 주세요.");
        }
        return new Channel(item.path("id").asString(), item.path("snippet").path("title").asString(""));
    }

    @Override
    public String upload(String accessToken, byte[] video, Video meta) {
        ObjectNode body = HttpJson.JSON.createObjectNode();
        ObjectNode snippet = body.putObject("snippet");
        snippet.put("title", meta.title());
        snippet.put("description", meta.description());
        var tags = snippet.putArray("tags");
        meta.tags().forEach(tags::add);
        snippet.put("categoryId", "28");
        ObjectNode status = body.putObject("status");
        status.put("privacyStatus", "private");
        if (meta.publishAt() != null) status.put("publishAt", DateTimeFormatter.ISO_INSTANT.format(meta.publishAt()));
        status.put("selfDeclaredMadeForKids", false);
        // AI 목소리·그림으로 만든 영상이라 "변경되거나 합성된 콘텐츠"로 표시한다
        status.put("containsSyntheticMedia", true);
        status.put("embeddable", true);
        HttpJson.Response start = http.postJson(api("/upload/youtube/v3/videos?uploadType=resumable&part=snippet,status"), accessToken, body,
                Map.of("X-Upload-Content-Type", "video/mp4", "X-Upload-Content-Length", Integer.toString(video.length)));
        if (!start.ok()) throw fail(start);
        String session = start.header("Location");
        if (session == null) throw new PlatformException(Kind.RETRYABLE, "YouTube가 올리기 주소를 주지 않았어요. 잠시 뒤 다시 해 볼게요.");
        HttpJson.Response put = http.send(http.request(session).header("Authorization", "Bearer " + accessToken)
                .header("Content-Type", "video/mp4").PUT(HttpRequest.BodyPublishers.ofByteArray(video)), Duration.ofMinutes(5));
        if (!put.ok()) throw fail(put);
        String id = put.body().path("id").asString(null);
        if (id == null) throw new PlatformException(Kind.RETRYABLE, "YouTube가 영상 번호를 주지 않았어요. 잠시 뒤 다시 해 볼게요.");
        return id;
    }

    @Override
    public Optional<String> findPlaylist(String accessToken, String title) {
        String page = null;
        // 재생목록이 많아도 5쪽(250개)까지만 본다
        for (int i = 0; i < 5; i++) {
            String q = "/youtube/v3/playlists?part=snippet&mine=true&maxResults=50" + (page == null ? "" : "&pageToken=" + HttpJson.enc(page));
            HttpJson.Response res = http.get(api(q), accessToken);
            if (!res.ok()) throw fail(res);
            for (JsonNode p : res.body().path("items")) {
                if (title.equals(p.path("snippet").path("title").asString(null))) return Optional.of(p.path("id").asString());
            }
            page = res.body().path("nextPageToken").asString(null);
            if (page == null) break;
        }
        return Optional.empty();
    }

    @Override
    public void addToPlaylist(String accessToken, String playlistId, String videoId) {
        ObjectNode body = HttpJson.JSON.createObjectNode();
        ObjectNode snippet = body.putObject("snippet");
        snippet.put("playlistId", playlistId);
        ObjectNode resource = snippet.putObject("resourceId");
        resource.put("kind", "youtube#video");
        resource.put("videoId", videoId);
        HttpJson.Response res = http.postJson(api("/youtube/v3/playlistItems?part=snippet"), accessToken, body, Map.of());
        if (!res.ok()) throw fail(res);
    }

    private String api(String path) {
        return HttpJson.trimBase(props.apiUrl()) + path;
    }

    /** Google API 오류: error.errors[0].reason으로 한도·권한을 가른다 */
    static PlatformException fail(HttpJson.Response res) {
        JsonNode error = res.body().path("error");
        String reason = error.path("errors").path(0).path("reason").asString("");
        if (reason.isEmpty()) reason = error.path("status").asString("");
        String message = error.isObject() ? error.path("message").asString("") : res.body().path("error_description").asString("");
        if (res.status() == 401) return PlatformException.auth(NAME);
        if (reason.equals("quotaExceeded") || reason.equals("uploadLimitExceeded") || reason.equals("dailyLimitExceeded")) {
            return new PlatformException(Kind.RETRYABLE, "YouTube 하루 올리기 한도를 넘었어요. 한도가 풀리면 다시 올려요.");
        }
        if (reason.equals("rateLimitExceeded") || reason.equals("userRateLimitExceeded") || res.status() == 429 || res.status() >= 500) {
            return new PlatformException(Kind.RETRYABLE, "YouTube가 잠시 바빠요 (HTTP " + res.status() + "). 잠시 뒤 다시 해 볼게요.");
        }
        if (reason.equals("insufficientPermissions") || (reason.equals("forbidden") && res.status() == 403)) {
            return new PlatformException(Kind.AUTH, "YouTube 권한이 모자라요. 다시 연결하면서 모든 권한에 동의해 주세요.");
        }
        String detail = HttpJson.brief(message);
        return new PlatformException(Kind.PERMANENT, "YouTube가 요청을 거절했어요 (HTTP " + res.status() + (reason.isEmpty() ? "" : ", " + reason) + ")"
                + (detail.isEmpty() ? "." : ": " + detail));
    }
}
