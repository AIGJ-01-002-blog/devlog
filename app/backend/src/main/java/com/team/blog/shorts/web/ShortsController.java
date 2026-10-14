package com.team.blog.shorts.web;

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

import com.team.blog.shared.security.CurrentMember;
import com.team.blog.shared.security.MemberPrincipal;
import com.team.blog.shorts.application.OriginalityCheck;
import com.team.blog.shorts.application.ShortsService;

/**
 * 쇼츠 스튜디오 API (099). 로그인한 회원 본인 것만. SHORTS_ENABLED가 꺼져 있으면 모두 404다.
 * <ul>
 *   <li>GET /api/me/shorts: 내 쇼츠 목록, 워커가 마지막으로 온 시각, 오늘 만든 횟수</li>
 *   <li>POST /api/me/shorts: 새 대본 (postId는 내 글이면 선택)</li>
 *   <li>PUT /api/me/shorts/{id}: 대본 고치기 (완성 영상은 지워지고 대본 상태로)</li>
 *   <li>POST /api/me/shorts/{id}/check: 양산형 방지 검사만</li>
 *   <li>POST /api/me/shorts/{id}/render: 검사를 통과하면 대기열에 넣기</li>
 * </ul>
 */
@RestController
public class ShortsController {
    private final ShortsService shorts;

    public ShortsController(ShortsService shorts) {
        this.shorts = shorts;
    }

    @GetMapping("/api/me/shorts")
    public ResponseEntity<ShortsService.Overview> list(@CurrentMember MemberPrincipal me) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(shorts.overview(me.id()));
    }

    @GetMapping("/api/me/shorts/{id}")
    public ResponseEntity<ShortsService.Item> get(@CurrentMember MemberPrincipal me, @PathVariable long id) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(shorts.get(me.id(), id));
    }

    @PostMapping("/api/me/shorts")
    public ResponseEntity<ShortsService.Item> create(@CurrentMember MemberPrincipal me, @RequestBody ShortsService.Draft body) {
        return ResponseEntity.status(HttpStatus.CREATED).body(shorts.create(me.id(), body));
    }

    @PutMapping("/api/me/shorts/{id}")
    public ShortsService.Item update(@CurrentMember MemberPrincipal me, @PathVariable long id, @RequestBody ShortsService.Edit body) {
        return shorts.update(me.id(), id, body);
    }

    @PostMapping("/api/me/shorts/{id}/check")
    public OriginalityCheck.Report check(@CurrentMember MemberPrincipal me, @PathVariable long id) {
        return shorts.check(me.id(), id);
    }

    @PostMapping("/api/me/shorts/{id}/render")
    public ShortsService.Item render(@CurrentMember MemberPrincipal me, @PathVariable long id) {
        return shorts.render(me.id(), id);
    }

    @DeleteMapping("/api/me/shorts/{id}")
    public ResponseEntity<Void> delete(@CurrentMember MemberPrincipal me, @PathVariable long id) {
        shorts.delete(me.id(), id);
        return ResponseEntity.noContent().build();
    }
}
