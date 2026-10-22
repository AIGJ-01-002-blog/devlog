package com.team.blog.page;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;

import com.team.blog.support.Browser;
import com.team.blog.support.IntegrationTest;

/** spec 107: 외부 회원 글은 운영진이 검수해야 검색 수집·사이트맵에 들어가고, 수집하지 않는 글 화면도 링크는 따라가게 한다 */
class IndexReviewTest extends IntegrationTest {
    long publish(Session s, String title, String content) throws Exception {
        long id = read(s.http().perform(asJson(post("/api/posts"), Map.of("title", title, "contentMd", "")))
                .andExpect(status().isCreated()).andReturn()).path("id").asLong();
        s.http().perform(asJson(post("/api/posts/" + id + "/publish"), Map.of("title", title, "contentMd", content,
                        "visibility", "PUBLIC", "baseVersion", 0, "tags", List.of()))
                .header("Idempotency-Key", UUID.randomUUID().toString())).andExpect(status().isOk());
        return id;
    }

    String page(String path) throws Exception {
        return browser().perform(get(path)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    Browser manager() throws Exception {
        Session m = staff(signup(uniqueLogin("idxm")));
        return relogin(m);
    }

    @Test
    void 외부_회원의_긴_글은_검수_전에는_noindex_follow이고_사이트맵에_없다() throws Exception {
        Session outsider = signup(uniqueLogin("idxu"));
        long id = publish(outsider, "외부 회원 글", ARTICLE_BODY);
        String path = "/@" + outsider.handle() + "/posts/" + id;

        String html = page(path);
        assertThat(html).contains("<meta name=\"robots\" content=\"noindex, follow\">").doesNotContain("adsbygoogle")
                .contains("<link rel=\"canonical\"").contains(SpaShell.SITE_FOOTER);
        assertThat(page("/sitemaps/posts.xml")).doesNotContain("/posts/" + id + "<");
        assertThat(page("/@" + outsider.handle())).contains("noindex, follow"); // 수집할 글이 없는 블로그

        Browser m = manager();
        JsonNode report = read(m.perform(get("/api/admin/index-report")).andExpect(status().isOk()).andReturn());
        assertThat(report.path("unreviewed").asLong()).isPositive();
        assertThat(report.path("pending").findValuesAsString("url")).contains(path);

        m.perform(asJson(put("/api/admin/posts/" + id + "/index-approval"), Map.of("approved", true))).andExpect(status().isNoContent());
        assertThat(page(path)).doesNotContain("noindex");
        assertThat(page("/sitemaps/posts.xml")).contains("/posts/" + id + "<");

        // 거두면 다시 빠진다
        m.perform(asJson(put("/api/admin/posts/" + id + "/index-approval"), Map.of("approved", false))).andExpect(status().isNoContent());
        assertThat(page(path)).contains("noindex, follow");
    }

    @Test
    void 운영진_글은_검수_없이_수집하고_일반_회원은_검수할_수_없다() throws Exception {
        Session operator = staff(signup(uniqueLogin("idxo")));
        long id = publish(operator, "운영자 글", ARTICLE_BODY);
        assertThat(page("/@" + operator.handle() + "/posts/" + id)).doesNotContain("noindex");
        assertThat(page("/sitemaps/posts.xml")).contains("/posts/" + id + "<");

        Session user = signup(uniqueLogin("idxn"));
        user.http().perform(get("/api/admin/index-report")).andExpect(status().isNotFound());
        user.http().perform(asJson(put("/api/admin/posts/" + id + "/index-approval"), Map.of("approved", true)))
                .andExpect(status().isNotFound());
        manager().perform(asJson(put("/api/admin/posts/999999999/index-approval"), Map.of("approved", true)))
                .andExpect(status().isNotFound());
    }

    @Test
    void 일기와_짧은_글은_독자에게_보이고_noindex_follow로_링크만_따라간다() throws Exception {
        Session s = staff(signup(uniqueLogin("idxd")));
        long diary = publish(s, "2026-10-20 개발 일기", ARTICLE_BODY);
        jdbc.update("UPDATE post SET ai_diary = true WHERE id = ?", diary);
        long shortPost = publish(s, "짧은 글", "짧은 본문");
        for (long id : new long[] {diary, shortPost}) {
            assertThat(page("/@" + s.handle() + "/posts/" + id)).contains("<meta name=\"robots\" content=\"noindex, follow\">");
            assertThat(page("/sitemaps/posts.xml")).doesNotContain("/posts/" + id + "<");
        }
        // 로그인 화면 같은 개인 화면은 지금처럼 링크도 따라가지 않는다
        assertThat(page("/login")).contains("noindex, nofollow");
    }
}
