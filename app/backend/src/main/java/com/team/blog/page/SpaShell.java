package com.team.blog.page;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

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
 * 광고 스위치가 adsense면 글 화면에 애드센스 코드를 넣고, 화면의 모든 스크립트에 요청마다 새 nonce를 붙인다 (spec 076, 107).
 * 로그인·글쓰기·설정·관리처럼 noindex인 화면에는 광고를 싣지 않는다.
 */
@Component
public class SpaShell {
    static final String HEAD_MARK = "<!--app-head-->";
    static final String BODY_MARK = "<!--app-body-->";
    /** 속성값을 닫고 머리 태그를 끝내는 꼬리 */
    private static final String TAG_END = "\">\n";
    /**
     * 수집하는 화면의 서버 HTML 끝에 붙이는 사이트 바닥글 (spec 104 FR-005). 스크립트를 돌리지 않는 심사 도구·검색 로봇도
     * 소개·약관·처리방침·문의로 갈 수 있게 한다. React가 뜨면 루트를 다시 그리고 SiteFooter가 같은 링크를 보여 준다.
     */
    static final String SITE_FOOTER = "<footer><nav aria-label=\"사이트 정보\"><a href=\"/about\">소개</a> · <a href=\"/portfolio\">포트폴리오</a> · "
            + "<a href=\"/contact\">연락처</a> · <a href=\"/terms\">이용약관</a> · <a href=\"/privacy\">개인정보 처리방침</a> · "
            + "<a href=\"/support\">문의</a> · <a href=\"/releases\">릴리스 노트</a></nav></footer>";
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
    private static final String ARTICLE = "article";
    private final AdSense adSense;
    /** 글 정규 주소: 블로그 주소(…/@handle) + /posts/번호 */
    private static final Pattern POST_URL = Pattern.compile("(https?://[^/]+/@[A-Za-z0-9_-]+)/posts/\\d+");
    /** 검색 엔진 소유 확인 메타 (spec 107). 시험용 생성자는 비워 둔다 */
    private String verificationMeta = "";
    private final JsonMapper json = JsonMapper.builder().build();

    @org.springframework.beans.factory.annotation.Autowired
    public SpaShell(AdSense adSense, SiteVerification verification) {
        this(adSense, load());
        this.verificationMeta = verification.meta();
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
        // 광고는 글 화면에만 싣는다. 홈·목록·프로필은 글 카드뿐인 화면이라 광고를 빼고 소유 확인 메타만 둔다 (spec 106)
        String nonce = meta.indexable() && ARTICLE.equals(meta.ogType()) && adSense.enabled() ? adNonce() : null;
        if (meta.indexable() && adSense.configured()) head.append(adSense.accountMeta());
        if (nonce != null) {
            // 'strict-dynamic'은 nonce가 없는 스크립트를 막으므로 앱 자신의 스크립트·모듈 미리 불러오기에도 붙인다
            page = page.replace("<script", "<script nonce=\"" + nonce + "\"")
                    .replace("<link rel=\"modulepreload\"", "<link rel=\"modulepreload\" nonce=\"" + nonce + "\"");
            head.append(adSense.scriptTag(nonce)).append(adSense.slotMeta());
        }
        head.append(verificationMeta);
        head.append("<title>").append(esc(meta.title())).append("</title>\n");
        if (meta.description() != null && !meta.description().isBlank()) {
            head.append("<meta name=\"description\" content=\"").append(esc(meta.description())).append(TAG_END);
        }
        if (!meta.indexable()) head.append("<meta name=\"robots\" content=\"").append(meta.follow() ? "noindex, follow" : "noindex, nofollow").append(TAG_END);
        // noindex, follow인 공개 화면(일기·짧은 글 등)도 정규 주소와 링크 미리보기는 둔다. RSS 알림은 수집하는 화면에만 (spec 107)
        boolean readable = meta.indexable() || meta.follow();
        if (readable && meta.canonical() != null) appendShareMeta(head, meta, meta.indexable() ? rssPath : null);
        if (initialData != null) {
            // </script> 탈출을 막기 위해 <, >, &를 유니코드 이스케이프로 바꾼다 (JSON 문자열 안에서만 나타나므로 의미는 같다)
            String data = json.writeValueAsString(initialData)
                    .replace("<", "\\u003c").replace(">", "\\u003e").replace("&", "\\u0026");
            head.append("<script id=\"initial-data\" type=\"application/json\">").append(data).append("</script>\n");
        }
        String body = bodyHtml == null ? "" : bodyHtml;
        if (readable) body += SITE_FOOTER;
        return page.replace(HEAD_MARK, head.toString()).replace(BODY_MARK, body);
    }

    /** 수집하는 화면의 정규 주소·RSS·링크 미리보기(og·twitter) 머리말 */
    private void appendShareMeta(StringBuilder head, HeadMeta meta, String rssPath) {
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
        if (meta.author() != null) {
            Map<String, Object> author = new LinkedHashMap<>(); // Map.of는 순서가 실행마다 달라 출력이 흔들린다
            author.put("@type", "Person");
            author.put("name", meta.author());
            String blog = blogUrl(meta.canonical());
            if (blog != null) author.put("url", blog);
            ld.put("author", author);
        }
        return jsonLd(ld) + breadcrumbJsonLd(meta);
    }

    /**
     * 글 화면의 이동 경로(schema.org BreadcrumbList, spec 107 FR-019): 홈 → 블로그 → 글. 블로그 주소는 글 정규 주소(/@handle/posts/id)에서 읽는다.
     * 정규 주소가 그 꼴이 아니면 넣지 않는다.
     */
    private String breadcrumbJsonLd(HeadMeta meta) {
        String blog = blogUrl(meta.canonical());
        if (blog == null) return "";
        String home = blog.substring(0, blog.indexOf("/@")) + "/";
        Map<String, Object> ld = new LinkedHashMap<>();
        ld.put("@context", "https://schema.org");
        ld.put("@type", "BreadcrumbList");
        ld.put("itemListElement", List.of(crumb(1, "devlog", home),
                crumb(2, meta.author() != null ? meta.author() : blog.substring(blog.indexOf("/@") + 1), blog),
                crumb(3, postName(meta), meta.canonical())));
        return jsonLd(ld);
    }

    /** 글 화면 제목은 "글 제목 - 작성자"라서 이동 경로에는 글 제목만 쓴다 */
    private static String postName(HeadMeta meta) {
        String suffix = meta.author() == null ? null : " - " + meta.author();
        return suffix != null && meta.title().endsWith(suffix) ? meta.title().substring(0, meta.title().length() - suffix.length()) : meta.title();
    }

    private static Map<String, Object> crumb(int position, String name, String url) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("@type", "ListItem");
        item.put("position", position);
        item.put("name", name);
        item.put("item", url);
        return item;
    }

    /** 글 정규 주소에서 블로그 주소(…/@handle)를 꺼낸다. 글 주소 꼴이 아니면 null */
    private static String blogUrl(String canonical) {
        if (canonical == null) return null;
        var m = POST_URL.matcher(canonical);
        return m.matches() ? m.group(1) : null;
    }

    private String jsonLd(Map<String, Object> ld) {
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
