package com.team.blog.page;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.util.HtmlUtils;

import com.team.blog.config.ContentSecurityPolicy;

import tools.jackson.databind.json.JsonMapper;

/**
 * React 앱의 index.html에 페이지별 머리말(제목·설명·정규 주소·OG)과 첫 화면 HTML, 초기 데이터를 넣는다.
 * 인라인 스크립트는 넣지 않는다(CSP script-src 'self'): 초기 데이터는 실행되지 않는 application/json 블록이다.
 * 프런트 빌드가 없으면(백엔드만 실행·테스트) 최소 껍데기를 쓴다.
 * 검색에 노출하는 공개 화면에는 애드센스 코드를 넣고, 화면의 모든 스크립트에 요청마다 새 nonce를 붙인다 (spec 076).
 * 로그인·글쓰기·설정·관리처럼 noindex인 화면에는 광고를 싣지 않는다.
 */
@Component
public class SpaShell {
    static final String HEAD_MARK = "<!--app-head-->";
    static final String BODY_MARK = "<!--app-body-->";
    /** 속성값을 닫고 머리 태그를 끝내는 꼬리 */
    private static final String TAG_END = "\">\n";
    private static final String FALLBACK = """
            <!doctype html>
            <html lang="ko">
            <head>
            <meta charset="utf-8">
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <!--app-head-->
            </head>
            <body>
            <div id="root"><!--app-body--></div>
            </body>
            </html>
            """;

    private static final SecureRandom RANDOM = new SecureRandom();

    private final String template;
    private final AdSense adSense;
    private final JsonMapper json = JsonMapper.builder().build();

    @org.springframework.beans.factory.annotation.Autowired
    public SpaShell(AdSense adSense) {
        this(adSense, load());
    }

    SpaShell(AdSense adSense, String template) {
        this.template = template;
        this.adSense = adSense;
    }

    private static String load() {
        ClassPathResource built = new ClassPathResource("static/index.html");
        if (!built.exists()) return FALLBACK;
        try (InputStream in = built.getInputStream()) {
            String html = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            // 빌드된 index.html에 표시가 없으면 넣을 자리를 만든다. 원래 <title>은 페이지별 제목으로 바뀐다
            html = html.replaceFirst("(?is)<title>.*?</title>", "");
            if (!html.contains(HEAD_MARK)) html = html.replaceFirst("(?i)</head>", HEAD_MARK + "\n</head>");
            if (!html.contains(BODY_MARK)) html = html.replaceFirst("<div id=\"root\"></div>", "<div id=\"root\">" + BODY_MARK + "</div>");
            return html;
        } catch (IOException e) {
            return FALLBACK;
        }
    }

    public String render(HeadMeta meta, String bodyHtml, Object initialData) {
        return render(meta, bodyHtml, initialData, "/rss");
    }

