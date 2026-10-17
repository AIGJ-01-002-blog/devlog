package com.team.blog.shared.security;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 로그인 세션 절대 만료 (보안 점검 2026-10-16). 세션은 14일 동안 쓰지 않으면 끝나는데, 계속 쓰면 끝없이 늘어나
 * 한 번 새어 나간 세션 쿠키가 오래 살 수 있었다. 로그인한 때(LoginSessions.LOGGED_IN_AT)부터 정한 기간이 지나면
 * 세션을 지우고 이번 요청은 비회원으로 처리한다. 이 기능 전에 로그인한 세션은 처음 본 때부터 센다.
 */
public class SessionLifetimeFilter extends OncePerRequestFilter {
    private final Duration maxAge;
    private final Clock clock;

    public SessionLifetimeFilter(Duration maxAge) {
        this(maxAge, Clock.systemUTC());
    }

    SessionLifetimeFilter(Duration maxAge, Clock clock) {
        this.maxAge = maxAge;
        this.clock = clock;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        HttpSession session = request.getSession(false);
        if (session != null && CurrentMemberArgumentResolver.current() != null) {
            Instant now = clock.instant();
            if (!(session.getAttribute(LoginSessions.LOGGED_IN_AT) instanceof Instant at)) {
                session.setAttribute(LoginSessions.LOGGED_IN_AT, now);
            } else if (at.plus(maxAge).isBefore(now)) {
                session.invalidate();
                SecurityContextHolder.clearContext();
            }
        }
        chain.doFilter(request, response);
    }
}
