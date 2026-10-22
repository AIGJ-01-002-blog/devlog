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
    void 사이트맵에는_공개_글만_나온다() throws Exception {
        Session s = staff(signup(uniqueLogin("smap")));
        long open = publish(s, "공개", "PUBLIC");
        long friends = publish(s, "친구만", "FRIENDS");
        long priv = publish(s, "비밀", "PRIVATE");
        long hidden = publish(s, "숨김", "PUBLIC");
        jdbc.update("UPDATE post SET hidden_at = now(), hidden_reason = 'SPAM' WHERE id = ?", hidden);
        Session quiet = signup(uniqueLogin("smapq"));
        publish(quiet, "비밀만", "PRIVATE");

        String xml = body("/sitemaps/posts.xml", "application/xml");
        RssTest.parse(xml); // 잘 짜인 XML
        assertThat(xml).contains("<urlset xmlns=\"http://www.sitemaps.org/schemas/sitemap/0.9\">")
                .contains("/@" + s.handle() + "/posts/" + open + "</loc>")
                .doesNotContain("/@" + s.handle() + "</loc>") // 블로그 첫 화면은 글 카드 목록이라 넣지 않는다 (spec 106)
                .doesNotContain("/posts/" + friends + "<").doesNotContain("/posts/" + priv + "<")
                .doesNotContain("/posts/" + hidden + "<")
                .doesNotContain("/@" + quiet.handle() + "<")
                .contains("<lastmod>");
    }

    @Test
    void 사이트맵_색인은_글_시리즈_고정_화면_세_파일을_가리킨다() throws Exception {
        Session s = staff(signup(uniqueLogin("smapi")));
        long post = publish(s, "시리즈 글", "PUBLIC");
        long seriesId = jdbc.queryForObject("INSERT INTO series (member_id, name, slug) VALUES (?, '색인 시리즈', 'index-series') RETURNING id",
                Long.class, s.memberId());
        jdbc.update("INSERT INTO series_post (series_id, post_id, position) VALUES (?, ?, 1)", seriesId, post);
        Session quiet = staff(signup(uniqueLogin("smapj")));
        long hiddenSeries = jdbc.queryForObject("INSERT INTO series (member_id, name, slug) VALUES (?, '빈 시리즈', 'empty-series') RETURNING id",
                Long.class, quiet.memberId());
        jdbc.update("INSERT INTO series_post (series_id, post_id, position) VALUES (?, ?, 1)", hiddenSeries, publish(quiet, "비밀", "PRIVATE"));

        String index = body("/sitemap.xml", "application/xml");
        RssTest.parse(index);
        assertThat(index).contains("<sitemapindex xmlns=\"http://www.sitemaps.org/schemas/sitemap/0.9\">")
                .contains("/sitemaps/posts.xml</loc>").contains("/sitemaps/series.xml</loc>").contains("/sitemaps/pages.xml</loc>");
        String series = body("/sitemaps/series.xml", "application/xml");
        RssTest.parse(series);
        assertThat(series).contains("/@" + s.handle() + "/series/index-series</loc>").doesNotContain("empty-series");
        String pages = body("/sitemaps/pages.xml", "application/xml");
        assertThat(pages).contains("/about</loc>").contains("/portfolio</loc>").contains("/contact</loc>")
                .contains("/privacy</loc>").contains("/terms</loc>").contains("/mcp</loc>").contains("/releases</loc>")
                .doesNotContain("/posts/");
        mvc.perform(get("/sitemaps/nope.xml")).andExpect(status().isNotFound());
    }

    @Test
    void robots는_개인_화면을_막고_사이트맵을_알린다() throws Exception {
        String txt = body("/robots.txt", "text/plain");
        assertThat(txt).startsWith("User-agent: *").contains("Disallow: /api/").contains("Disallow: /admin")
                .contains("Disallow: /write").contains("Sitemap: ").contains("/sitemap.xml")
                // 운영자·회원 본인만 쓰는 화면도 막는다 (spec 107)
                .contains("Disallow: /studio\n").contains("Disallow: /trade\n").contains("Disallow: /trades\n").contains("Disallow: /paper\n")
                .doesNotContain("Disallow: /\n");
    }

    @Test
    void 사이트맵에는_robots가_막은_주소가_없다() throws Exception {
        Session s = staff(signup(uniqueLogin("smapr")));
        publish(s, "열린 글", "PUBLIC");
        java.util.List<String> disallow = body("/robots.txt", "text/plain").lines()
                .filter(l -> l.startsWith("Disallow: ")).map(l -> l.substring("Disallow: ".length())).toList();
        String all = body("/sitemaps/posts.xml", "application/xml") + body("/sitemaps/series.xml", "application/xml")
                + body("/sitemaps/pages.xml", "application/xml");
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("<loc>https?://[^/]+([^<]*)</loc>").matcher(all);
        int n = 0;
        while (m.find()) {
            String path = m.group(1);
            n++;
            assertThat(disallow).as(path).noneMatch(path::startsWith);
        }
        assertThat(n).isGreaterThan(5);
    }
}
