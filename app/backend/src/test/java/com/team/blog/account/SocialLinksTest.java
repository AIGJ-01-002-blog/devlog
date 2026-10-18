package com.team.blog.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.ResultActions;

import tools.jackson.databind.JsonNode;

import com.team.blog.account.application.WithdrawalPurgeStep;
import com.team.blog.support.IntegrationTest;

/** spec 043: 블로그 소셜 정보. 본인이 설정에서 저장하고, 누구나 블로그 머리에서 본다. */
class SocialLinksTest extends IntegrationTest {
    /** 빈 이름으로 고른다. 단계 클래스는 패키지 안에서만 보인다 */
    @Autowired Map<String, WithdrawalPurgeStep> purgeSteps;

    ResultActions save(Session s, Map<String, String> body) throws Exception {
        return s.http().perform(asJson(put("/api/me/social-links"), body));
    }

    JsonNode profileLinks(String handle) throws Exception {
        return read(browser().perform(get("/api/members/" + handle)).andExpect(status().isOk()).andReturn()).path("socialLinks");
    }

    @Test
    void 붙여_넣은_주소에서_아이디를_꺼내_정리해_저장하고_누구나_본다() throws Exception {
        Session me = signup(uniqueLogin("sl"));
        assertThat(profileLinks(me.handle()).isEmpty()).isTrue();

        JsonNode saved = read(save(me, Map.of(
                "email", "  Me@Example.COM ",
                "github", "https://github.com/octo-cat/",
                "x", "@dev_kim",
                "facebook", "https://www.facebook.com/kim.dev.5?ref=bookmarks",
                "homepage", "devlog.life/@me")).andExpect(status().isOk()).andReturn());
        assertThat(saved.path("email").asString()).isEqualTo("me@example.com");
        assertThat(saved.path("github").asString()).isEqualTo("octo-cat");
        assertThat(saved.path("x").asString()).isEqualTo("dev_kim");
        assertThat(saved.path("facebook").asString()).isEqualTo("kim.dev.5");
        assertThat(saved.path("homepage").asString()).isEqualTo("https://devlog.life/@me");

        assertThat(profileLinks(me.handle())).isEqualTo(saved);

        // spec 103: 쇼츠 채널도 같은 칸 묶음으로 저장한다
        JsonNode channels = read(save(me, Map.of(
                "youtube", "https://www.youtube.com/@devlog_kim/shorts",
                "instagram", "https://www.instagram.com/dev.kim/",
                "tiktok", "https://www.tiktok.com/@dev.kim")).andExpect(status().isOk()).andReturn());
        assertThat(channels.path("youtube").asString()).isEqualTo("@devlog_kim");
        assertThat(channels.path("instagram").asString()).isEqualTo("dev.kim");
        assertThat(channels.path("tiktok").asString()).isEqualTo("dev.kim");
        assertThat(profileLinks(me.handle())).isEqualTo(channels);
        saved = read(save(me, Map.of(
                "email", "me@example.com", "github", "octo-cat", "x", "dev_kim", "facebook", "kim.dev.5",
                "homepage", "https://devlog.life/@me")).andExpect(status().isOk()).andReturn());

        assertThat(read(me.http().perform(get("/api/me/settings")).andReturn()).path("socialLinks")).isEqualTo(saved);

        // 보내지 않거나 비운 칸은 지운다
        save(me, Map.of("github", "octo-cat", "x", " ")).andExpect(status().isOk());
        JsonNode after = profileLinks(me.handle());
        assertThat(after.path("github").asString()).isEqualTo("octo-cat");
        assertThat(after.has("x")).isFalse();
        assertThat(after.has("email")).isFalse();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM member_social_link WHERE member_id = ?", Long.class, me.memberId())).isOne();
    }

