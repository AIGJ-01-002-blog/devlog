package com.team.blog.trade.application;

import org.springframework.stereotype.Component;

import com.team.blog.account.application.WithdrawalPurgeStep;

/** (49) 탈퇴 정리 때 매매 기록과 하루 메모(#243), 자동매매 모의 기록(spec 101)을 지운다. 매매 일지가 꺼져 있어도 남은 것은 지운다 */
@Component
class TradeWithdrawalPurgeStep implements WithdrawalPurgeStep {
    private final TradeJournal trades;
    private final TradeBotSim bot;

    TradeWithdrawalPurgeStep(TradeJournal trades, TradeBotSim bot) {
        this.trades = trades;
        this.bot = bot;
    }

    @Override
    public int order() {
        return 49;
    }

    @Override
    public void purge(long memberId) {
        trades.purge(memberId);
        bot.purge(memberId);
    }
}
