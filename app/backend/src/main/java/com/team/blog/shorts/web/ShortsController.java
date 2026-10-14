package com.team.blog.shorts.web;

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

import com.team.blog.shared.security.CurrentMember;
import com.team.blog.shared.security.MemberPrincipal;
import com.team.blog.shorts.application.OriginalityCheck;
import com.team.blog.shorts.application.ShortsBatches;
import com.team.blog.shorts.application.ShortsService;

/**
 * 쇼츠 스튜디오 API (099). 로그인한 회원 본인 것만. SHORTS_ENABLED가 꺼져 있으면 모두 404다.
 * <ul>
 *   <li>GET /api/me/shorts: 내 쇼츠 목록, 워커가 마지막으로 온 시각, 오늘 만든 횟수</li>
 *   <li>POST /api/me/shorts: 새 대본 (postId는 내 글이면 선택)</li>
 *   <li>PUT /api/me/shorts/{id}: 대본 고치기 (완성 영상은 지워지고 대본 상태로)</li>
 *   <li>POST /api/me/shorts/{id}/check: 양산형 방지 검사만</li>
 *   <li>POST /api/me/shorts/{id}/render: 검사를 통과하면 대기열에 넣기</li>
 *   <li>PUT·DELETE /api/me/shorts/{id}/uploads/{platform}: 직접 올린 영상 주소 남기기·지우기 (youtube·instagram·tiktok)</li>
 *   <li>GET /api/me/shorts/batches: 주제 묶음 목록 (#276). 묶음은 AI가 MCP plan_shorts로 만든다</li>
 *   <li>POST /api/me/shorts/batches/{id}/render: 묶음의 대본을 모두 영상 만들기 대기열에 넣기 (막힌 편은 이유와 함께 건너뜀)</li>
 *   <li>DELETE /api/me/shorts/batches/{id}: 묶음 풀기 (대본·영상은 남는다)</li>
 * </ul>
 */
@RestController
public class ShortsController {
    private final ShortsService shorts;
    private final ShortsBatches batches;

    public ShortsController(ShortsService shorts, ShortsBatches batches) {
        this.shorts = shorts;
        this.batches = batches;
    }

    @GetMapping("/api/me/shorts/batches")
    public ResponseEntity<List<ShortsBatches.Batch>> batches(@CurrentMember MemberPrincipal me) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(batches.list(me.id()));
    }

    @PostMapping("/api/me/shorts/batches/{id}/render")
    public ShortsBatches.RenderAll renderBatch(@CurrentMember MemberPrincipal me, @PathVariable long id) {
        return batches.renderAll(me.id(), id);
    }

    @DeleteMapping("/api/me/shorts/batches/{id}")
    public ResponseEntity<Void> deleteBatch(@CurrentMember MemberPrincipal me, @PathVariable long id) {
        batches.delete(me.id(), id);
        return ResponseEntity.noContent().build();
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

    @PutMapping("/api/me/shorts/{id}/uploads/{platform}")
    public ShortsService.Item recordUpload(@CurrentMember MemberPrincipal me, @PathVariable long id, @PathVariable String platform,
                                           @RequestBody ShortsService.UploadBody body) {
        return shorts.recordUpload(me.id(), id, platform, body);
    }

    @DeleteMapping("/api/me/shorts/{id}/uploads/{platform}")
    public ShortsService.Item deleteUpload(@CurrentMember MemberPrincipal me, @PathVariable long id, @PathVariable String platform) {
        return shorts.deleteUpload(me.id(), id, platform);
    }

    @DeleteMapping("/api/me/shorts/{id}")
    public ResponseEntity<Void> delete(@CurrentMember MemberPrincipal me, @PathVariable long id) {
        shorts.delete(me.id(), id);
        return ResponseEntity.noContent().build();
    }
}
