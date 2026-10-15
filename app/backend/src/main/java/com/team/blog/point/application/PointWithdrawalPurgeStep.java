package com.team.blog.point.application;

import org.springframework.stereotype.Component;

import com.team.blog.account.application.WithdrawalPurgeStep;

/** (51) 탈퇴 정리 때 포인트 원장과 출석 기록을 지운다 (#266). 포인트가 꺼져 있어도 남은 것은 지운다 */
@Component
class PointWithdrawalPurgeStep implements WithdrawalPurgeStep {
    private final PointLedger points;

    PointWithdrawalPurgeStep(PointLedger points) {
        this.points = points;
    }

    @Override
    public int order() {
        return 51;
    }

    @Override
    public void purge(long memberId) {
        points.purge(memberId);
    }
}
