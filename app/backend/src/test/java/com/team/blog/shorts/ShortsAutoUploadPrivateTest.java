package com.team.blog.shorts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.URI;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import tools.jackson.databind.JsonNode;

import com.team.blog.media.storage.ObjectStorage;
import com.team.blog.shorts.autoupload.AutoUploadJobs;
import com.team.blog.support.Browser;
import com.team.blog.support.FakePlatforms;
import com.team.blog.support.IntegrationTest;

/**
 * 쇼츠 자동 올리기를 공개 예약 없이 비공개로만 (#501). 렌더가 끝나면 바로 비공개로 올리고 UPLOADED로 끝낸다.
 * 공개는 주인이 YouTube 스튜디오에서 직접 하므로 블로그에 보이는 올린 주소(shorts_upload)는 적지 않는다.
 * 진짜 계정에는 아무것도 보내지 않는다.
 */
class ShortsAutoUploadPrivateTest extends IntegrationTest {
    static final FakePlatforms FAKE = FakePlatforms.start();

    @DynamicPropertySource
    static void platforms(DynamicPropertyRegistry r) {
        r.add("blog.shorts.auto-upload.youtube.auth-url", () -> FAKE.baseUrl() + "/o/oauth2/v2/auth");
        r.add("blog.shorts.auto-upload.youtube.token-url", () -> FAKE.baseUrl() + "/token");
        r.add("blog.shorts.auto-upload.youtube.api-url", FAKE::baseUrl);
        r.add("blog.shorts.auto-upload.youtube.privacy", () -> "PRIVATE");
    }

    @AfterAll
    static void stop() {
        FAKE.stop();
    }

    private static final List<Map<String, String>> SCENES = List.of(
            Map.of("caption", "배포하면 다 로그아웃?", "narration", "왜 배포만 하면 사용자가 전부 로그아웃될까요?"),
            Map.of("caption", "범인은 직렬화", "narration", "로그를 보니 세션을 저장한 클래스 모양이 바뀌어 예전 세션을 못 읽고 있었어요."),
            Map.of("caption", "결과", "narration", "JSON으로 바꾸니 재배포 뒤 다시 로그인하는 사람이 없어졌어요."));

    @Autowired AutoUploadJobs jobs;
    @Autowired ObjectStorage storage;

    @BeforeEach
    void clean() {
        FAKE.reset();
        jdbc.update("DELETE FROM shorts_auto_upload");
        jdbc.update("DELETE FROM shorts_platform_connection");
    }

    private long readyShorts(Browser b, String title) throws Exception {
        long id = read(b.perform(asJson(post("/api/me/shorts"), Map.of("title", title, "scenes", SCENES))).andExpect(status().isCreated()).andReturn())
                .path("id").asLong();
        String key = "shorts/2026/10/private-" + id + ".mp4";
        storage.put(key, new byte[] {0, 0, 0, 0x18, 'f', 't', 'y', 'p'}, "video/mp4");
        jdbc.update("UPDATE shorts SET status = 'READY', video_key = ?, video_bytes = 8, duration_ms = 33000, rendered_at = ? WHERE id = ?",
                key, Timestamp.from(Instant.now()), id);
        return id;
    }

    private void enableYouTube(Browser b) throws Exception {
        String url = read(b.perform(post("/api/me/shorts/auto-upload/youtube/connect").with(SecurityMockMvcRequestPostProcessors.csrf()))
                .andExpect(status().isOk()).andReturn()).path("url").asString();
        String state = null;
        for (String pair : URI.create(url).getRawQuery().split("&")) {
            if (pair.startsWith("state=")) state = java.net.URLDecoder.decode(pair.substring(6), java.nio.charset.StandardCharsets.UTF_8);
        }
        b.perform(get("/api/me/shorts/auto-upload/youtube/callback").param("code", "code-1").param("state", state)).andExpect(status().isFound());
        long sample = readyShorts(b, "시험 영상");
        b.perform(asJson(post("/api/me/shorts/auto-upload/youtube/test"), Map.of("shortsId", sample))).andExpect(status().isOk());
        b.perform(asJson(put("/api/me/shorts/auto-upload/youtube/enabled"), Map.of("enabled", true))).andExpect(status().isOk());
        FAKE.reset();
    }

