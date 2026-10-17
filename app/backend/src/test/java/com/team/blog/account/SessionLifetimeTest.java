package com.team.blog.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.session.data.redis.RedisIndexedSessionRepository;

import com.team.blog.shared.security.LoginSessions;
import com.team.blog.support.IntegrationTest;

/** 로그인 세션 절대 만료 (보안 점검 2026-10-16, #379): 실제 보안 필터 체인에서 세션을 다시 읽어 판단하는지 본다. */
class SessionLifetimeTest extends IntegrationTest {
    @Autowired RedisIndexedSessionRepository sessionRepository;

    @Test
    void 로그인하고_30일이_지난_세션은_다음_요청부터_비회원이다() throws Exception {
        Session s = signup(uniqueLogin("lifetime"));
        s.http().perform(get("/api/me/settings")).andExpect(status().isOk());

        var session = sessionRepository.findById(sessionId(s));
        assertThat(session.<Instant>getAttribute(LoginSessions.LOGGED_IN_AT)).isNotNull();
        session.setAttribute(LoginSessions.LOGGED_IN_AT, Instant.now().minus(Duration.ofDays(30)).minusSeconds(60));
        sessionRepository.save(session);

        s.http().perform(get("/api/me/settings")).andExpect(status().isUnauthorized());
        s.http().perform(get("/api/me/settings")).andExpect(status().isUnauthorized());
    }

    @Test
    void 기간_안의_세션은_계속_쓴다() throws Exception {
        Session s = signup(uniqueLogin("lifetimeok"));
        var session = sessionRepository.findById(sessionId(s));
        session.setAttribute(LoginSessions.LOGGED_IN_AT, Instant.now().minus(Duration.ofDays(29)));
        sessionRepository.save(session);

        s.http().perform(get("/api/me/settings")).andExpect(status().isOk());
    }

    /**
     * 세션 쿠키 값은 Spring Session이 Base64로 감싼 세션 ID다. 운영 이름은 BLOGSESSION이지만 MockMvc 시험에서는
     * 내장 서버 쿠키 설정을 거치지 않아 Spring Session 기본 이름 SESSION으로 나온다.
     */
    private static String sessionId(Session s) {
        String cookie = s.http().cookie("BLOGSESSION");
        if (cookie == null) cookie = s.http().cookie("SESSION");
        assertThat(cookie).as("세션 쿠키").isNotNull();
        return new String(Base64.getDecoder().decode(cookie), StandardCharsets.UTF_8);
    }
}
