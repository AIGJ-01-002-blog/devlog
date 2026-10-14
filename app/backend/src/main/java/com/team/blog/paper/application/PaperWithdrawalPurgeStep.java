package com.team.blog.paper.application;

import org.springframework.stereotype.Component;

import com.team.blog.account.application.WithdrawalPurgeStep;

/** (53) 탈퇴 정리 때 모의투자 보유분과 주문 번호를 지운다 (#267). 매매 일지·원장은 각자의 단계가 지운다 */
@Component
class PaperWithdrawalPurgeStep implements WithdrawalPurgeStep {
    private final PaperTrading paper;

    PaperWithdrawalPurgeStep(PaperTrading paper) {
        this.paper = paper;
    }

    @Override
    public int order() {
        return 53;
    }

    @Override
    public void purge(long memberId) {
        paper.purge(memberId);
    }
}
