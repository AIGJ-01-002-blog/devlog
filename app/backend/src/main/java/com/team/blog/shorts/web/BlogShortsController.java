package com.team.blog.shorts.web;

import java.util.List;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import com.team.blog.shared.error.NotFoundException;
import com.team.blog.shorts.application.BlogShorts;

/**
 * 블로그·글 화면의 쇼츠 (spec 103). 누구나 읽는다. SHORTS_ENABLED가 꺼져 있으면 404다.
 * <ul>
 *   <li>GET /api/members/{handle}/shorts: 블로그의 "쇼츠" 탭</li>
 *   <li>GET /api/posts/{postId}/shorts: 글 상세의 "이 글의 쇼츠"</li>
 * </ul>
 */
@RestController
public class BlogShortsController {
    private final BlogShorts shorts;

    public BlogShortsController(BlogShorts shorts) {
        this.shorts = shorts;
    }

    @GetMapping("/api/members/{handle}/shorts")
    public ResponseEntity<List<BlogShorts.Card>> blog(@PathVariable String handle) {
        return ResponseEntity.ok().cacheControl(CacheControl.noCache()).body(shorts.ofBlog(handle));
    }

    @GetMapping("/api/posts/{postId}/shorts")
    public ResponseEntity<List<BlogShorts.Card>> post(@PathVariable String postId) {
        long id;
        try {
            id = Long.parseLong(postId);
        } catch (NumberFormatException e) {
            throw new NotFoundException();
        }
        return ResponseEntity.ok().cacheControl(CacheControl.noCache()).body(shorts.ofPost(id));
    }
}
