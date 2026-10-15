package com.team.blog.post.web;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.team.blog.post.application.MyPostsQuery;
import com.team.blog.shared.security.CurrentMember;
import com.team.blog.shared.security.MemberPrincipal;

/** 내 글 관리 목록 (docs/41 §5). 개인 정보라 캐시하지 않는다. */
@RestController
public class MyPostsController {
    private final MyPostsQuery query;

    public MyPostsController(MyPostsQuery query) {
        this.query = query;
    }

    @GetMapping("/api/me/posts")
    public ResponseEntity<MyPostsQuery.Result> list(@CurrentMember MemberPrincipal me,
                                                    @RequestParam(required = false) String tab,
                                                    @RequestParam(required = false) String visibility,
                                                    @RequestParam(required = false) String cursor,
                                                    @RequestParam(required = false) String q) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(query.list(me.id(), tab, visibility, cursor, q));
    }
}