    @Test
    void 틀린_칸이_있으면_아무것도_바꾸지_않고_모두_알려준다() throws Exception {
        Session me = signup(uniqueLogin("slv"));
        save(me, Map.of("github", "octo")).andExpect(status().isOk());

        Map<String, String> bad = new HashMap<>();
        bad.put("email", "not-an-email");
        bad.put("github", "-bad-");
        bad.put("x", "this_is_too_long_for_x");
        bad.put("facebook", "abc");
        bad.put("homepage", "javascript:alert(1)");
        bad.put("youtube", "https://www.youtube.com/watch?v=abc");
        bad.put("instagram", "한글");
        bad.put("tiktok", "https://www.tiktok.com/@kim/video/1");
        String body = save(me, bad).andExpect(status().isBadRequest()).andReturn().getResponse().getContentAsString();
        assertThat(body).contains("SOCIAL_EMAIL_INVALID", "SOCIAL_GITHUB_INVALID", "SOCIAL_X_INVALID", "SOCIAL_FACEBOOK_INVALID",
                "SOCIAL_HOMEPAGE_INVALID", "SOCIAL_YOUTUBE_INVALID", "SOCIAL_INSTAGRAM_INVALID", "SOCIAL_TIKTOK_INVALID");
        assertThat(profileLinks(me.handle()).path("github").asString()).isEqualTo("octo");

        // 다른 사이트 주소나 계정 정보가 든 홈페이지는 받지 않는다
        save(me, Map.of("github", "https://gitlab.com/octo")).andExpect(status().isBadRequest());
        save(me, Map.of("homepage", "https://user:pw@example.com")).andExpect(status().isBadRequest());
        save(me, Map.of("homepage", "ftp://example.com")).andExpect(status().isBadRequest());

        browser().perform(asJson(put("/api/me/social-links"), Map.of("github", "octo"))).andExpect(status().isUnauthorized());
    }

    @Test
    void 동시에_저장해도_서버_오류_없이_한쪽_값으로_남는다() throws Exception {
        // 리뷰 지적: 지우고 다시 넣는 사이에 다른 저장이 끼면 기본 키가 겹쳐 500이 났다. 회원 행을 잠가 차례로 처리한다
        Session me = signup(uniqueLogin("slc"));
        for (int round = 0; round < 10; round++) {
            var pool = java.util.concurrent.Executors.newFixedThreadPool(4);
            var results = new java.util.ArrayList<java.util.concurrent.Future<Integer>>();
            for (int i = 0; i < 4; i++) {
                String id = "octo" + round + i;
                results.add(pool.submit(() -> save(me, Map.of("github", id, "x", id)).andReturn().getResponse().getStatus()));
            }
            for (var f : results) assertThat(f.get()).isEqualTo(200);
            pool.shutdown();
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM member_social_link WHERE member_id = ?", Long.class, me.memberId())).isEqualTo(2);
    }

    @Test
    void 글_아래_작성자_영역에도_온다() throws Exception {
        // spec 044: velog처럼 글 상세의 작성자 영역에도 소셜 정보를 보인다
        Session me = signup(uniqueLogin("slp"));
        save(me, Map.of("github", "octo", "homepage", "https://devlog.life")).andExpect(status().isOk());
        long id = read(me.http().perform(asJson(post("/api/posts"), Map.of("title", "소셜", "contentMd", "본문")))
                .andExpect(status().isCreated()).andReturn()).path("id").asLong();
        me.http().perform(asJson(post("/api/posts/" + id + "/publish"), Map.of("title", "소셜", "contentMd", "본문",
                        "visibility", "PUBLIC", "baseVersion", 0, "tags", List.of()))
                .header("Idempotency-Key", UUID.randomUUID().toString())).andExpect(status().isOk());

        JsonNode links = read(browser().perform(get("/api/posts/" + id)).andExpect(status().isOk()).andReturn())
                .path("author").path("socialLinks");
        assertThat(links.path("github").asString()).isEqualTo("octo");
        assertThat(links.path("homepage").asString()).isEqualTo("https://devlog.life");
        assertThat(links.has("email")).isFalse();
    }

    @Test
    void 탈퇴_정리에서_지운다() throws Exception {
        Session me = signup(uniqueLogin("slw"));
        save(me, Map.of("email", "bye@example.com")).andExpect(status().isOk());
        purgeSteps.get("socialLinksWithdrawalPurgeStep").purge(me.memberId());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM member_social_link WHERE member_id = ?", Long.class, me.memberId())).isZero();
    }
}
