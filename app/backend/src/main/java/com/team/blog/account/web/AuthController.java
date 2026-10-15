package com.team.blog.account.web;

import java.time.Instant;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.team.blog.account.application.EmailVerification;
import com.team.blog.account.application.MemberQueryService;
import com.team.blog.media.ProfileImages;
import com.team.blog.shorts.application.ShortsProperties;
import com.team.blog.point.application.PointProperties;
import com.team.blog.trade.application.TradeProperties;
import com.team.blog.shared.security.CurrentMember;
import com.team.blog.shared.security.MemberPrincipal;
import com.team.blog.shared.web.SafeRedirects;

@RestController
@RequestMapping("/api")
public class AuthController {
    private final MemberQueryService memberQuery;
    private final EmailVerification verification;
    private final ProfileImages profileImages;
    private final ShortsProperties shortsProps;
    private final TradeProperties tradeProps;
    private final PointProperties pointProps;
    private final PendingSignups pendings;

    public AuthController(MemberQueryService memberQuery, EmailVerification verification, ProfileImages profileImages,
                          ShortsProperties shortsProps, TradeProperties tradeProps, PointProperties pointProps,
                          PendingSignups pendings) {
        this.memberQuery = memberQuery;
        this.verification = verification;
        this.profileImages = profileImages;
        this.shortsProps = shortsProps;
        this.tradeProps = tradeProps;
        this.pointProps = pointProps;
        this.pendings = pendings;
    }

    /** @param emailVerified 메일 인증 전이면 쓰기 행동이 막힌다 (004 FR-008) */
    public record MeResponse(boolean authenticated, MemberView member, boolean agreementRequired,
                             PreviousLogin previousLogin, boolean pendingSignup, boolean emailVerified, Features features) {}

    /** 서버 설정으로 켜고 끄는 기능. 화면이 메뉴를 보일지 정한다 (099 FR-008) */
    /** @param trades 매매 일지 (#243, TRADES_ENABLED), points 블로그 포인트 (#266, POINTS_ENABLED) */
    public record Features(boolean shorts, boolean trades, boolean points) {}

    public record MemberView(long id, String handle, String nickname, String role, String defaultVisibility, String status,
                             String profileImageUrl) {}

    /** 직전 로그인 (docs/07 §6). at이 null이면 첫 로그인. */
    public record PreviousLogin(Instant at, String provider) {}

    @GetMapping("/auth/me")
    public MeResponse me(@CurrentMember(required = false) MemberPrincipal principal, HttpServletRequest request) {
        if (principal == null) {
            HttpSession session = request.getSession(false);
            boolean pending = session != null && pendings.valid(session) != null;
            return new MeResponse(false, null, false, null, pending, false, features());
        }
        MemberView view = memberQuery.findById(principal.id())
                .map(m -> new MemberView(m.id(), m.handle(), m.nickname(), principal.role(), m.defaultVisibility().name(), m.status().name(),
                        profileImages.currentUrl(m.id()).orElse(null)))
                .orElse(null);
        return new MeResponse(view != null, view, principal.agreementRequired(),
                new PreviousLogin(principal.previousLoginAt(), principal.provider()), false,
                view != null && verification.isVerified(principal.id()), features());
    }

    private Features features() {
        return new Features(shortsProps.enabled(), tradeProps.enabled(), pointProps.enabled());
    }

    public record LoginErrorResponse(String code, Instant suspendedUntil, String reason) {}

    /** 직전 로그인 실패 이유를 한 번만 돌려준다 (정지 기한·사유 안내). */
    @GetMapping("/auth/login-error")
    public ResponseEntity<LoginErrorResponse> loginError(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session == null) return ResponseEntity.noContent().build();
        Object v = session.getAttribute(LoginFlow.ERROR_KEY);
        session.removeAttribute(LoginFlow.ERROR_KEY);
        if (!(v instanceof LoginFlow.LoginError e)) return ResponseEntity.noContent().build();
        return ResponseEntity.ok(new LoginErrorResponse(e.code(), e.suspendedUntil(), e.reason()));
    }

    /** 로그인 화면에서 돌아갈 주소를 미리 정해 둘 때 (이메일 로그인 등). */
    @PostMapping("/auth/redirect")
    public ResponseEntity<Void> rememberRedirect(@RequestBody Map<String, String> body, HttpServletRequest request) {
        request.getSession(true).setAttribute(LoginFlow.REDIRECT_KEY, SafeRedirects.sanitize(body.get("redirect")));
        return ResponseEntity.noContent().build();
    }
}