    @Test
    void 렌더가_끝나면_공개_예약_없이_바로_비공개로_올리고_UPLOADED로_끝낸다() throws Exception {
        Session me = signup(uniqueLogin("aupriv"));
        jdbc.update("UPDATE member SET role = 'ADMIN' WHERE id = ?", me.memberId());
        Browser b = relogin(me);
        enableYouTube(b);
        assertThat(read(b.perform(get("/api/me/shorts/auto-upload")).andExpect(status().isOk()).andReturn()).path("youtubePrivate").asBoolean())
                .as("화면이 안내 문구를 바꾸도록 알린다").isTrue();

        long first = readyShorts(b, "첫 영상");
        long second = readyShorts(b, "같은 날 만든 두 번째 영상");
        assertThat(jobs.enqueue()).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM shorts_auto_upload WHERE member_id = ? AND next_attempt_at <= now()", Integer.class,
                me.memberId())).as("공개 날을 기다리지 않고 바로 올릴 차례").isEqualTo(2);

        assertThat(jobs.runDue()).isEqualTo(2);
        assertThat(FAKE.calls("PUT /upload-session/1")).as("같은 날 두 편도 바로 올린다").hasSize(2);
        for (FakePlatforms.Seen call : FAKE.calls("POST /upload/youtube/v3/videos")) {
            JsonNode meta = json.readTree(call.body());
            assertThat(meta.path("status").path("privacyStatus").asString()).isEqualTo("private");
            assertThat(meta.path("status").has("publishAt")).as("공개 예약을 걸지 않는다").isFalse();
        }

        for (long id : List.of(first, second)) {
            JsonNode view = read(b.perform(get("/api/me/shorts/" + id + "/auto-uploads")).andExpect(status().isOk()).andReturn()).path(0);
            assertThat(view.path("status").asString()).isEqualTo("UPLOADED");
            assertThat(view.path("privateOnly").asBoolean()).isTrue();
            assertThat(view.path("url").asString()).isEqualTo("https://youtube.com/shorts/" + FakePlatforms.VIDEO_ID);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM shorts_upload WHERE shorts_id = ?", Integer.class, id))
                    .as("비공개 영상 주소는 블로그에 보이는 올린 주소에 적지 않는다").isZero();
        }

        // 끝난 작업은 다시 깨워도 아무것도 하지 않는다
        jdbc.update("UPDATE shorts_auto_upload SET next_attempt_at = now() - interval '1 second'");
        assertThat(jobs.runDue()).isZero();
        assertThat(FAKE.calls("PUT /upload-session/1")).hasSize(2);
    }

    @Test
    void 올린_뒤_실패해_다시_해도_같은_영상을_또_올리지_않고_UPLOADED로_끝낸다() throws Exception {
        Session me = signup(uniqueLogin("auprivonce"));
        jdbc.update("UPDATE member SET role = 'ADMIN' WHERE id = ?", me.memberId());
        Browser b = relogin(me);
        enableYouTube(b);
        long id = readyShorts(b, "한 번만 올릴 영상");
        jobs.enqueue();
        jdbc.update("UPDATE shorts_auto_upload SET status = 'QUEUED', external_id = 'vidSaved', url = 'https://youtube.com/shorts/vidSaved', "
                + "next_attempt_at = now() - interval '1 second' WHERE shorts_id = ?", id);
        jobs.runDue();
        assertThat(FAKE.calls("POST /upload/youtube/v3/videos")).isEmpty();
        assertThat(jdbc.queryForObject("SELECT status FROM shorts_auto_upload WHERE shorts_id = ?", String.class, id)).isEqualTo("UPLOADED");
    }
}
