package com.team.blog.discovery.web;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.team.blog.discovery.application.AdjacentPostQuery;
import com.team.blog.discovery.application.FeedQuery;
import com.team.blog.discovery.application.PostDetailQuery;
import com.team.blog.post.access.Viewer;
import com.team.blog.shared.error.NotFoundException;
import com.team.blog.shared.security.CurrentMember;
import com.team.blog.shared.security.MemberPrincipal;
import com.team.blog.tag.application.TagNormalizer;
import com.team.blog.post.query.PostCardPage;

/** 읽기 API: 홈 목록, 개인 블로그, 글 상세 (docs/10 §4-2, docs/40). size 파라미터는 받지 않는다(9개 고정). */
@RestController
public class DiscoveryController {
    private final FeedQuery feed;
    private final PostDetailQuery details;
    private final AdjacentPostQuery adjacent;

    public DiscoveryController(FeedQuery feed, PostDetailQuery details, AdjacentPostQuery adjacent) {
        this.feed = feed;
        this.details = details;
        this.adjacent = adjacent;
    }

    @GetMapping("/api/posts")
    public ResponseEntity<PostCardPage> home(@RequestParam(required = false) String cursor,
                                             @RequestParam(required = false) String branch) {
        return ResponseEntity.ok().cacheControl(CacheControl.noCache()).body(feed.home(cursor, branch));
    }

    @GetMapping("/api/members/{handle}")
    public ResponseEntity<FeedQuery.BlogProfile> profile(@PathVariable String handle,
                                                         @CurrentMember(required = false) MemberPrincipal me) {
        FeedQuery.BlogProfile p = feed.profile(handle, me == null ? null : me.id()).orElseThrow(NotFoundException::new);
        return ResponseEntity.ok().cacheControl(CacheControl.noCache().cachePrivate()).body(p);
    }

    @GetMapping("/api/members/{handle}/posts")
    public ResponseEntity<PostCardPage> blog(@PathVariable String handle, @RequestParam(required = false) String cursor,
                                               @RequestParam(required = false) String tag,
                                               @CurrentMember(required = false) MemberPrincipal me) {
        // 블로그 안 태그 필터 (010 FR-031). 형식에 맞지 않는 태그는 404
        String name = tag == null || tag.isEmpty() ? null : TagNormalizer.canonical(tag).orElseThrow(NotFoundException::new);
        PostCardPage page = feed.blog(handle, name, cursor, me == null ? null : me.id());
        // 친구에게만 보이는 글이 섞인 목록은 저장하지 않는다 (docs/06 R-5)
        return ResponseEntity.ok().cacheControl(page.friendsView() ? CacheControl.noStore().cachePrivate() : CacheControl.noCache())
                .body(page);
    }

    /** 내가 좋아한 글 (027). 개인 목록이라 어디에도 저장하지 않는다. */
    @GetMapping("/api/me/liked-posts")
    public ResponseEntity<PostCardPage> liked(@CurrentMember MemberPrincipal me, @RequestParam(required = false) String cursor) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore().cachePrivate()).body(feed.liked(me.id(), cursor));
    }

    /** 상세는 보는 사람마다 달라 공유 캐시에 넣지 않고, 공개가 아닌 글은 어디에도 저장하지 않는다 (docs/40 R-9). */
    @GetMapping("/api/posts/{postId}")
    public ResponseEntity<PostDetailQuery.Detail> detail(@PathVariable String postId,
                                                        @CurrentMember(required = false) MemberPrincipal me) {
        long id = parseId(postId);
        PostDetailQuery.Detail d = details.find(id, Viewer.of(me)).orElseThrow(NotFoundException::new);
        CacheControl cc = d.publiclyVisible() ? CacheControl.noCache().cachePrivate() : CacheControl.noStore().cachePrivate();
        return ResponseEntity.ok().cacheControl(cc).body(d);
    }

    /** 글 상세 아래의 이전·다음 글 (spec 040). 읽을 수 없는 글이면 404, 친구 목록 기준이면 저장하지 않는다. */
    @GetMapping("/api/posts/{postId}/adjacent")
    public ResponseEntity<AdjacentPostQuery.Adjacent> adjacent(@PathVariable String postId,
                                                               @CurrentMember(required = false) MemberPrincipal me) {
        AdjacentPostQuery.Adjacent a = adjacent.find(parseId(postId), Viewer.of(me)).orElseThrow(NotFoundException::new);
        return ResponseEntity.ok()
                .cacheControl(a.friendsView() ? CacheControl.noStore().cachePrivate() : CacheControl.noCache().cachePrivate())
                .body(a);
    }

    static long parseId(String raw) {
        if (raw == null || !raw.matches("[1-9]\\d{0,17}")) throw new NotFoundException();
        return Long.parseLong(raw);
    }
}
