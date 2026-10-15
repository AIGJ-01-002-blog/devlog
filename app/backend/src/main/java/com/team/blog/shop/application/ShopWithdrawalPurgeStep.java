package com.team.blog.shop.application;

import org.springframework.stereotype.Component;

import com.team.blog.account.application.WithdrawalPurgeStep;

/** (52) 탈퇴 정리 때 산 아이템과 적용한 꾸미기를 지운다 (#268). 원장(51)보다 뒤에 돌아도 서로 묶여 있지 않다 */
@Component
class ShopWithdrawalPurgeStep implements WithdrawalPurgeStep {
    private final ShopService shop;

    ShopWithdrawalPurgeStep(ShopService shop) {
        this.shop = shop;
    }

    @Override
    public int order() {
        return 52;
    }

    @Override
    public void purge(long memberId) {
        shop.purge(memberId);
    }
}
