package com.team.blog.account.web;

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
import com.team.blog.account.application.PendingSignup;
import com.team.blog.account.application.SignupService;
import com.team.blog.account.infra.oauth.SocialAvatarRelay;
import com.team.blog.shared.config.BlogProperties;
import com.team.blog.shared.error.ApiException;
import com.team.blog.shared.security.CurrentMember;
import com.team.blog.shared.security.LoginSessions;
import com.team.blog.shared.security.MemberPrincipal;

/** 소셜 가입 마무리와 약관 동의. 로그인 상태 조회는 AuthController가 맡는다 */
@RestController
@RequestMapping("/api")
public class SignupController {
    private final SignupService signupService;
    private final AgreementService agreementService;
    private final LoginSessions sessions;
    private final SocialAvatarRelay avatarRelay;
    private final BlogProperties props;
    private final PendingSignups pendings;

    public SignupController(SignupService signupService, AgreementService agreementService, LoginSessions sessions,
                            SocialAvatarRelay avatarRelay, BlogProperties props, PendingSignups pendings) {
        this.signupService = signupService;
        this.agreementService = agreementService;
        this.sessions = sessions;
        this.avatarRelay = avatarRelay;
        this.props = props;
        this.pendings = pendings;
    }

    public record SignupDraftResponse(String provider, String prefix, String handleBody, String nickname, String email,
                                      String avatarUrl, boolean emailRequired, TermsView terms) {}

    @GetMapping("/auth/signup")
    public SignupDraftResponse signupDraft(HttpServletRequest request) {
        PendingSignup pending = pendings.require(request);
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
        PendingSignup pending = pendings.require(request);
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
        PendingSignup pending = pendings.require(request);
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

    public record TermsView(String termsVersion, String termsEffectiveDate, String privacyVersion, String privacyEffectiveDate) {}

    @GetMapping("/terms/current")
    public TermsView terms() {
        BlogProperties.Agreements a = props.agreements();
        return new TermsView(a.termsVersion(), a.termsEffectiveDate(), a.privacyVersion(), a.privacyEffectiveDate());
    }
}
