package com.team.blog.admin.web;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.team.blog.account.application.RoleService;
import com.team.blog.account.application.WithdrawalPurgeJob;
import com.team.blog.account.domain.Member;
import com.team.blog.account.domain.MemberStatus;
import com.team.blog.account.infra.MemberRepository;
import com.team.blog.admin.application.AdminStats;
import com.team.blog.moderation.application.ModerationService;
import com.team.blog.post.query.IndexStats;
import com.team.blog.shared.error.ApiException;
import com.team.blog.shared.error.NotFoundException;
import com.team.blog.shared.security.CurrentMember;
import com.team.blog.shared.security.MemberPrincipal;

/**
 * 관리자 페이지 (062): 대시보드 통계, 회원 목록·회원별 통계·권한, 글 목록·숨기기. 관리자·매니저만 쓰고(일반 회원 404),
 * 권한 바꾸기는 관리자만 한다(매니저는 403).
 */
@RestController
@RequestMapping("/api/admin")
public class AdminConsoleController {
    private final AdminStats stats;
    private final RoleService roles;
    private final ModerationService moderation;
    private final MemberRepository members;
    private final WithdrawalPurgeJob purgeJob;
    private final IndexStats index;

    public AdminConsoleController(AdminStats stats, RoleService roles, ModerationService moderation, MemberRepository members,
                                  WithdrawalPurgeJob purgeJob, IndexStats index) {
        this.stats = stats;
        this.roles = roles;
        this.moderation = moderation;
        this.members = members;
        this.purgeJob = purgeJob;
        this.index = index;
    }

    public record IndexApproval(boolean approved) {}

    public record RoleRequest(String role) {}

    public record RoleResult(String handle, String role) {}

    public record HideRequest(String reason) {}

    @GetMapping("/dashboard")
    public ResponseEntity<AdminStats.Dashboard> dashboard(@RequestParam(defaultValue = "30") int days, @CurrentMember MemberPrincipal me) {
        staff(me);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(stats.dashboard(days));
    }

    @GetMapping("/members")
    public ResponseEntity<AdminStats.MemberPage> members(@RequestParam(required = false) String q, @RequestParam(required = false) String role,
                                                         @RequestParam(required = false) String status,
                                                         @RequestParam(defaultValue = "1") int page, @CurrentMember MemberPrincipal me) {
        staff(me);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(stats.memberPage(q, role, status, page));
    }

    @GetMapping("/members/{handle}/stats")
    public ResponseEntity<AdminStats.MemberDetail> memberStats(@PathVariable String handle, @CurrentMember MemberPrincipal me) {
        staff(me);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(stats.memberDetail(handle));
    }

    @PutMapping("/members/{handle}/role")
    public ResponseEntity<RoleResult> role(@PathVariable String handle, @RequestBody RoleRequest body, @CurrentMember MemberPrincipal me) {
        staff(me);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(new RoleResult(handle, roles.change(me.id(), handle, body == null ? null : body.role()).name()));
    }

    @GetMapping("/posts")
    public ResponseEntity<AdminStats.PostPage> posts(@RequestParam(required = false) String q, @RequestParam(required = false) String filter,
                                                     @RequestParam(required = false) String author,
                                                     @RequestParam(defaultValue = "1") int page, @CurrentMember MemberPrincipal me) {
        staff(me);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(stats.postPage(q, filter, author, page));
    }

    @PostMapping("/posts/{postId}/hide")
    public ResponseEntity<Void> hide(@PathVariable long postId, @RequestBody HideRequest body, @CurrentMember MemberPrincipal me) {
        staff(me);
        moderation.hidePost(me.id(), postId, body == null ? null : body.reason());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/posts/{postId}/unhide")
    public ResponseEntity<Void> unhide(@PathVariable long postId, @CurrentMember MemberPrincipal me) {
        staff(me);
        moderation.unhidePost(me.id(), postId);
        return ResponseEntity.noContent().build();
    }

    /** 검색 수집 대상·제외 글 수와 검수를 기다리는 외부 회원 글 (spec 107) */
    @GetMapping("/index-report")
    public ResponseEntity<IndexStats.Report> indexReport(@CurrentMember MemberPrincipal me) {
        staff(me);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(index.report());
    }

    /** 외부 회원 글을 검수해 검색 수집을 허락하거나 거둔다 (spec 107). 운영진 글은 이 값과 상관없이 수집 기준만 본다 */
    @PutMapping("/posts/{postId}/index-approval")
    public ResponseEntity<Void> indexApproval(@PathVariable long postId, @RequestBody IndexApproval body, @CurrentMember MemberPrincipal me) {
        staff(me);
        index.approve(postId, body != null && body.approved());
        return ResponseEntity.noContent().build();
    }

    /**
     * 탈퇴 회원을 30일 유예를 기다리지 않고 바로 정리한다 (020 FR-039). 관리자만 한다(매니저는 403). 정리하면 되돌릴 수 없고,
     * 블로그 주소는 그대로 예약되며, 같은 소셜 계정으로 오면 새로 가입한다.
     */
    @PostMapping("/members/{handle}/purge")
    public ResponseEntity<Void> purge(@PathVariable String handle, @CurrentMember MemberPrincipal me) {
        staff(me);
        if (!me.isAdmin()) throw new ApiException(HttpStatus.FORBIDDEN, "ADMIN_ONLY", "탈퇴 회원 정리는 관리자만 할 수 있어요.");
        Member m = members.findByHandle(handle).orElseThrow(NotFoundException::new);
        if (m.getStatus() != MemberStatus.WITHDRAWN || m.getDeletedAt() != null) {
            throw new ApiException(HttpStatus.CONFLICT, "NOT_WITHDRAWN", "탈퇴 신청한 회원만 바로 정리할 수 있어요.");
        }
        if (!purgeJob.purgeNow(m.getId())) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "PURGE_FAILED", "정리하지 못했어요. 잠시 후 다시 시도해 주세요.");
        }
        return ResponseEntity.noContent().build();
    }

    private static void staff(MemberPrincipal me) {
        if (!me.isStaff()) throw new NotFoundException();
    }
}
