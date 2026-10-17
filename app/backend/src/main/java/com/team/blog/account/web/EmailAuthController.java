package com.team.blog.account.web;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.team.blog.account.application.EmailAccountService;
import com.team.blog.account.application.EmailVerification;
import com.team.blog.account.application.LoginOutcome;
import com.team.blog.account.application.PasswordService;
import com.team.blog.account.application.SignupEmailCode;
import com.team.blog.shared.config.BlogProperties;
import com.team.blog.shared.error.ApiException;
import com.team.blog.shared.security.CurrentMember;
import com.team.blog.shared.security.LoginSessions;
import com.team.blog.shared.security.MemberPrincipal;
import com.team.blog.shared.web.ClientIpResolver;
import com.team.blog.shared.web.RateLimiter;
import com.team.blog.shared.web.TooManyRequestsException;

/** 이메일 가입·로그인·인증, 비밀번호 재설정·변경 (004). 같은 IP 로그인 제한과 저장소 장애 처리는 LoginGuardFilter가 한다. */
@RestController
public class EmailAuthController {
    private final EmailAccountService accounts;
    private final EmailVerification verification;
    private final PasswordService passwords;
    private final LoginSessions sessions;
    private final ClientIpResolver ipResolver;
    private final ClientRegistrationRepository clients;
    private final SignupEmailCode signupCode;
    private final RateLimiter rateLimiter;
    private final int signupPerIpPerHour;

    /** 가입 화면에서 인증번호를 맞힌 이메일과 그 시각 (spec 065). 이 브라우저 세션에만 둔다 */
    static final String VERIFIED_EMAIL = "signup.verifiedEmail";
    static final String VERIFIED_AT = "signup.verifiedAt";
    static final String SIGNUP_IP_KEY = "signup:ip:";
    static final Duration SIGNUP_WINDOW = Duration.ofHours(1);

    public EmailAuthController(EmailAccountService accounts, EmailVerification verification, PasswordService passwords,
                               LoginSessions sessions, ClientIpResolver ipResolver, ClientRegistrationRepository clients,
                               SignupEmailCode signupCode, RateLimiter rateLimiter, BlogProperties props) {
        this.rateLimiter = rateLimiter;
        this.signupPerIpPerHour = props.auth().signupPerIpPerHour();
        this.clients = clients;
        this.signupCode = signupCode;
        this.accounts = accounts;
        this.verification = verification;
        this.passwords = passwords;
        this.sessions = sessions;
        this.ipResolver = ipResolver;
    }

    /** 화면이 보여 줄 로그인 수단 (설정된 소셜만). */
    @GetMapping("/api/auth/providers")
    public Map<String, Object> providers() {
        List<String> social = new ArrayList<>();
        if (clients instanceof Iterable<?> it) {
            for (Object r : it) social.add(((ClientRegistration) r).getRegistrationId());
        }
        return Map.of("email", true, "social", social, "emailCode", signupCode.required());
    }

    public record EmailCodeRequest(String email) {}

    /** 가입 전 인증번호 보내기 (spec 065). 이미 가입된 이메일이면 보내지 않고 알려 준다(가입 응답과 같은 노출 범위). */
    @PostMapping("/api/auth/signup/email-code")
    public ResponseEntity<Map<String, Object>> sendCode(@RequestBody EmailCodeRequest b, HttpServletRequest request) {
        signupCode.send(b.email(), ipResolver.resolve(request));
        return ResponseEntity.accepted().body(Map.of("expiresInSeconds", SignupEmailCode.CODE_TTL.toSeconds()));
    }

    public record EmailCodeConfirm(String email, String code) {}

    @PostMapping("/api/auth/signup/email-code/verify")
    public Map<String, Object> confirmCode(@RequestBody EmailCodeConfirm b, HttpServletRequest request) {
        String email = signupCode.confirm(b.email(), b.code());
        HttpSession session = request.getSession(true);
        session.setAttribute(VERIFIED_EMAIL, email);
        session.setAttribute(VERIFIED_AT, Instant.now());
        return Map.of("verified", true, "email", email);
    }

    /** 인증한 지 30분이 지났으면 없는 것으로 본다. */
    private static String verifiedEmail(HttpSession session) {
        if (session == null) return null;
        Object email = session.getAttribute(VERIFIED_EMAIL);
        Object at = session.getAttribute(VERIFIED_AT);
        if (!(email instanceof String e) || !(at instanceof Instant t)) return null;
        return t.plus(SignupEmailCode.VERIFIED_TTL).isAfter(Instant.now()) ? e : null;
    }

    /** agreeAi는 선택 항목이라 보내지 않으면 동의하지 않은 것으로 본다. */
    public record EmailSignupRequest(String email, String handleBody, String password, String passwordConfirm,
                                     String nickname, boolean agreeTerms, boolean agreePrivacy, Boolean agreeAi) {}

