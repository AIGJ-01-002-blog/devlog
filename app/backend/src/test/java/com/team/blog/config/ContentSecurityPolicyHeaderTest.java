package com.team.blog.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import org.junit.jupiter.api.Test;

import com.team.blog.support.IntegrationTest;

/** spec 103: 광고 없는 화면의 실제 응답 헤더(보안 필터 체인을 거친 것)에도 쇼츠 카드용 출처가 들어간다. */
class ContentSecurityPolicyHeaderTest extends IntegrationTest {

    @Test
    void 광고_없는_화면_응답의_CSP가_YouTube_썸네일과_재생_화면을_연다() throws Exception {
        String policy = mvc.perform(get("/settings")).andReturn().getResponse().getHeader("Content-Security-Policy");

        assertThat(policy).contains("img-src 'self' https://i.ytimg.com data: blob:")
                .contains("frame-src https://www.youtube-nocookie.com;")
                .contains("frame-ancestors 'none'")
                .doesNotContain("frame-src https:;");
    }
}
