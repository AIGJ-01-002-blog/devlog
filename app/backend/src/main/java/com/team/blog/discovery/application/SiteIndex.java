package com.team.blog.discovery.application;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.web.util.HtmlUtils;
import org.springframework.web.util.UriUtils;

import com.team.blog.post.query.PostCardQuery;
import com.team.blog.series.application.SeriesQuery;
import com.team.blog.shared.config.BlogProperties;
import com.team.blog.shared.text.TextCleaner;

/**
 * 검색 엔진용 robots.txt와 sitemap.xml. 구글 서치 콘솔·네이버 서치어드바이저에 제출한다.
 * 사이트맵에는 공개 목록과 같은 조건의 글만 담아, 친구 공개·비공개·숨김 글과 탈퇴 회원은 나오지 않는다.
 * /sitemap.xml은 사이트맵 색인이고 글·시리즈·고정 화면을 세 파일로 나눈다(spec 107 FR-021). 제출한 주소는 그대로다.
 */
@Service
public class SiteIndex {
    /** 사이트맵 한 파일의 한도(50,000)에서 첫 화면·고정 화면 몫을 남긴다. 넘으면 사이트맵 색인으로 나눈다 */
    static final int MAX_POSTS = 40_000;
    /**
     * 로그인해야 보이거나 검색 결과에 나올 이유가 없는 화면. 쇼츠 스튜디오·자동매매·모의투자·포인트처럼 운영자나 회원 본인만 쓰는 화면도
     * 막는다(spec 107, 전에는 비로그인 404에만 기댔다). 공개 글·목록·소개·태그·검색 결과는 열어 두고 noindex 메타로 수집만 고른다.
     */
    private static final List<String> DISALLOW = List.of("/api/", "/write", "/settings", "/manage/", "/admin",
            "/notifications", "/feed", "/lists/", "/login", "/signup", "/forgot-password", "/reset-password", "/verify-email",
            "/studio", "/trade", "/trades", "/paper", "/points", "/shop", "/dashboard", "/account/", "/agreements", "/oauth/", "/withdrawn");

    /** 사이트맵 색인이 가리키는 세 파일. 컨트롤러가 같은 이름으로 연다 */
    public enum Part { POSTS, SERIES, PAGES }

    /** 고정 화면: 소개(077)·포트폴리오·연락처(107)·처리방침·약관(085)·AI 연결 안내·릴리스 노트(090) */
    private static final List<String> PAGES = List.of("/about", "/portfolio", "/contact", "/privacy", "/terms", "/mcp", "/releases");

    private final PostCardQuery posts;
    private final SeriesQuery series;
    private final BlogProperties.Site site;

    public SiteIndex(PostCardQuery posts, SeriesQuery series, BlogProperties props) {
        this.posts = posts;
        this.series = series;
        this.site = props.site();
    }

    public String robots() {
        StringBuilder txt = new StringBuilder("User-agent: *\n");
        for (String path : DISALLOW) txt.append("Disallow: ").append(path).append('\n');
        return txt.append("\nSitemap: ").append(absolute("/sitemap.xml")).append('\n').toString();
    }

    /** 사이트맵 색인: 세 파일의 주소. 글 파일의 lastmod는 가장 최근 발행 시각이다 */
    public String sitemap() {
        List<PostCardQuery.SitemapEntry> entries = posts.sitemapEntries(1);
        Instant newest = entries.isEmpty() ? null : entries.get(0).publishedAt();
        StringBuilder xml = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
                .append("<sitemapindex xmlns=\"http://www.sitemaps.org/schemas/sitemap/0.9\">\n");
        for (Part part : Part.values()) {
            xml.append("<sitemap><loc>").append(HtmlUtils.htmlEscape(absolute(partPath(part)), "UTF-8")).append("</loc>");
            if (part == Part.POSTS && newest != null) xml.append("<lastmod>").append(iso(newest)).append("</lastmod>");
            xml.append("</sitemap>\n");
        }
        return xml.append("</sitemapindex>\n").toString();
    }

    public static String partPath(Part part) {
        return "/sitemaps/" + part.name().toLowerCase() + ".xml";
    }

    /** 사이트맵 한 파일. 블로그 첫 화면(글 카드 목록)은 넣지 않고 글 본문과 시리즈 목차만 알린다 (spec 106, 107) */
    public String sitemap(Part part) {
        StringBuilder xml = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
                .append("<urlset xmlns=\"http://www.sitemaps.org/schemas/sitemap/0.9\">\n");
        switch (part) {
            case POSTS -> posts.sitemapEntries(MAX_POSTS)
                    .forEach(e -> url(xml, "/@" + e.handle() + "/posts/" + e.postId(), e.publishedAt()));
            case SERIES -> series.sitemapEntries(MAX_POSTS).forEach(e -> url(xml, "/@" + e.handle() + "/series/"
                    + UriUtils.encodePathSegment(e.slug(), StandardCharsets.UTF_8), e.lastmod()));
            case PAGES -> {
                List<PostCardQuery.SitemapEntry> newest = posts.sitemapEntries(1);
                url(xml, "/", newest.isEmpty() ? null : newest.get(0).publishedAt());
                PAGES.forEach(path -> url(xml, path, null));
            }
        }
        return xml.append("</urlset>\n").toString();
    }

    private void url(StringBuilder xml, String path, Instant lastmod) {
        xml.append("<url><loc>").append(HtmlUtils.htmlEscape(absolute(path), "UTF-8")).append("</loc>");
        if (lastmod != null) xml.append("<lastmod>").append(iso(lastmod)).append("</lastmod>");
        xml.append("</url>\n");
    }

    private static String iso(Instant t) {
        return DateTimeFormatter.ISO_INSTANT.format(t.truncatedTo(ChronoUnit.SECONDS));
    }

    private String absolute(String path) {
        String base = site.baseUrl() == null ? "" : TextCleaner.trimEnd(site.baseUrl(), '/');
        return base + path;
    }
}
