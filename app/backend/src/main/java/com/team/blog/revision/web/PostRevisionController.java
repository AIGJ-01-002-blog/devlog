package com.team.blog.revision.web;

import java.util.List;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import com.team.blog.revision.application.PostRevisionQuery;
import com.team.blog.shared.error.NotFoundException;
import com.team.blog.shared.security.CurrentMember;
import com.team.blog.shared.security.MemberPrincipal;

/** 글 수정 이력 (058). 작성자에게만 보이는 개인 정보라 캐시하지 않는다. */
@RestController
public class PostRevisionController {
    private final PostRevisionQuery query;

    public PostRevisionController(PostRevisionQuery query) {
        this.query = query;
    }

    @GetMapping("/api/posts/{postId}/revisions")
    public ResponseEntity<List<PostRevisionQuery.Item>> list(@CurrentMember MemberPrincipal me, @PathVariable String postId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(query.list(me.id(), parseLong(postId)));
    }

    @GetMapping("/api/posts/{postId}/revisions/{no}")
    public ResponseEntity<PostRevisionQuery.Detail> get(@CurrentMember MemberPrincipal me, @PathVariable String postId,
                                                        @PathVariable String no) {
        long n = parseLong(no);
        if (n > Integer.MAX_VALUE) throw new NotFoundException();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(query.get(me.id(), parseLong(postId), (int) n));
    }

    private static long parseLong(String raw) {
        if (raw == null || !raw.matches("[1-9]\\d{0,17}")) throw new NotFoundException();
        return Long.parseLong(raw);
    }
}
