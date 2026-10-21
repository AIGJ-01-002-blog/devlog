package com.team.blog.page;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 검색 엔진 소유 확인 메타 (spec 107 FR-020). 구글 서치 콘솔·네이버 서치어드바이저가 주는 값으로, 페이지 소스에 보이는 공개 값이다(비밀값 아님).
 * 예전에는 index.html에 네이버 값을 적어 두었는데, 운영자가 빌드 없이 바꾸도록 실행 설정으로 옮겼다. 비우면 그 메타를 넣지 않는다.
 */
@Component
public class SiteVerification {
    private final String meta;

    public SiteVerification(@Value("${blog.seo.google-site-verification:}") String google,
                            @Value("${blog.seo.naver-site-verification:}") String naver) {
        this.meta = tag("google-site-verification", google) + tag("naver-site-verification", naver);
    }

    /** 머리말에 넣을 메타 태그들. 설정이 둘 다 비면 빈 문자열 */
    public String meta() {
        return meta;
    }

    private static String tag(String name, String value) {
        if (value == null || value.isBlank()) return "";
        return "<meta name=\"" + name + "\" content=\"" + SpaShell.esc(value.strip()) + "\">\n";
    }
}
