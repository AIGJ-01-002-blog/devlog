package com.team.blog.like.web;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RestController;

import com.team.blog.like.application.LikeService;
import com.team.blog.shared.error.NotFoundException;
import com.team.blog.shared.security.CurrentMember;
import com.team.blog.shared.security.MemberPrincipal;

/** 좋아요 상태 지정 (docs/30 §3). PUT은 누름, DELETE는 취소. 이미 그 상태여도 200이다. */
@RestController
public class LikeController {
    private final LikeService likes;

    public LikeController(LikeService likes) {
        this.likes = likes;
    }

    @PutMapping("/api/posts/{postId}/like")
    public ResponseEntity<LikeService.LikeState> like(@PathVariable String postId, @CurrentMember MemberPrincipal me) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore().cachePrivate()).body(likes.set(me.id(), id(postId), true));
    }

    @DeleteMapping("/api/posts/{postId}/like")
    public ResponseEntity<LikeService.LikeState> unlike(@PathVariable String postId, @CurrentMember MemberPrincipal me) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore().cachePrivate()).body(likes.set(me.id(), id(postId), false));
    }

    private static long id(String raw) {
        if (raw == null || !raw.matches("[1-9]\\d{0,17}")) throw new NotFoundException();
        return Long.parseLong(raw);
    }
}
