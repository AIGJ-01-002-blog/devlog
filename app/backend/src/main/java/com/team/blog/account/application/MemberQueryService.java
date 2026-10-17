package com.team.blog.account.application;

import java.util.Locale;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.team.blog.account.domain.Member;
import com.team.blog.account.domain.MemberStatus;
import com.team.blog.account.domain.Role;
import com.team.blog.account.domain.Visibility;
import com.team.blog.account.infra.MemberRepository;

/** 다른 모듈이 회원 정보를 읽는 유일한 통로 (헌법 IV: 다른 모듈의 Repository를 직접 쓰지 않는다). */
@Service
@Transactional(readOnly = true)
public class MemberQueryService {
    private final MemberRepository members;

    public MemberQueryService(MemberRepository members) {
        this.members = members;
    }

    public record MemberSummary(long id, String handle, String nickname, String bio, MemberStatus status,
                                Visibility defaultVisibility, boolean withdrawn, boolean lastActiveVisible) {
        static MemberSummary of(Member m) {
            return new MemberSummary(m.getId(), m.getHandle(), m.getNickname(), m.getBio(), m.getStatus(),
                    m.getDefaultVisibility(), m.getWithdrawnAt() != null, m.isLastActiveVisible());
        }
    }

    public Optional<MemberSummary> findById(long id) {
        return members.findById(id).map(MemberSummary::of);
    }

    /** 대소문자를 무시하고 찾는다(대문자 주소 → 소문자 301 판단용). */
    public Optional<MemberSummary> findByHandle(String handle) {
        if (handle == null) return Optional.empty();
        return members.findByHandle(handle.toLowerCase(Locale.ROOT)).map(MemberSummary::of);
    }

    /** 공개 화면용: 탈퇴했거나 정리된(deleted) 회원은 없는 것으로 본다. */
    public Optional<MemberSummary> findActiveByHandle(String handle) {
        if (handle == null) return Optional.empty();
        return members.findByHandle(handle.toLowerCase(Locale.ROOT))
                .filter(m -> m.getWithdrawnAt() == null && m.getDeletedAt() == null)
                .map(MemberSummary::of);
    }

    /** 정리되지 않은 관리자 회원인지 (MCP 관리자 도구 켜기 판단용). */
    public boolean isAdmin(long id) {
        return members.findById(id).filter(m -> m.getDeletedAt() == null && m.getRole() == Role.ADMIN).isPresent();
    }
}
