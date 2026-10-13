package com.team.blog.search.web;

import java.time.Duration;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.team.blog.discovery.application.FeedQuery;
import com.team.blog.search.application.SearchQuery;
import com.team.blog.search.semantic.SemanticSearch;
import com.team.blog.shared.config.BlogProperties;
import com.team.blog.shared.error.NotFoundException;
import com.team.blog.shared.security.CurrentMember;
import com.team.blog.shared.security.MemberPrincipal;
import com.team.blog.shared.web.ClientIpResolver;
import com.team.blog.shared.web.RateLimiter;
import com.team.blog.shared.web.TooManyRequestsException;
import com.team.blog.view.application.ViewRecorder;
import com.team.blog.view.web.ViewController;

/**
 * 검색 API (spec 014, docs/33 §5). 결과는 보는 사람과 상관없이 같지만 검색어가 주소에 들어가므로 공유 캐시에 두지 않는다.
 * 같은 방문자 1분 30번(FR-021). 운영 기록에는 검색어 길이와 걸린 시간만 남긴다(FR-023).
 */
@RestController
public class SearchController {
    private static final Logger log = LoggerFactory.getLogger(SearchController.class);

    private final SearchQuery search;
    private final SemanticSearch semantic;
    private final FeedQuery feed;
    private final RateLimiter rateLimiter;
    private final ViewRecorder visitors;
    private final ClientIpResolver ipResolver;
    private final int perMinute;

    public SearchController(SearchQuery search, SemanticSearch semantic, FeedQuery feed, RateLimiter rateLimiter, ViewRecorder visitors,
                            ClientIpResolver ipResolver, BlogProperties props) {
        this.search = search;
        this.semantic = semantic;
        this.feed = feed;
        this.rateLimiter = rateLimiter;
        this.visitors = visitors;
        this.ipResolver = ipResolver;
        this.perMinute = props.search().perMinute();
    }

    /**
     * @param blog 블로그 안 검색이면 그 블로그 주소 (FR-003). 없는 블로그는 404
     */
    @GetMapping("/api/search/posts")
    public ResponseEntity<SearchQuery.PostPage> posts(@RequestParam(required = false) String q,
                                                      @RequestParam(required = false) String sort,
                                                      @RequestParam(required = false) String cursor,
                                                      @RequestParam(required = false) String blog,
                                                      @CurrentMember(required = false) MemberPrincipal me,
                                                      HttpServletRequest request) {
        limit(me, request);
        Long authorId = blog == null || blog.isEmpty() ? null : feed.ownerId(blog.toLowerCase()).orElseThrow(NotFoundException::new);
        long started = System.nanoTime();
        SearchQuery.PostPage page = search.posts(q, parseSort(sort), cursor, authorId);
        logTiming("posts", page.query(), started);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(page);
    }

    /** 검색창을 열 때 화면이 부른다. 임베딩 모델을 미리 올리고 바로 돌아온다(응답을 기다리지 않음) */
    @GetMapping("/api/search/warm")
    public ResponseEntity<Void> warm() {
        semantic.warmUp();
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    @GetMapping("/api/search/people")
    public ResponseEntity<SearchQuery.PeoplePage> people(@RequestParam(required = false) String q,
                                                         @CurrentMember(required = false) MemberPrincipal me,
                                                         HttpServletRequest request) {
        limit(me, request);
        long started = System.nanoTime();
        SearchQuery.PeoplePage page = search.people(q);
        logTiming("people", page.query(), started);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(page);
    }

    public static SearchQuery.Sort parseSort(String sort) {
        return "latest".equalsIgnoreCase(sort) ? SearchQuery.Sort.LATEST : SearchQuery.Sort.RELEVANCE;
    }

    /** 조회수와 같은 방문자 구분(013)으로 1분 30번. 구분 값을 못 만들면(저장소 장애) 제한하지 않는다 (H8). */
    private void limit(MemberPrincipal me, HttpServletRequest request) {
        String visitor = visitors.visitorKey(new ViewRecorder.Visit(me == null ? null : me.id(), false,
                cookie(request, ViewController.VISITOR_COOKIE), null, ipResolver.resolve(request), request.getHeader("User-Agent"), false));
        if (visitor != null && !rateLimiter.tryAcquire("search:" + visitor, perMinute, Duration.ofMinutes(1))) {
            throw new TooManyRequestsException(60, "RATE_LIMITED", "검색을 너무 자주 했어요. 잠시 후 다시 시도해 주세요.");
        }
    }

    private static void logTiming(String kind, String query, long startedNanos) {
        if (log.isDebugEnabled()) {
            log.debug("search {} length={} took={}ms", kind, query.codePointCount(0, query.length()), (System.nanoTime() - startedNanos) / 1_000_000);
        }
    }

    private static String cookie(HttpServletRequest request, String name) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) return null;
        for (Cookie c : cookies) if (name.equals(c.getName())) return c.getValue();
        return null;
    }
}
