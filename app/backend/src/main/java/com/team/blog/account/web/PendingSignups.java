package com.team.blog.account.web;

import java.time.Clock;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import com.team.blog.account.application.PendingSignup;
import com.team.blog.shared.config.BlogProperties;
import com.team.blog.shared.error.ApiException;
import com.team.blog.shared.time.Times;

/** 세션에 담긴 소셜 가입 대기 정보. 기한이 지났으면 지우고 없는 것으로 본다 */
@Component
class PendingSignups {
    private final BlogProperties props;
    private final Clock clock;

    PendingSignups(BlogProperties props, Clock clock) {
        this.props = props;
        this.clock = clock;
    }

    PendingSignup require(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        PendingSignup pending = session == null ? null : valid(session);
        if (pending == null) {
            throw new ApiException(HttpStatus.GONE, "SIGNUP_EXPIRED", "가입 정보가 만료됐어요. 소셜 로그인부터 다시 해 주세요.");
        }
        return pending;
    }

    PendingSignup valid(HttpSession session) {
        if (!(session.getAttribute(PendingSignup.SESSION_KEY) instanceof PendingSignup p)) return null;
        if (p.isExpired(Times.now(clock), props.auth().pendingSignupTtl())) {
            session.removeAttribute(PendingSignup.SESSION_KEY);
            return null;
        }
        return p;
    }
}
