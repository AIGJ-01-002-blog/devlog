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
        return publish(s, title, tag, ARTICLE_BODY);
    }

    long publish(Session s, String title, String tag, String content) throws Exception {
        long id = read(s.http().perform(asJson(post("/api/posts"), Map.of("title", title, "contentMd", "")))
                .andExpect(status().isCreated()).andReturn()).path("id").asLong();
        s.http().perform(asJson(post("/api/posts/" + id + "/publish"), Map.of("title", title, "contentMd", content,
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
    void 홈_피드에는_AI_일기가_없고_블로그에는_남는다() throws Exception {
        Session s = signup(uniqueLogin("homediary"));
        String tag = "hd" + System.nanoTime() % 1_000_000;
        long diary = publish(s, "홈에 안 보일 일기", tag);
        markDiary(diary);
        long article = publish(s, "홈에 보일 글", tag);
        assertThat(ids("/api/posts")).contains(article).doesNotContain(diary);
        assertThat(ids("/api/members/" + s.handle() + "/posts")).contains(diary, article);
    }

    List<Long> ids(String path) throws Exception {
        List<Long> out = new java.util.ArrayList<>();
        read(mvc.perform(get(path)).andExpect(status().isOk()).andReturn()).path("items").forEach(n -> out.add(n.path("id").asLong()));
        return out;
    }

    @Test
    void 본문이_짧은_글은_독자에게_보이되_수집_광고_사이트맵에서_빠진다() throws Exception {
        Session s = signup(uniqueLogin("shortpost"));
        String tag = "sp" + System.nanoTime() % 1_000_000;
        // 코드 블록·그림·링크 주소는 글자 수에 넣지 않는다
        String padded = "짧은 소개\n\n```java\n" + "x".repeat(2000) + "\n```\n\n![그림](https://example.com/" + "a".repeat(1500)
                + ".png) [링크](https://example.com/" + "b".repeat(1500) + ")";
        long shortPost = publish(s, "짧은 글", tag, padded);
        String shortPage = page("/@" + s.handle() + "/posts/" + shortPost);
        assertThat(shortPage).contains("noindex").doesNotContain("adsbygoogle").contains("짧은 소개");
        assertThat(page("/sitemap.xml")).doesNotContain("/posts/" + shortPost + "<");
        assertThat(page("/@" + s.handle())).contains("noindex"); // 짧은 글만 있는 블로그도 얇은 화면

        long longPost = publish(s, "긴 글", tag);
        assertThat(page("/@" + s.handle() + "/posts/" + longPost)).doesNotContain("noindex").contains("<link rel=\"canonical\"");
        assertThat(page("/sitemap.xml")).contains("/posts/" + longPost + "<");
        assertThat(page("/@" + s.handle())).doesNotContain("noindex");
        assertThat(ids("/api/posts")).contains(shortPost, longPost); // 홈 목록에는 그대로 보인다
    }

    @Test
    void 홈_서버_화면에_사이트_소개_문단이_있다() throws Exception {
        assertThat(page("/")).contains("devlog는 개발자가 직접 겪은 문제와 해결 과정을 기술 글로 남기는 블로그예요")
                .contains("<a href=\"/about\">");
    }

    @Test
    void 트렌딩_탭의_정규_주소는_홈이다() throws Exception {
        assertThat(page("/?tab=trending")).contains("<link rel=\"canonical\" href=\"http://localhost:8080/\">")
                .doesNotContain("tab=trending\">");
    }
}
