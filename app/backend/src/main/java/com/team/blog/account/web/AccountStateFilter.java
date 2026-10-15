package com.team.blog.account.web;

import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import tools.jackson.databind.json.JsonMapper;

import com.team.blog.account.domain.Member;
import com.team.blog.account.domain.MemberStatus;
import com.team.blog.account.infra.AuthIdentityRepository;
import com.team.blog.account.infra.MemberRepository;
import com.team.blog.shared.error.ErrorResponse;
import com.team.blog.shared.security.CurrentMemberArgumentResolver;
import com.team.blog.shared.security.MemberPrincipal;
import com.team.blog.shared.web.RequestPaths;

/**
 * 로그인한 사용자의 계정 상태 판정 (docs/42 §3 ②). 세션 값이 아니라 DB의 현재 상태로 판단해
 * 정지·탈퇴가 남아 있는 세션에도 바로 적용되게 한다.
 * <ul>
 *   <li>정지: 쓰기 요청은 403 ACCOUNT_SUSPENDED, 읽기는 비회원으로 처리 (P-7)</li>
 *   <li>탈퇴 유예: 복구·로그아웃 외에는 403 ACCOUNT_WITHDRAWN (P-12)</li>
 *   <li>약관 재동의 필요: 동의·로그아웃 외에는 403 AGREEMENT_REQUIRED (docs/07 §3-1)</li>
 *   <li>이메일 인증 전: 글쓰기·댓글·사진·좋아요·신고는 403 EMAIL_NOT_VERIFIED (004 FR-008). 삭제·복구·프로필·비밀번호 등은 허용</li>
 * </ul>
 */
public class AccountStateFilter extends OncePerRequestFilter {
    private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS");
    private static final Set<String> ALWAYS_ALLOWED = Set.of("/api/auth/me", "/api/auth/logout", "/api/terms/current");
    private static final Set<String> RECONSENT_ALLOWED = Set.of("/api/auth/agreements");
    private static final Set<String> WITHDRAWN_ALLOWED = Set.of("/api/me/restore", "/api/me/withdrawal");
    /** 인증 전 회원에게 막는 쓰기 (메서드 + 경로). 새 기능이 들어오면 여기에 더한다 (004 A-7). */
    private static final List<Pattern> VERIFIED_ONLY = List.of(
            Pattern.compile("^POST /api/posts$"),
            Pattern.compile("^PUT /api/posts/\\d+(/autosave)?$"),
            Pattern.compile("^DELETE /api/posts/\\d+/draft$"),
            Pattern.compile("^POST /api/posts/\\d+/publish$"),
            Pattern.compile("^POST /api/posts/\\d+/ai-tags$"),
            Pattern.compile("^PATCH /api/posts/\\d+/visibility$"),
            Pattern.compile("^(POST|PUT|PATCH) /api/(posts/\\d+/)?comments(/.*)?$"),
            Pattern.compile("^(PUT|DELETE) /api/posts/\\d+/like$"),
            Pattern.compile("^POST /api/(images|files|uploads)(/.*)?$"),
            Pattern.compile("^PUT /api/posts/\\d+/files$"),
            Pattern.compile("^PUT /api/posts/\\d+/series$"),
            Pattern.compile("^(POST|PATCH|PUT) /api/me/series(/.*)?$"),
            Pattern.compile("^POST /api/me/profile-image$"),
            Pattern.compile("^POST /api/reports(/.*)?$"));

    private final MemberRepository members;
    private final AuthIdentityRepository identities;
    private final JsonMapper json = JsonMapper.builder().build();

    public AccountStateFilter(MemberRepository members, AuthIdentityRepository identities) {
        this.members = members;
        this.identities = identities;
    }

    static boolean requiresVerifiedEmail(String method, String path) {
        String line = method + " " + path;
        return VERIFIED_ONLY.stream().anyMatch(p -> p.matcher(line).matches());
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !RequestPaths.of(request).startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        MemberPrincipal principal = CurrentMemberArgumentResolver.current();
        if (principal == null) {
            chain.doFilter(request, response);
            return;
        }
        String path = RequestPaths.of(request);
        MemberStatus status = members.findById(principal.id()).map(Member::getStatus).orElse(null);
        if (status == null) {
            SecurityContextHolder.clearContext();
            chain.doFilter(request, response);
            return;
        }
        boolean safe = SAFE_METHODS.contains(request.getMethod());
        if (status == MemberStatus.SUSPENDED) {
            if (!safe && !path.equals("/api/auth/logout")) {
                deny(response, HttpStatus.FORBIDDEN, "ACCOUNT_SUSPENDED", "정지된 계정이에요.");
                return;
            }
            if (!ALWAYS_ALLOWED.contains(path)) SecurityContextHolder.clearContext();
            chain.doFilter(request, response);
            return;
        }
        if (status == MemberStatus.WITHDRAWN && !ALWAYS_ALLOWED.contains(path) && !WITHDRAWN_ALLOWED.contains(path)) {
            deny(response, HttpStatus.FORBIDDEN, "ACCOUNT_WITHDRAWN", "탈퇴 신청한 계정이에요. 복구하면 다시 이용할 수 있어요.");
            return;
        }
        if (principal.agreementRequired() && !ALWAYS_ALLOWED.contains(path) && !RECONSENT_ALLOWED.contains(path)) {
            deny(response, HttpStatus.FORBIDDEN, "AGREEMENT_REQUIRED", "바뀐 약관에 동의해 주세요.");
            return;
        }
        if (!safe && requiresVerifiedEmail(request.getMethod(), path)
                && !identities.findByMemberId(principal.id()).map(i -> i.isEmailVerified()).orElse(false)) {
            deny(response, HttpStatus.FORBIDDEN, "EMAIL_NOT_VERIFIED", "이메일 인증을 마쳐야 할 수 있어요. 인증 메일을 다시 받을 수 있어요.");
            return;
        }
        chain.doFilter(request, response);
    }

    private void deny(HttpServletResponse response, HttpStatus status, String code, String message) throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(json.writeValueAsString(ErrorResponse.of(code, message)));
    }
}
