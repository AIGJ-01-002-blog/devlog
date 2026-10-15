package com.team.blog.shorts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import tools.jackson.databind.JsonNode;

import com.team.blog.shorts.application.RenderQueue;
import com.team.blog.support.IntegrationTest;

/**
 * spec 099: 쇼츠 대본 → 양산형 방지 검사 → 영상 만들기 대기 → 워커가 받아 MP4를 올림 → 미리보기.
 * 워커 API는 토큰만 보고, 결과는 받은 작업의 claim 값이 맞을 때만 받는다.
 */
class ShortsTest extends IntegrationTest {
    private static final String TOKEN = "test-render-token";
    /** 서버는 MP4 머리(ftyp)만 확인한다 */
    private static final byte[] MP4 = {0, 0, 0, 0x18, 'f', 't', 'y', 'p', 'i', 's', 'o', 'm', 0, 0, 2, 0, 'i', 's', 'o', 'm'};

    private static final String POST_BODY = """
            배포할 때마다 로그인한 사용자가 모두 로그아웃되는 문제가 있었다. 원인은 세션 직렬화 클래스가 바뀌면서
            기존 세션을 읽지 못한 것이었다. Spring Session의 직렬화를 JSON으로 바꾸고 클래스 이름 대신 타입 정보를 고정했다.
            그 뒤로는 배포해도 세션이 유지된다.
            """;

    private static final List<Map<String, String>> GOOD = List.of(
            Map.of("caption", "배포하면 다 로그아웃?", "narration", "왜 배포만 하면 사용자가 전부 로그아웃될까요?"),
            Map.of("caption", "범인은 직렬화", "narration", "제가 로그를 뒤져 보니 세션을 저장한 클래스 모양이 바뀌어서 예전 세션을 못 읽고 있었어요."),
            Map.of("caption", "JSON으로 바꾸기", "narration", "세션 저장 방식을 자바 기본 직렬화에서 JSON으로 바꾸고, 타입 이름을 고정해 뒀어요.",
                    "code", "spring.session.redis.serializer: json"),
            Map.of("caption", "결과", "narration", "덕분에 재배포 뒤 다시 로그인하던 사람이 하루 마흔 명에서 한 명도 없게 줄었어요."));

    @Autowired RenderQueue queue;

    @BeforeEach
    void emptyQueue() {
        // 다른 시험이 남긴 대기 작업을 워커가 받지 않게 비운다
        jdbc.update("UPDATE shorts SET status = 'FAILED', claim_token = NULL WHERE status IN ('QUEUED', 'RENDERING')");
    }

    private long writePost(Session s, String body) throws Exception {
        return read(s.http().perform(asJson(post("/api/posts"), Map.of("title", "세션이 풀리는 문제", "contentMd", body)))
                .andExpect(status().isCreated()).andReturn()).path("id").asLong();
    }

