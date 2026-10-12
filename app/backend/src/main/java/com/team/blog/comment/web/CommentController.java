package com.team.blog.comment.web;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.team.blog.comment.application.CommentQuery;
import com.team.blog.comment.application.CommentService;
import com.team.blog.post.access.Viewer;
import com.team.blog.shared.error.NotFoundException;
import com.team.blog.shared.security.CurrentMember;
import com.team.blog.shared.security.MemberPrincipal;

/**
 * 댓글 API (docs/21 §5~§8). 읽을 수 없는 글·남의 댓글·없는 댓글은 모두 같은 404다.
 * 공개 글이 아닌 글의 댓글 응답은 어디에도 저장하지 않는다 (FR-030).
 */
@RestController
public class CommentController {
    private final CommentService comments;
    private final CommentQuery query;

    public CommentController(CommentService comments, CommentQuery query) {
        this.comments = comments;
        this.query = query;
    }

    public record WriteRequest(String content, Long replyToCommentId) {}

    @GetMapping("/api/posts/{postId}/comments")
    public ResponseEntity<CommentQuery.CommentPage> list(@PathVariable String postId, @RequestParam(required = false) String cursor,
                                                         @RequestParam(required = false) String before,
                                                         @RequestParam(required = false) String around,
                                                         @CurrentMember(required = false) MemberPrincipal me) {
        Long aroundId = around != null && around.matches("[1-9]\\d{0,17}") ? Long.parseLong(around) : null;
        CommentQuery.CommentPage page = query.page(id(postId), Viewer.of(me), cursor, before, aroundId);
        return ResponseEntity.ok().cacheControl(cache(page.publiclyVisible())).body(page);
    }

    @GetMapping("/api/comments/{rootId}/replies")
    public ResponseEntity<CommentQuery.ReplyPage> replies(@PathVariable String rootId, @RequestParam(required = false) String cursor,
                                                          @CurrentMember(required = false) MemberPrincipal me) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore().cachePrivate()).body(query.replies(id(rootId), Viewer.of(me), cursor));
    }

    @PostMapping("/api/posts/{postId}/comments")
    public ResponseEntity<CommentQuery.CommentView> create(@PathVariable String postId, @RequestBody WriteRequest body,
                                                           @CurrentMember MemberPrincipal me) {
        CommentService.Created c = comments.create(me.id(), id(postId), body.content(), body.replyToCommentId());
        return ResponseEntity.status(c.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .cacheControl(CacheControl.noStore().cachePrivate()).body(c.comment());
    }

    @PatchMapping("/api/comments/{commentId}")
    public ResponseEntity<CommentQuery.CommentView> update(@PathVariable String commentId, @RequestBody WriteRequest body,
                                                           @CurrentMember MemberPrincipal me) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore().cachePrivate())
                .body(comments.update(me.id(), id(commentId), body.content()));
    }

    @DeleteMapping("/api/comments/{commentId}")
    public ResponseEntity<Void> delete(@PathVariable String commentId, @CurrentMember MemberPrincipal me) {
        comments.delete(me.id(), id(commentId));
        return ResponseEntity.noContent().build();
    }

    private static CacheControl cache(boolean publiclyVisible) {
        return publiclyVisible ? CacheControl.noCache().cachePrivate() : CacheControl.noStore().cachePrivate();
    }

    private static long id(String raw) {
        if (raw == null || !raw.matches("[1-9]\\d{0,17}")) throw new NotFoundException();
        return Long.parseLong(raw);
    }
}
