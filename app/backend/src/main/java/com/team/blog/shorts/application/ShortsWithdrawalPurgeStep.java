package com.team.blog.shorts.application;

import org.springframework.stereotype.Component;

import com.team.blog.account.application.WithdrawalPurgeStep;

/** (48) 탈퇴 정리 때 쇼츠 영상 파일과 대본을 지운다 (099 FR-001). 쇼츠가 꺼져 있어도 남은 것은 지운다 */
@Component
class ShortsWithdrawalPurgeStep implements WithdrawalPurgeStep {
    private final ShortsService shorts;

    ShortsWithdrawalPurgeStep(ShortsService shorts) {
        this.shorts = shorts;
    }

    @Override
    public int order() {
        return 48;
    }

    @Override
    public void purge(long memberId) {
        shorts.purge(memberId);
    }
}
