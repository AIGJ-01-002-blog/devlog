package com.team.blog.account.application;

import java.security.SecureRandom;
import java.time.Duration;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import com.team.blog.account.domain.AuthProvider;
import com.team.blog.account.domain.MemberStatus;
import com.team.blog.account.infra.AuthIdentityRepository;
import com.team.blog.account.infra.AuthTokens;
import com.team.blog.account.infra.MemberRepository;
import com.team.blog.shared.config.BlogProperties;
import com.team.blog.shared.error.ApiException;
import com.team.blog.shared.mail.Mailer;
import com.team.blog.shared.web.RateLimiter;

/**
 * 가입 화면 이메일 인증번호 (spec 065). 가입 전에 6자리 번호를 메일로 보내고, 같은 화면에서 맞히면 그 이메일로 가입할 수 있다.
 * 번호는 10분·마지막 것만 유효하고, 5번 틀리면 버린다. Redis에는 해시만 둔다.
 * 보내기는 이메일마다 1분에 1번·하루 10번, 같은 IP 1시간에 20번이다.
 * 메일을 실제로 보내지 않는 운영(SMTP 미설정)에서는 번호를 받을 수 없으므로 요구하지 않고, 가입 뒤 링크 인증(004)으로 둔다.
 */
@Component
public class SignupEmailCode {
    public static final Duration CODE_TTL = Duration.ofMinutes(10);
    /** 인증을 마친 뒤 가입하기를 누를 때까지의 여유 */
    public static final Duration VERIFIED_TTL = Duration.ofMinutes(30);
    static final int MAX_TRIES = 5;
    private static final SecureRandom RANDOM = new SecureRandom();

    public enum EmailState {
        AVAILABLE(null), INVALID("이메일 형식을 확인해 주세요."), TAKEN("이미 가입된 이메일이에요."),
        WITHDRAWN("탈퇴 신청한 계정이 있어요. 로그인하면 복구할 수 있어요.");

        private final String message;

        EmailState(String message) {
            this.message = message;
        }

        public String message() {
            return message;
        }
    }

    private final StringRedisTemplate redis;
    private final AuthIdentityRepository identities;
    private final MemberRepository members;
    private final Mailer mailer;
    private final AccountMails mails;
    private final RateLimiter limiter;
    private final boolean required;

    public SignupEmailCode(StringRedisTemplate redis, AuthIdentityRepository identities, MemberRepository members,
                           Mailer mailer, AccountMails mails, RateLimiter limiter, BlogProperties props) {
        this.redis = redis;
        this.identities = identities;
        this.members = members;
        this.mailer = mailer;
        this.mails = mails;
        this.limiter = limiter;
        // auto: 개발 환경은 보관함(/api/dev/mails)에서 번호를 볼 수 있으므로 요구한다
        this.required = switch (String.valueOf(props.auth().signupEmailCode())) {
            case "on" -> true;
            case "off" -> false;
            default -> mailer.delivers() || props.devLogin().enabled();
        };
    }

    /** 이메일 가입에 인증번호가 필요한지 (화면이 [인증] 버튼을 켤지). */
    public boolean required() {
        return required;
    }

    /** @param email normalize를 거친 값 */
    public EmailState state(String email) {
        if (!EmailAddress.isValid(email)) return EmailState.INVALID;
        var existing = identities.findByProviderAndProviderUserId(AuthProvider.LOCAL, email);
        if (existing.isEmpty()) return EmailState.AVAILABLE;
        boolean withdrawn = members.findById(existing.get().getMemberId())
                .map(m -> m.getStatus() == MemberStatus.WITHDRAWN).orElse(false);
        return withdrawn ? EmailState.WITHDRAWN : EmailState.TAKEN;
    }

    public void send(String rawEmail, String ip) {
        String email = EmailAddress.normalize(rawEmail);
        EmailState state = state(email);
        switch (state) {
            case INVALID -> throw ApiException.badRequest("EMAIL_INVALID", state.message());
            case TAKEN -> throw ApiException.conflict("EMAIL_TAKEN", state.message());
            case WITHDRAWN -> throw ApiException.conflict("WITHDRAWN_ACCOUNT", state.message());
            case AVAILABLE -> {
                // 보낼 수 있는 상태라 그대로 진행한다
            }
        }
        limiter.check("signup-code:min:" + email, 1, Duration.ofMinutes(1));
        limiter.check("signup-code:day:" + email, 10, Duration.ofDays(1));
        limiter.check("signup-code:ip:" + ip, 20, Duration.ofHours(1));
        String code = "%06d".formatted(RANDOM.nextInt(1_000_000));
        try {
            redis.opsForValue().set(codeKey(email), AuthTokens.hash(code), CODE_TTL);
            redis.delete(triesKey(email));
        } catch (RuntimeException e) {
            throw unavailable();
        }
        mailer.send(mails.signupCode(email, code, CODE_TTL));
    }

    /** 맞으면 번호를 지우고 정리된 이메일을 돌려준다. */
    public String confirm(String rawEmail, String code) {
        String email = EmailAddress.normalize(rawEmail);
        String c = code == null ? "" : code.strip();
        String stored;
        try {
            stored = EmailAddress.isValid(email) ? redis.opsForValue().get(codeKey(email)) : null;
        } catch (RuntimeException e) {
            throw unavailable();
        }
        if (stored == null) throw new ApiException(HttpStatus.GONE, "CODE_EXPIRED", "인증번호가 만료됐어요. 다시 받아 주세요.");
        if (!c.matches("\\d{6}") || !stored.equals(AuthTokens.hash(c))) {
            long tries;
            try {
                Long n = redis.opsForValue().increment(triesKey(email));
                redis.expire(triesKey(email), CODE_TTL);
                tries = n == null ? 0 : n;
                if (tries >= MAX_TRIES) redis.delete(codeKey(email));
            } catch (RuntimeException e) {
                throw unavailable();
            }
            if (tries >= MAX_TRIES) {
                throw new ApiException(HttpStatus.GONE, "CODE_TOO_MANY_TRIES", "여러 번 틀려서 인증번호를 버렸어요. 다시 받아 주세요.");
            }
            throw ApiException.badRequest("CODE_MISMATCH", "인증번호가 맞지 않아요. (" + (MAX_TRIES - tries) + "번 남음)");
        }
        try {
            redis.delete(codeKey(email));
            redis.delete(triesKey(email));
        } catch (RuntimeException ignored) {
            // 번호는 10분 뒤 저절로 사라진다
        }
        return email;
    }

    private static String codeKey(String email) {
        return "signup-code:" + AuthTokens.hash(email);
    }

    private static String triesKey(String email) {
        return "signup-code:tries:" + AuthTokens.hash(email);
    }

    private static ApiException unavailable() {
        return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "TEMPORARILY_UNAVAILABLE", "잠시 후 다시 시도해 주세요.");
    }
}
