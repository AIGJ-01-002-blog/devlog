package com.team.blog.discovery.application;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.web.util.HtmlUtils;

import com.team.blog.post.query.PostCardQuery;
import com.team.blog.shared.config.BlogProperties;
import com.team.blog.shared.text.TextCleaner;

/**
 * 검색 엔진용 robots.txt와 sitemap.xml. 구글 서치 콘솔·네이버 서치어드바이저에 제출한다.
 * 사이트맵에는 공개 목록과 같은 조건의 글만 담아, 친구 공개·비공개·숨김 글과 탈퇴 회원은 나오지 않는다.
 */
@Service
public class SiteIndex {
    /** 사이트맵 한 파일의 한도(50,000)에서 첫 화면·고정 화면 몫을 남긴다. 넘으면 사이트맵 색인으로 나눈다 */
    static final int MAX_POSTS = 40_000;
    /** 로그인해야 보이거나 검색 결과에 나올 이유가 없는 화면 */
    private static final List<String> DISALLOW = List.of("/api/", "/write", "/settings", "/manage/", "/admin",
            "/notifications", "/feed", "/lists/", "/login", "/signup", "/forgot-password", "/reset-password", "/verify-email");

    private final PostCardQuery posts;
    private final BlogProperties.Site site;

    public SiteIndex(PostCardQuery posts, BlogProperties props) {
        this.posts = posts;
        this.site = props.site();
    }

    public String robots() {
        StringBuilder txt = new StringBuilder("User-agent: *\n");
        for (String path : DISALLOW) txt.append("Disallow: ").append(path).append('\n');
        return txt.append("\nSitemap: ").append(absolute("/sitemap.xml")).append('\n').toString();
    }

    public String sitemap() {
        List<PostCardQuery.SitemapEntry> entries = posts.sitemapEntries(MAX_POSTS);
        Instant newest = null;
        for (var e : entries) {
            if (newest == null || e.publishedAt().isAfter(newest)) newest = e.publishedAt();
        }
        StringBuilder xml = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
                .append("<urlset xmlns=\"http://www.sitemaps.org/schemas/sitemap/0.9\">\n");
        url(xml, "/", newest);
        url(xml, "/about", null); // 사이트 소개 (077)
        url(xml, "/privacy", null); // 개인정보 처리방침·이용약관 (085)
        url(xml, "/terms", null);
        url(xml, "/mcp", null); // AI 연결 안내·릴리스 노트: 수집을 여는 화면인데 사이트맵에 빠져 있었다 (spec 090)
        url(xml, "/releases", null);
        // 블로그 첫 화면(글 카드 목록)은 넣지 않는다. 글 본문만 사이트맵으로 알린다 (spec 106)
        for (var e : entries) url(xml, "/@" + e.handle() + "/posts/" + e.postId(), e.publishedAt());
        return xml.append("</urlset>\n").toString();
    }

    private void url(StringBuilder xml, String path, Instant lastmod) {
        xml.append("<url><loc>").append(HtmlUtils.htmlEscape(absolute(path), "UTF-8")).append("</loc>");
        if (lastmod != null) xml.append("<lastmod>").append(DateTimeFormatter.ISO_INSTANT.format(lastmod.truncatedTo(ChronoUnit.SECONDS))).append("</lastmod>");
        xml.append("</url>\n");
    }

    private String absolute(String path) {
        String base = site.baseUrl() == null ? "" : TextCleaner.trimEnd(site.baseUrl(), '/');
        return base + path;
    }
}
