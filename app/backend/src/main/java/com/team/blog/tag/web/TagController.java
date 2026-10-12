package com.team.blog.tag.web;

import java.time.Duration;
import java.util.List;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.team.blog.discovery.application.FeedQuery;
import com.team.blog.shared.error.NotFoundException;
import com.team.blog.shared.security.CurrentMember;
import com.team.blog.shared.security.MemberPrincipal;
import com.team.blog.shared.web.RateLimiter;
import com.team.blog.tag.application.TagBrowse;
import com.team.blog.tag.application.TagNormalizer;
import com.team.blog.tag.application.TagQuery;
import com.team.blog.post.query.PostCardPage;
import com.team.blog.post.query.PostCard;

/**
 * 태그 API (docs/22 §5~§8). 태그 이름은 주소에서 정규화한 뒤 쓰고, 형식에 맞지 않으면 404다.
 * 공개 글이 없는 태그는 아무도 안 쓴 태그와 같은 응답(200, 글 수 0, 빈 목록)이다 (FR-022).
 */
@RestController
public class TagController {
    private static final int SUGGEST_PER_MINUTE = 60;

    private final TagQuery tags;
    private final FeedQuery feed;
    private final RateLimiter rateLimiter;
    private final TagBrowse browse;

    public TagController(TagQuery tags, FeedQuery feed, RateLimiter rateLimiter, TagBrowse browse) {
        this.tags = tags;
        this.feed = feed;
        this.rateLimiter = rateLimiter;
        this.browse = browse;
    }

    /** 태그 페이지 한 쪽. 상단 글 수는 첫 쪽에만 싣는다. */
    public record TagPage(String name, Long postCount, List<PostCard> items, String nextCursor) {}

    @GetMapping("/api/tags")
    public ResponseEntity<List<TagQuery.TagCount>> top(@RequestParam(defaultValue = "100") int limit) {
        return ResponseEntity.ok().cacheControl(CacheControl.noCache()).body(tags.top(limit));
    }

    /** 태그 둘러보기 (spec 089): 검색어(q, 이름 안 어디든)·정렬(popular|name|recent)로 몇 줄씩, 줄마다 인기 글 카드 */
    @GetMapping("/api/tags/browse")
    public ResponseEntity<TagBrowse.Page> browse(@RequestParam(required = false) String q, @RequestParam(required = false) String sort,
                                                 @RequestParam(required = false) String cursor) {
        return ResponseEntity.ok().cacheControl(CacheControl.noCache()).body(browse.page(q, TagBrowse.Sort.parse(sort), cursor));
    }

    @GetMapping("/api/tags/suggest")
    public ResponseEntity<List<TagQuery.Suggestion>> suggest(@RequestParam(defaultValue = "") String q, @CurrentMember MemberPrincipal me) {
        rateLimiter.check("tag-suggest:" + me.id(), SUGGEST_PER_MINUTE, Duration.ofMinutes(1));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore().cachePrivate()).body(tags.suggest(me.id(), q));
    }

    @GetMapping("/api/tags/{name}/posts")
    public ResponseEntity<TagPage> posts(@PathVariable String name, @RequestParam(required = false) String cursor) {
        return ResponseEntity.ok().cacheControl(CacheControl.noCache()).body(page(canonical(name), cursor));
    }

    @GetMapping("/api/members/{handle}/tags")
    public ResponseEntity<List<TagQuery.TagCount>> blogTags(@PathVariable String handle) {
        long authorId = feed.ownerId(handle).orElseThrow(NotFoundException::new);
        return ResponseEntity.ok().cacheControl(CacheControl.noCache()).body(tags.blogTags(authorId));
    }

    public TagPage page(String name, String cursor) {
        PostCardPage p = feed.tag(name, cursor);
        Long count = cursor == null || cursor.isBlank() ? tags.publicPostCount(name) : null;
        return new TagPage(name, count, p.items(), p.nextCursor());
    }

    static String canonical(String raw) {
        return TagNormalizer.canonical(raw).orElseThrow(NotFoundException::new);
    }
}
