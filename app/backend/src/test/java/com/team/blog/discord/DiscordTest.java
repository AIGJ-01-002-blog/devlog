package com.team.blog.discord;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;

import com.team.blog.support.FakeDiscord;
import com.team.blog.support.IntegrationTest;
import com.team.blog.support.Waits;

/** spec 078 인수 시나리오: 웹훅 주소로 연결(US1), 새 알림을 디스코드로(US2), 지운 웹훅 정리(US3). */
class DiscordTest extends IntegrationTest {
    static final FakeDiscord DC = FakeDiscord.INSTANCE;
    static final String TOKEN = "Abc-def_ghiJKLmnopQRSTuvwxYZ0123456789abcdefghijklmnopqrstuvwxyzAB";

    static String newWebhookId() {
        return Long.toString(ThreadLocalRandom.current().nextLong(100_000_000_000_000_000L, 999_999_999_999_999_999L));
    }

    static String url(String id) {
        return "https://discord.com/api/webhooks/" + id + "/" + TOKEN;
    }

    String connect(Session s) throws Exception {
        String id = newWebhookId();
        s.http().perform(asJson(put("/api/me/discord"), Map.of("url", url(id)))).andExpect(status().isOk())
                .andExpect(jsonPath("$.linked").value(true)).andExpect(jsonPath("$.webhookName").value(FakeDiscord.NAME));
        assertThat(DC.await(id, 1)).contains("devlog 알림을 이 채널로");
        return id;
    }

    JsonNode discord(Session s) throws Exception {
        return read(s.http().perform(get("/api/me/discord")).andExpect(status().isOk()).andReturn());
    }

