package com.team.blog.shorts.autoupload;

import java.time.Clock;
import java.time.Instant;
import java.util.Locale;

import org.springframework.stereotype.Component;

import tools.jackson.databind.JsonNode;

import com.team.blog.shorts.autoupload.PlatformException.Kind;

/**
 * Instagram API with Instagram Login을 직접 부른다. 토큰은 GET이면 주소(access_token), POST면 폼 본문으로 보낸다.
 * 주소·본문은 로그에 남기지 않는다(HttpJson).
 */
@Component
class HttpInstagramApi implements InstagramApi {
    static final String NAME = "Instagram";
    private final AutoUploadProperties.Instagram props;
    private final Clock clock;
    private final HttpJson http = new HttpJson(NAME);

    HttpInstagramApi(AutoUploadProperties props, Clock clock) {
        this.props = props.instagram();
        this.clock = clock;
    }

    @Override
    public String authorizeUrl(String redirectUri, String state) {
        return props.authUrl() + "?" + HttpJson.form(HttpJson.params("client_id", props.appId(), "redirect_uri", redirectUri,
                "response_type", "code", "scope", String.join(",", SCOPES), "state", state));
    }

    @Override
    public Tokens exchange(String code, String redirectUri) {
        HttpJson.Response res = http.postForm(HttpJson.trimBase(props.tokenUrl()) + "/oauth/access_token", HttpJson.params(
                "client_id", props.appId(), "client_secret", props.appSecret(), "grant_type", "authorization_code",
                "redirect_uri", redirectUri, "code", code));
        if (!res.ok()) throw fail(res);
        // 응답이 {access_token,…} 또는 {data:[{access_token,…}]} 두 모양으로 온다
        JsonNode b = res.body().has("data") ? res.body().path("data").path(0) : res.body();
        String shortToken = b.path("access_token").asString(null);
        if (shortToken == null) throw new PlatformException(Kind.RETRYABLE, "Instagram이 토큰을 주지 않았어요. 다시 연결해 주세요.");
        return longLived(http.get(graph("/access_token?" + HttpJson.form(HttpJson.params("grant_type", "ig_exchange_token",
                "client_secret", props.appSecret(), "access_token", shortToken))), null));
    }

    @Override
    public Tokens refresh(String accessToken) {
        return longLived(http.get(graph("/refresh_access_token?" + HttpJson.form(HttpJson.params("grant_type", "ig_refresh_token",
                "access_token", accessToken))), null));
    }

    private Tokens longLived(HttpJson.Response res) {
        if (!res.ok()) throw fail(res);
        String token = res.body().path("access_token").asString(null);
        if (token == null) throw new PlatformException(Kind.RETRYABLE, "Instagram이 장기 토큰을 주지 않았어요. 잠시 뒤 다시 해 볼게요.");
        long expires = res.body().path("expires_in").asLong(60L * 24 * 3600);
        return new Tokens(token, Instant.now(clock).plusSeconds(expires));
    }

    @Override
    public Account account(String accessToken) {
        HttpJson.Response res = http.get(versioned("/me?" + HttpJson.form(HttpJson.params("fields", "user_id,username", "access_token", accessToken))), null);
        if (!res.ok()) throw fail(res);
        String id = res.body().path("user_id").asString(null);
        if (id == null) id = res.body().path("id").asString(null);
        if (id == null) throw new PlatformException(Kind.PERMANENT, "Instagram 계정 번호를 받지 못했어요. 프로페셔널 계정인지 확인해 주세요.");
        return new Account(id, res.body().path("username").asString(""));
    }

    @Override
    public String createReel(String accessToken, String userId, String videoUrl, String caption) {
        HttpJson.Response res = http.postForm(versioned("/" + HttpJson.enc(userId) + "/media"), HttpJson.params(
                "media_type", "REELS", "video_url", videoUrl, "caption", caption, "share_to_feed", "true", "access_token", accessToken));
        if (!res.ok()) throw fail(res);
        return id(res);
    }

    @Override
    public ContainerStatus status(String accessToken, String containerId) {
        HttpJson.Response res = http.get(versioned("/" + HttpJson.enc(containerId) + "?" + HttpJson.form(HttpJson.params(
                "fields", "status_code,status", "access_token", accessToken))), null);
        if (!res.ok()) throw fail(res);
        String code = res.body().path("status_code").asString("IN_PROGRESS").toUpperCase(Locale.ROOT);
        try {
            return ContainerStatus.valueOf(code);
        } catch (IllegalArgumentException e) {
            return ContainerStatus.IN_PROGRESS;
        }
    }

    @Override
    public String publish(String accessToken, String userId, String containerId) {
        HttpJson.Response res = http.postForm(versioned("/" + HttpJson.enc(userId) + "/media_publish"), HttpJson.params(
                "creation_id", containerId, "access_token", accessToken));
        if (!res.ok()) throw fail(res);
        return id(res);
    }

    @Override
    public String permalink(String accessToken, String mediaId) {
        try {
            HttpJson.Response res = http.get(versioned("/" + HttpJson.enc(mediaId) + "?" + HttpJson.form(HttpJson.params(
                    "fields", "permalink", "access_token", accessToken))), null);
            return res.ok() ? res.body().path("permalink").asString(null) : null;
        } catch (PlatformException e) {
            return null;
        }
    }

    private static String id(HttpJson.Response res) {
        String id = res.body().path("id").asString(null);
        if (id == null) throw new PlatformException(Kind.RETRYABLE, "Instagram이 번호를 주지 않았어요. 잠시 뒤 다시 해 볼게요.");
        return id;
    }

    private String graph(String path) {
        return HttpJson.trimBase(props.graphUrl()) + path;
    }

    private String versioned(String path) {
        return graph("/" + props.apiVersion() + path);
    }

    /** Graph API 오류: error.code 190은 토큰 만료·취소, 4·17·32·613·9는 호출 한도 */
    static PlatformException fail(HttpJson.Response res) {
        JsonNode error = res.body().path("error");
        int code = error.path("code").asInt(0);
        String message = error.isObject() ? error.path("message").asString("") : res.body().path("error_message").asString("");
        if (code == 190 || res.status() == 401) return PlatformException.auth(NAME);
        if (code == 4 || code == 17 || code == 32 || code == 613 || code == 9 || res.status() == 429 || res.status() >= 500) {
            return new PlatformException(Kind.RETRYABLE, "Instagram 호출 한도에 걸렸거나 잠시 바빠요. 잠시 뒤 다시 해 볼게요.");
        }
        if (code == 10 || code == 200) {
            return new PlatformException(Kind.AUTH, "Instagram 게시 권한이 없어요. 다시 연결하면서 콘텐츠 게시 권한에 동의해 주세요.");
        }
        String detail = HttpJson.brief(message);
        return new PlatformException(Kind.PERMANENT, "Instagram이 요청을 거절했어요 (HTTP " + res.status() + (code == 0 ? "" : ", 코드 " + code) + ")"
                + (detail.isEmpty() ? "." : ": " + detail));
    }
}
