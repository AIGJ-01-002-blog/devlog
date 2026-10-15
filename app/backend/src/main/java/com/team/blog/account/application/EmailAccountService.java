package com.team.blog.account.application;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.team.blog.account.domain.AuthIdentity;
import com.team.blog.account.domain.AuthProvider;
import com.team.blog.account.domain.Member;
import com.team.blog.account.infra.AuthIdentityRepository;
import com.team.blog.account.infra.LoginAttempts;
import com.team.blog.account.infra.MemberRepository;
import com.team.blog.shared.error.ApiException;
import com.team.blog.shared.error.FieldErrorItem;
import com.team.blog.shared.security.MemberPrincipal;
import com.team.blog.shared.time.Times;
import com.team.blog.shared.web.TooManyRequestsException;

/**
 * 이메일 가입·로그인 (docs/07 §3·§6, 004 US1·US2). 가입은 회원·로그인 수단·약관 동의를 한 트랜잭션으로 만들고,
 * 커밋 뒤 인증 메일을 보낸다. 로그인 실패는 이유를 구별하지 않고, 가입되지 않은 이메일도 같은 시간·같은 잠금으로 처리한다.
 */
@Service
public class EmailAccountService {
    static final String LOGIN_FAILED_MESSAGE = "이메일 또는 비밀번호가 올바르지 않아요.";

    private final MemberRepository members;
    private final AuthIdentityRepository identities;
    private final AgreementService agreements;
    private final HandlePolicy handlePolicy;
    private final HandleSuggester suggester;
    private final NicknamePolicy nicknamePolicy;
    private final PasswordPolicy passwordPolicy;
    private final PasswordEncoder encoder;
    private final SocialLoginService loginService;
    private final EmailVerification verification;
    private final SignupEmailCode signupCode;
    private final LoginAttempts attempts;
    private final TransactionTemplate tx;
    private final Clock clock;
    // 가입되지 않은 이메일에도 같은 비용의 비교를 해 응답 시간으로 가입 여부를 알 수 없게 한다
    private final String dummyHash;

    public EmailAccountService(MemberRepository members, AuthIdentityRepository identities, AgreementService agreements,
                               HandlePolicy handlePolicy, HandleSuggester suggester, NicknamePolicy nicknamePolicy,
                               PasswordPolicy passwordPolicy, PasswordEncoder encoder, SocialLoginService loginService,
                               EmailVerification verification, SignupEmailCode signupCode, LoginAttempts attempts, TransactionTemplate tx, Clock clock) {
        this.members = members;
        this.identities = identities;
        this.agreements = agreements;
        this.handlePolicy = handlePolicy;
        this.suggester = suggester;
        this.nicknamePolicy = nicknamePolicy;
        this.passwordPolicy = passwordPolicy;
        this.encoder = encoder;
        this.loginService = loginService;
        this.verification = verification;
        this.signupCode = signupCode;
        this.attempts = attempts;
        this.tx = tx;
        this.clock = clock;
        // 없는 이메일도 비밀번호 비교 시간을 같게 하는 해시. 맞힐 수 없도록 실행마다 무작위 값으로 만든다
        this.dummyHash = encoder.encode(UUID.randomUUID().toString());
    }

    /** @param agreeAi 선택 항목 "AI 기능 이용 동의". 동의하지 않아도 가입된다 */
    public record EmailSignupForm(String email, String handleBody, String password, String passwordConfirm,
                                  String nickname, boolean agreeTerms, boolean agreePrivacy, boolean agreeAi) {}

    /**
     * @param verifiedEmail 이 브라우저가 가입 화면에서 인증번호로 확인한 이메일 (spec 065). 인증번호가 필요한데
     *                      가입 이메일과 다르면 가입하지 않는다. 맞으면 인증된 채로 가입되어 링크 메일을 보내지 않는다
     */
    public MemberPrincipal signup(EmailSignupForm form, String verifiedEmail) {
        String email = EmailAddress.normalize(form.email());
        String body = form.handleBody() == null ? "" : form.handleBody().strip().toLowerCase(java.util.Locale.ROOT);
        String nickname = NicknamePolicy.normalize(form.nickname());

        boolean emailOk = EmailAddress.isValid(email);
        boolean verified = emailOk && email.equals(verifiedEmail);
        List<FieldErrorItem> errors = signupErrors(form, email, emailOk, body, nickname, verified);
        if (!errors.isEmpty()) throw ApiException.validation(errors);
        rejectExisting(email);

        String hash = encoder.encode(form.password());
        String handle = AuthProvider.LOCAL.handlePrefix() + body;
        MemberPrincipal principal;
        try {
            principal = tx.execute(status -> create(email, hash, handle, nickname, form.agreeAi(), verified));
        } catch (DataIntegrityViolationException e) {
            throw signupConflict(e, body);
        }
        if (!verified) verification.sendAfterSignup(principal.id(), email);
        return principal;
    }

