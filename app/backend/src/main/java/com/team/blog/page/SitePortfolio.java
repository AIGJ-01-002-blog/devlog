package com.team.blog.page;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import tools.jackson.databind.json.JsonMapper;

/**
 * 사이트 포트폴리오 본문 (spec 107 FR-015). 채용 담당자가 한 장으로 읽는 사례 5개를 site/portfolio.html 한 곳에 두고,
 * 서버가 /portfolio 첫 화면에 넣고 React도 같은 HTML을 받아 그린다. 근거 글 주소의 {{owner}}는 운영자 블로그 주소로 바꾼다.
 * 운영자 주소가 비면 근거 링크를 글자로만 남긴다(없는 블로그로 잇지 않는다). 저장소에 든 문안이라 정화하지 않는다.
 * 인쇄물에서도 주소를 읽을 수 있게 사이트 안 링크는 절대 주소로 바꾸고(print CSS가 주소를 함께 찍는다),
 * {{links}} 자리에 GitHub·YouTube·jpop.today 링크 줄을, 끝에 같은 링크를 담은 schema.org Person을 붙인다. 링크 값이 비면 뺀다.
 */
@Component
public class SitePortfolio {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final String html;

    /** 링크 없이 문안만 쓰는 시험용 */
    public SitePortfolio(SiteOwner owner) {
        this(owner, "", new Links("", "", ""));
    }

    @Autowired
    public SitePortfolio(SiteOwner owner, @Value("${blog.site.base-url:}") String baseUrl,
                         @Value("${blog.site.portfolio.github-url:}") String github,
                         @Value("${blog.site.portfolio.youtube-url:}") String youtube,
                         @Value("${blog.site.portfolio.jpop-url:}") String jpop) {
        this(owner, baseUrl, new Links(github, youtube, jpop));
    }

    private SitePortfolio(SiteOwner owner, String baseUrl, Links links) {
        String raw = load("site/portfolio.html").replace("{{links}}", links.html());
        String body = owner.handle().map(h -> raw.replace("{{owner}}", h))
                .orElseGet(() -> raw.replaceAll("<a href=\"/@\\{\\{owner}}[^\"]*\">([^<]*)</a>", "$1"));
        String base = baseUrl == null ? "" : baseUrl.strip().replaceAll("/+$", "");
        if (!base.isEmpty()) body = body.replace("href=\"/", "href=\"" + base + "/");
        this.html = body + links.personJsonLd(owner.handle().orElse(null), base);
    }

    public String html() {
        return html;
    }

    private static String load(String path) {
        try (InputStream in = new ClassPathResource(path).getInputStream()) {
            // 맨 앞의 안내 주석은 화면에 필요 없다
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).replaceFirst("(?s)^<!--.*?-->\\s*", "");
        } catch (IOException e) {
            throw new UncheckedIOException("포트폴리오 문안을 읽지 못했어요: " + path, e);
        }
    }

    /** 운영 설정에서 오는 바깥 링크. 비어 있는 값은 화면과 구조화 데이터 모두에서 뺀다 */
    private record Links(String github, String youtube, String jpop) {
        private List<String[]> present() {
            List<String[]> out = new ArrayList<>();
            for (String[] l : new String[][] {{"GitHub", github}, {"YouTube", youtube}, {"jpop.today", jpop}}) {
                // 운영 설정 값이라도 http·https 절대 주소만 싣는다(스킴 없는 값은 상대 경로가 되고, javascript: 등은 막는다)
                if (l[1] != null && l[1].strip().matches("(?i)https?://\\S+")) out.add(new String[] {l[0], l[1].strip()});
            }
            return out;
        }

        String html() {
            List<String[]> ls = present();
            if (ls.isEmpty()) return "";
            StringBuilder p = new StringBuilder("<p class=\"portfolio-links\"><b>링크</b> ");
            for (int i = 0; i < ls.size(); i++) {
                if (i > 0) p.append(" · ");
                p.append("<a href=\"").append(SpaShell.esc(ls.get(i)[1])).append("\" rel=\"me noopener\">")
                        .append(SpaShell.esc(ls.get(i)[0])).append("</a>");
            }
            return p.append("</p>").toString();
        }

        /**
         * schema.org Person: 이름은 실명 대신 블로그 주소(handle)를 쓴다. 실행되지 않는 데이터 블록이라 CSP nonce가 필요 없고,
         * SpaShell과 같이 <, >, &를 유니코드 이스케이프로 바꿔 </script> 탈출을 막는다.
         */
        String personJsonLd(String handle, String base) {
            List<String[]> ls = present();
            if (handle == null && ls.isEmpty()) return "";
            Map<String, Object> ld = new LinkedHashMap<>(); // Map.of는 순서가 실행마다 달라 출력이 흔들린다
            ld.put("@context", "https://schema.org");
            ld.put("@type", "Person");
            ld.put("name", handle != null ? handle : "devlog 운영자");
            ld.put("jobTitle", "백엔드 개발자");
            if (handle != null && !base.isEmpty()) ld.put("url", base + "/@" + handle);
            if (!ls.isEmpty()) ld.put("sameAs", ls.stream().map(l -> l[1]).toList());
            String data = JSON.writeValueAsString(ld).replace("<", "\\u003c").replace(">", "\\u003e").replace("&", "\\u0026");
            return "\n<script type=\"application/ld+json\">" + data + "</script>";
        }
    }
}
