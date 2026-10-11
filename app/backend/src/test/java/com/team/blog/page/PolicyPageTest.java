package com.team.blog.page;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.team.blog.support.IntegrationTest;

/** spec 085: 이용약관·개인정보 처리방침은 스크립트 없이도 읽히는 공개 화면이고, 얇은 태그 화면은 수집에서 뺀다 (애드센스 심사·검색 엔진). */
class PolicyPageTest extends IntegrationTest {

    @Test
    void 개인정보_처리방침은_서버가_본문을_그리고_검색_엔진이_읽는다() throws Exception {
        String page = mvc.perform(get("/privacy")).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        assertThat(page).contains("<title>개인정보 처리방침 - devlog</title>")
                .contains("<link rel=\"canonical\" href=\"http://localhost:8080/privacy\">")
                .contains("<h1>개인정보 처리방침</h1>")
                .contains("<h2 id=\"ads\">7. 광고와 쿠키</h2>").contains("https://adssettings.google.com")
                .contains("href=\"/terms\"")
                .contains("\"page\":\"policy\"")
                .doesNotContain("noindex").doesNotContain("<!--");
    }

    @Test
    void 이용약관도_공개_화면이다() throws Exception {
        String page = mvc.perform(get("/terms")).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        assertThat(page).contains("<h1>이용약관</h1>").contains("<h2>1. 목적</h2>").contains("href=\"/privacy\"")
                .doesNotContain("noindex");
    }

    @Test
    void 화면_안에서_옮겨_오면_API로_같은_문안을_받는다() throws Exception {
        mvc.perform(get("/api/site/policies/privacy")).andExpect(status().isOk())
                .andExpect(jsonPath("$.kind").value("privacy"))
                .andExpect(jsonPath("$.title").value("개인정보 처리방침"))
                .andExpect(jsonPath("$.effectiveDate").isNotEmpty())
                .andExpect(jsonPath("$.html").value(org.hamcrest.Matchers.containsString("광고와 쿠키")));
        mvc.perform(get("/api/site/policies/terms")).andExpect(status().isOk()).andExpect(jsonPath("$.kind").value("terms"));
        mvc.perform(get("/api/site/policies/other")).andExpect(status().isNotFound());
    }

    void publish(Session s, String title, String tag) throws Exception {
        long id = read(s.http().perform(asJson(post("/api/posts"), Map.of("title", title, "contentMd", "")))
                .andExpect(status().isCreated()).andReturn()).path("id").asLong();
        s.http().perform(asJson(post("/api/posts/" + id + "/publish"), Map.of("title", title, "contentMd", "본문",
                        "visibility", "PUBLIC", "baseVersion", 0, "tags", List.of(tag)))
                .header("Idempotency-Key", UUID.randomUUID().toString())).andExpect(status().isOk());
    }

    @Test
    void 글이_하나뿐인_태그_화면은_검색_수집에서_빼고_둘부터_연다() throws Exception {
        Session s = signup(uniqueLogin("thin"));
        String tag = "thin" + System.nanoTime() % 1_000_000;
        publish(s, "첫 글", tag);
        assertThat(mvc.perform(get("/tags/" + tag)).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8))
                .contains("noindex").doesNotContain("adsbygoogle");
        publish(s, "둘째 글", tag);
        assertThat(mvc.perform(get("/tags/" + tag)).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8))
                .doesNotContain("noindex").contains("<link rel=\"canonical\"");
    }
}
