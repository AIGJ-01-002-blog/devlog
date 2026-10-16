package com.team.blog.search.web;

import java.time.Duration;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.stereotype.Component;

import com.team.blog.shared.config.BlogProperties;
import com.team.blog.shared.web.ClientIpResolver;
import com.team.blog.shared.web.RateLimiter;

/**
 * 같은 IP의 검색 횟수 상한. 방문자 구분(visitor 쿠키)은 요청마다 쿠키를 바꾸면 새 칸이 되고, 화면 주소 /search는 서버에서 바로
 * 검색(의미 검색 임베딩 포함)을 돌려서, 로그인 없이 비용이 큰 작업을 무제한 일으킬 수 있었다. 학교·회사처럼 여럿이 한 IP를 쓰는 곳을 생각해
 * 방문자 한 명 상한의 두 배로 둔다. 저장소 장애 때는 RateLimiter가 통과시킨다.
 */
@Component
public class SearchIpLimit {
    static final String KEY = "search-ip:";

    private final RateLimiter rateLimiter;
    private final ClientIpResolver ipResolver;
    private final int perMinute;

    public SearchIpLimit(RateLimiter rateLimiter, ClientIpResolver ipResolver, BlogProperties props) {
        this.rateLimiter = rateLimiter;
        this.ipResolver = ipResolver;
        this.perMinute = props.search().perMinute() * 2;
    }

    /** 이번 검색을 허용하면 true. IP를 알 수 없으면 막지 않는다 */
    public boolean tryAcquire(HttpServletRequest request) {
        String ip = ipResolver.resolve(request);
        if (ip == null || ip.isBlank()) return true;
        return rateLimiter.tryAcquire(KEY + ip, perMinute, Duration.ofMinutes(1));
    }
}
