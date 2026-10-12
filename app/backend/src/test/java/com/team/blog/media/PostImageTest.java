package com.team.blog.media;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayOutputStream;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import tools.jackson.databind.JsonNode;

import com.team.blog.shared.markdown.ImageUrls;
import com.team.blog.support.IntegrationTest;
import com.team.blog.support.TestImages;

/** spec 009 인수 시나리오: 올리기·검사(US1), 대체글(US3), GIF(US4), 저장 공간(US5), 정리(US6), 남의 사진. */
class PostImageTest extends IntegrationTest {
    @Autowired PostImageCleanupJob cleanup;
    @Autowired ImageUrls imageUrls;

    static final byte[] THUMB = TestImages.png(320, 200);

    ResultActions upload(Session s, byte[] image, byte[] thumb) throws Exception {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.write(image);
        body.write(thumb);
        return s.http().perform(post("/api/images").with(csrf()).contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header("X-Thumbnail-Bytes", thumb.length).content(body.toByteArray()));
    }

    JsonNode uploaded(Session s, byte[] image) throws Exception {
        return read(upload(s, image, THUMB).andExpect(status().isCreated()).andReturn());
    }

    String keyOf(String url) {
        return imageUrls.keyOf(url);
    }

    long publish(Session s, String content) throws Exception {
        long id = read(s.http().perform(asJson(post("/api/posts"), Map.of("title", "사진 글", "contentMd", "")))
                .andExpect(status().isCreated()).andReturn()).path("id").asLong();
        s.http().perform(asJson(post("/api/posts/" + id + "/publish"), Map.of("title", "사진 글 " + id, "contentMd", content,
                "visibility", "PUBLIC", "baseVersion", 0, "tags", List.of()))
                .header("Idempotency-Key", UUID.randomUUID().toString())).andExpect(status().isOk());
        return id;
    }

    JsonNode detail(long id) throws Exception {
        return read(browser().perform(get("/api/posts/" + id)).andExpect(status().isOk()).andReturn());
    }

    long resources(Session s) {
        return jdbc.queryForObject("SELECT count(*) FROM resource WHERE uploader_id = ?", Long.class, s.memberId());
    }

    // ---------------------------------------------------------------- US1

    @Test
    void 사진과_썸네일을_올리면_본문에서_보이고_카드_대표_이미지는_썸네일이다() throws Exception {
        Session s = signup(uniqueLogin("img"));
        JsonNode up = uploaded(s, TestImages.png(1200, 800));
        assertThat(up.path("width").asInt()).isEqualTo(1200);
        String key = keyOf(up.path("url").asString());
        assertThat(key).matches("images/\\d{4}/\\d{2}/[0-9a-f-]{36}\\.png");
        assertThat(keyOf(up.path("thumbUrl").asString())).endsWith("_thumb.png");
        // 저장 경로·크기는 서버가 정하고 원래 파일 이름은 어디에도 없다
        Map<String, Object> row = jdbc.queryForMap("""
                SELECT r.size_bytes, ri.thumb_size_bytes, ri.width, ri.height FROM resource r JOIN resource_image ri ON ri.resource_id = r.id
                WHERE r.storage_key = ?""", key);
        assertThat(row).containsEntry("thumb_size_bytes", THUMB.length);
        mvc.perform(get("/media/" + key)).andExpect(status().isOk());

        long id = publish(s, "본문\n\n![](" + up.path("url").asString() + ")\n\n끝");
        JsonNode d = detail(id);
        assertThat(d.path("contentHtml").asString()).contains("src=\"" + up.path("url").asString() + "\"").contains("alt=\"\"");
        JsonNode home = read(browser().perform(get("/api/posts")).andReturn());
        JsonNode card = null;
        for (JsonNode c : home.path("items")) if (c.path("id").asLong() == id) card = c;
        assertThat(card).isNotNull();
        assertThat(card.path("thumbnailUrl").asString()).isEqualTo(up.path("thumbUrl").asString());
    }

