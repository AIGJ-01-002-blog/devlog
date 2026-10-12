package com.team.blog.post.web;

import java.time.Duration;
import java.util.List;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.team.blog.account.domain.Visibility;
import com.team.blog.post.application.PostCommandService;
import com.team.blog.post.application.PostEditorQuery;
import com.team.blog.post.application.PostTrashService;
import com.team.blog.post.application.PublishCommand;
import com.team.blog.shared.config.BlogProperties;
import com.team.blog.shared.error.ApiException;
import com.team.blog.shared.error.FieldErrorItem;
import com.team.blog.shared.security.CurrentMember;
import com.team.blog.shared.security.MemberPrincipal;
import com.team.blog.shared.web.RateLimiter;

/** 글 쓰기 API (docs/04 §2-3, docs/05 §5, docs/06 §4). 모든 요청은 로그인한 작성자 본인 기준이다. */
@RestController
@RequestMapping("/api/posts")
public class PostController {
    /** 자동 저장·저장·발행 요청 본문 상한 (docs/04 §2-1). 본문 100,000자를 UTF-8로 넉넉히 담는 크기다. */
    static final long MAX_BODY_BYTES = 1024L * 1024;
    private static final String DELETE_RATE_KEY = "post-delete:";

    private final PostCommandService commands;
    private final PostEditorQuery editor;
    private final PostTrashService trash;
    private final RateLimiter rateLimiter;
    private final BlogProperties.Post rules;

    public PostController(PostCommandService commands, PostEditorQuery editor, PostTrashService trash,
                          RateLimiter rateLimiter, BlogProperties props) {
        this.commands = commands;
        this.editor = editor;
        this.trash = trash;
        this.rateLimiter = rateLimiter;
        this.rules = props.post();
    }

    public record ContentRequest(String title, String contentMd, Long baseVersion) {}

    public record PublishRequest(String title, String contentMd, String summary, List<String> tags, String visibility, Long baseVersion,
                                 String thumbnailUrl, Boolean thumbnailHidden) {}

    public record VisibilityRequest(String visibility) {}

