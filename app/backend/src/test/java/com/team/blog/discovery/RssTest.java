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

import javax.xml.parsers.DocumentBuilderFactory;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;

import com.team.blog.support.IntegrationTest;

/** spec 026: 전체·블로그별 RSS. 공개 글만, 올바른 XML. */
class RssTest extends IntegrationTest {
    long publish(Session s, String title, String visibility) throws Exception {
        long id = read(s.http().perform(asJson(post("/api/posts"), Map.of("title", title, "contentMd", "")))
                .andExpect(status().isCreated()).andReturn()).path("id").asLong();
        s.http().perform(asJson(post("/api/posts/" + id + "/publish"), Map.of("title", title, "contentMd", "본문 <b>굵게</b> & 기호\n\n" + ARTICLE_BODY,
                        "visibility", visibility, "baseVersion", 0, "tags", List.of()))
                .header("Idempotency-Key", UUID.randomUUID().toString())).andExpect(status().isOk());
        return id;
    }

    String rss(String url) throws Exception {
        return browser().perform(get(url)).andExpect(status().isOk())
                .andExpect(header().string("Content-Type", org.hamcrest.Matchers.startsWith("application/rss+xml")))
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("public")))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    static Document parse(String xml) throws Exception {
        var f = DocumentBuilderFactory.newInstance();
        f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        return f.newDocumentBuilder().parse(new java.io.ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void 블로그_RSS에는_공개_글만_최신순으로_나온다() throws Exception {
        Session s = staff(signup(uniqueLogin("rss")));
        long old = publish(s, "처음 & <끝>", "PUBLIC");
        long friends = publish(s, "친구만", "FRIENDS");
        long priv = publish(s, "비밀", "PRIVATE");
        long latest = publish(s, "최신", "PUBLIC");

        String xml = rss("/@" + s.handle() + "/rss");
        Document doc = parse(xml); // 잘 짜인 XML
        var items = doc.getElementsByTagName("item");
        assertThat(items.getLength()).isEqualTo(2);
        assertThat(items.item(0).getChildNodes().item(1).getTextContent()).isEqualTo("최신");
        assertThat(xml).contains("/@" + s.handle() + "/posts/" + latest).contains("/posts/" + old)
                .doesNotContain("/posts/" + friends + "<").doesNotContain("/posts/" + priv + "<")
                .contains("처음 &amp; &lt;끝&gt;").contains("<pubDate>").contains("<dc:creator>");

        String all = rss("/rss");
        parse(all);
        assertThat(all).contains("/posts/" + latest + "</link>");

        browser().perform(get("/@nobody-" + UUID.randomUUID().toString().substring(0, 6) + "/rss")).andExpect(status().isNotFound());

        // 탈퇴한 회원의 블로그 피드는 없는 것으로 본다
        Session gone = signup(uniqueLogin("rssw"));
        publish(gone, "떠나기 전 글", "PUBLIC");
        jdbc.update("UPDATE member SET status = 'WITHDRAWN', withdrawn_at = now() WHERE handle = ?", gone.handle());
        browser().perform(get("/@" + gone.handle() + "/rss")).andExpect(status().isNotFound());

        // 블로그 첫 화면은 구독 주소를 알린다
        String page = browser().perform(get("/@" + s.handle())).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        assertThat(page).contains("type=\"application/rss+xml\"").contains("href=\"/@" + s.handle() + "/rss\"");
    }

    @Test
    void 브라우저로_RSS를_열면_안내_화면이고_구독_앱은_XML을_받는다() throws Exception {
        Session s = signup(uniqueLogin("rssg"));
        publish(s, "안내", "PUBLIC");
        String url = "/@" + s.handle() + "/rss";

        var guide = browser().perform(get(url).header("Sec-Fetch-Dest", "document")).andExpect(status().isOk())
                .andExpect(header().string("Content-Type", org.hamcrest.Matchers.startsWith("text/html")))
                .andExpect(header().string("Vary", org.hamcrest.Matchers.containsString("Sec-Fetch-Dest")))
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(guide).contains("RSS 구독 안내").contains("noindex").doesNotContain("<rss");

        // 구독 앱·fetch는 Sec-Fetch-Dest가 document가 아니다. ?format=xml이면 브라우저에서도 XML
        assertThat(parse(rss(url)).getElementsByTagName("item").getLength()).isEqualTo(1);
        browser().perform(get(url).header("Sec-Fetch-Dest", "empty")).andExpect(status().isOk())
                .andExpect(header().string("Content-Type", org.hamcrest.Matchers.startsWith("application/rss+xml")));
        browser().perform(get(url + "?format=xml").header("Sec-Fetch-Dest", "document")).andExpect(status().isOk())
                .andExpect(header().string("Content-Type", org.hamcrest.Matchers.startsWith("application/rss+xml")))
                .andExpect(header().string("Vary", org.hamcrest.Matchers.containsString("Sec-Fetch-Dest")));
        browser().perform(get("/rss").header("Sec-Fetch-Dest", "document")).andExpect(status().isOk())
                .andExpect(header().string("Content-Type", org.hamcrest.Matchers.startsWith("text/html")));
        // 없는 블로그는 브라우저로 열어도 404
        browser().perform(get("/@nobody-here-xyz/rss").header("Sec-Fetch-Dest", "document")).andExpect(status().isNotFound());
    }
}
