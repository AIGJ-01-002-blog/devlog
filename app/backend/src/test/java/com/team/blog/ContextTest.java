package com.team.blog;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;

import com.team.blog.support.IntegrationTest;

class ContextTest extends IntegrationTest {
    @Test
    void 앱이_뜨고_비회원은_로그인되지_않은_상태다() throws Exception {
        mvc.perform(get("/api/auth/me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authenticated").value(false))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Referrer-Policy", "strict-origin-when-cross-origin"))
                .andExpect(header().exists("Content-Security-Policy"))
                // 시험 설정은 http(COOKIE_SECURE=false)라 HSTS를 쓰지 않는다. https 전용일 때는 HttpsOnlyHeadersTest가 본다
                .andExpect(header().doesNotExist("Strict-Transport-Security"));
    }

    @Test
    void 쿠버네티스_프로브가_응답한다() throws Exception {
        mvc.perform(get("/actuator/health/liveness")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UP"));
        mvc.perform(get("/actuator/health/readiness")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UP"));
        // 탐색 목록은 바깥에 알리지 않는다
        mvc.perform(get("/actuator")).andExpect(status().isNotFound());
    }
}