    @PostMapping("/api/auth/signup/email")
    public ResponseEntity<Map<String, String>> signup(@RequestBody EmailSignupRequest b, HttpServletRequest request,
                                                      HttpServletResponse response) {
        // 같은 IP의 가입은 만든 계정 수로 센다. 동시에 들어온 가입이 한도를 함께 넘지 않게 먼저 한 자리를 잡고,
        // 가입이 실패하면(입력 오류 등) 그 자리를 돌려준다 (보안 점검 2026-10-16)
        String ipKey = SIGNUP_IP_KEY + ipResolver.resolve(request);
        if (!rateLimiter.tryAcquire(ipKey, signupPerIpPerHour, SIGNUP_WINDOW)) {
            rateLimiter.release(ipKey);
            throw new TooManyRequestsException(SIGNUP_WINDOW.toSeconds(), "TOO_MANY_SIGNUPS",
                    "이 네트워크에서 가입이 너무 많아요. 잠시 후 다시 시도해 주세요.");
        }
        MemberPrincipal principal;
        try {
            principal = accounts.signup(new EmailAccountService.EmailSignupForm(b.email(), b.handleBody(),
                    b.password(), b.passwordConfirm(), b.nickname(), b.agreeTerms(), b.agreePrivacy(), Boolean.TRUE.equals(b.agreeAi())),
                    verifiedEmail(request.getSession(false)));
        } catch (RuntimeException e) {
            rateLimiter.release(ipKey);
            throw e;
        }
        HttpSession before = request.getSession(false);
        if (before != null) {
            before.removeAttribute(VERIFIED_EMAIL);
            before.removeAttribute(VERIFIED_AT);
        }
        String target = LoginFlow.popRedirect(request.getSession(true));
        sessions.login(request, response, principal);
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("handle", principal.handle(), "redirect", target));
    }

    public record LoginRequest(String email, String password) {}

    public record SuspendedDetails(Instant suspendedUntil, String reason) {}

    @PostMapping("/api/auth/login")
    public Map<String, String> login(@RequestBody LoginRequest b, HttpServletRequest request, HttpServletResponse response) {
        LoginOutcome outcome = accounts.login(b.email(), b.password());
        HttpSession session = request.getSession(true);
        return switch (outcome) {
            case LoginOutcome.LoggedIn(MemberPrincipal principal) -> {
                String target = LoginFlow.popRedirect(session);
                sessions.login(request, response, principal);
                yield Map.of("redirect", principal.agreementRequired()
                        ? "/agreements?redirect=" + java.net.URLEncoder.encode(target, java.nio.charset.StandardCharsets.UTF_8)
                        : target);
            }
            case LoginOutcome.Suspended(Instant endsAt, String reason) -> throw new ApiException(HttpStatus.FORBIDDEN, "ACCOUNT_SUSPENDED",
                    "정지된 계정이에요.", List.of(), new SuspendedDetails(endsAt, reason));
            case LoginOutcome.NeedsSignup n -> throw new IllegalStateException("이메일 로그인에는 가입 대기가 없다");
        };
    }

    public record TokenRequest(String token) {}

    @PostMapping("/api/auth/email/verify")
    public Map<String, Object> verify(@RequestBody TokenRequest b) {
        verification.verify(b.token());
        return Map.of("verified", true);
    }

    @PostMapping("/api/auth/email/resend")
    public ResponseEntity<Void> resend(@CurrentMember MemberPrincipal me) {
        verification.resend(me.id());
        return ResponseEntity.accepted().build();
    }

    public record ResetRequest(String email) {}

    /** 가입 여부와 상관없이 같은 응답. */
    @PostMapping("/api/auth/password/reset-request")
    public ResponseEntity<Map<String, String>> requestReset(@RequestBody ResetRequest b, HttpServletRequest request) {
        passwords.requestReset(b.email(), ipResolver.resolve(request));
        return ResponseEntity.accepted().body(Map.of("message", "가입된 이메일이면 안내 메일을 보냈어요."));
    }

    @PostMapping("/api/auth/password/reset-check")
    public Map<String, Object> checkReset(@RequestBody TokenRequest b) {
        return Map.of("valid", passwords.isResetLinkValid(b.token()));
    }

    public record ResetPasswordRequest(String token, String password, String passwordConfirm) {}

    @PostMapping("/api/auth/password/reset")
    public ResponseEntity<Void> reset(@RequestBody ResetPasswordRequest b, HttpServletRequest request, HttpServletResponse response) {
        passwords.reset(b.token(), b.password(), b.passwordConfirm());
        // 이 브라우저가 다른 계정으로 로그인해 있었어도 재설정 뒤에는 새로 로그인하게 한다
        HttpSession session = request.getSession(false);
        if (session != null) session.invalidate();
        return ResponseEntity.noContent().build();
    }

    public record ChangePasswordRequest(String currentPassword, String password, String passwordConfirm) {}

    @PutMapping("/api/me/password")
    public ResponseEntity<Void> change(@CurrentMember MemberPrincipal me, @RequestBody ChangePasswordRequest b,
                                       HttpServletRequest request) {
        HttpSession session = request.getSession(true);
        passwords.change(me.id(), b.currentPassword(), b.password(), b.passwordConfirm(), session.getId());
        request.changeSessionId(); // 지금 기기는 유지하되 로그인 상태 식별값은 새로 (FR-032)
        return ResponseEntity.noContent().build();
    }
}
