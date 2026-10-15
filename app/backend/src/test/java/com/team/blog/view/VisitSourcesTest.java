package com.team.blog.view;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.team.blog.view.application.VisitSources;
import com.team.blog.view.application.VisitSources.Source;

/** spec 070: 유입 경로 분류와 화면 주소 정리 */
class VisitSourcesTest {
    static final String SITE = "devlog.life";

    static String src(String referrer, String ua) {
        return VisitSources.classify(referrer, ua, SITE).source();
    }

    @Test
    void 검색_사이트를_나눈다() {
        assertThat(src("https://www.google.com/", ViewTest.CHROME)).isEqualTo("google");
        assertThat(src("https://www.google.co.kr/", ViewTest.CHROME)).isEqualTo("google");
        assertThat(src("android-app://com.google.android.googlequicksearchbox/", ViewTest.CHROME)).isEqualTo("google");
        assertThat(src("https://search.naver.com/search.naver?query=devlog", ViewTest.CHROME)).isEqualTo("naver");
        assertThat(src("https://m.search.naver.com/", ViewTest.CHROME)).isEqualTo("naver");
        assertThat(src("https://search.daum.net/search?q=x", ViewTest.CHROME)).isEqualTo("daum");
        assertThat(src("https://www.bing.com/", ViewTest.CHROME)).isEqualTo("bing");
        assertThat(VisitSources.SEARCH).contains("google", "naver", "daum", "bing");
    }

    @Test
    void SNS와_앱_안_브라우저를_나눈다() {
        assertThat(src("https://l.instagram.com/?u=x", ViewTest.CHROME)).isEqualTo("instagram");
        assertThat(src("https://t.co/abc", ViewTest.CHROME)).isEqualTo("x");
        assertThat(src("https://m.facebook.com/", ViewTest.CHROME)).isEqualTo("facebook");
        assertThat(src("https://youtu.be/x", ViewTest.CHROME)).isEqualTo("youtube");
        // 카카오톡 안에서 연 링크는 이전 주소가 비어 온다
        assertThat(src(null, "Mozilla/5.0 (iPhone) AppleWebKit KAKAOTALK 10.4.5")).isEqualTo("kakao");
        assertThat(src("", "Mozilla/5.0 Instagram 300.0")).isEqualTo("instagram");
    }

    @Test
    void 이전_주소가_없거나_이_사이트면_직접_들어온_것이고_나머지는_사이트_이름만_남긴다() {
        assertThat(src(null, ViewTest.CHROME)).isEqualTo(VisitSources.DIRECT);
        assertThat(src("https://devlog.life/@a/posts/1", ViewTest.CHROME)).isEqualTo(VisitSources.DIRECT);
        assertThat(src("javascript:alert(1)", ViewTest.CHROME)).isEqualTo(VisitSources.DIRECT);
        assertThat(src("not a url", ViewTest.CHROME)).isEqualTo(VisitSources.DIRECT);
        assertThat(VisitSources.classify("https://www.Example.com/a/b?secret=1", ViewTest.CHROME, SITE))
                .isEqualTo(new Source(VisitSources.OTHER, "example.com"));
    }

    @Test
    void 화면_주소는_정해_둔_모양만_남기고_검색어와_대소문자를_정리한다() {
        assertThat(VisitSources.page("/")).isEqualTo("/");
        assertThat(VisitSources.page("/@Kim/posts/12?ref=x#c")).isEqualTo("/@kim/posts/12");
        assertThat(VisitSources.page("/@kim/")).isEqualTo("/@kim");
        assertThat(VisitSources.page("/search?q=비밀")).isEqualTo("/search");
        assertThat(VisitSources.page("/tags/%EC%9E%90%EB%B0%94")).isEqualTo("/tags/%EC%9E%90%EB%B0%94");
        assertThat(VisitSources.page("/admin")).isNull();
        assertThat(VisitSources.page("/write/3")).isNull();
        assertThat(VisitSources.page("/reset-password?token=abc")).isNull();
        assertThat(VisitSources.page("/nope/../admin")).isNull();
        assertThat(VisitSources.page("https://evil.example/")).isNull();
        assertThat(VisitSources.page(null)).isNull();
    }

    @Test
    void 블로그_주소는_정해_둔_탭과_글_시리즈만_남긴다() {
        assertThat(VisitSources.page("/@kim")).isEqualTo("/@kim");
        assertThat(VisitSources.page("/@kim/series")).isEqualTo("/@kim/series");
        assertThat(VisitSources.page("/@kim/series/Spring")).isEqualTo("/@kim/series/Spring");
        assertThat(VisitSources.page("/@kim/about")).isEqualTo("/@kim/about");
        assertThat(VisitSources.page("/@kim/followers")).isEqualTo("/@kim/followers");
        assertThat(VisitSources.page("/@kim/following")).isEqualTo("/@kim/following");
        assertThat(VisitSources.page("/@kim/rss")).isEqualTo("/@kim/rss");
        assertThat(VisitSources.page("/@" + "a".repeat(40))).isEqualTo("/@" + "a".repeat(40));
        assertThat(VisitSources.page("/@" + "a".repeat(41))).isNull();
        assertThat(VisitSources.page("/@")).isNull();
        assertThat(VisitSources.page("/@kim/posts/0")).isNull();
        assertThat(VisitSources.page("/@kim/posts/012")).isNull();
        assertThat(VisitSources.page("/@kim/posts/123456789012345678")).isEqualTo("/@kim/posts/123456789012345678");
        assertThat(VisitSources.page("/@kim/posts/1234567890123456789")).isNull();
        assertThat(VisitSources.page("/@kim/series/a/b")).isNull();
        assertThat(VisitSources.page("/@kim/series/" + "y".repeat(121))).isNull();
        assertThat(VisitSources.page("/@kim/edit")).isNull();
        assertThat(VisitSources.page("/@ki m")).isNull();
    }

    @Test
    void 고정_화면과_태그_화면만_남긴다() {
        for (String p : new String[] {"/rss", "/search", "/mcp", "/support", "/releases", "/tags", "/login", "/signup", "/feed",
                "/terms", "/privacy", "/notifications", "/settings", "/lists/liked", "/manage/posts"}) {
            assertThat(VisitSources.page(p)).as(p).isEqualTo(p);
        }
        assertThat(VisitSources.page("/tags/" + "x".repeat(120))).isEqualTo("/tags/" + "x".repeat(120));
        assertThat(VisitSources.page("/tags/" + "x".repeat(121))).isNull();
        assertThat(VisitSources.page("/tags/a/b")).isNull();
        assertThat(VisitSources.page("/lists/read")).isNull();
        assertThat(VisitSources.page("//@kim")).isNull();
    }
}
