package com.team.blog.account.web;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.team.blog.account.application.AgreementService;
import com.team.blog.account.application.EmailVerification;
import com.team.blog.account.application.MemberQueryService;
import com.team.blog.account.application.PendingSignup;
import com.team.blog.account.application.SignupService;
import com.team.blog.account.infra.oauth.SocialAvatarRelay;
import com.team.blog.media.ProfileImages;
import com.team.blog.shared.config.BlogProperties;
import com.team.blog.shorts.application.ShortsProperties;
import com.team.blog.point.application.PointProperties;
import com.team.blog.trade.application.TradeProperties;
import com.team.blog.shared.error.ApiException;
import com.team.blog.shared.security.CurrentMember;
import com.team.blog.shared.security.LoginSessions;
import com.team.blog.shared.security.MemberPrincipal;
import com.team.blog.shared.time.Times;
import com.team.blog.shared.web.SafeRedirects;

@RestController
@RequestMapping("/api")
public class AuthController {
    private final SignupService signupService;
    private final AgreementService agreementService;
    private final MemberQueryService memberQuery;
    private final EmailVerification verification;
    private final ProfileImages profileImages;
    private final LoginSessions sessions;
    private final SocialAvatarRelay avatarRelay;
    private final BlogProperties props;
    private final ShortsProperties shortsProps;
    private final TradeProperties tradeProps;
    private final PointProperties pointProps;
    private final Clock clock;

    public AuthController(SignupService signupService, AgreementService agreementService, MemberQueryService memberQuery,
                          EmailVerification verification, ProfileImages profileImages, LoginSessions sessions,
                          SocialAvatarRelay avatarRelay, BlogProperties props, ShortsProperties shortsProps, TradeProperties tradeProps,
                          PointProperties pointProps, Clock clock) {
        this.signupService = signupService;
        this.agreementService = agreementService;
        this.memberQuery = memberQuery;
        this.verification = verification;
        this.profileImages = profileImages;
        this.sessions = sessions;
        this.avatarRelay = avatarRelay;
        this.props = props;
        this.shortsProps = shortsProps;
        this.tradeProps = tradeProps;
        this.pointProps = pointProps;
        this.clock = clock;
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
            boolean pending = session != null && validPending(session) != null;
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

    public record SignupDraftResponse(String provider, String prefix, String handleBody, String nickname, String email,
                                      String avatarUrl, boolean emailRequired, TermsView terms) {}

    @GetMapping("/auth/signup")
    public SignupDraftResponse signupDraft(HttpServletRequest request) {
        PendingSignup pending = requirePending(request);
        SignupService.SignupDraft d = signupService.draftFor(pending);
        return new SignupDraftResponse(d.provider().name(), d.prefix(), d.handleBody(), d.nickname(), d.email(), d.avatarUrl(),
                d.emailRequired(), terms());
    }

    /**
     * 가입 대기 중인 사람의 소셜 사진을 대신 받아 준다 (080 FR-012). 카카오 사진 서버처럼 브라우저가 직접 가공할 수 없을 때 가입 화면이 쓴다.
     * 소셜 인증이 돌려준 주소만 받으므로 다른 주소를 요청하게 만들 수 없다.
     */
    @GetMapping("/auth/signup/avatar")
    public ResponseEntity<byte[]> signupAvatar(HttpServletRequest request) {
        PendingSignup pending = requirePending(request);
        SocialAvatarRelay.Image image = avatarRelay.fetch(pending.profile().avatarUrl())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "AVATAR_NOT_AVAILABLE", "소셜 사진을 가져오지 못했어요."));
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(image.contentType()))
                .cacheControl(CacheControl.noStore()).body(image.data());
    }

    /** @param agreeAi 선택 항목이라 보내지 않으면 동의하지 않은 것으로 본다 */
    public record SignupRequest(String handleBody, String nickname, boolean agreeTerms, boolean agreePrivacy, String email,
                                Boolean agreeAi) {}

    @PostMapping("/auth/signup")
    public ResponseEntity<Map<String, String>> signup(@RequestBody SignupRequest body, HttpServletRequest request,
                                                      HttpServletResponse response) {
        PendingSignup pending = requirePending(request);
        MemberPrincipal principal = signupService.complete(pending,
                new SignupService.SignupForm(body.handleBody(), body.nickname(), body.agreeTerms(), body.agreePrivacy(), body.email(),
                        Boolean.TRUE.equals(body.agreeAi())));
        HttpSession session = request.getSession(true);
        session.removeAttribute(PendingSignup.SESSION_KEY);
        String target = LoginFlow.popRedirect(session);
        sessions.login(request, response, principal);
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("handle", principal.handle(), "redirect", target));
    }

    public record AgreementRequest(boolean agreeTerms, boolean agreePrivacy) {}

    @PostMapping("/auth/agreements")
    public ResponseEntity<Void> agree(@CurrentMember MemberPrincipal principal, @RequestBody AgreementRequest body,
                                      HttpServletRequest request, HttpServletResponse response) {
        if (!body.agreeTerms() || !body.agreePrivacy()) {
            throw ApiException.badRequest("AGREEMENT_REQUIRED", "필수 약관에 모두 동의해 주세요.");
        }
        agreementService.acceptCurrent(principal.id());
        sessions.refresh(request, response, principal.withAgreementAccepted());
        return ResponseEntity.noContent().build();
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

    public record TermsView(String termsVersion, String termsEffectiveDate, String privacyVersion, String privacyEffectiveDate) {}

    @GetMapping("/terms/current")
    public TermsView terms() {
        BlogProperties.Agreements a = props.agreements();
        return new TermsView(a.termsVersion(), a.termsEffectiveDate(), a.privacyVersion(), a.privacyEffectiveDate());
    }

    /** 로그인 화면에서 돌아갈 주소를 미리 정해 둘 때 (이메일 로그인 등). */
    @PostMapping("/auth/redirect")
    public ResponseEntity<Void> rememberRedirect(@RequestBody Map<String, String> body, HttpServletRequest request) {
        request.getSession(true).setAttribute(LoginFlow.REDIRECT_KEY, SafeRedirects.sanitize(body.get("redirect")));
        return ResponseEntity.noContent().build();
    }

    private PendingSignup requirePending(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        PendingSignup pending = session == null ? null : validPending(session);
        if (pending == null) {
            throw new ApiException(HttpStatus.GONE, "SIGNUP_EXPIRED", "가입 정보가 만료됐어요. 소셜 로그인부터 다시 해 주세요.");
        }
        return pending;
    }

    private PendingSignup validPending(HttpSession session) {
        if (!(session.getAttribute(PendingSignup.SESSION_KEY) instanceof PendingSignup p)) return null;
        if (p.isExpired(Times.now(clock), props.auth().pendingSignupTtl())) {
            session.removeAttribute(PendingSignup.SESSION_KEY);
            return null;
        }
        return p;
    }
}
