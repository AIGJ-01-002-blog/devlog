package com.team.blog.account.web;

import java.io.IOException;
import java.time.Duration;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.filter.OncePerRequestFilter;

import com.team.blog.shared.config.BlogProperties;
import com.team.blog.shared.web.ClientIpResolver;
import com.team.blog.shared.web.RateLimiter;
import com.team.blog.shared.web.RequestPaths;
import com.team.blog.shared.web.SafeRedirects;

/**
 * 로그인 시도 입구 (소셜 인증 시작·콜백, 이메일 로그인, 개발용 로그인).
 * <ul>
 *   <li>같은 IP 1분에 20회 제한 (docs/07 L-7, IP는 신뢰 프록시 규칙)</li>
 *   <li>세션 저장소(Redis)가 멈췄으면 새 로그인을 "잠시 후 다시 시도"로 거부 (docs/07 §6)</li>
 *   <li>인증 시작 때 돌아갈 주소(redirect)를 세션에 보관 (사이트 안 상대 경로만)</li>
 * </ul>
 */
public class LoginGuardFilter extends OncePerRequestFilter {
    private final RateLimiter rateLimiter;
    private final ClientIpResolver ipResolver;
    private final StringRedisTemplate redis;
    private final int perMinute;

    public LoginGuardFilter(RateLimiter rateLimiter, ClientIpResolver ipResolver, StringRedisTemplate redis, BlogProperties props) {
        this.rateLimiter = rateLimiter;
        this.ipResolver = ipResolver;
        this.redis = redis;
        this.perMinute = props.auth().loginRateLimitPerMinute();
    }

    static boolean isLoginAttempt(HttpServletRequest request) {
        String p = RequestPaths.of(request);
        return p.startsWith("/oauth2/authorization/") || p.startsWith("/login/oauth2/code/")
                || ("POST".equals(request.getMethod()) && (p.equals("/api/auth/login") || p.equals("/api/dev/login")));
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !isLoginAttempt(request);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = RequestPaths.of(request);
        boolean api = path.startsWith("/api/");
        String ip = ipResolver.resolve(request);
        if (!rateLimiter.tryAcquire("login:ip:" + ip, perMinute, Duration.ofMinutes(1))) {
            fail(response, api, 429, "TOO_MANY_REQUESTS", "로그인 시도가 너무 많아요. 잠시 후 다시 시도해 주세요.");
            return;
        }
        if (!sessionStoreAvailable()) {
            fail(response, api, 503, "TEMPORARILY_UNAVAILABLE", "잠시 후 다시 시도해 주세요.");
            return;
        }
        if (path.startsWith("/oauth2/authorization/")) {
            request.getSession(true).setAttribute(LoginFlow.REDIRECT_KEY, SafeRedirects.sanitize(request.getParameter("redirect")));
        }
        chain.doFilter(request, response);
    }

    private boolean sessionStoreAvailable() {
        try {
            String pong = redis.execute(c -> c.ping(), true);
            return pong != null;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private void fail(HttpServletResponse response, boolean api, int status, String code, String message) throws IOException {
        if (api) {
            response.setStatus(status);
            response.setContentType("application/json");
            response.setCharacterEncoding("UTF-8");
            if (status == 429) response.setHeader("Retry-After", "60");
            response.getWriter().write("{\"code\":\"" + code + "\",\"message\":\"" + message + "\",\"errors\":[],\"details\":null}");
        } else {
            response.sendRedirect("/login?error=" + code);
        }
    }
}
