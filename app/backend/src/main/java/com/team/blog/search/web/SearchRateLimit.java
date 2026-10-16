package com.team.blog.search.web;

import java.time.Duration;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;

import org.springframework.stereotype.Component;

import com.team.blog.shared.config.BlogProperties;
import com.team.blog.shared.security.MemberPrincipal;
import com.team.blog.shared.web.ClientIpResolver;
import com.team.blog.shared.web.RateLimiter;
import com.team.blog.shared.web.TooManyRequestsException;
import com.team.blog.view.application.ViewRecorder;
import com.team.blog.view.web.ViewController;

/**
 * 검색 요청 제한 (FR-021). 조회수와 같은 방문자 구분(013)으로 1분 30번. 구분 값을 못 만들면(저장소 장애) 제한하지 않는다 (H8).
 * 로그인하지 않은 방문자는 쿠키를 바꿔 새 칸을 만들 수 있어 IP 상한(SearchIpLimit)도 함께 본다.
 */
@Component
public class SearchRateLimit {
    private final RateLimiter rateLimiter;
    private final ViewRecorder visitors;
    private final ClientIpResolver ipResolver;
    private final int perMinute;
    private final SearchIpLimit ipLimit;

    public SearchRateLimit(RateLimiter rateLimiter, ViewRecorder visitors, ClientIpResolver ipResolver, BlogProperties props, SearchIpLimit ipLimit) {
        this.rateLimiter = rateLimiter;
        this.visitors = visitors;
        this.ipResolver = ipResolver;
        this.perMinute = props.search().perMinute();
        this.ipLimit = ipLimit;
    }

    /** 넘으면 429 */
    public void check(MemberPrincipal me, HttpServletRequest request) {
        if (me == null && !ipLimit.tryAcquire(request)) throw tooMany();
        String visitor = visitors.visitorKey(new ViewRecorder.Visit(me == null ? null : me.id(), false,
                cookie(request, ViewController.VISITOR_COOKIE), null, ipResolver.resolve(request), request.getHeader("User-Agent"), false));
        if (visitor != null && !rateLimiter.tryAcquire("search:" + visitor, perMinute, Duration.ofMinutes(1))) {
            throw tooMany();
        }
    }

    private static TooManyRequestsException tooMany() {
        return new TooManyRequestsException(60, "RATE_LIMITED", "검색을 너무 자주 했어요. 잠시 후 다시 시도해 주세요.");
    }

    private static String cookie(HttpServletRequest request, String name) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) return null;
        for (Cookie c : cookies) if (name.equals(c.getName())) return c.getValue();
        return null;
    }
}
