package com.team.blog.account.application;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.team.blog.account.domain.AgreementType;
import com.team.blog.account.domain.MemberAgreement;
import com.team.blog.account.infra.MemberAgreementRepository;
import com.team.blog.shared.config.BlogProperties;
import com.team.blog.shared.time.Times;

/** 약관·처리방침 동의와 재동의 판단 (docs/07 §3-1). */
@Service
public class AgreementService {
    private final MemberAgreementRepository agreements;
    private final BlogProperties.Agreements current;
    private final Clock clock;

    public AgreementService(MemberAgreementRepository agreements, BlogProperties props, Clock clock) {
        this.agreements = agreements;
        this.current = props.agreements();
        this.clock = clock;
    }

    public String currentVersion(AgreementType type) {
        return switch (type) {
            case TERMS -> current.termsVersion();
            case PRIVACY -> current.privacyVersion();
            case AI -> "1";
        };
    }

    /** @param agreeAi 가입 화면의 선택 항목 "AI 기능 이용 동의". 필수 약관과 따로 받아 따로 기록한다 */
    @Transactional
    public void recordSignup(long memberId, Instant now, boolean agreeAi) {
        List<MemberAgreement> rows = new ArrayList<>(List.of(
                new MemberAgreement(memberId, AgreementType.TERMS, current.termsVersion(), now),
                new MemberAgreement(memberId, AgreementType.PRIVACY, current.privacyVersion(), now)));
        if (agreeAi) rows.add(new MemberAgreement(memberId, AgreementType.AI, currentVersion(AgreementType.AI), now));
        agreements.saveAll(rows);
    }

    @Transactional(readOnly = true)
    public boolean requiresReconsent(long memberId) {
        Map<AgreementType, String> saved = agreements.findAllByMemberId(memberId).stream()
                .collect(Collectors.toMap(MemberAgreement::type, MemberAgreement::getVersion));
        return !current.termsVersion().equals(saved.get(AgreementType.TERMS))
                || !current.privacyVersion().equals(saved.get(AgreementType.PRIVACY));
    }

    @Transactional
    public void acceptCurrent(long memberId) {
        Instant now = Times.now(clock);
        for (AgreementType type : List.of(AgreementType.TERMS, AgreementType.PRIVACY)) {
            upsert(memberId, type, currentVersion(type), now);
        }
    }

    @Transactional
    public void agree(long memberId, AgreementType type, String version, Instant now) {
        upsert(memberId, type, version, now);
    }

    // 같은 트랜잭션 안에서 부르는 본체 (자기 호출은 프록시를 거치지 않는다)
    private void upsert(long memberId, AgreementType type, String version, Instant now) {
        agreements.findById(new MemberAgreement.Key(memberId, type))
                .ifPresentOrElse(a -> a.agree(version, now),
                        () -> agreements.save(new MemberAgreement(memberId, type, version, now)));
    }

    /** AI 동의 (018 FR-030): 지금 문구 버전과 일자로 기록한다. */
    @Transactional
    public void agreeAi(long memberId) {
        upsert(memberId, AgreementType.AI, currentVersion(AgreementType.AI), Times.now(clock));
    }

    @Transactional(readOnly = true)
    public boolean hasAgreed(long memberId, AgreementType type) {
        return agreements.findById(new MemberAgreement.Key(memberId, type))
                .map(a -> currentVersion(type).equals(a.getVersion()))
                .orElse(false);
    }

    /**
     * AI 동의 철회 (005 FR-025): 기록을 지워 다음 사용 때 다시 동의를 받는다. 필수 약관은 철회 대상이 아니다.
     * @return 지운 기록이 있었으면 true
     */
    @Transactional
    public boolean withdrawAi(long memberId) {
        MemberAgreement.Key key = new MemberAgreement.Key(memberId, AgreementType.AI);
        if (!agreements.existsById(key)) return false;
        agreements.deleteById(key);
        return true;
    }

    public BlogProperties.Agreements current() {
        return current;
    }
}
