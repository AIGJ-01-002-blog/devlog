package com.team.blog.shared.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.context.SecurityContextHolder;

class SessionLifetimeFilterTest {
    private static final Instant NOW = Instant.parse("2026-10-17T00:00:00Z");
    private final SessionLifetimeFilter filter =
            new SessionLifetimeFilter(Duration.ofDays(30), Clock.fixed(NOW, ZoneOffset.UTC));

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void 로그인하고_30일이_지난_세션은_지우고_비회원으로_넘긴다() throws Exception {
        MockHttpSession session = loggedIn(NOW.minus(Duration.ofDays(30)).minusSeconds(1));

        filter.doFilter(request(session), new MockHttpServletResponse(), new MockFilterChain());

        assertThat(session.isInvalid()).isTrue();
        assertThat(CurrentMemberArgumentResolver.current()).isNull();
    }

    @Test
    void 기간_안의_세션은_그대로_둔다() throws Exception {
        MockHttpSession session = loggedIn(NOW.minus(Duration.ofDays(29)));

        filter.doFilter(request(session), new MockHttpServletResponse(), new MockFilterChain());

        assertThat(session.isInvalid()).isFalse();
        assertThat(CurrentMemberArgumentResolver.current()).isNotNull();
    }

    @Test
    void 로그인_시각이_없는_옛_세션은_지금부터_센다() throws Exception {
        MockHttpSession session = loggedIn(null);

        filter.doFilter(request(session), new MockHttpServletResponse(), new MockFilterChain());

        assertThat(session.isInvalid()).isFalse();
        assertThat(session.getAttribute(LoginSessions.LOGGED_IN_AT)).isEqualTo(NOW);
    }

    @Test
    void 비회원_세션은_건드리지_않는다() throws Exception {
        MockHttpSession session = new MockHttpSession();

        filter.doFilter(request(session), new MockHttpServletResponse(), new MockFilterChain());

        assertThat(session.isInvalid()).isFalse();
        assertThat(session.getAttribute(LoginSessions.LOGGED_IN_AT)).isNull();
    }

    private static MockHttpSession loggedIn(Instant at) {
        MockHttpSession session = new MockHttpSession();
        if (at != null) session.setAttribute(LoginSessions.LOGGED_IN_AT, at);
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new MemberAuthentication(new MemberPrincipal(1L, "tester", "USER", "LOCAL", false, null)));
        SecurityContextHolder.setContext(context);
        return session;
    }

    private static MockHttpServletRequest request(MockHttpSession session) {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/auth/me");
        req.setSession(session);
        return req;
    }
}