    @PostMapping
    public ResponseEntity<PostCommandService.Created> create(@CurrentMember MemberPrincipal me,
                                                            @RequestBody(required = false) ContentRequest body,
                                                            HttpServletRequest request) {
        checkSize(request);
        rateLimiter.check("post-create:" + me.id(), 30, Duration.ofMinutes(1));
        PostCommandService.Created created = commands.create(me.id(),
                body == null ? "" : body.title(), body == null ? "" : body.contentMd());
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @GetMapping("/{postId}/edit")
    public PostEditorQuery.EditorView edit(@CurrentMember MemberPrincipal me, @PathVariable long postId) {
        return editor.open(me.id(), me.handle(), postId);
    }

    @PutMapping("/{postId}/autosave")
    public PostCommandService.Saved autosave(@CurrentMember MemberPrincipal me, @PathVariable long postId,
                                            @RequestBody ContentRequest body, HttpServletRequest request) {
        checkSize(request);
        if (!rules.autosaveMinInterval().isZero()) {
            rateLimiter.check("autosave:" + me.id() + ":" + postId, 1, rules.autosaveMinInterval());
        }
        return commands.autosave(me.id(), postId, body.title(), body.contentMd(), requireVersion(body.baseVersion()));
    }

    @PutMapping("/{postId}")
    public PostCommandService.Saved save(@CurrentMember MemberPrincipal me, @PathVariable long postId,
                                        @RequestBody ContentRequest body, HttpServletRequest request) {
        checkSize(request);
        return commands.save(me.id(), postId, body.title(), body.contentMd(), requireVersion(body.baseVersion()));
    }

    @DeleteMapping("/{postId}/draft")
    public PostCommandService.Saved discard(@CurrentMember MemberPrincipal me, @PathVariable long postId) {
        return commands.discardWorkingCopy(me.id(), postId);
    }

    @PostMapping("/{postId}/publish")
    public PostCommandService.PublishResult publish(@CurrentMember MemberPrincipal me, @PathVariable long postId,
                                                    @RequestBody PublishRequest body,
                                                    @RequestHeader(name = "Idempotency-Key", required = false) String key,
                                                    HttpServletRequest request) {
        checkSize(request);
        PublishCommand cmd = new PublishCommand(postId, me.id(), body.title(), body.contentMd(), body.summary(),
                parseVisibility(body.visibility(), true), body.tags(), requireVersion(body.baseVersion()),
                body.thumbnailUrl(), Boolean.TRUE.equals(body.thumbnailHidden()));
        return commands.publish(cmd, me.handle(), key);
    }

    @PatchMapping("/{postId}/visibility")
    public PostCommandService.VisibilityResult visibility(@CurrentMember MemberPrincipal me, @PathVariable long postId,
                                                          @RequestBody VisibilityRequest body) {
        return commands.changeVisibility(me.id(), postId, parseVisibility(body.visibility(), false));
    }

    /** [삭제] → 휴지통 (007). 빈 임시글은 바로 지워진다. 같은 요청을 다시 보내도 결과가 같다. */
    @DeleteMapping("/{postId}")
    public PostTrashService.TrashResult trash(@CurrentMember MemberPrincipal me, @PathVariable long postId) {
        rateLimiter.check(DELETE_RATE_KEY + me.id(), 60, Duration.ofMinutes(1));
        return trash.trash(me.id(), postId);
    }

    public record DiscardResult(boolean deleted) {}

    /** 에디터 [나가기] (spec 090): 아무것도 쓰지 않은 임시글이면 휴지통 없이 바로 지운다. 아니면 그대로 두고 deleted=false. */
    @DeleteMapping("/{postId}/if-empty")
    public DiscardResult discardIfEmpty(@CurrentMember MemberPrincipal me, @PathVariable long postId) {
        rateLimiter.check(DELETE_RATE_KEY + me.id(), 60, Duration.ofMinutes(1));
        return new DiscardResult(trash.discardIfBlank(me.id(), postId));
    }

    @PostMapping("/{postId}/restore")
    public PostTrashService.RestoreResult restore(@CurrentMember MemberPrincipal me, @PathVariable long postId) {
        rateLimiter.check(DELETE_RATE_KEY + me.id(), 60, Duration.ofMinutes(1));
        return trash.restore(me.id(), postId);
    }

    @DeleteMapping("/{postId}/permanent")
    public ResponseEntity<Void> purge(@CurrentMember MemberPrincipal me, @PathVariable long postId) {
        rateLimiter.check(DELETE_RATE_KEY + me.id(), 60, Duration.ofMinutes(1));
        trash.purge(me.id(), postId);
        return ResponseEntity.noContent().build();
    }

    private static long requireVersion(Long v) {
        if (v == null || v < 0) {
            throw ApiException.validation(List.of(new FieldErrorItem("baseVersion", "INVALID_VERSION", "편집 버전이 필요해요.")));
        }
        return v;
    }

    /** 발행은 검증 오류를 다른 항목과 함께 모으려고 null로 넘긴다. */
    private static Visibility parseVisibility(String raw, boolean lenient) {
        if (raw != null) {
            try {
                return Visibility.valueOf(raw.trim().toUpperCase());
            } catch (IllegalArgumentException ignored) { /* 아래 */ }
        }
        if (lenient) return null;
        throw ApiException.validation(List.of(new FieldErrorItem("visibility", "INVALID_VISIBILITY", "공개 범위를 골라 주세요.")));
    }

    private static void checkSize(HttpServletRequest request) {
        if (request.getContentLengthLong() > MAX_BODY_BYTES) {
            throw new ApiException(HttpStatus.CONTENT_TOO_LARGE, "PAYLOAD_TOO_LARGE", "요청이 너무 커요.");
        }
    }
}