    @Test
    void 형식이_틀리거나_손상되거나_너무_큰_파일은_거부하고_기록을_남기지_않는다() throws Exception {
        Session s = signup(uniqueLogin("imgbad"));
        upload(s, "not an image at all, just text".getBytes(), THUMB).andExpect(status().isBadRequest());
        upload(s, TestImages.png(10_001, 4), THUMB).andExpect(status().isBadRequest());
        upload(s, TestImages.jpegWithExif(400, 300), THUMB).andExpect(status().isBadRequest());
        upload(s, TestImages.png(400, 300), TestImages.png(700, 300)).andExpect(status().isBadRequest()); // 썸네일 가로 640 초과
        upload(s, TestImages.png(400, 300), new byte[0]).andExpect(status().isBadRequest());
        byte[] big = new byte[10 * 1024 * 1024 + 10];
        upload(s, big, THUMB).andExpect(status().is4xxClientError());
        assertThat(resources(s)).isZero();
        browser().perform(post("/api/images").with(csrf()).header("X-Thumbnail-Bytes", 1).content(new byte[] {1, 2}))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 남이_올린_사진은_내_글에서_링크가_되고_연결되지_않는다() throws Exception {
        Session owner = signup(uniqueLogin("imgowner")), other = signup(uniqueLogin("imgother"));
        String url = uploaded(owner, TestImages.png(300, 200)).path("url").asString();
        long id = publish(other, "![남의 사진](" + url + ")");
        assertThat(detail(id).path("contentHtml").asString()).doesNotContain("<img").contains("[이미지] 남의 사진");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM post_image WHERE post_id = ?", Long.class, id)).isZero();
    }

    // ---------------------------------------------------------------- US4 GIF

    @Test
    void GIF는_그대로_저장하고_첫_장면과_원본_링크로_보인다() throws Exception {
        Session s = signup(uniqueLogin("gif"));
        byte[] gif = TestImages.animatedGif(40, 30, 3);
        JsonNode up = uploaded(s, gif);
        String key = keyOf(up.path("url").asString());
        assertThat(key).endsWith(".gif");
        assertThat(jdbc.queryForObject("SELECT size_bytes FROM resource WHERE storage_key = ?", Integer.class, key)).isEqualTo(gif.length);

        long id = publish(s, "![](" + up.path("url").asString() + ")");
        String html = detail(id).path("contentHtml").asString();
        assertThat(html).contains("href=\"" + up.path("url").asString() + "\"")
                .contains("src=\"" + up.path("thumbUrl").asString() + "\"")
                .contains("alt=\"움직이는 이미지 재생\"");

        upload(s, TestImages.animatedGif(1921, 10, 1), THUMB).andExpect(status().isBadRequest());
        upload(s, TestImages.animatedGif(2, 2, 301), THUMB).andExpect(status().isBadRequest());
    }

    @Test
    void GIF_장면_수를_그림을_풀지_않고_센다() {
        assertThat(ImageInspector.gifFrames(TestImages.animatedGif(4, 4, 5), 300)).isEqualTo(5);
        assertThat(ImageInspector.gifFrames(TestImages.gif(4, 4), 300)).isEqualTo(1);
        assertThat(ImageInspector.gifFrames(TestImages.animatedGif(2, 2, 12), 10)).isEqualTo(11);
        byte[] cut = TestImages.animatedGif(4, 4, 3);
        assertThat(ImageInspector.gifFrames(java.util.Arrays.copyOf(cut, 20), 300)).isEqualTo(-1);
    }

    // ---------------------------------------------------------------- US5 저장 공간

    void fillStorage(Session s, int tenMegabyteFiles) {
        jdbc.update("""
                INSERT INTO resource (uploader_id, storage_key, content_type, size_bytes, kind)
                SELECT ?, 'images/2026/01/' || gen_random_uuid() || '.png', 'image/png', 10485760, 'IMAGE' FROM generate_series(1, ?)
                """, s.memberId(), tenMegabyteFiles);
    }

    @Test
    void 저장_공간_1GB를_넘는_업로드는_동시에_보내도_받아들이지_않는다() throws Exception {
        Session s = signup(uniqueLogin("quota"));
        byte[] image = TestImages.png(600, 400);
        long one = image.length + THUMB.length;
        fillStorage(s, 102); // 1020MB
        long used = 102L * 10485760;
        long quota = 1024L * 1024 * 1024;
        // 남은 공간이 정확히 한 장 반이 되도록 맞춘다
        long filler = quota - used - one - one / 2;
        jdbc.update("INSERT INTO resource (uploader_id, storage_key, content_type, size_bytes, kind) VALUES (?, ?, 'image/png', ?, 'IMAGE')",
                s.memberId(), "images/2026/01/" + UUID.randomUUID() + ".png", filler);

        ExecutorService pool = Executors.newFixedThreadPool(6);
        int created = 0, rejected = 0;
        try {
            List<Callable<Integer>> calls = new ArrayList<>();
            for (int i = 0; i < 6; i++) calls.add(() -> upload(s, image, THUMB).andReturn().getResponse().getStatus());
            for (Future<Integer> f : pool.invokeAll(calls)) {
                if (f.get() == 201) created++;
                else if (f.get() == 413) rejected++;
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(created).isEqualTo(1);
        assertThat(rejected).isEqualTo(5);
        JsonNode usage = read(s.http().perform(get("/api/me/storage")).andReturn());
        assertThat(usage.path("usedBytes").asLong()).isLessThanOrEqualTo(quota);
        assertThat(usage.path("quotaBytes").asLong()).isEqualTo(quota);
        assertThat(usage.path("todayCount").asLong()).isEqualTo(6);
    }

    @Test
    void 하루_200장을_넘으면_내일_다시_시도하라고_한다() throws Exception {
        Session s = signup(uniqueLogin("daily"));
        String day = DateTimeFormatter.BASIC_ISO_DATE.format(LocalDate.now(ZoneId.of("Asia/Seoul")));
        redis.opsForValue().set("rl:upload:day:" + s.memberId() + ":" + day, "200");
        String body = upload(s, TestImages.png(10, 10), THUMB).andExpect(status().isTooManyRequests()).andReturn().getResponse().getContentAsString();
        assertThat(body).contains("IMAGE_DAILY_LIMIT").contains("200장");
    }

    // ---------------------------------------------------------------- US6 정리

    void age(String key, Instant created, Instant detached) {
        jdbc.update("UPDATE resource SET created_at = ?, detached_at = ? WHERE storage_key = ?",
                Timestamp.from(created), detached == null ? null : Timestamp.from(detached), key);
    }

    boolean exists(String key) {
        return jdbc.queryForObject("SELECT count(*) FROM resource WHERE storage_key = ?", Long.class, key) > 0;
    }

    @Test
    void 쓰지_않는_사진만_기간이_지나면_파일과_기록을_지운다() throws Exception {
        Session s = signup(uniqueLogin("clean"));
        Instant now = Instant.now();
        String unused = keyOf(uploaded(s, TestImages.png(50, 50)).path("url").asString());
        String fresh = keyOf(uploaded(s, TestImages.png(51, 50)).path("url").asString());
        String inDraft = keyOf(uploaded(s, TestImages.png(52, 50)).path("url").asString());
        String inAbout = keyOf(uploaded(s, TestImages.png(56, 50)).path("url").asString());
        String detachedOld = keyOf(uploaded(s, TestImages.png(53, 50)).path("url").asString());
        String detachedRecent = keyOf(uploaded(s, TestImages.png(54, 50)).path("url").asString());
        JsonNode linkedUp = uploaded(s, TestImages.png(55, 50));
        String linked = keyOf(linkedUp.path("url").asString());
        String thumbOfUnused = jdbc.queryForObject("SELECT thumb_storage_key FROM resource_image ri JOIN resource r ON r.id = ri.resource_id WHERE r.storage_key = ?", String.class, unused);

        // 아직 발행하지 않은 임시글에만 든 사진
        s.http().perform(asJson(post("/api/posts"), Map.of("title", "임시", "contentMd", "![](" + imageUrls.urlOf(inDraft) + ")")))
                .andExpect(status().isCreated());
        publish(s, "![](" + linkedUp.path("url").asString() + ")");
        // 블로그 소개에만 든 사진 (042)
        s.http().perform(asJson(put("/api/me/about"), Map.of("contentMd", "![](" + imageUrls.urlOf(inAbout) + ")")))
                .andExpect(status().isNoContent());

        Instant old = now.minus(3, ChronoUnit.DAYS);
        age(unused, old, null);
        age(fresh, now.minus(2, ChronoUnit.HOURS), null);
        age(inDraft, old, null);
        age(inAbout, old, null);
        age(detachedOld, now.minus(20, ChronoUnit.DAYS), now.minus(8, ChronoUnit.DAYS));
        age(detachedRecent, now.minus(20, ChronoUnit.DAYS), now.minus(3, ChronoUnit.DAYS));
        age(linked, now.minus(20, ChronoUnit.DAYS), null);

        cleanup.runOnce();

        assertThat(exists(unused)).isFalse();
        assertThat(exists(detachedOld)).isFalse();
        assertThat(exists(fresh)).isTrue();
        assertThat(exists(inDraft)).isTrue();
        assertThat(exists(inAbout)).isTrue();
        assertThat(exists(detachedRecent)).isTrue();
        assertThat(exists(linked)).isTrue();
        mvc.perform(get("/media/" + unused)).andExpect(status().isNotFound());
        mvc.perform(get("/media/" + thumbOfUnused)).andExpect(status().isNotFound());
        mvc.perform(get("/media/" + linked)).andExpect(status().isOk());
    }
}
