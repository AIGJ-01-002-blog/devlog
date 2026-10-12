package com.team.blog.friend.application;

import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.team.blog.account.application.MemberEvents;

/**
 * 탈퇴 신청 즉시 친구 관계(요청 중·수락 모두, 양쪽)를 지운다. 개인정보 처리방침 "탈퇴하면 친구 관계는 바로 지우고"와 맞춘다.
 * 탈퇴 신청과 같은 트랜잭션에서 지워서, 탈퇴는 됐는데 친구 관계가 남는 일이 없다. 30일 안에 복구해도 되살리지 않는다(다시 친구 신청).
 * 30일 뒤 정리 단계(FriendshipWithdrawalPurgeStep)는 그대로 두어 이 변경 전에 탈퇴한 회원도 정리한다.
 */
@Component
class FriendshipWithdrawalListener {
    private final JdbcTemplate jdbc;

    FriendshipWithdrawalListener(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @EventListener
    void onWithdrawn(MemberEvents.MemberWithdrawn e) {
        jdbc.update("DELETE FROM friendship WHERE member_a_id = ? OR member_b_id = ?", e.memberId(), e.memberId());
    }
}
