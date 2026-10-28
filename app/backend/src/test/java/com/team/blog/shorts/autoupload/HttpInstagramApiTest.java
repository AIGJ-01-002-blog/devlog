package com.team.blog.shorts.autoupload;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.team.blog.shorts.autoupload.PlatformException.Kind;
import com.team.blog.support.FakePlatforms;
import com.team.blog.support.FakePlatforms.Reply;

/** Instagram API with Instagram Login 호출을 가짜 서버로 본다 (spec 108). 진짜 계정에는 보내지 않는다 */
class HttpInstagramApiTest {
    static final Instant NOW = Instant.parse("2026-10-28T02:00:00Z");
    static final String USER = FakePlatforms.IG_USER;
    private FakePlatforms fake;
    private HttpInstagramApi api;

    @BeforeEach
    void start() {
        fake = FakePlatforms.start();
        api = new HttpInstagramApi(Fakes.props(fake.baseUrl()), Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @AfterEach
    void stop() {
        fake.stop();
    }

    @Test
    void 동의_화면은_게시_권한과_state를_싣는다() {
        assertThat(api.authorizeUrl("https://devlog.life/cb", "s1")).startsWith(fake.baseUrl() + "/oauth/authorize?")
                .contains("client_id=ig-app", "scope=instagram_business_basic%2Cinstagram_business_content_publish", "state=s1", "response_type=code")
                .doesNotContain("ig-secret");
    }

    @Test
    void 코드를_장기_토큰으로_바꾸고_늘리고_계정을_읽는다() {
        InstagramApi.Tokens t = api.exchange("c", "https://devlog.life/cb");
        assertThat(t.accessToken()).isEqualTo("ig-long-1");
        assertThat(t.expiresAt()).isEqualTo(NOW.plusSeconds(5184000));
        assertThat(fake.calls("POST /oauth/access_token").getFirst().form()).containsEntry("grant_type", "authorization_code")
                .containsEntry("client_secret", "ig-secret").containsEntry("redirect_uri", "https://devlog.life/cb");
        assertThat(fake.calls("GET /access_token").getFirst().query()).containsEntry("grant_type", "ig_exchange_token")
                .containsEntry("access_token", "ig-short");

        assertThat(api.refresh("ig-long-1").accessToken()).isEqualTo("ig-long-2");
        assertThat(fake.calls("GET /refresh_access_token").getFirst().query()).containsEntry("grant_type", "ig_refresh_token");

        assertThat(api.account("ig-long-2")).isEqualTo(new InstagramApi.Account(USER, "devlog.k"));
        assertThat(fake.calls("GET /v24.0/me").getFirst().query()).containsEntry("fields", "user_id,username");
    }

    @Test
    void 토큰_응답이_data_배열로_와도_읽는다() {
        fake.once("POST /oauth/access_token", new Reply(200, "{\"data\":[{\"access_token\":\"ig-short\",\"user_id\":\"1\"}]}"));
        assertThat(api.exchange("c", "https://devlog.life/cb").accessToken()).isEqualTo("ig-long-1");
    }

    @Test
    void 릴스_컨테이너를_만들고_상태를_보고_게시한다() {
        String container = api.createReel("tok", USER, "https://media.devlog.life/shorts/a.mp4", "캡션 #spring");
        assertThat(container).isEqualTo("container1");
        assertThat(fake.calls("POST /v24.0/" + USER + "/media").getFirst().form()).containsEntry("media_type", "REELS")
                .containsEntry("video_url", "https://media.devlog.life/shorts/a.mp4").containsEntry("caption", "캡션 #spring")
                .containsEntry("access_token", "tok");
        fake.containerStatus = "IN_PROGRESS";
        assertThat(api.status("tok", container)).isEqualTo(InstagramApi.ContainerStatus.IN_PROGRESS);
        fake.containerStatus = "FINISHED";
        assertThat(api.status("tok", container)).isEqualTo(InstagramApi.ContainerStatus.FINISHED);
        assertThat(api.publish("tok", USER, container)).isEqualTo("media1");
        assertThat(fake.calls("POST /v24.0/" + USER + "/media_publish").getFirst().form()).containsEntry("creation_id", "container1");
        assertThat(api.permalink("tok", "media1")).isEqualTo("https://www.instagram.com/reel/DAbc123/");
        assertThat(api.permalink("tok", "nope")).as("못 받으면 null").isNull();
    }

    @Test
    void 만료_토큰은_다시_연결_호출_한도는_다시_하기_거절은_그대로() {
        fake.once("GET /v24.0/me", new Reply(400, "{\"error\":{\"message\":\"Error validating access token\",\"type\":\"OAuthException\",\"code\":190}}"));
        assertThatThrownBy(() -> api.account("tok")).satisfies(e -> assertThat(((PlatformException) e).kind()).isEqualTo(Kind.AUTH));
        fake.once("POST /v24.0/" + USER + "/media", new Reply(400, "{\"error\":{\"message\":\"Application request limit reached\",\"code\":4}}"));
        assertThatThrownBy(() -> api.createReel("tok", USER, "https://x/a.mp4", "c"))
                .satisfies(e -> assertThat(((PlatformException) e).kind()).isEqualTo(Kind.RETRYABLE));
        fake.once("POST /v24.0/" + USER + "/media", new Reply(400, "{\"error\":{\"message\":\"The video file is invalid\",\"code\":352}}"));
        assertThatThrownBy(() -> api.createReel("tok", USER, "https://x/a.mp4", "c"))
                .satisfies(e -> assertThat(((PlatformException) e).kind()).isEqualTo(Kind.PERMANENT))
                .hasMessage("Instagram이 요청을 거절했어요 (HTTP 400, 코드 352): The video file is invalid");
        fake.once("POST /v24.0/" + USER + "/media_publish", new Reply(400, "{\"error\":{\"message\":\"no permission\",\"code\":10}}"));
        assertThatThrownBy(() -> api.publish("tok", USER, "container1"))
                .satisfies(e -> assertThat(((PlatformException) e).kind()).isEqualTo(Kind.AUTH)).hasMessageContaining("게시 권한");
    }
}
