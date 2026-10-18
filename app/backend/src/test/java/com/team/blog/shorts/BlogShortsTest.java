package com.team.blog.shorts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;

import com.team.blog.support.IntegrationTest;

/** spec 103 (#399): 직접 올린 영상 주소를 남긴 쇼츠가 블로그 "쇼츠" 탭과 바탕 글 상세에 보인다 */
class BlogShortsTest extends IntegrationTest {
    private static final List<Map<String, String>> SCENES = List.of(
            Map.of("caption", "왜 느릴까", "narration", "왜 이 API만 유독 느렸을까요? 제가 직접 재 봤어요."),
            Map.of("caption", "범인", "narration", "인덱스가 없어서 매번 테이블을 끝까지 읽고 있었어요."),
            Map.of("caption", "결과", "narration", "인덱스 하나로 1200밀리초가 35밀리초로 줄었어요."));

    private long publish(Session s, String visibility) throws Exception {
        long id = read(s.http().perform(asJson(post("/api/posts"), Map.of("title", "느린 API", "contentMd", "본문")))
                .andExpect(status().isCreated()).andReturn()).path("id").asLong();
        s.http().perform(asJson(post("/api/posts/" + id + "/publish"), Map.of("title", "느린 API", "contentMd", "본문",
                        "visibility", visibility, "baseVersion", 0, "tags", List.of()))
                .header("Idempotency-Key", UUID.randomUUID().toString())).andExpect(status().isOk());
        return id;
    }

    /** 완성된 쇼츠를 만들고 올린 주소를 남긴다 */
    private long uploaded(Session s, Long postId, String title, Map<String, String> uploads) throws Exception {
        Map<String, Object> body = new java.util.HashMap<>(Map.of("title", title, "scenes", SCENES));
        if (postId != null) body.put("postId", postId);
        long id = read(s.http().perform(asJson(post("/api/me/shorts"), body)).andExpect(status().isCreated()).andReturn()).path("id").asLong();
        jdbc.update("UPDATE shorts SET status = 'READY', video_key = 'shorts/2026/10/x.mp4', duration_ms = 31000 WHERE id = ?", id);
        for (var e : uploads.entrySet()) {
            s.http().perform(asJson(put("/api/me/shorts/" + id + "/uploads/" + e.getKey()), Map.of("url", e.getValue()))).andExpect(status().isOk());
        }
        return id;
    }

    private JsonNode blog(String handle) throws Exception {
        return read(browser().perform(get("/api/members/" + handle + "/shorts")).andExpect(status().isOk()).andReturn());
    }

    private JsonNode ofPost(long postId) throws Exception {
        return read(browser().perform(get("/api/posts/" + postId + "/shorts")).andExpect(status().isOk()).andReturn());
    }

    @Test
    void 올린_주소를_남긴_쇼츠만_블로그와_공개_글에_보이고_YouTube는_재생할_영상_ID를_준다() throws Exception {
        Session me = signup(uniqueLogin("bshorts"));
        long publicPost = publish(me, "PUBLIC");
        long privatePost = publish(me, "PRIVATE");

        long shown = uploaded(me, publicPost, "인덱스 하나로 35ms", Map.of(
                "youtube", "https://www.youtube.com/shorts/AbCdEfGhIj0", "instagram", "https://www.instagram.com/reel/Cxyz/"));
        uploaded(me, privatePost, "비공개 글의 쇼츠", Map.of("tiktok", "https://www.tiktok.com/@me/video/1"));
        uploaded(me, null, "바탕 글 없는 쇼츠", Map.of("youtube", "https://youtu.be/ZyXwVuTsRq9"));
        uploaded(me, publicPost, "아직 안 올린 쇼츠", Map.of());

        JsonNode list = blog(me.handle());
        assertThat(list.findValuesAsString("title")).containsExactlyInAnyOrder("인덱스 하나로 35ms", "바탕 글 없는 쇼츠");
        JsonNode card = list.findParents("title").stream().filter(n -> n.path("id").asLong() == shown).findFirst().orElseThrow();
        assertThat(card.path("postUrl").asString()).isEqualTo("/@" + me.handle() + "/posts/" + publicPost);
        assertThat(card.path("postTitle").asString()).isEqualTo("느린 API");
        assertThat(card.path("durationMs").asInt()).isEqualTo(31000);
        assertThat(card.path("links")).hasSize(2);
        assertThat(card.path("links").get(0).path("platform").asString()).isEqualTo("YOUTUBE");
        assertThat(card.path("links").get(0).path("embedId").asString()).isEqualTo("AbCdEfGhIj0");
        assertThat(card.path("links").get(1).path("platform").asString()).isEqualTo("INSTAGRAM");
        assertThat(card.path("links").get(1).path("embedId").isNull()).isTrue();

        assertThat(ofPost(publicPost).findValuesAsString("title")).containsExactly("인덱스 하나로 35ms");
        assertThat(ofPost(privatePost)).as("비공개 글에는 보이지 않는다").isEmpty();

        // 블로그 [쇼츠] 탭 주소는 서버가 첫 화면을 그린다
        String html = browser().perform(get("/@" + me.handle() + "/shorts")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(html).contains("인덱스 하나로 35ms", "https://www.youtube.com/shorts/AbCdEfGhIj0").doesNotContain("비공개 글의 쇼츠");
    }

    @Test
    void 블로그에_보이기를_끄면_어디에도_보이지_않고_본인만_끌_수_있다() throws Exception {
        Session me = signup(uniqueLogin("bshoff"));
        Session other = signup(uniqueLogin("bshoth"));
        long postId = publish(me, "PUBLIC");
        long id = uploaded(me, postId, "끌 쇼츠", Map.of("youtube", "https://www.youtube.com/watch?v=AbCdEfGhIj0&t=3"));
        assertThat(blog(me.handle())).hasSize(1);

        other.http().perform(asJson(put("/api/me/shorts/" + id + "/blog"), Map.of("visible", false))).andExpect(status().isNotFound());
        me.http().perform(asJson(put("/api/me/shorts/" + id + "/blog"), Map.of())).andExpect(status().isBadRequest());
        JsonNode item = read(me.http().perform(asJson(put("/api/me/shorts/" + id + "/blog"), Map.of("visible", false)))
                .andExpect(status().isOk()).andReturn());
        assertThat(item.path("onBlog").asBoolean()).isFalse();
        assertThat(blog(me.handle())).isEmpty();
        assertThat(ofPost(postId)).isEmpty();

        me.http().perform(asJson(put("/api/me/shorts/" + id + "/blog"), Map.of("visible", true))).andExpect(status().isOk());
        assertThat(blog(me.handle()).get(0).path("links").get(0).path("embedId").asString()).isEqualTo("AbCdEfGhIj0");
    }

    @Test
    void 없는_블로그는_404이고_글이_숨겨지면_보이지_않는다() throws Exception {
        browser().perform(get("/api/members/no-such-" + UUID.randomUUID().toString().substring(0, 8) + "/shorts")).andExpect(status().isNotFound());
        browser().perform(get("/api/posts/abc/shorts")).andExpect(status().isNotFound());

        Session me = signup(uniqueLogin("bshhid"));
        long postId = publish(me, "PUBLIC");
        uploaded(me, postId, "숨길 글의 쇼츠", Map.of("youtube", "https://youtube.com/shorts/AbCdEfGhIj0"));
        jdbc.update("UPDATE post SET hidden_at = now(), hidden_reason = 'SPAM' WHERE id = ?", postId);
        assertThat(blog(me.handle())).isEmpty();
        assertThat(ofPost(postId)).isEmpty();
    }
}
