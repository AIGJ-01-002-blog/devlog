package com.team.blog.account.application;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.team.blog.account.domain.AuthIdentity;
import com.team.blog.account.domain.Member;
import com.team.blog.account.domain.MemberSuspension;
import com.team.blog.account.infra.AuthIdentityRepository;
import com.team.blog.account.infra.MemberRepository;
import com.team.blog.shared.security.MemberPrincipal;
import com.team.blog.shared.time.Times;

/**
 * 소셜 인증 직후 처리 (docs/07 §5): (provider, 고유 ID)로 연결된 계정이 있으면 로그인, 처음이면 가입 대기.
 */
@Service
public class SocialLoginService {
    private final AuthIdentityRepository identities;
    private final MemberRepository members;
    private final SuspensionService suspensions;
    private final AgreementService agreements;
    private final Clock clock;

    public SocialLoginService(AuthIdentityRepository identities, MemberRepository members, SuspensionService suspensions,
                              AgreementService agreements, Clock clock) {
        this.identities = identities;
        this.members = members;
        this.suspensions = suspensions;
        this.agreements = agreements;
        this.clock = clock;
    }

    @Transactional
    public LoginOutcome onAuthenticated(SocialProfile profile) {
        Instant now = Times.now(clock);
        Optional<AuthIdentity> found = identities.findByProviderAndProviderUserId(profile.provider(), profile.providerUserId());
        // 인증된 이메일이 없으면 가입 마무리 화면에서 이메일을 받아 메일로 인증한다 (004 FR-028)
        if (found.isEmpty()) return new LoginOutcome.NeedsSignup(new PendingSignup(profile, now));
        AuthIdentity identity = found.get();
        if (profile.verifiedEmail() != null && !profile.verifiedEmail().equals(identity.getEmail())) {
            identity.updateEmail(profile.verifiedEmail());
        }
        return doLogin(identity, now);
    }

    /** 로그인 수단 하나로 회원을 로그인시킨다 (이메일 로그인도 이 경로를 쓴다). */
    @Transactional
    public LoginOutcome login(AuthIdentity identity, Instant now) {
        return doLogin(identity, now);
    }

    // 같은 트랜잭션 안에서 부르는 본체 (자기 호출은 프록시를 거치지 않는다)
    private LoginOutcome doLogin(AuthIdentity identity, Instant now) {
        Member member = members.findById(identity.getMemberId()).orElseThrow();
        Optional<MemberSuspension> suspension = suspensions.activeSuspension(member, now);
        if (suspension.isPresent()) {
            return new LoginOutcome.Suspended(suspension.get().getEndsAt(), suspension.get().getReason());
        }
        Instant previous = identity.recordLogin(now);
        boolean reconsent = agreements.requiresReconsent(member.getId());
        return new LoginOutcome.LoggedIn(new MemberPrincipal(member.getId(), member.getHandle(), member.getRole().name(),
                identity.getProvider().name(), reconsent, previous));
    }
}
