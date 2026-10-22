package com.team.blog.page;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

import com.team.blog.support.IntegrationTest;

/**
 * 광고 스위치 기본값 none (spec 107): 애드센스 재신청 전까지 광고 코드는 어디에도 넣지 않고,
 * 게시자 확인용 ads.txt와 소유 확인 메타만 둔다.
 */
class AdsOffTest extends IntegrationTest {

    @Test
    void 기본값에서는_글_화면에도_광고_코드와_nonce가_없고_ads_txt와_소유_확인은_남는다() throws Exception {
        Session s = staff(signup(uniqueLogin("adsoff")));
        long id = read(s.http().perform(asJson(post("/api/posts"), Map.of("title", "광고 없는 글", "contentMd", "")))
                .andExpect(status().isCreated()).andReturn()).path("id").asLong();
        s.http().perform(asJson(post("/api/posts/" + id + "/publish"), Map.of("title", "광고 없는 글", "contentMd", ARTICLE_BODY,
                        "visibility", "PUBLIC", "baseVersion", 0, "tags", List.of()))
                .header("Idempotency-Key", UUID.randomUUID().toString())).andExpect(status().isOk());

        MockHttpServletResponse res = browser().perform(get("/@" + s.handle() + "/posts/" + id)).andExpect(status().isOk())
                .andReturn().getResponse();
        assertThat(res.getContentAsString(StandardCharsets.UTF_8)).doesNotContain("adsbygoogle").doesNotContain("devlog-ad-slot")
                .contains("<meta name=\"google-adsense-account\" content=\"ca-pub-5861067712842534\">");
        assertThat(res.getHeader("Content-Security-Policy")).doesNotContain("nonce-");
        browser().perform(get("/ads.txt")).andExpect(status().isOk())
                .andExpect(content().string("google.com, pub-5861067712842534, DIRECT, f08c47fec0942fa0\n"));
    }
}
