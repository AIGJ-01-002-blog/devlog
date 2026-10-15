package com.team.blog.account.web;

import java.time.Duration;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.stereotype.Component;

import com.team.blog.shared.config.BlogProperties;
import com.team.blog.shared.web.ClientIpResolver;
import com.team.blog.shared.web.RateLimiter;

/** 주소·닉네임·이메일 확인의 요청 제한 (docs/08 §4-2): 같은 IP 1분에 설정한 횟수까지 */
@Component
class AvailabilityLimit {
    private static final String RATE_KEY_PREFIX = "avail:ip:";

    private final RateLimiter rateLimiter;
    private final ClientIpResolver ipResolver;
    private final int perMinute;

    AvailabilityLimit(RateLimiter rateLimiter, ClientIpResolver ipResolver, BlogProperties props) {
        this.rateLimiter = rateLimiter;
        this.ipResolver = ipResolver;
        this.perMinute = props.auth().availabilityRateLimitPerMinute();
    }

    void check(HttpServletRequest request) {
        rateLimiter.check(RATE_KEY_PREFIX + ipResolver.resolve(request), perMinute, Duration.ofMinutes(1));
    }
}