    /** 연결이 남아 있는가 (기다리는 조건에 쓴다) */
    boolean linked(Session s) {
        try {
            return discord(s).path("linked").asBoolean();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    long publish(Session s) throws Exception {
        long id = read(s.http().perform(asJson(post("/api/posts"), Map.of("title", "글", "contentMd", "")))
                .andExpect(status().isCreated()).andReturn()).path("id").asLong();
        s.http().perform(asJson(post("/api/posts/" + id + "/publish"), Map.of("title", "레디스 정리", "contentMd", "본문",
                "visibility", "PUBLIC", "baseVersion", 0, "tags", List.of()))
                .header("Idempotency-Key", UUID.randomUUID().toString())).andExpect(status().isOk());
        return id;
    }

    // ---------------------------------------------------------------- US1

    @Test
    void 웹훅_주소로_연결하면_첫_메시지를_보내고_토큰은_돌려주지_않는다() throws Exception {
        Session s = signup(uniqueLogin("dc"));
        assertThat(discord(s).path("linked").asBoolean()).isFalse();
        assertThat(discord(s).path("available").asBoolean()).isTrue();
        String id = connect(s);

        String body = s.http().perform(get("/api/me/discord")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain(TOKEN).doesNotContain(id).contains("\"notifications\":true");
        JsonNode first = DC.sent.stream().filter(m -> m.webhookId().equals(id)).findFirst().orElseThrow().body();
        assertThat(first.path("allowed_mentions").path("parse").isEmpty()).isTrue();
    }

    @Test
    void 디스코드_웹훅_주소가_아니면_받지_않는다() throws Exception {
        Session s = signup(uniqueLogin("dcb"));
        for (String bad : List.of("http://discord.com/api/webhooks/123456789012345678/" + TOKEN,
                "https://evil.example.com/api/webhooks/123456789012345678/" + TOKEN,
                "https://discord.com.evil.example/api/webhooks/123456789012345678/" + TOKEN,
                "https://discord.com/api/webhooks/123456789012345678/" + TOKEN + "/../../x",
                "https://discord.com/channels/1/2")) {
            s.http().perform(asJson(put("/api/me/discord"), Map.of("url", bad))).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("DISCORD_INVALID_URL"));
        }
        assertThat(discord(s).path("linked").asBoolean()).isFalse();
    }

    @Test
    void 디스코드에_없는_웹훅이면_연결하지_않는다() throws Exception {
        Session s = signup(uniqueLogin("dcn"));
        String id = newWebhookId();
        DC.deleted.add(id);
        s.http().perform(asJson(put("/api/me/discord"), Map.of("url", url(id)))).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("DISCORD_WEBHOOK_NOT_FOUND"));
        assertThat(discord(s).path("linked").asBoolean()).isFalse();
    }

    @Test
    void 시험_보내기와_연결_끊기() throws Exception {
        Session s = signup(uniqueLogin("dct"));
        String id = connect(s);
        s.http().perform(post("/api/me/discord/test").with(csrf())).andExpect(status().isOk());
        assertThat(DC.await(id, 2)).contains("시험 알림");
        assertThat(DC.waited).doesNotContain(false);

        // 디스코드가 잠깐 기다리라고(429) 하면 알려 준 시간만큼 기다렸다 한 번 더 보낸다
        DC.limitedOnce.add(id);
        s.http().perform(post("/api/me/discord/test").with(csrf())).andExpect(status().isOk());
        assertThat(DC.await(id, 3)).contains("시험 알림");

        s.http().perform(delete("/api/me/discord").with(csrf())).andExpect(status().isNoContent());
        assertThat(discord(s).path("linked").asBoolean()).isFalse();
        s.http().perform(post("/api/me/discord/test").with(csrf())).andExpect(status().isConflict());
    }

    // ---------------------------------------------------------------- US2

    @Test
    void 새_알림이_디스코드로_가고_끄면_가지_않는다() throws Exception {
        Session author = signup(uniqueLogin("dcw")), reader = signup(uniqueLogin("dcr"));
        String id = connect(author);
        long post = publish(author);
        reader.http().perform(asJson(post("/api/posts/" + post + "/comments"), Map.of("content", "@everyone 좋은 글 감사합니다")))
                .andExpect(status().isCreated());
        drain();
        assertThat(DC.await(id, 2)).contains("댓글을 남겼어요").contains("「레디스 정리」").contains("좋은 글 감사합니다")
                .contains("/@" + author.handle() + "/posts/" + post);

        author.http().perform(asJson(patch("/api/me/discord"), Map.of("notifications", false))).andExpect(status().isOk());
        reader.http().perform(asJson(post("/api/posts/" + post + "/comments"), Map.of("content", "두 번째"))).andExpect(status().isCreated());
        drain();
        // 알림을 끈 뒤에는 잠시 기다려도 더 오지 않는다
        Waits.during(Duration.ofMillis(300), () -> assertThat(DC.to(id)).hasSize(2));
    }

    // ---------------------------------------------------------------- US3

    @Test
    void 디스코드에서_웹훅을_지우면_연결을_지운다() throws Exception {
        Session author = signup(uniqueLogin("dcx")), reader = signup(uniqueLogin("dcy"));
        String id = connect(author);
        DC.deleted.add(id);
        long post = publish(author);
        reader.http().perform(asJson(post("/api/posts/" + post + "/comments"), Map.of("content", "안녕"))).andExpect(status().isCreated());
        drain();
        Waits.until(() -> !linked(author), Duration.ofSeconds(5));
        assertThat(discord(author).path("linked").asBoolean()).isFalse();
    }

    @Test
    void 연결은_한_시간에_열_번까지만_시도할_수_있다() throws Exception {
        Session s = signup(uniqueLogin("dcl"));
        for (int i = 0; i < 10; i++) {
            s.http().perform(asJson(put("/api/me/discord"), Map.of("url", url(newWebhookId())))).andExpect(status().isOk());
        }
        s.http().perform(asJson(put("/api/me/discord"), Map.of("url", url(newWebhookId())))).andExpect(status().isTooManyRequests());
    }
}