    private JsonNode create(Session s, Long postId, String title, List<? extends Map<String, ?>> scenes) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("title", title);
        body.put("scenes", scenes);
        if (postId != null) body.put("postId", postId);
        return read(s.http().perform(asJson(post("/api/me/shorts"), body)).andExpect(status().isCreated()).andReturn());
    }

    private JsonNode claim() throws Exception {
        var r = mvc.perform(post("/api/render/jobs/claim").header("X-Render-Token", TOKEN)).andReturn();
        return r.getResponse().getStatus() == 204 ? null : read(r);
    }

    /** 대본을 쓰고 영상 만들기 대기열에 넣는다 */
    private long queue(Session me, long postId) throws Exception {
        long id = create(me, postId, "  배포하면 왜 로그아웃될까  ", GOOD).path("id").asLong();
        me.http().perform(post("/api/me/shorts/" + id + "/render").with(csrf())).andExpect(status().isOk());
        return id;
    }

    /** 워커가 받은 작업의 claim */
    private String claimOf(long id) throws Exception {
        JsonNode job = claim();
        assertThat(job.path("id").asLong()).isEqualTo(id);
        return job.path("claim").asString();
    }

    private void upload(long id, String claim) throws Exception {
        mvc.perform(put("/api/render/jobs/" + id + "/video").param("claim", claim).header("X-Render-Token", TOKEN)
                .header("X-Duration-Ms", "31500").contentType("video/mp4").content(MP4)).andExpect(status().isOk());
    }

    @Test
    void 대본을_검사하고_영상_만들기_대기열에_넣으면_워커가_받는다() throws Exception {
        Session me = signup(uniqueLogin("shorts"));
        long postId = writePost(me, POST_BODY);
        JsonNode item = create(me, postId, "  배포하면 왜 로그아웃될까  ", GOOD);
        long id = item.path("id").asLong();
        assertThat(item.path("title").asString()).isEqualTo("배포하면 왜 로그아웃될까");
        assertThat(item.path("status").asString()).isEqualTo("DRAFT");
        assertThat(item.path("postUrl").asString()).isEqualTo("/@" + me.handle() + "/posts/" + postId);
        assertThat(item.path("estimatedSeconds").asDouble()).isBetween(20.0, 40.0);

        JsonNode report = read(me.http().perform(post("/api/me/shorts/" + id + "/check").with(csrf())).andExpect(status().isOk()).andReturn());
        assertThat(report.path("passed").asBoolean()).isTrue();
        assertThat(report.path("score").asInt()).isEqualTo(100);

        JsonNode queued = read(me.http().perform(post("/api/me/shorts/" + id + "/render").with(csrf())).andExpect(status().isOk()).andReturn());
        assertThat(queued.path("status").asString()).isEqualTo("QUEUED");

        // 워커가 받는다: 장면·원본 글 주소·일회용 claim
        JsonNode job = claim();
        assertThat(job.path("id").asLong()).isEqualTo(id);
        assertThat(job.path("scenes")).hasSize(4);
        assertThat(job.path("scenes").get(2).path("code").asString()).contains("serializer");
        assertThat(job.path("postUrl").asString()).isEqualTo("/@" + me.handle() + "/posts/" + postId);
        assertThat(claim()).as("받은 작업은 다른 워커가 또 받지 않는다").isNull();
    }

    @Test
    void 워커는_claim이_맞는_MP4만_올리고_완성된_영상을_미리볼_수_있다() throws Exception {
        Session me = signup(uniqueLogin("shorts"));
        long id = queue(me, writePost(me, POST_BODY));
        String claim = claimOf(id);

        // claim이 틀리면 결과를 받지 않는다
        mvc.perform(put("/api/render/jobs/" + id + "/video").param("claim", "wrong").header("X-Render-Token", TOKEN)
                .contentType("video/mp4").content(MP4)).andExpect(status().isConflict());
        // MP4가 아니면 거절
        mvc.perform(put("/api/render/jobs/" + id + "/video").param("claim", claim).header("X-Render-Token", TOKEN)
                .contentType("video/mp4").content("not a video".getBytes())).andExpect(status().isBadRequest());
        mvc.perform(put("/api/render/jobs/" + id + "/video").param("claim", claim).header("X-Render-Token", TOKEN)
                .header("X-Duration-Ms", "31500").contentType("video/mp4").content(MP4)).andExpect(status().isOk());

        JsonNode ready = read(me.http().perform(get("/api/me/shorts/" + id)).andExpect(status().isOk()).andReturn());
        assertThat(ready.path("status").asString()).isEqualTo("READY");
        assertThat(ready.path("durationMs").asInt()).isEqualTo(31500);
        String videoUrl = ready.path("videoUrl").asString();
        assertThat(videoUrl).matches(".*/media/shorts/\\d{4}/\\d{2}/[0-9a-f-]{36}\\.mp4");
        String key = jdbc.queryForObject("SELECT video_key FROM shorts WHERE id = ?", String.class, id);
        mvc.perform(get("/media/" + key)).andExpect(status().isOk())
                .andExpect(r -> assertThat(r.getResponse().getContentType()).isEqualTo("video/mp4"));

        // 목록: 워커가 다녀간 시각과 오늘 만든 횟수
        JsonNode overview = read(me.http().perform(get("/api/me/shorts")).andExpect(status().isOk()).andReturn());
        assertThat(overview.path("items").get(0).path("id").asLong()).isEqualTo(id);
        assertThat(overview.path("workerSeenAt").isNull()).isFalse();
        assertThat(overview.path("rendersToday").asInt()).isEqualTo(1);
    }

    @Test
    void 완성된_대본을_고치면_영상이_지워지고_대본_상태로_돌아간다() throws Exception {
        Session me = signup(uniqueLogin("shorts"));
        long id = queue(me, writePost(me, POST_BODY));
        upload(id, claimOf(id));
        JsonNode ready = read(me.http().perform(get("/api/me/shorts/" + id)).andExpect(status().isOk()).andReturn());
        String key = jdbc.queryForObject("SELECT video_key FROM shorts WHERE id = ?", String.class, id);

        // 대본을 고치면 영상이 지워지고 대본 상태로 돌아간다
        JsonNode edited = read(me.http().perform(asJson(put("/api/me/shorts/" + id),
                Map.of("title", "배포하면 왜 로그아웃될까요", "version", ready.path("version").asInt()))).andExpect(status().isOk()).andReturn());
        assertThat(edited.path("status").asString()).isEqualTo("DRAFT");
        assertThat(edited.path("videoUrl").isNull()).isTrue();
        mvc.perform(get("/media/" + key)).andExpect(status().isNotFound());
        // 낡은 version으로 고치면 충돌
        me.http().perform(asJson(put("/api/me/shorts/" + id), Map.of("title", "x", "version", ready.path("version").asInt())))
                .andExpect(status().isConflict());

        me.http().perform(delete("/api/me/shorts/" + id).with(csrf())).andExpect(status().isNoContent());
        me.http().perform(get("/api/me/shorts/" + id)).andExpect(status().isNotFound());
    }

    @Test
    void 장면_그림과_연출과_효과음은_워커가_아는_것만_받는다() throws Exception {
        Session me = signup(uniqueLogin("shortsimg"));
        long postId = writePost(me, POST_BODY);

        // 바깥 주소는 워커가 아무 곳에나 요청하게 되니 막는다
        List<Map<String, String>> outside = new java.util.ArrayList<>(GOOD);
        outside.set(1, Map.of("caption", "범인은 직렬화", "narration", GOOD.get(1).get("narration"), "image", "https://evil.example/x.png"));
        Map<String, Object> body = Map.of("postId", postId, "title", "그림 있는 쇼츠", "scenes", outside);
        JsonNode bad = read(me.http().perform(asJson(post("/api/me/shorts"), body)).andExpect(status().isBadRequest()).andReturn());
        assertThat(bad.toString()).contains("scenes[1].image");

        // 연출 이름과 효과음은 워커가 아는 것만 받는다
        List<Map<String, ?>> badVisual = new java.util.ArrayList<>(GOOD);
        badVisual.set(0, Map.of("caption", "배포하면 다 로그아웃?", "narration", GOOD.get(0).get("narration"), "visual", "explode",
                "effects", List.of(Map.of("sound", "airhorn", "at", 0.5))));
        JsonNode badBody = read(me.http().perform(asJson(post("/api/me/shorts"), Map.of("title", "연출", "scenes", badVisual)))
                .andExpect(status().isBadRequest()).andReturn());
        assertThat(badBody.toString()).contains("scenes[0].visual", "scenes[0].effects");
    }

    /** 그림·연출·도식·효과음을 넣은 대본을 대기열에 넣고 워커가 받은 작업. key는 장면 그림의 저장소 키 */
    private record ImageJob(long id, String key, JsonNode job) {}

    private ImageJob claimWithImage() throws Exception {
        Session me = signup(uniqueLogin("shortsimg"));
        long postId = writePost(me, POST_BODY);
        String key = "images/2026/10/" + java.util.UUID.randomUUID() + ".webp";
        long resource = jdbc.queryForObject("""
                INSERT INTO resource (uploader_id, storage_key, content_type, size_bytes, kind)
                VALUES (?, ?, 'image/webp', 10, 'IMAGE') RETURNING id
                """, Long.class, me.memberId(), key);
        jdbc.update("INSERT INTO resource_image (resource_id, kind) VALUES (?, 'IMAGE')", resource);
        jdbc.update("INSERT INTO post_image (post_id, resource_id, position) VALUES (?, ?, 0)", postId, resource);

        List<Map<String, ?>> withImage = new java.util.ArrayList<>(GOOD);
        withImage.set(0, Map.of("caption", "배포하면 다 로그아웃?", "narration", GOOD.get(0).get("narration"), "visual", "THINK",
                "effects", List.of(Map.of("sound", "pop", "at", 0.4, "volume", 0.6))));
        withImage.set(1, Map.of("caption", "범인은 직렬화", "narration", GOOD.get(1).get("narration"), "image", "/media/" + key,
                "visual", "typing", "effects", List.of(Map.of("sound", "typing", "at", 1.0))));
        withImage.set(2, Map.of("caption", GOOD.get(2).get("caption"), "narration", GOOD.get(2).get("narration"), "code", GOOD.get(2).get("code"),
                "diagram", Map.of("type", "stack", "items", List.of("세션", Map.of("label", "직렬화", "icon", "code"), "Redis"), "highlight", 1)));
        JsonNode item = create(me, postId, "그림 있는 쇼츠", withImage);
        assertThat(item.path("scenes").get(1).path("image").asString()).isEqualTo("/media/" + key);
        long id = item.path("id").asLong();
        me.http().perform(post("/api/me/shorts/" + id + "/render").with(csrf())).andExpect(status().isOk());
        return new ImageJob(id, key, claim());
    }

    @Test
    void 장면_그림과_연출과_도식은_작업에_실어_보낸다() throws Exception {
        ImageJob r = claimWithImage();
        JsonNode job = r.job();
        assertThat(job.path("id").asLong()).isEqualTo(r.id());
        assertThat(job.path("scenes").get(1).path("image").asString()).isEqualTo("/media/" + r.key());
        assertThat(job.path("scenes").get(0).path("image").isNull()).isTrue();
        assertThat(job.path("coverImage").asString()).endsWith("/" + r.key());
        assertThat(job.path("scenes").get(0).path("visual").asString()).isEqualTo("think");
        assertThat(job.path("scenes").get(1).path("visual").asString()).isEqualTo("typing");
        assertThat(job.path("scenes").get(2).path("visual").isNull()).isTrue();
        assertThat(job.path("scenes").get(2).path("diagram").path("type").asString()).isEqualTo("stack");
        assertThat(job.path("scenes").get(2).path("diagram").path("items").get(1).path("icon").asString()).isEqualTo("code");
        assertThat(job.path("scenes").get(2).path("diagram").path("highlight").asInt()).isEqualTo(1);
        assertThat(job.path("scenes").get(0).path("diagram").isNull()).isTrue();
    }

    @Test
    void 대본마다_고른_캐릭터를_작업에_실어_보내고_모르는_캐릭터는_받지_않는다() throws Exception {
        Session me = signup(uniqueLogin("shortschar"));
        long postId = writePost(me, POST_BODY);
        Map<String, Object> body = new HashMap<>(Map.of("postId", postId, "title", "두부가 설명하는 로그아웃", "scenes", GOOD, "character", " Dubu "));
        JsonNode item = read(me.http().perform(asJson(post("/api/me/shorts"), body)).andExpect(status().isCreated()).andReturn());
        assertThat(item.path("character").asString()).isEqualTo("dubu");
        long id = item.path("id").asLong();

        body.put("character", "pikachu");
        JsonNode bad = read(me.http().perform(asJson(post("/api/me/shorts"), body)).andExpect(status().isBadRequest()).andReturn());
        assertThat(bad.toString()).contains("character");

        // 제목만 고치면 캐릭터는 그대로, 빈 문자열이면 기본 캐릭터로 돌아간다
        JsonNode kept = read(me.http().perform(asJson(put("/api/me/shorts/" + id), Map.of("title", "두부와 로그아웃"))).andExpect(status().isOk()).andReturn());
        assertThat(kept.path("character").asString()).isEqualTo("dubu");
        JsonNode reset = read(me.http().perform(asJson(put("/api/me/shorts/" + id), Map.of("character", ""))).andExpect(status().isOk()).andReturn());
        assertThat(reset.path("character").isNull()).isTrue();

        me.http().perform(asJson(put("/api/me/shorts/" + id), Map.of("character", "logduck"))).andExpect(status().isOk());
        me.http().perform(post("/api/me/shorts/" + id + "/render").with(csrf())).andExpect(status().isOk());
        JsonNode job = claim();
        assertThat(job.path("id").asLong()).isEqualTo(id);
        assertThat(job.path("character").asString()).isEqualTo("logduck");
    }

    @Test
    void 효과음은_작업_단위로_펼쳐_보낸다() throws Exception {
        ImageJob r = claimWithImage();
        JsonNode job = r.job();
        assertThat(job.path("id").asLong()).isEqualTo(r.id());
        assertThat(job.path("scenes").get(0).has("effects")).as("효과음은 작업 단위로만 보낸다").isFalse();
        JsonNode effects = job.path("effects");
        assertThat(effects).hasSize(2);
        assertThat(effects.get(0).path("scene").asInt()).isZero();
        assertThat(effects.get(0).path("sound").asString()).isEqualTo("pop");
        assertThat(effects.get(0).path("volume").asDouble()).isEqualTo(0.6);
        assertThat(effects.get(1).path("scene").asInt()).isEqualTo(1);
        assertThat(effects.get(1).path("at").asDouble()).isEqualTo(1.0);
    }

    @Test
    void 원본_글을_그대로_읽는_대본은_영상으로_만들지_못하고_이유를_돌려준다() throws Exception {
        Session me = signup(uniqueLogin("shcopy"));
        long postId = writePost(me, POST_BODY);
        long id = create(me, postId, "세션이 풀리는 문제", List.of(
                Map.of("caption", "문제", "narration", "배포할 때마다 로그인한 사용자가 모두 로그아웃되는 문제가 있었다."),
                Map.of("caption", "원인", "narration", "원인은 세션 직렬화 클래스가 바뀌면서 기존 세션을 읽지 못한 것이었다."),
                Map.of("caption", "해결", "narration", "Spring Session의 직렬화를 JSON으로 바꾸고 클래스 이름 대신 타입 정보를 고정했다.")))
                .path("id").asLong();
        JsonNode err = read(me.http().perform(post("/api/me/shorts/" + id + "/render").with(csrf())).andExpect(status().isUnprocessableContent()).andReturn());
        assertThat(err.path("code").asString()).isEqualTo("SHORTS_NOT_ORIGINAL");
        assertThat(err.path("details").path("report").path("passed").asBoolean()).isFalse();
        assertThat(err.path("details").path("report").path("issues").findValuesAsString("code")).contains("COPIED");
        assertThat(jdbc.queryForObject("SELECT status FROM shorts WHERE id = ?", String.class, id)).isEqualTo("DRAFT");
    }

    @Test
    void 남의_쇼츠와_남의_글은_쓸_수_없고_장면이_모자라면_만들지_못한다() throws Exception {
        Session owner = signup(uniqueLogin("showner"));
        Session other = signup(uniqueLogin("shother"));
        long postId = writePost(owner, POST_BODY);
        long id = create(owner, null, "내 쇼츠", GOOD).path("id").asLong();
        other.http().perform(get("/api/me/shorts/" + id)).andExpect(status().isNotFound());
        other.http().perform(post("/api/me/shorts/" + id + "/render").with(csrf())).andExpect(status().isNotFound());
        other.http().perform(asJson(post("/api/me/shorts"), Map.of("title", "남의 글", "scenes", GOOD, "postId", postId)))
                .andExpect(status().isBadRequest());
        browser().perform(get("/api/me/shorts")).andExpect(status().isUnauthorized());

        long few = create(owner, null, "짧은 대본", GOOD.subList(0, 2)).path("id").asLong();
        JsonNode err = read(owner.http().perform(post("/api/me/shorts/" + few + "/render").with(csrf())).andExpect(status().isBadRequest()).andReturn());
        assertThat(err.path("errors").findValuesAsString("code")).contains("TOO_FEW");
    }

    @Test
    void 완성된_영상에만_직접_올린_주소를_플랫폼마다_남기고_글_태그를_함께_돌려준다() throws Exception {
        Session me = signup(uniqueLogin("shupload"));
        Session other = signup(uniqueLogin("shupother"));
        long postId = writePost(me, POST_BODY);
        jdbc.update("INSERT INTO tag (name) VALUES ('spring'), ('세션') ON CONFLICT (name) DO NOTHING");
        jdbc.update("INSERT INTO post_tag (post_id, tag_id, position) SELECT ?, id, CASE name WHEN 'spring' THEN 0 ELSE 1 END FROM tag WHERE name IN ('spring', '세션')", postId);
        long id = create(me, postId, "배포하면 왜 로그아웃될까", GOOD).path("id").asLong();
        String yt = "/api/me/shorts/" + id + "/uploads/youtube";

        // 영상이 없으면 남길 수 없다
        me.http().perform(asJson(put(yt), Map.of("url", "https://youtube.com/shorts/abc"))).andExpect(status().isConflict());
        jdbc.update("UPDATE shorts SET status = 'READY', video_key = 'shorts/2026/10/x.mp4' WHERE id = ?", id);

        // 다른 플랫폼 주소·모르는 플랫폼·남의 쇼츠는 막는다
        JsonNode bad = read(me.http().perform(asJson(put(yt), Map.of("url", "https://www.tiktok.com/@me/video/1"))).andExpect(status().isBadRequest()).andReturn());
        assertThat(bad.path("errors").findValuesAsString("field")).contains("url");
        me.http().perform(asJson(put("/api/me/shorts/" + id + "/uploads/facebook"), Map.of("url", "https://facebook.com/x"))).andExpect(status().isNotFound());
        other.http().perform(asJson(put(yt), Map.of("url", "https://youtube.com/shorts/abc"))).andExpect(status().isNotFound());

        me.http().perform(asJson(put(yt), Map.of("url", "https://youtube.com/shorts/abc"))).andExpect(status().isOk());
        JsonNode item = read(me.http().perform(asJson(put(yt), Map.of("url", "https://youtube.com/shorts/def"))).andExpect(status().isOk()).andReturn());
        assertThat(item.path("uploads")).as("플랫폼마다 하나, 다시 붙여 넣으면 바뀐다").hasSize(1);
        assertThat(item.path("uploads").get(0).path("platform").asString()).isEqualTo("YOUTUBE");
        assertThat(item.path("uploads").get(0).path("url").asString()).isEqualTo("https://youtube.com/shorts/def");
        assertThat(item.path("postTags").get(0).asString()).isEqualTo("spring");
        assertThat(item.path("postTags").get(1).asString()).isEqualTo("세션");

        me.http().perform(asJson(put("/api/me/shorts/" + id + "/uploads/tiktok"), Map.of("url", "https://www.tiktok.com/@me/video/1"))).andExpect(status().isOk());
        JsonNode list = read(me.http().perform(get("/api/me/shorts")).andExpect(status().isOk()).andReturn());
        assertThat(list.path("items").get(0).path("uploads")).hasSize(2);

        JsonNode removed = read(me.http().perform(delete(yt).with(csrf())).andExpect(status().isOk()).andReturn());
        assertThat(removed.path("uploads")).hasSize(1);
        assertThat(removed.path("uploads").get(0).path("platform").asString()).isEqualTo("TIKTOK");

        // 쇼츠를 지우면 기록도 함께 지워진다
        jdbc.update("UPDATE shorts SET video_key = NULL, status = 'DRAFT' WHERE id = ?", id);
        me.http().perform(delete("/api/me/shorts/" + id).with(csrf())).andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM shorts_upload WHERE shorts_id = ?", Integer.class, id)).isZero();
    }

    @Test
    void 하루_한도를_넘으면_만들지_못한다() throws Exception {
        Session me = signup(uniqueLogin("shlimit"));
        long id = create(me, null, "한도 시험", GOOD).path("id").asLong();
        for (int i = 0; i < 10; i++) jdbc.update("INSERT INTO shorts_render_request (member_id) VALUES (?)", me.memberId());
        JsonNode err = read(me.http().perform(post("/api/me/shorts/" + id + "/render").with(csrf())).andExpect(status().isTooManyRequests()).andReturn());
        assertThat(err.path("code").asString()).isEqualTo("SHORTS_DAILY_LIMIT");
    }

    @Test
    void 워커가_실패하거나_응답이_없으면_다시_대기하고_세_번째에는_실패로_끝난다() throws Exception {
        Session me = signup(uniqueLogin("shfail"));
        long id = create(me, null, "실패 시험", GOOD).path("id").asLong();
        me.http().perform(post("/api/me/shorts/" + id + "/render").with(csrf())).andExpect(status().isOk());

        JsonNode first = claim();
        mvc.perform(asJsonRaw(post("/api/render/jobs/" + id + "/fail").param("claim", first.path("claim").asString()), Map.of("message", "ffmpeg 종료 코드 1")))
                .andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject("SELECT status FROM shorts WHERE id = ?", String.class, id)).isEqualTo("QUEUED");

        // 두 번째: 워커가 받고 사라짐 → 시간 초과로 되돌림
        JsonNode second = claim();
        assertThat(second.path("attempt").asInt()).isEqualTo(2);
        jdbc.update("UPDATE shorts SET claimed_at = now() - interval '1 hour' WHERE id = ?", id);
        assertThat(queue.releaseStale()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM shorts WHERE id = ?", String.class, id)).isEqualTo("QUEUED");
        // 되돌린 뒤 늦게 온 결과는 버린다
        mvc.perform(put("/api/render/jobs/" + id + "/video").param("claim", second.path("claim").asString()).header("X-Render-Token", TOKEN)
                .contentType("video/mp4").content(MP4)).andExpect(status().isConflict());

        JsonNode third = claim();
        mvc.perform(asJsonRaw(post("/api/render/jobs/" + id + "/fail").param("claim", third.path("claim").asString()), Map.of("message", "글꼴 없음")))
                .andExpect(status().isNoContent());
        Map<String, Object> row = jdbc.queryForMap("SELECT status, attempts, error FROM shorts WHERE id = ?", id);
        assertThat(row).containsEntry("status", "FAILED").containsEntry("attempts", 3).containsEntry("error", "글꼴 없음");
    }

    @Test
    void 워커_토큰이_틀리면_401이다() throws Exception {
        mvc.perform(post("/api/render/jobs/claim").header("X-Render-Token", "nope")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/render/jobs/claim")).andExpect(status().isUnauthorized());
    }

    @Test
    void 토큰을_바꾸는_동안_옛_토큰도_받고_관리자_대시보드에_워커_상태가_보인다() throws Exception {
        redis.delete(List.of("shorts:worker:seen", "shorts:worker:previous-token"));
        Session admin = signup(uniqueLogin("shadm"));
        jdbc.update("UPDATE member SET role = 'ADMIN' WHERE id = ?", admin.memberId());
        var a = relogin(admin);
        JsonNode before = read(a.perform(get("/api/admin/dashboard")).andExpect(status().isOk()).andReturn()).path("worker");
        assertThat(before.path("tokenSet").asBoolean()).isTrue();
        assertThat(before.path("lastSeen").isNull()).isTrue();
        assertThat(before.path("previousToken").asBoolean()).isFalse();

        // 대기 중인 영상이 있는데 워커가 안 보이면 대시보드가 알 수 있다
        Session me = signup(uniqueLogin("shwk"));
        long id = create(me, writePost(me, POST_BODY), "배포하면 로그아웃", GOOD).path("id").asLong();
        me.http().perform(post("/api/me/shorts/" + id + "/render").with(csrf())).andExpect(status().isOk());
        JsonNode queued = read(a.perform(get("/api/admin/dashboard")).andExpect(status().isOk()).andReturn()).path("worker");
        assertThat(queued.path("queued").asLong()).isPositive();
        assertThat(queued.path("oldestQueuedAt").isNull()).isFalse();

        // 옛 토큰으로 받아 가면 받되, 하루 동안 옛 토큰을 쓰는 워커가 있다고 보여 준다
        mvc.perform(post("/api/render/jobs/claim").header("X-Render-Token", "test-render-token-old")).andExpect(status().isOk());
        JsonNode after = read(a.perform(get("/api/admin/dashboard")).andExpect(status().isOk()).andReturn()).path("worker");
        assertThat(after.path("lastSeen").isNull()).isFalse();
        assertThat(after.path("previousToken").asBoolean()).isTrue();
        assertThat(after.path("rendering").asLong()).isPositive();
    }

    @Test
    void 연결한_AI가_대본을_저장하면_검사_결과와_스튜디오_링크를_받는다() throws Exception {
        Session me = signup(uniqueLogin("shmcp"));
        long postId = writePost(me, POST_BODY);
        String token = read(me.http().perform(asJson(post("/api/me/tokens"), Map.of("name", "Claude Code", "scope", "WRITE")))
                .andExpect(status().isCreated()).andReturn()).path("secret").asString();
        JsonNode list = rpc(token, "tools/list", null);
        assertThat(list.path("tools").findValuesAsString("name")).contains("create_shorts_script", "update_shorts_script", "list_shorts");

        JsonNode r = rpc(token, "tools/call", Map.of("name", "create_shorts_script",
                "arguments", Map.of("post_id", postId, "title", "배포하면 로그아웃", "scenes", List.of(
                        Map.of("caption", "문제", "narration", "배포할 때마다 로그인한 사용자가 모두 로그아웃되는 문제가 있었다."),
                        Map.of("caption", "원인", "narration", "원인은 세션 직렬화 클래스가 바뀌면서 기존 세션을 읽지 못한 것이었다."),
                        Map.of("caption", "해결", "narration", "Spring Session의 직렬화를 JSON으로 바꾸고 클래스 이름 대신 타입 정보를 고정했다.")))));
        assertThat(r.path("isError").asBoolean()).isFalse();
        String text = r.path("content").get(0).path("text").asString();
        assertThat(text).contains("쇼츠 번호", "[막힘]", "원본 글", "/studio?id=");
        long id = Long.parseLong(text.replaceAll("(?s).*쇼츠 번호 (\\d+).*", "$1"));

        JsonNode fixed = rpc(token, "tools/call", Map.of("name", "update_shorts_script", "arguments", Map.of("shorts_id", id, "scenes", GOOD)));
        assertThat(fixed.path("content").get(0).path("text").asString()).contains("100점", "영상으로 만들 수 있어요");
        assertThat(rpc(token, "tools/call", Map.of("name", "list_shorts", "arguments", Map.of())).path("content").get(0).path("text").asString())
                .contains(id + " · 배포하면 로그아웃 · 대본");
        // 남의 쇼츠는 없는 것과 같다
        Session other = signup(uniqueLogin("shmcp2"));
        String otherToken = read(other.http().perform(asJson(post("/api/me/tokens"), Map.of("name", "Codex", "scope", "WRITE")))
                .andExpect(status().isCreated()).andReturn()).path("secret").asString();
        JsonNode denied = rpc(otherToken, "tools/call", Map.of("name", "update_shorts_script", "arguments", Map.of("shorts_id", id, "title", "남의 것")));
        assertThat(denied.path("isError").asBoolean()).isTrue();
        assertThat(denied.path("content").get(0).path("text").asString()).contains("찾을 수 없어요");
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder asJsonRaw(
            org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder b, Object body) {
        return b.header("X-Render-Token", TOKEN).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
    }

    private JsonNode rpc(String token, String method, Object params) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("jsonrpc", "2.0");
        body.put("id", 1);
        body.put("method", method);
        if (params != null) body.put("params", params);
        return read(mvc.perform(post("/api/mcp").contentType(MediaType.APPLICATION_JSON).header("Authorization", "Bearer " + token)
                .content(json.writeValueAsString(body))).andExpect(status().isOk()).andReturn()).path("result");
    }
}
