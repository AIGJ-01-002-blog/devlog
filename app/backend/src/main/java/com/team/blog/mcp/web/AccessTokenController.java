package com.team.blog.mcp.web;

import java.time.Instant;
import java.util.List;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.team.blog.mcp.application.AccessTokens;
import com.team.blog.mcp.application.AiDraftHints;
import com.team.blog.post.application.PostEditorQuery;
import com.team.blog.shared.error.ApiException;
import com.team.blog.shared.security.CurrentMember;
import com.team.blog.shared.security.MemberPrincipal;

/**
 * 설정 화면의 AI 연결(개인 접근 토큰)과 편집 화면의 AI 제안 (052), AI 발행·삭제 허용 설정 (053).
 * 모두 로그인 세션 + CSRF로만 부른다. /api/mcp 전용 보안 체인 밖이라 접근 토큰(Bearer)으로는 들어올 수 없다.
 */
@RestController
public class AccessTokenController {
    private final AccessTokens tokens;
    private final AiDraftHints hints;
    private final PostEditorQuery editor;

    public AccessTokenController(AccessTokens tokens, AiDraftHints hints, PostEditorQuery editor) {
        this.tokens = tokens;
        this.hints = hints;
        this.editor = editor;
    }

    /** @param adminTools 관리자만: 이 토큰에 문의 관리 도구를 켠다 (#378) */
    public record CreateRequest(String name, String scope, Integer expiresInDays, Boolean adminTools) {}

    public record HintView(List<String> tags, Instant publishRequestedAt) {}

    /** @param allowed true면 연결한 AI가 publish_post·delete_post로 글을 바로 발행하거나 휴지통으로 옮길 수 있다 */
    public record AiPublishSetting(Boolean allowed) {}

    @GetMapping("/api/me/tokens")
    public ResponseEntity<List<AccessTokens.TokenView>> list(@CurrentMember MemberPrincipal me) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(tokens.list(me.id()));
    }

    /** 원문(secret)은 이 응답에서 한 번만 보인다 */
    @PostMapping("/api/me/tokens")
    public ResponseEntity<AccessTokens.Issued> create(@CurrentMember MemberPrincipal me, @RequestBody CreateRequest body) {
        if (body == null) throw ApiException.badRequest("INVALID_REQUEST", "토큰 정보를 확인해 주세요.");
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
                .body(tokens.create(me.id(), body.name(), body.scope(), body.expiresInDays(), Boolean.TRUE.equals(body.adminTools())));
    }

    @DeleteMapping("/api/me/tokens/{id}")
    public ResponseEntity<Void> revoke(@CurrentMember MemberPrincipal me, @PathVariable long id) {
        tokens.revoke(me.id(), id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/api/me/ai-publish")
    public ResponseEntity<AiPublishSetting> aiPublish(@CurrentMember MemberPrincipal me) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new AiPublishSetting(tokens.aiPublishAllowed(me.id())));
    }

    /** 켜고 끄기 (053). AI가 스스로 켜지 못하게 MCP 도구로는 열어 두지 않는다. */
    @PutMapping("/api/me/ai-publish")
    public AiPublishSetting setAiPublish(@CurrentMember MemberPrincipal me, @RequestBody AiPublishSetting body) {
        if (body == null || body.allowed() == null) throw ApiException.badRequest("INVALID_REQUEST", "켤지 끌지(allowed)를 보내 주세요.");
        return new AiPublishSetting(tokens.setAiPublishAllowed(me.id(), body.allowed()));
    }

    /** AI가 만든 임시글이면 태그 제안·발행 요청. 본인 글만, 없으면 204. */
    @GetMapping("/api/posts/{id}/ai-hint")
    public ResponseEntity<HintView> hint(@CurrentMember MemberPrincipal me, @PathVariable long id) {
        editor.open(me.id(), me.handle(), id);
        return hints.find(id)
                .map(h -> ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new HintView(h.tags(), h.publishRequestedAt())))
                .orElseGet(() -> ResponseEntity.noContent().build());
    }
}
