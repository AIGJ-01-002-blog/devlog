package com.team.blog.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** spec 103: 블로그 쇼츠 카드의 YouTube 썸네일과 재생 화면은 광고 없는 화면에서도 열린다. */
class ContentSecurityPolicyTest {
    private final ContentSecurityPolicy csp = new ContentSecurityPolicy("");

    @Test
    void 쇼츠_카드의_YouTube_썸네일과_재생_화면만_더_연다() {
        // 화면 사이 이동은 첫 문서의 CSP를 쓰므로 첫 화면(/)에서도 열려 있어야 한다
        for (String path : new String[] {"/", "/@kim/shorts", "/@kim/posts/1"}) {
            String policy = csp.policyFor(path);
            assertThat(policy).contains("img-src 'self' https://i.ytimg.com data: blob:")
                    .contains("frame-src https://www.youtube-nocookie.com;")
                    .contains("frame-ancestors 'none'")
                    .doesNotContain("frame-src https:;");
        }
    }
}
