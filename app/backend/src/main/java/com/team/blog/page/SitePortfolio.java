package com.team.blog.page;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * 사이트 포트폴리오 본문 (spec 107 FR-015). 채용 담당자가 한 장으로 읽는 사례 5개를 site/portfolio.html 한 곳에 두고,
 * 서버가 /portfolio 첫 화면에 넣고 React도 같은 HTML을 받아 그린다. 근거 글 주소의 {{owner}}는 운영자 블로그 주소로 바꾼다.
 * 운영자 주소가 비면 근거 링크를 글자로만 남긴다(없는 블로그로 잇지 않는다). 저장소에 든 문안이라 정화하지 않는다.
 */
@Component
public class SitePortfolio {
    private final String html;

    public SitePortfolio(SiteOwner owner) {
        String raw = load("site/portfolio.html");
        this.html = owner.handle().map(h -> raw.replace("{{owner}}", h))
                .orElseGet(() -> raw.replaceAll("<a href=\"/@\\{\\{owner}}[^\"]*\">([^<]*)</a>", "$1"));
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
}
