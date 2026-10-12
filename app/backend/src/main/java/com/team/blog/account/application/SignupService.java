package com.team.blog.account.application;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.team.blog.account.domain.AuthIdentity;
import com.team.blog.account.domain.AuthProvider;
import com.team.blog.account.domain.Member;
import com.team.blog.account.infra.AuthIdentityRepository;
import com.team.blog.account.infra.MemberRepository;
import com.team.blog.shared.error.ApiException;
import com.team.blog.shared.error.FieldErrorItem;
import com.team.blog.shared.security.MemberPrincipal;
import com.team.blog.shared.time.Times;

/**
 * 소셜 가입 마무리 (docs/07 §5, 001 US1). 회원·로그인 수단·약관 동의를 한 트랜잭션으로 만든다.
 * 동시에 같은 주소·닉네임으로 가입하면 DB UNIQUE가 한 명만 통과시키고, 늦은 쪽은 409 + 새 제안을 받는다.
 */
@Service
public class SignupService {
    private final MemberRepository members;
    private final AuthIdentityRepository identities;
    private final AgreementService agreements;
    private final HandlePolicy handlePolicy;
    private final HandleSuggester suggester;
    private final NicknamePolicy nicknamePolicy;
    private final SocialLoginService loginService;
    private final EmailVerification verification;
    private final TransactionTemplate tx;
    private final Clock clock;

    public SignupService(MemberRepository members, AuthIdentityRepository identities, AgreementService agreements,
                         HandlePolicy handlePolicy, HandleSuggester suggester, NicknamePolicy nicknamePolicy,
                         SocialLoginService loginService, EmailVerification verification, TransactionTemplate tx, Clock clock) {
        this.members = members;
        this.identities = identities;
        this.agreements = agreements;
        this.handlePolicy = handlePolicy;
        this.suggester = suggester;
        this.nicknamePolicy = nicknamePolicy;
        this.loginService = loginService;
        this.verification = verification;
        this.tx = tx;
        this.clock = clock;
    }

    /**
     * @param email   소셜이 인증된 이메일을 주지 않았을 때만 쓴다 (004 FR-028). 메일 인증을 거친다
     * @param agreeAi 선택 항목. 동의하지 않아도 가입되고, AI를 처음 쓸 때 다시 묻는다
     */
    public record SignupForm(String handleBody, String nickname, boolean agreeTerms, boolean agreePrivacy, String email,
                             boolean agreeAi) {}

    /** 가입 마무리 화면의 미리 채우는 값. emailRequired면 화면이 이메일을 입력받는다. */
    public record SignupDraft(AuthProvider provider, String prefix, String handleBody, String nickname,
                              String email, String avatarUrl, boolean emailRequired) {}

    public SignupDraft draftFor(PendingSignup pending) {
        SocialProfile p = pending.profile();
        String material;
        if (p.provider() == AuthProvider.GITHUB) material = p.login();
        else if (p.verifiedEmail() != null) material = localPart(p.verifiedEmail());
        else material = p.name();
        String handle = suggester.suggest(p.provider(), material);
        String nickname = nicknamePolicy.prefill(p.name() != null && !p.name().isBlank() ? p.name() : p.login());
        return new SignupDraft(p.provider(), p.provider().handlePrefix(), HandlePolicy.bodyOf(handle), nickname,
                p.verifiedEmail(), SocialAvatar.safeUrl(p.avatarUrl()), p.verifiedEmail() == null);
    }

    public MemberPrincipal complete(PendingSignup pending, SignupForm form) {
        SocialProfile profile = pending.profile();
        String body = form.handleBody() == null ? "" : form.handleBody().strip().toLowerCase(java.util.Locale.ROOT);
        String nickname = NicknamePolicy.normalize(form.nickname());

        List<FieldErrorItem> errors = new ArrayList<>();
        String email = profile.verifiedEmail();
        boolean emailVerified = email != null;
        if (!emailVerified) {
            email = EmailAddress.normalize(form.email());
            if (!EmailAddress.isValid(email)) errors.add(new FieldErrorItem("email", "EMAIL_INVALID", "이메일 형식을 확인해 주세요. (최대 254자)"));
        }
        HandlePolicy.Reason handleReason = handlePolicy.checkBody(profile.provider(), body);
        if (handleReason != null) errors.add(new FieldErrorItem("handleBody", "HANDLE_" + handleReason.name(), handleReason.message()));
        NicknamePolicy.Code nickCode = nicknamePolicy.checkRules(nickname);
        if (nickCode != null) errors.add(new FieldErrorItem("nickname", nickCode.name(), nickCode.message()));
        if (!form.agreeTerms()) errors.add(new FieldErrorItem("agreeTerms", "AGREEMENT_REQUIRED", "이용약관에 동의해 주세요."));
        if (!form.agreePrivacy()) errors.add(new FieldErrorItem("agreePrivacy", "AGREEMENT_REQUIRED", "개인정보 처리방침에 동의해 주세요."));
        if (!errors.isEmpty()) throw ApiException.validation(errors);

        String handle = profile.provider().handlePrefix() + body;
        String finalEmail = email;
        try {
            MemberPrincipal principal = tx.execute(status -> create(profile, handle, nickname, finalEmail, emailVerified, form.agreeAi()));
            if (!emailVerified) verification.sendAfterSignup(principal.id(), finalEmail);
            return principal;
        } catch (DataIntegrityViolationException e) {
            String msg = String.valueOf(e.getMostSpecificCause().getMessage());
            if (msg.contains("uq_auth_identity")) {
                // 같은 소셜 계정으로 두 번 제출: 이미 만들어진 계정으로 로그인한다 (계정은 하나뿐)
                return tx.execute(status -> loggedInExisting(profile));
            }
            if (msg.contains("uq_member_handle")) {
                String suggestion = suggester.firstFree(profile.provider(), body);
                throw new ApiException(HttpStatus.CONFLICT, "HANDLE_TAKEN",
                        "방금 다른 분이 이 주소를 사용했어요. " + suggestion + "는 어떠세요?", List.of(),
                        Map.of("suggestion", HandlePolicy.bodyOf(suggestion)));
            }
            if (msg.contains("uq_member_nickname")) {
                throw ApiException.conflict("NICKNAME_TAKEN", "방금 다른 분이 이 닉네임을 사용했어요.");
            }
            throw e;
        }
    }

    private MemberPrincipal create(SocialProfile profile, String handle, String nickname, String email, boolean emailVerified,
                                   boolean agreeAi) {
        Instant now = Times.now(clock);
        Member member = members.saveAndFlush(Member.join(handle, nickname, now));
        AuthIdentity identity = identities.saveAndFlush(
                AuthIdentity.social(member.getId(), profile.provider(), profile.providerUserId(), email, emailVerified, now));
        agreements.recordSignup(member.getId(), now, agreeAi);
        identity.recordLogin(now);
        return new MemberPrincipal(member.getId(), handle, member.getRole().name(), profile.provider().name(), false, null);
    }

    private MemberPrincipal loggedInExisting(SocialProfile profile) {
        AuthIdentity identity = identities.findByProviderAndProviderUserId(profile.provider(), profile.providerUserId()).orElseThrow();
        LoginOutcome outcome = loginService.login(identity, Times.now(clock));
        if (outcome instanceof LoginOutcome.LoggedIn(MemberPrincipal principal)) return principal;
        throw ApiException.conflict("SIGNUP_FAILED", "로그인할 수 없는 계정이에요.");
    }

    private static String localPart(String email) {
        if (email == null) return "";
        int at = email.indexOf('@');
        return at < 0 ? email : email.substring(0, at);
    }
}
