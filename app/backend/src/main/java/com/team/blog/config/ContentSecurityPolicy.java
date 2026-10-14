package com.team.blog.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.web.header.HeaderWriter;
import org.springframework.stereotype.Component;

/**
 * 본문 정화의 2차 방어 (docs/12 §8, 헌법 III). 저장소 공개 주소는 정화 허용 목록과 같은 설정값을 쓴다.
 * 소셜 가입 마무리 화면에만 Google·GitHub·카카오·Facebook·네이버 사진 호스트를 img-src에 더한다 (화면별 CSP, 2026-10-07 H3).
 * 광고를 싣는 공개 화면(SpaShell이 요청에 nonce를 남긴 화면)은 애드센스가 지원하는 엄격한 CSP로 바꾼다 (spec 076).
 * 애드센스는 쓰는 도메인이 수시로 바뀌어 허용 목록 방식을 지원하지 않고, nonce + 'strict-dynamic'만 지원한다.
 */
@Component
public class ContentSecurityPolicy implements HeaderWriter {
    /** SpaShell이 광고 코드를 넣은 화면에 남기는 요청 속성. 이 값이 있으면 광고용 정책을 쓴다 */
    public static final String AD_NONCE_ATTRIBUTE = ContentSecurityPolicy.class.getName() + ".adNonce";
    static final String SOCIAL_AVATAR_HOSTS = "https://avatars.githubusercontent.com https://lh3.googleusercontent.com https://k.kakaocdn.net https://platform-lookaside.fbsbx.com https://phinf.pstatic.net";
    private final String storageOrigin;

    public ContentSecurityPolicy(@Value("${blog.image.public-base-url:}") String publicBaseUrl) {
        this.storageOrigin = origin(publicBaseUrl);
    }

    @Override
    public void writeHeaders(HttpServletRequest request, HttpServletResponse response) {
        if (response.containsHeader("Content-Security-Policy")) return;
        Object nonce = request.getAttribute(AD_NONCE_ATTRIBUTE);
        response.setHeader("Content-Security-Policy",
                nonce instanceof String n ? adPolicy(n) : policyFor(request.getRequestURI()));
    }

    /**
     * 애드센스 도움말의 엄격한 CSP를 따른다. nonce와 'strict-dynamic'을 아는 브라우저는 https:·'unsafe-inline'을 무시하므로
     * 스크립트는 nonce가 붙은 것과 그것이 불러온 것만 돈다. 광고는 여러 구글 도메인의 iframe·사진·요청을 쓰므로 그 셋은 https:로 연다.
     * 글 본문은 서버 정화가 1차 방어라 img-src를 넓혀도 정화 허용 목록 밖 사진은 본문에 들어오지 않는다.
     */
    public String adPolicy(String nonce) {
        String storage = storageOrigin.isEmpty() ? "" : " " + storageOrigin;
        return "default-src 'self'; script-src 'nonce-" + nonce + "' 'strict-dynamic' 'unsafe-inline' 'unsafe-eval' https: http:"
                + "; connect-src 'self'" + storage + " https:; img-src 'self'" + storage + " https: data: blob:"
                + "; media-src 'self'" + storage + " blob:; frame-src https:; style-src 'self' 'unsafe-inline'; font-src 'self' data: https:; object-src 'none'"
                + "; frame-ancestors 'none'; base-uri 'none'; form-action 'self'";
    }

    public String policyFor(String path) {
        String storage = storageOrigin.isEmpty() ? "" : " " + storageOrigin;
        String imgExtra = "/signup/social".equals(path) ? " " + SOCIAL_AVATAR_HOSTS : "";
        return "default-src 'self'; script-src 'self'; connect-src 'self'" + storage
                + "; img-src 'self'" + storage + imgExtra + " data: blob:"
                + "; media-src 'self'" + storage + " blob:; style-src 'self' 'unsafe-inline'; font-src 'self' data:; object-src 'none'; frame-ancestors 'none'"
                + "; base-uri 'none'; form-action 'self'";
    }

    static String origin(String url) {
        if (url == null || url.isBlank()) return "";
        try {
            java.net.URI u = java.net.URI.create(url.strip());
            if (u.getScheme() == null || u.getHost() == null) return "";
            return u.getScheme() + "://" + u.getHost() + (u.getPort() > 0 ? ":" + u.getPort() : "");
        } catch (IllegalArgumentException e) {
            return "";
        }
    }
}
