package com.team.blog.account.application;

import java.net.URI;
import java.util.Locale;
import java.util.Set;

/**
 * 가입 마무리 화면에 넘길 소셜 사진 주소 (005 FR-019). Google·GitHub·카카오·Facebook·네이버 사진 서버의 https 주소만 넘기고 나머지는 버린다.
 * 서버는 이 주소로 요청하지 않고 저장하지도 않는다(FR-020). 브라우저가 받아 256×256으로 바꿔 직접 올린다.
 * 호스트 목록은 가입 마무리 화면 CSP(img-src)와 같아야 한다.
 */
public final class SocialAvatar {
    static final Set<String> HOSTS = Set.of("avatars.githubusercontent.com", "lh3.googleusercontent.com", "k.kakaocdn.net",
            "platform-lookaside.fbsbx.com", "phinf.pstatic.net");

    private SocialAvatar() {}

    public static String safeUrl(String raw) {
        if (raw == null || raw.isBlank() || raw.length() > 2048) return null;
        try {
            URI u = new URI(raw.strip());
            if (!"https".equalsIgnoreCase(u.getScheme()) || u.getRawUserInfo() != null || u.getPort() != -1) return null;
            String host = u.getHost();
            if (host == null || !HOSTS.contains(host.toLowerCase(Locale.ROOT))) return null;
            return u.toASCIIString();
        } catch (java.net.URISyntaxException e) {
            return null;
        }
    }
}
