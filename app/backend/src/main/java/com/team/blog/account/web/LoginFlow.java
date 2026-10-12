package com.team.blog.account.web;

import java.io.Serial;
import java.io.Serializable;
import java.time.Instant;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

import org.springframework.stereotype.Component;

import com.team.blog.account.application.LoginOutcome;
import com.team.blog.account.application.SocialLoginService;
import com.team.blog.account.application.SocialProfile;
import com.team.blog.account.application.PendingSignup;
import com.team.blog.shared.security.LoginSessions;
import com.team.blog.shared.web.SafeRedirects;

/**
 * 소셜 인증이 끝난 뒤의 공통 흐름. GitHub OAuth2 성공 핸들러와 개발용 로그인이 같은 경로를 탄다.
 * @return 브라우저를 보낼 주소
 */
@Component
public class LoginFlow {
    public static final String REDIRECT_KEY = "blog.loginRedirect";
    public static final String ERROR_KEY = "blog.loginError";

    private final SocialLoginService loginService;
    private final LoginSessions sessions;

    public LoginFlow(SocialLoginService loginService, LoginSessions sessions) {
        this.loginService = loginService;
        this.sessions = sessions;
    }

    public record LoginError(String code, Instant suspendedUntil, String reason) implements Serializable {
        @Serial
        private static final long serialVersionUID = 1L;
    }

    public String complete(SocialProfile profile, HttpServletRequest request, HttpServletResponse response) {
        LoginOutcome outcome = loginService.onAuthenticated(profile);
        HttpSession session = request.getSession(true);
        session.removeAttribute(PendingSignup.SESSION_KEY);
        return switch (outcome) {
            case LoginOutcome.LoggedIn(var principal) -> {
                String target = popRedirect(session);
                sessions.login(request, response, principal);
                yield principal.agreementRequired() ? "/agreements?redirect=" + encode(target) : target;
            }
            case LoginOutcome.NeedsSignup(PendingSignup pending) -> {
                sessions.clearAuthentication(request, response);
                request.getSession(true).setAttribute(PendingSignup.SESSION_KEY, pending);
                yield "/signup/social";
            }
            case LoginOutcome.Suspended(Instant endsAt, String reason) -> {
                sessions.clearAuthentication(request, response);
                session.setAttribute(ERROR_KEY, new LoginError("ACCOUNT_SUSPENDED", endsAt, reason));
                yield "/login?error=ACCOUNT_SUSPENDED";
            }
        };
    }

    public static String popRedirect(HttpSession session) {
        Object v = session.getAttribute(REDIRECT_KEY);
        session.removeAttribute(REDIRECT_KEY);
        return SafeRedirects.sanitize(v == null ? null : v.toString());
    }

    private static String encode(String s) {
        return java.net.URLEncoder.encode(s, java.nio.charset.StandardCharsets.UTF_8);
    }
}