    /** @param rssPath 이 화면의 RSS 주소 (026). 수집하는 화면에만 붙인다 */
    public String render(HeadMeta meta, String bodyHtml, Object initialData, String rssPath) {
        StringBuilder head = new StringBuilder();
        String page = template;
        String nonce = meta.indexable() && adSense.enabled() ? adNonce() : null;
        if (nonce != null) {
            // 'strict-dynamic'은 nonce가 없는 스크립트를 막으므로 앱 자신의 스크립트·모듈 미리 불러오기에도 붙인다
            page = page.replace("<script", "<script nonce=\"" + nonce + "\"")
                    .replace("<link rel=\"modulepreload\"", "<link rel=\"modulepreload\" nonce=\"" + nonce + "\"");
            head.append(adSense.scriptTag(nonce));
        }
        head.append("<title>").append(esc(meta.title())).append("</title>\n");
        if (meta.description() != null && !meta.description().isBlank()) {
            head.append("<meta name=\"description\" content=\"").append(esc(meta.description())).append(TAG_END);
        }
        if (!meta.indexable()) head.append("<meta name=\"robots\" content=\"noindex, nofollow\">\n");
        if (meta.indexable() && meta.canonical() != null) {
            head.append("<link rel=\"canonical\" href=\"").append(esc(meta.canonical())).append(TAG_END);
            if (rssPath != null) {
                head.append("<link rel=\"alternate\" type=\"application/rss+xml\" title=\"RSS\" href=\"").append(esc(rssPath)).append(TAG_END);
            }
            head.append("<meta property=\"og:type\" content=\"").append(esc(meta.ogType())).append(TAG_END);
            head.append("<meta property=\"og:url\" content=\"").append(esc(meta.canonical())).append(TAG_END);
            head.append("<meta property=\"og:title\" content=\"").append(esc(meta.title())).append(TAG_END);
            if (meta.description() != null && !meta.description().isBlank()) {
                head.append("<meta property=\"og:description\" content=\"").append(esc(meta.description())).append(TAG_END);
            }
            if (meta.ogImage() != null) head.append("<meta property=\"og:image\" content=\"").append(esc(meta.ogImage())).append(TAG_END);
            if (meta.publishedTime() != null) {
                head.append("<meta property=\"article:published_time\" content=\"").append(meta.publishedTime()).append(TAG_END);
            }
            if (meta.modifiedTime() != null) {
                head.append("<meta property=\"article:modified_time\" content=\"").append(meta.modifiedTime()).append(TAG_END);
            }
            // X(트위터)·슬랙 등은 twitter:card가 있어야 큰 미리보기를 그린다. 제목·사진은 og 값을 읽는다 (spec 097)
            head.append("<meta name=\"twitter:card\" content=\"").append(meta.ogImage() != null ? "summary_large_image" : "summary").append(TAG_END);
            if ("article".equals(meta.ogType())) head.append(articleJsonLd(meta));
        }
        if (initialData != null) {
            // </script> 탈출을 막기 위해 <, >, &를 유니코드 이스케이프로 바꾼다 (JSON 문자열 안에서만 나타나므로 의미는 같다)
            String data = json.writeValueAsString(initialData)
                    .replace("<", "\\u003c").replace(">", "\\u003e").replace("&", "\\u0026");
            head.append("<script id=\"initial-data\" type=\"application/json\">").append(data).append("</script>\n");
        }
        return page.replace(HEAD_MARK, head.toString()).replace(BODY_MARK, bodyHtml == null ? "" : bodyHtml);
    }

    /**
     * 글 화면의 구조화 데이터(schema.org Article). 실행되지 않는 데이터 블록이라 CSP nonce가 필요 없다.
     * initial-data와 같이 <, >, &를 유니코드 이스케이프로 바꿔 </script> 탈출을 막는다.
     */
    private String articleJsonLd(HeadMeta meta) {
        Map<String, Object> ld = new LinkedHashMap<>();
        ld.put("@context", "https://schema.org");
        ld.put("@type", "BlogPosting");
        ld.put("headline", meta.title());
        if (meta.description() != null && !meta.description().isBlank()) ld.put("description", meta.description());
        ld.put("url", meta.canonical());
        ld.put("mainEntityOfPage", meta.canonical());
        if (meta.ogImage() != null) ld.put("image", meta.ogImage());
        if (meta.publishedTime() != null) ld.put("datePublished", meta.publishedTime().toString());
        Instant modified = meta.modifiedTime() != null ? meta.modifiedTime() : meta.publishedTime();
        if (modified != null) ld.put("dateModified", modified.toString());
        if (meta.author() != null) ld.put("author", Map.of("@type", "Person", "name", meta.author()));
        String data = json.writeValueAsString(ld).replace("<", "\\u003c").replace(">", "\\u003e").replace("&", "\\u0026");
        return "<script type=\"application/ld+json\">" + data + "</script>\n";
    }

    /** 요청에 nonce를 남겨 CSP 헤더가 같은 값을 쓰게 한다. 요청 밖(직접 호출)이면 광고를 넣지 않는다 */
    private static String adNonce() {
        RequestAttributes request = RequestContextHolder.getRequestAttributes();
        if (request == null) return null;
        byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        String nonce = Base64.getEncoder().encodeToString(bytes);
        request.setAttribute(ContentSecurityPolicy.AD_NONCE_ATTRIBUTE, nonce, RequestAttributes.SCOPE_REQUEST);
        return nonce;
    }

    public static String esc(String s) {
        return s == null ? "" : HtmlUtils.htmlEscape(s, "UTF-8");
    }
}
