package com.team.blog.discovery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.team.blog.support.IntegrationTest;

/** 검색 엔진용 robots.txt·sitemap.xml: 공개 글만, 막을 화면은 막는다 */
class SiteIndexTest extends IntegrationTest {
    long publish(Session s, String title, String visibility) throws Exception {
        long id = read(s.http().perform(asJson(post("/api/posts"), Map.of("title", title, "contentMd", "")))
                .andExpect(status().isCreated()).andReturn()).path("id").asLong();
        s.http().perform(asJson(post("/api/posts/" + id + "/publish"), Map.of("title", title, "contentMd", ARTICLE_BODY,
                        "visibility", visibility, "baseVersion", 0, "tags", List.of()))
                .header("Idempotency-Key", UUID.randomUUID().toString())).andExpect(status().isOk());
        return id;
    }

    String body(String url, String type) throws Exception {
        return browser().perform(get(url)).andExpect(status().isOk())
                .andExpect(header().string("Content-Type", org.hamcrest.Matchers.startsWith(type)))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    @Test
    void 사이트맵에는_공개_글과_그_블로그만_나온다() throws Exception {
        Session s = signup(uniqueLogin("smap"));
        long open = publish(s, "공개", "PUBLIC");
        long friends = publish(s, "친구만", "FRIENDS");
        long priv = publish(s, "비밀", "PRIVATE");
        long hidden = publish(s, "숨김", "PUBLIC");
        jdbc.update("UPDATE post SET hidden_at = now(), hidden_reason = 'SPAM' WHERE id = ?", hidden);
        Session quiet = signup(uniqueLogin("smapq"));
        publish(quiet, "비밀만", "PRIVATE");

        String xml = body("/sitemap.xml", "application/xml");
        RssTest.parse(xml); // 잘 짜인 XML
        assertThat(xml).contains("<urlset xmlns=\"http://www.sitemaps.org/schemas/sitemap/0.9\">")
                .contains("/@" + s.handle() + "/posts/" + open + "</loc>")
                .contains("/@" + s.handle() + "</loc>")
                .contains("/about</loc>").contains("/privacy</loc>").contains("/terms</loc>")
                .contains("/mcp</loc>").contains("/releases</loc>")
                .doesNotContain("/posts/" + friends + "<").doesNotContain("/posts/" + priv + "<")
                .doesNotContain("/posts/" + hidden + "<")
                .doesNotContain("/@" + quiet.handle() + "<")
                .contains("<lastmod>");
    }

    @Test
    void robots는_개인_화면을_막고_사이트맵을_알린다() throws Exception {
        String txt = body("/robots.txt", "text/plain");
        assertThat(txt).startsWith("User-agent: *").contains("Disallow: /api/").contains("Disallow: /admin")
                .contains("Disallow: /write").contains("Sitemap: ").contains("/sitemap.xml")
                .doesNotContain("Disallow: /\n");
    }

    @Test
    void 사이트맵에는_robots가_막은_주소가_없다() throws Exception {
        Session s = signup(uniqueLogin("smapr"));
        publish(s, "열린 글", "PUBLIC");
        java.util.List<String> disallow = body("/robots.txt", "text/plain").lines()
                .filter(l -> l.startsWith("Disallow: ")).map(l -> l.substring("Disallow: ".length())).toList();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("<loc>https?://[^/]+([^<]*)</loc>")
                .matcher(body("/sitemap.xml", "application/xml"));
        int n = 0;
        while (m.find()) {
            String path = m.group(1);
            n++;
            assertThat(disallow).as(path).noneMatch(path::startsWith);
        }
        assertThat(n).isGreaterThan(5);
    }
}
