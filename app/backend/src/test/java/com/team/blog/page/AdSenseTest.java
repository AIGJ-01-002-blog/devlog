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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import com.team.blog.support.IntegrationTest;

/** 구글 애드센스 (spec 076): 글 화면에 광고 코드(spec 106), 같은 nonce의 엄격한 CSP, 사이트 루트 ads.txt */
class AdSenseTest extends IntegrationTest {
    private static final String CLIENT = "ca-pub-5861067712842534";

    String publishArticle() throws Exception {
        Session s = signup(uniqueLogin("adpost"));
        long id = read(s.http().perform(asJson(post("/api/posts"), Map.of("title", "광고 글", "contentMd", "")))
                .andExpect(status().isCreated()).andReturn()).path("id").asLong();
        s.http().perform(asJson(post("/api/posts/" + id + "/publish"), Map.of("title", "광고 글", "contentMd", ARTICLE_BODY,
                        "visibility", "PUBLIC", "baseVersion", 0, "tags", List.of()))
                .header("Idempotency-Key", UUID.randomUUID().toString())).andExpect(status().isOk());
        return "/@" + s.handle() + "/posts/" + id;
    }

    @Test
    void 글_화면은_광고_코드를_싣고_CSP가_그_nonce만_믿는다() throws Exception {
        String path = publishArticle();
        MockHttpServletResponse res = browser().perform(get(path)).andExpect(status().isOk()).andReturn().getResponse();
        String html = res.getContentAsString(StandardCharsets.UTF_8);
        assertThat(html).contains("https://pagead2.googlesyndication.com/pagead/js/adsbygoogle.js?client=" + CLIENT);
        Matcher m = Pattern.compile("<script async nonce=\"([^\"]+)\" src=\"https://pagead2").matcher(html);
        assertThat(m.find()).isTrue();
        String csp = res.getHeader("Content-Security-Policy");
        assertThat(csp).contains("'nonce-" + m.group(1) + "'", "'strict-dynamic'", "object-src 'none'", "base-uri 'none'",
                "frame-ancestors 'none'", "frame-src https:");

        // 요청마다 nonce가 바뀐다
        String again = browser().perform(get(path)).andReturn().getResponse().getHeader("Content-Security-Policy");
        assertThat(again).doesNotContain("'nonce-" + m.group(1) + "'");
    }

    @Test
    void 홈과_블로그_목록에는_광고_없이_소유_확인_메타만_둔다() throws Exception {
        String article = publishArticle();
        String blog = article.substring(0, article.indexOf("/posts/"));
        for (String path : new String[]{"/", blog}) {
            MockHttpServletResponse res = browser().perform(get(path)).andExpect(status().isOk()).andReturn().getResponse();
            assertThat(res.getContentAsString(StandardCharsets.UTF_8)).as(path).doesNotContain("adsbygoogle")
                    .contains("<meta name=\"google-adsense-account\" content=\"" + CLIENT + "\">");
            assertThat(res.getHeader("Content-Security-Policy")).as(path).doesNotContain("nonce-");
        }
    }

    @Test
    void 로그인_글쓰기_설정_관리_화면에는_광고를_싣지_않는다() throws Exception {
        for (String path : new String[]{"/login", "/write", "/settings", "/admin"}) {
            MockHttpServletResponse res = browser().perform(get(path)).andReturn().getResponse();
            assertThat(res.getContentAsString(StandardCharsets.UTF_8)).as(path).doesNotContain("adsbygoogle");
            assertThat(res.getHeader("Content-Security-Policy")).as(path).contains("script-src 'self';").doesNotContain("nonce-");
        }
    }

    @Test
    void ads_txt에_구글_판매자_한_줄이_있다() throws Exception {
        browser().perform(get("/ads.txt")).andExpect(status().isOk())
                .andExpect(content().string("google.com, pub-5861067712842534, DIRECT, f08c47fec0942fa0\n"));
    }

    @Test
    void 앱_스크립트와_모듈_미리_불러오기에도_nonce를_붙인다() {
        String template = "<head><script src=\"/theme.js\"></script><link rel=\"modulepreload\" href=\"/a.js\">"
                + "<script type=\"module\" src=\"/assets/i.js\"></script><!--app-head--></head><body><!--app-body--></body>";
        SpaShell shell = new SpaShell(new AdSense(CLIENT), template);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));
        try {
            HeadMeta article = new HeadMeta("글", "d", "https://devlog.life/@a/posts/1", "article", null, null, null, true);
            String html = shell.render(article, "<p>본문</p>", null);
            Matcher m = Pattern.compile("nonce=\"([^\"]+)\"").matcher(html);
            String first = null;
            int count = 0;
            while (m.find()) {
                if (first == null) first = m.group(1);
                assertThat(m.group(1)).isEqualTo(first);
                count++;
            }
            assertThat(count).isEqualTo(4); // theme.js, modulepreload, 앱 모듈, 광고 코드
            assertThat(html).doesNotContain("<script src=").doesNotContain("<link rel=\"modulepreload\" href");

            String closed = shell.render(HeadMeta.privatePage("devlog", "설정"), "", null);
            assertThat(closed).doesNotContain("nonce=").doesNotContain("adsbygoogle").doesNotContain("google-adsense-account");

            String home = shell.render(HeadMeta.site("devlog", "d", "https://devlog.life/", null), "", null);
            assertThat(home).doesNotContain("nonce=").doesNotContain("adsbygoogle").contains("google-adsense-account");
        } finally {
            RequestContextHolder.resetRequestAttributes();
        }
    }

    @Test
    void 게시자_ID가_비었거나_형식이_틀리면_끈다() {
        assertThat(new AdSense("").enabled()).isFalse();
        assertThat(new AdSense("ca-pub-1\"><script>").enabled()).isFalse();
        assertThat(new AdSense(" " + CLIENT + " ").enabled()).isTrue();
        String html = new SpaShell(new AdSense(""), "<head><script src=\"/t.js\"></script><!--app-head--></head><!--app-body-->")
                .render(HeadMeta.site("devlog", "d", "https://devlog.life/", null), "", null);
        assertThat(html).doesNotContain("nonce=").doesNotContain("adsbygoogle");
    }
}