    private List<FieldErrorItem> signupErrors(EmailSignupForm form, String email, boolean emailOk, String body,
            String nickname, boolean verified) {
        List<FieldErrorItem> errors = new ArrayList<>();
        if (!emailOk) errors.add(new FieldErrorItem("email", "EMAIL_INVALID", "이메일 형식을 확인해 주세요. (최대 254자)"));
        HandlePolicy.Reason handleReason = handlePolicy.checkBody(AuthProvider.LOCAL, body);
        if (handleReason != null) errors.add(new FieldErrorItem("handleBody", "HANDLE_" + handleReason.name(), handleReason.message()));
        errors.addAll(passwordPolicy.errors("password", form.password(), emailOk ? email : null));
        if (form.password() == null || !form.password().equals(form.passwordConfirm())) {
            errors.add(new FieldErrorItem("passwordConfirm", "PASSWORD_MISMATCH", "비밀번호가 서로 달라요."));
        }
        NicknamePolicy.Code nickCode = nicknamePolicy.checkRules(nickname);
        if (nickCode != null) errors.add(new FieldErrorItem("nickname", nickCode.name(), nickCode.message()));
        if (!form.agreeTerms()) errors.add(new FieldErrorItem("agreeTerms", "AGREEMENT_REQUIRED", "이용약관에 동의해 주세요."));
        if (!form.agreePrivacy()) errors.add(new FieldErrorItem("agreePrivacy", "AGREEMENT_REQUIRED", "개인정보 처리방침에 동의해 주세요."));
        if (emailOk && signupCode.required() && !verified) {
            errors.add(new FieldErrorItem("email", "EMAIL_NOT_VERIFIED", "이메일 인증을 먼저 마쳐 주세요."));
        }
        return errors;
    }

    /** 이미 가입된 이메일이면 막는다 */
    private void rejectExisting(String email) {
        var existing = identities.findByProviderAndProviderUserId(AuthProvider.LOCAL, email);
        if (existing.isPresent()) {
            // 탈퇴 유예 중인 계정이면 복구 방법을 알려 준다 (020 FR-020, 가입 여부 노출은 감수)
            boolean withdrawn = members.findById(existing.get().getMemberId())
                    .map(m -> m.getStatus() == com.team.blog.account.domain.MemberStatus.WITHDRAWN).orElse(false);
            if (withdrawn) throw ApiException.conflict("WITHDRAWN_ACCOUNT", "탈퇴 신청한 계정이 있어요. 로그인하면 복구할 수 있어요.");
            throw emailTaken();
        }
    }

    /** 가입 중 동시에 같은 이메일·주소·닉네임을 쓴 경우를 알맞은 오류로 바꾼다 */
    private RuntimeException signupConflict(DataIntegrityViolationException e, String body) {
        String msg = String.valueOf(e.getMostSpecificCause().getMessage());
        if (msg.contains("uq_auth_identity")) return emailTaken();
        if (msg.contains("uq_member_handle")) {
            String suggestion = suggester.firstFree(AuthProvider.LOCAL, body);
            return new ApiException(HttpStatus.CONFLICT, "HANDLE_TAKEN",
                    "방금 다른 분이 이 주소를 사용했어요. " + suggestion + "는 어떠세요?", List.of(),
                    Map.of("suggestion", HandlePolicy.bodyOf(suggestion)));
        }
        if (msg.contains("uq_member_nickname")) return ApiException.conflict("NICKNAME_TAKEN", "방금 다른 분이 이 닉네임을 사용했어요.");
        return e;
    }

    private MemberPrincipal create(String email, String hash, String handle, String nickname, boolean agreeAi, boolean verified) {
        Instant now = Times.now(clock);
        Member member = members.saveAndFlush(Member.join(handle, nickname, now));
        AuthIdentity identity = identities.saveAndFlush(AuthIdentity.local(member.getId(), email, hash, now));
        agreements.recordSignup(member.getId(), now, agreeAi);
        identity.recordLogin(now);
        if (verified) identity.markEmailVerified(now);
        return new MemberPrincipal(member.getId(), handle, member.getRole().name(), AuthProvider.LOCAL.name(), false, null);
    }

    /** 정지·재동의는 소셜과 같은 규칙(SocialLoginService.login)으로 처리한다. */
    public LoginOutcome login(String rawEmail, String password) {
        String email = EmailAddress.normalize(rawEmail);
        String subject = "login:" + email;
        if (attempts.isLocked(subject)) throw locked(attempts.lockMinutes());
        Optional<AuthIdentity> found = EmailAddress.isValid(email)
                ? identities.findByProviderAndProviderUserId(AuthProvider.LOCAL, email) : Optional.empty();
        String hash = found.map(AuthIdentity::getPasswordHash).orElse(dummyHash);
        boolean matches = password != null && encoder.matches(password, hash);
        if (found.isEmpty() || !matches) {
            attempts.recordFailure(subject);
            throw new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", LOGIN_FAILED_MESSAGE);
        }
        attempts.reset(subject);
        long identityId = found.get().getId();
        // 비밀번호 비교(느림)는 트랜잭션 밖에서, 마지막 로그인 기록은 다시 읽어 트랜잭션 안에서
        return tx.execute(s -> loginService.login(identities.findById(identityId).orElseThrow(), Times.now(clock)));
    }

    static TooManyRequestsException locked(long minutes) {
        return new TooManyRequestsException(minutes * 60, "LOGIN_LOCKED", "잠시 후 다시 시도해 주세요(약 " + minutes + "분).");
    }

    private static ApiException emailTaken() {
        return ApiException.conflict("EMAIL_TAKEN", "이미 가입된 이메일이에요.");
    }
}
