package com.team.blog.trade.application;

import org.springframework.stereotype.Component;

import com.team.blog.account.application.WithdrawalPurgeStep;

/** (49) 탈퇴 정리 때 매매 기록과 하루 메모를 지운다 (#243). 매매 일지가 꺼져 있어도 남은 것은 지운다 */
@Component
class TradeWithdrawalPurgeStep implements WithdrawalPurgeStep {
    private final TradeJournal trades;

    TradeWithdrawalPurgeStep(TradeJournal trades) {
        this.trades = trades;
    }

    @Override
    public int order() {
        return 49;
    }

    @Override
    public void purge(long memberId) {
        trades.purge(memberId);
    }
}
