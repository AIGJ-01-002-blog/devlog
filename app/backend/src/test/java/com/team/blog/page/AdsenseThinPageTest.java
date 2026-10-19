package com.team.blog.page;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.team.blog.support.IntegrationTest;

/** spec 104: 글 없는 블로그·빈 시리즈·트렌딩 탭·AI 일기로만 채운 태그는 얇은 화면이라 검색 수집과 광고에서 뺀다 (애드센스 심사). */
class AdsenseThinPageTest extends IntegrationTest {

    long publish(Session s, String title, String tag) throws Exception {
        long id = read(s.http().perform(asJson(post("/api/posts"), Map.of("title", title, "contentMd", "")))
                .andExpect(status().isCreated()).andReturn()).path("id").asLong();
        s.http().perform(asJson(post("/api/posts/" + id + "/publish"), Map.of("title", title, "contentMd", "본문",
                        "visibility", "PUBLIC", "baseVersion", 0, "tags", List.of(tag)))
                .header("Idempotency-Key", UUID.randomUUID().toString())).andExpect(status().isOk());
        return id;
    }

    String page(String path) throws Exception {
        return mvc.perform(get(path)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    void markDiary(long postId) {
        jdbc.update("UPDATE post SET ai_diary = true WHERE id = ?", postId);
    }

    @Test
    void 글이_없거나_AI_일기만_있는_블로그는_수집하지_않고_글이_생기면_연다() throws Exception {
        Session s = signup(uniqueLogin("thinblog"));
        assertThat(page("/@" + s.handle())).contains("noindex").doesNotContain("adsbygoogle");
        String tag = "diary" + System.nanoTime() % 1_000_000;
        markDiary(publish(s, "오늘의 일기", tag));
        assertThat(page("/@" + s.handle())).contains("noindex");
        publish(s, "직접 쓴 글", tag);
        assertThat(page("/@" + s.handle())).doesNotContain("noindex").contains("<link rel=\"canonical\"");
    }

    @Test
    void AI_일기로만_두_편을_채운_태그는_수집하지_않는다() throws Exception {
        Session s = signup(uniqueLogin("diarytag"));
        String tag = "dtag" + System.nanoTime() % 1_000_000;
        markDiary(publish(s, "일기 하나", tag));
        markDiary(publish(s, "일기 둘", tag));
        assertThat(page("/tags/" + tag)).contains("noindex").contains("공개 글 2");
        publish(s, "글 하나", tag);
        publish(s, "글 둘", tag);
        assertThat(page("/tags/" + tag)).doesNotContain("noindex");
    }

    @Test
    void 시리즈가_없는_시리즈_목록은_수집하지_않는다() throws Exception {
        Session s = signup(uniqueLogin("noseries"));
        assertThat(page("/@" + s.handle() + "/series")).contains("noindex");
    }

    @Test
    void 트렌딩_탭의_정규_주소는_홈이다() throws Exception {
        assertThat(page("/?tab=trending")).contains("<link rel=\"canonical\" href=\"http://localhost:8080/\">")
                .doesNotContain("tab=trending\">");
    }
}
