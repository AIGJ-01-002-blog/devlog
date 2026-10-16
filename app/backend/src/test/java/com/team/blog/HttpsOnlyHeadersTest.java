package com.team.blog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

import com.team.blog.support.IntegrationTest;

/**
 * 운영(COOKIE_SECURE=true)은 앞단이 요청을 http로 넘겨도(X-Forwarded-Proto 덮어씀) HSTS와 CSRF 쿠키의 Secure를 붙인다.
 * 요청은 일부러 http로 보낸다.
 */
@TestPropertySource(properties = "server.servlet.session.cookie.secure=true")
class HttpsOnlyHeadersTest extends IntegrationTest {
    @Test
    void http로_와도_HSTS와_보안_쿠키를_쓴다() throws Exception {
        var res = mvc.perform(get("/api/auth/me"))
                .andExpect(status().isOk())
                .andExpect(header().string("Strict-Transport-Security", "max-age=31536000"))
                .andReturn().getResponse();
        jakarta.servlet.http.Cookie xsrf = res.getCookie("XSRF-TOKEN");
        assertThat(xsrf).isNotNull();
        assertThat(xsrf.getSecure()).isTrue();
        assertThat(xsrf.isHttpOnly()).isFalse();
        assertThat(xsrf.getAttribute("SameSite")).isEqualTo("Lax");
    }

    @Test
    void 정적_파일도_HSTS를_쓴다() throws Exception {
        mvc.perform(get("/theme.js")).andExpect(header().string("Strict-Transport-Security", "max-age=31536000"));
    }
}
