package com.team.blog.shorts.autoupload;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import com.team.blog.shorts.autoupload.PlatformException.Kind;
import com.team.blog.support.FakePlatforms;
import com.team.blog.support.FakePlatforms.Reply;

/** YouTube Data API v3·Google OAuth 호출을 가짜 서버로 본다 (spec 108). 진짜 계정에는 보내지 않는다 */
@ExtendWith(OutputCaptureExtension.class)
class HttpYouTubeApiTest {
    static final Instant NOW = Instant.parse("2026-10-28T02:00:00Z");
    private FakePlatforms fake;
    private HttpYouTubeApi api;
    private final JsonMapper json = JsonMapper.builder().build();

    @BeforeEach
    void start() {
        fake = FakePlatforms.start();
        api = new HttpYouTubeApi(Fakes.props(fake.baseUrl()), Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @AfterEach
    void stop() {
        fake.stop();
    }

    @Test
    void 동의_화면_주소는_오프라인_권한과_state를_싣는다() {
        String url = api.authorizeUrl("https://devlog.life/api/me/shorts/auto-upload/youtube/callback", "st4te");
        assertThat(url).startsWith(fake.baseUrl() + "/o/oauth2/v2/auth?")
                .contains("client_id=yt-client", "access_type=offline", "prompt=consent", "state=st4te", "response_type=code",
                        "scope=https%3A%2F%2Fwww.googleapis.com%2Fauth%2Fyoutube.upload+https%3A%2F%2Fwww.googleapis.com%2Fauth%2Fyoutube",
                        "redirect_uri=https%3A%2F%2Fdevlog.life%2Fapi%2Fme%2Fshorts%2Fauto-upload%2Fyoutube%2Fcallback")
                .doesNotContain("yt-secret");
    }

    @Test
    void 코드를_토큰으로_바꾸고_갱신하고_채널을_읽는다() {
        YouTubeApi.Tokens t = api.exchange("code-1", "https://x/cb");
        assertThat(t.accessToken()).isEqualTo("yt-access-1");
        assertThat(t.refreshToken()).isEqualTo("yt-refresh-1");
        assertThat(t.expiresAt()).isEqualTo(NOW.plusSeconds(3599));
        assertThat(t.toString()).doesNotContain("yt-access-1");
        var form = fake.calls("POST /token").getFirst().form();
        assertThat(form).containsEntry("grant_type", "authorization_code").containsEntry("code", "code-1")
                .containsEntry("client_secret", "yt-secret").containsEntry("redirect_uri", "https://x/cb");

        YouTubeApi.Tokens r = api.refresh("yt-refresh-1");
        assertThat(r.accessToken()).isEqualTo("yt-access-2");
        assertThat(r.refreshToken()).as("갱신 응답에는 갱신 토큰이 없다").isNull();

        YouTubeApi.Channel ch = api.channel("yt-access-2");
        assertThat(ch).isEqualTo(new YouTubeApi.Channel("UCtest", "devlog 채널"));
        assertThat(fake.calls("GET /youtube/v3/channels").getFirst().authorization()).isEqualTo("Bearer yt-access-2");
    }

    @Test
    void 끊긴_갱신_토큰은_다시_연결하라고_한다() {
        fake.once("POST /token", new Reply(400, "{\"error\":\"invalid_grant\",\"error_description\":\"Token has been expired or revoked.\"}"));
        assertThatThrownBy(() -> api.refresh("old")).isInstanceOf(PlatformException.class)
                .satisfies(e -> assertThat(((PlatformException) e).kind()).isEqualTo(Kind.AUTH)).hasMessageContaining("다시 연결해 주세요");
    }

    @Test
    void 비공개_예약과_AI_표시로_재개_가능_업로드를_한다() {
        byte[] video = new byte[4096];
        String id = api.upload("tok", video, new YouTubeApi.Video("[백엔드 면접] 제목", "설명", List.of("spring"), Instant.parse("2026-10-28T11:00:00Z")));
        assertThat(id).isEqualTo(FakePlatforms.VIDEO_ID);
        FakePlatforms.Seen start = fake.calls("POST /upload/youtube/v3/videos").getFirst();
        assertThat(start.query()).containsEntry("uploadType", "resumable").containsEntry("part", "snippet,status");
        assertThat(start.uploadLength()).isEqualTo("4096");
        JsonNode body = json.readTree(start.body());
        assertThat(body.path("snippet").path("title").asString()).isEqualTo("[백엔드 면접] 제목");
        assertThat(body.path("snippet").path("tags").path(0).asString()).isEqualTo("spring");
        assertThat(body.path("status").path("privacyStatus").asString()).isEqualTo("private");
        assertThat(body.path("status").path("publishAt").asString()).isEqualTo("2026-10-28T11:00:00Z");
        assertThat(body.path("status").path("containsSyntheticMedia").asBoolean()).isTrue();
        assertThat(body.path("status").path("selfDeclaredMadeForKids").asBoolean()).isFalse();
        FakePlatforms.Seen put = fake.calls("PUT /upload-session/1").getFirst();
        assertThat(put.bytes()).isEqualTo(4096);
        assertThat(put.contentType()).isEqualTo("video/mp4");
        assertThat(put.authorization()).isEqualTo("Bearer tok");
    }

    @Test
    void 시험_올리기는_공개_예약_없이_비공개로만() {
        api.upload("tok", new byte[10], new YouTubeApi.Video("t", "d", List.of(), null));
        JsonNode body = json.readTree(fake.calls("POST /upload/youtube/v3/videos").getFirst().body());
        assertThat(body.path("status").path("privacyStatus").asString()).isEqualTo("private");
        assertThat(body.path("status").has("publishAt")).isFalse();
    }

    @Test
    void 한도와_서버_오류는_다시_하고_거절은_다시_하지_않는다(CapturedOutput out) {
        fake.once("POST /upload/youtube/v3/videos", new Reply(403, "{\"error\":{\"code\":403,\"message\":\"quota\",\"errors\":[{\"reason\":\"quotaExceeded\"}]}}"));
        assertThatThrownBy(() -> api.upload("secret-tok", new byte[1], new YouTubeApi.Video("t", "d", List.of(), null)))
                .satisfies(e -> assertThat(((PlatformException) e).kind()).isEqualTo(Kind.RETRYABLE)).hasMessageContaining("하루 올리기 한도");
        fake.once("POST /upload/youtube/v3/videos", new Reply(503, "{}"));
        assertThatThrownBy(() -> api.upload("secret-tok", new byte[1], new YouTubeApi.Video("t", "d", List.of(), null)))
                .satisfies(e -> assertThat(((PlatformException) e).kind()).isEqualTo(Kind.RETRYABLE));
        fake.once("POST /upload/youtube/v3/videos", new Reply(400, "{\"error\":{\"code\":400,\"message\":\"Invalid title\",\"errors\":[{\"reason\":\"invalidTitle\"}]}}"));
        assertThatThrownBy(() -> api.upload("secret-tok", new byte[1], new YouTubeApi.Video("t", "d", List.of(), null)))
                .satisfies(e -> assertThat(((PlatformException) e).kind()).isEqualTo(Kind.PERMANENT))
                .hasMessage("YouTube가 요청을 거절했어요 (HTTP 400, invalidTitle): Invalid title");
        fake.once("GET /youtube/v3/channels", new Reply(401, "{\"error\":{\"code\":401}}"));
        assertThatThrownBy(() -> api.channel("secret-tok")).satisfies(e -> assertThat(((PlatformException) e).kind()).isEqualTo(Kind.AUTH));
        assertThat(out.getAll()).doesNotContain("secret-tok");
    }

    @Test
    void 재생목록은_제목이_꼭_같은_것을_찾아_넣고_없으면_비어_있다() {
        assertThat(api.findPlaylist("tok", "[백엔드 면접] AI협업")).contains("PLai");
        assertThat(api.findPlaylist("tok", "[백엔드 면접] 없는 목록")).isEmpty();
        fake.once("GET /youtube/v3/playlists", new Reply(200, "{\"items\":[],\"nextPageToken\":\"p2\"}"));
        assertThat(api.findPlaylist("tok", "[백엔드 면접] AI협업")).as("다음 쪽까지 본다").contains("PLai");
        assertThat(fake.calls("GET /youtube/v3/playlists").getLast().query()).containsEntry("pageToken", "p2").containsEntry("mine", "true");
        api.addToPlaylist("tok", "PLai", "vid");
        JsonNode body = json.readTree(fake.calls("POST /youtube/v3/playlistItems").getFirst().body());
        assertThat(body.path("snippet").path("playlistId").asString()).isEqualTo("PLai");
        assertThat(body.path("snippet").path("resourceId").path("videoId").asString()).isEqualTo("vid");
        assertThat(fake.calls("POST /youtube/v3/playlists")).as("재생목록은 만들지 않는다").isEmpty();
    }

    @Test
    void 서버에_닿지_못하면_다시_할_수_있는_실패다() {
        fake.stop();
        assertThatThrownBy(() -> api.channel("tok")).satisfies(e -> assertThat(((PlatformException) e).kind()).isEqualTo(Kind.RETRYABLE))
                .hasMessage("YouTube에 연결하지 못했어요. 잠시 뒤 다시 해 볼게요.");
        fake = FakePlatforms.start();
    }
}
