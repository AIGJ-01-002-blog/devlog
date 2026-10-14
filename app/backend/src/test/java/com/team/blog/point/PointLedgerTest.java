package com.team.blog.point;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import tools.jackson.databind.JsonNode;

import com.team.blog.point.application.PointLedger;
import com.team.blog.point.application.PointReason;
import com.team.blog.point.application.Wallet;
import com.team.blog.shared.error.ApiException;
import com.team.blog.support.IntegrationTest;

/**
 * #266 블로그 포인트: 가입 첫 지급은 투자 전용, 출석·연속 출석·글 발행·모의투자 수익은 상점 포인트. 잔액은 원장 합계이고 같은 요청은 한 번만.
 */
class PointLedgerTest extends IntegrationTest {
    static final String LONG_BODY = "포인트를 받으려면 글이 충분히 길어야 해요. ".repeat(20);

    @Autowired
    PointLedger points;

    @AfterEach
    void resetRules() {
        jdbc.update("DELETE FROM point_rule");
    }

    long publish(Session s, String title, String content, String visibility) throws Exception {
        long id = read(s.http().perform(asJson(post("/api/posts"), Map.of())).andExpect(status().isCreated()).andReturn()).path("id").asLong();
        s.http().perform(asJson(post("/api/posts/" + id + "/publish"),
                Map.of("title", title, "contentMd", content, "visibility", visibility, "baseVersion", 0, "tags", List.of())))
                .andExpect(status().isOk());
        return id;
    }

    JsonNode summary(Session s) throws Exception {
        return read(s.http().perform(get("/api/me/points")).andExpect(status().isOk()).andReturn());
    }

    @Test
    void 처음_보면_투자_전용_첫_지급을_한_번만_받는다() throws Exception {
        Session me = signup(uniqueLogin("ptwel"));
        assertThat(read(me.http().perform(get("/api/auth/me")).andReturn()).path("features").path("points").asBoolean()).isTrue();

        JsonNode s = summary(me);
        assertThat(s.path("balance").path("invest").asLong()).isEqualTo(10_000_000L);
        assertThat(s.path("balance").path("shop").asLong()).isZero();
        assertThat(s.path("history").path("items")).hasSize(1);
        assertThat(s.path("history").path("items").get(0).path("reason").asString()).isEqualTo("WELCOME");
        assertThat(s.path("history").path("items").get(0).path("wallet").asString()).isEqualTo("INVEST_ONLY");

        // 다시 봐도 두 번 주지 않는다
        assertThat(summary(me).path("balance").path("invest").asLong()).isEqualTo(10_000_000L);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM point_ledger WHERE member_id = ?", Integer.class, me.memberId())).isEqualTo(1);
    }

    @Test
    void 출석은_하루_한_번이고_연속_출석이_주기에_닿으면_보너스를_준다() throws Exception {
        Session me = signup(uniqueLogin("ptatt"));
        JsonNode first = read(me.http().perform(post("/api/me/points/attendance").with(csrf())).andExpect(status().isOk()).andReturn());
        assertThat(first.path("already").asBoolean()).isFalse();
        assertThat(first.path("earned").asLong()).isEqualTo(100);
        assertThat(first.path("summary").path("attendance").path("attendedToday").asBoolean()).isTrue();
        assertThat(first.path("summary").path("attendance").path("streak").asInt()).isEqualTo(1);
        assertThat(first.path("summary").path("attendance").path("nextBonusIn").asInt()).isEqualTo(6);
        assertThat(first.path("summary").path("balance").path("shop").asLong()).isEqualTo(100);

        JsonNode again = read(me.http().perform(post("/api/me/points/attendance").with(csrf())).andExpect(status().isOk()).andReturn());
        assertThat(again.path("already").asBoolean()).isTrue();
        assertThat(again.path("earned").asLong()).isZero();
        assertThat(again.path("summary").path("balance").path("shop").asLong()).isEqualTo(100);

        // 어제까지 6일 연속이었던 회원은 오늘 출석으로 7일째 보너스를 받는다
        Session streaker = signup(uniqueLogin("ptstr"));
        LocalDate today = points.today();
        for (int i = 6; i >= 1; i--) {
            jdbc.update("INSERT INTO point_attendance (member_id, day, streak) VALUES (?, ?, ?)", streaker.memberId(), today.minusDays(i), 7 - i);
        }
        JsonNode before = summary(streaker);
        assertThat(before.path("attendance").path("streak").asInt()).isEqualTo(6);
        assertThat(before.path("attendance").path("nextBonusIn").asInt()).isEqualTo(1);
        assertThat(before.path("attendance").path("recent")).hasSize(6);
        JsonNode bonus = read(streaker.http().perform(post("/api/me/points/attendance").with(csrf())).andExpect(status().isOk()).andReturn());
        assertThat(bonus.path("earned").asLong()).isEqualTo(800);
        assertThat(bonus.path("bonus").asLong()).isEqualTo(700);
        assertThat(bonus.path("summary").path("attendance").path("streak").asInt()).isEqualTo(7);
        assertThat(bonus.path("summary").path("attendance").path("nextBonusIn").asInt()).isEqualTo(7);

        // 하루 비면 연속이 끊긴다
        Session gap = signup(uniqueLogin("ptgap"));
        jdbc.update("INSERT INTO point_attendance (member_id, day, streak) VALUES (?, ?, 5)", gap.memberId(), today.minusDays(2));
        assertThat(summary(gap).path("attendance").path("streak").asInt()).isZero();
        JsonNode reset = read(gap.http().perform(post("/api/me/points/attendance").with(csrf())).andExpect(status().isOk()).andReturn());
        assertThat(reset.path("summary").path("attendance").path("streak").asInt()).isEqualTo(1);
    }

    @Test
    void 동시에_눌러도_출석은_한_번만_적립된다() throws Exception {
        Session me = signup(uniqueLogin("ptcon"));
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            List<Callable<PointLedger.AttendResult>> jobs = new ArrayList<>();
            for (int i = 0; i < 4; i++) jobs.add(() -> points.attend(me.memberId()));
            int earned = 0;
            for (Future<PointLedger.AttendResult> f : pool.invokeAll(jobs)) if (!f.get().already()) earned++;
            assertThat(earned).isEqualTo(1);
        } finally {
            pool.shutdown();
        }
        assertThat(points.balance(me.memberId()).shop()).isEqualTo(100);
        assertThat(points.balance(me.memberId()).invest()).isEqualTo(10_000_000L);
    }

    @Test
    void 공개_글_발행은_하루_상한까지_적립하고_짧은_글_비공개_글은_빼고_다시_발행해도_한_번이다() throws Exception {
        Session me = signup(uniqueLogin("ptpost"));
        publish(me, "짧은 글", "짧아요", "PUBLIC");
        publish(me, "나만 보는 글", LONG_BODY, "PRIVATE");
        long first = publish(me, "첫 글", LONG_BODY, "PUBLIC");
        publish(me, "둘째 글", LONG_BODY, "PUBLIC");
        publish(me, "셋째 글", LONG_BODY, "PUBLIC");
        publish(me, "넷째 글", LONG_BODY, "PUBLIC");
        drain();

        JsonNode s = summary(me);
        assertThat(s.path("balance").path("shop").asLong()).isEqualTo(900);
        assertThat(s.path("postsRewardedToday").asInt()).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM point_ledger WHERE member_id = ? AND reason = 'POST' AND ref_id = ?", Integer.class,
                me.memberId(), first)).isEqualTo(1);

        // 같은 글에 다시 주려고 해도 원장은 그대로
        assertThat(points.rewardPost(me.memberId(), first)).isZero();
        assertThat(points.balance(me.memberId()).shop()).isEqualTo(900);
    }

    @Test
    void 모의투자_수익은_비율만큼_하루_상한까지_상점_포인트가_되고_손실은_적립하지_않는다() throws Exception {
        Session me = signup(uniqueLogin("pttrade"));
        assertThat(points.rewardTradeProfit(me.memberId(), 1, new BigDecimal("125000"))).isEqualTo(1250);
        assertThat(points.rewardTradeProfit(me.memberId(), 1, new BigDecimal("125000"))).isZero();
        assertThat(points.rewardTradeProfit(me.memberId(), 2, new BigDecimal("-50000"))).isZero();
        assertThat(points.rewardTradeProfit(me.memberId(), 3, new BigDecimal("99"))).isZero();
        assertThat(points.rewardTradeProfit(me.memberId(), 4, new BigDecimal("500000"))).isEqualTo(1750);
        assertThat(points.rewardTradeProfit(me.memberId(), 5, new BigDecimal("500000"))).isZero();
        assertThat(points.balance(me.memberId()).shop()).isEqualTo(3000);
        assertThat(summary(me).path("tradeProfitToday").asLong()).isEqualTo(3000);
    }

    @Test
    void 지갑마다_따로_쓰고_모자라면_거절하며_같은_요청은_한_번만_빠진다() throws Exception {
        Session me = signup(uniqueLogin("ptspend"));
        points.attend(me.memberId());
        // 첫 지급(투자 전용)은 상점 지갑 사용에 섞이지 않는다
        assertThatThrownBy(() -> points.spend(me.memberId(), Wallet.SHOP, 500, PointReason.SHOP, "shop:test:1", null, "테마"))
                .isInstanceOf(ApiException.class).hasMessageContaining("상점 포인트가 모자라요");
        assertThat(points.spend(me.memberId(), Wallet.SHOP, 60, PointReason.SHOP, "shop:test:2", null, "테두리")).isTrue();
        assertThat(points.spend(me.memberId(), Wallet.SHOP, 60, PointReason.SHOP, "shop:test:2", null, "테두리")).isFalse();
        assertThat(points.spend(me.memberId(), Wallet.INVEST_ONLY, 1_000_000, PointReason.INVEST, "invest:test:1", null, "BTC 매수")).isTrue();
        PointLedger.Balance b = points.balance(me.memberId());
        assertThat(b.shop()).isEqualTo(40);
        assertThat(b.invest()).isEqualTo(9_000_000L);

        // 내역은 지갑으로 거르고, 쪽 단위로 넘긴다
        JsonNode shop = read(me.http().perform(get("/api/me/points/history?wallet=SHOP")).andExpect(status().isOk()).andReturn());
        assertThat(shop.path("items").findValuesAsString("reason")).containsExactly("SHOP", "ATTENDANCE");
        me.http().perform(get("/api/me/points/history?wallet=CASH")).andExpect(status().isBadRequest());
    }

    @Test
    void 관리자만_적립_규칙을_바꾸고_바꾼_값으로_적립한다() throws Exception {
        Session member = signup(uniqueLogin("ptmem"));
        member.http().perform(get("/api/admin/points/rules")).andExpect(status().isNotFound());

        Session manager = signup(uniqueLogin("ptmgr"));
        jdbc.update("UPDATE member SET role = 'MANAGER' WHERE id = ?", manager.memberId());
        manager = new Session(relogin(manager), manager.memberId(), manager.handle(), manager.githubId());
        manager.http().perform(asJson(put("/api/admin/points/rules"), Map.of("ATTENDANCE", 200))).andExpect(status().isForbidden());

        Session admin = signup(uniqueLogin("ptadm"));
        jdbc.update("UPDATE member SET role = 'ADMIN' WHERE id = ?", admin.memberId());
        admin = new Session(relogin(admin), admin.memberId(), admin.handle(), admin.githubId());
        JsonNode rules = read(admin.http().perform(get("/api/admin/points/rules")).andExpect(status().isOk()).andReturn());
        assertThat(rules.findValuesAsString("code")).contains("WELCOME", "ATTENDANCE", "STREAK_BONUS", "POST_DAILY_MAX", "TRADE_PROFIT_PERCENT");

        admin.http().perform(asJson(put("/api/admin/points/rules"), Map.of("ATTENDANCE", 999_999_999))).andExpect(status().isBadRequest());
        admin.http().perform(asJson(put("/api/admin/points/rules"), Map.of("NOPE", 1))).andExpect(status().isBadRequest());
        admin.http().perform(asJson(put("/api/admin/points/rules"), Map.of("STREAK_DAYS", 0))).andExpect(status().isBadRequest());
        admin.http().perform(asJson(put("/api/admin/points/rules"), Map.of("ATTENDANCE", 250, "WELCOME", 5_000_000))).andExpect(status().isOk());

        Session fresh = signup(uniqueLogin("ptnew"));
        JsonNode got = read(fresh.http().perform(post("/api/me/points/attendance").with(csrf())).andExpect(status().isOk()).andReturn());
        assertThat(got.path("earned").asLong()).isEqualTo(250);
        assertThat(got.path("summary").path("balance").path("invest").asLong()).isEqualTo(5_000_000L);

        // 기본값으로 되돌리면 행이 지워진다
        admin.http().perform(asJson(put("/api/admin/points/rules"), Map.of("ATTENDANCE", 100))).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM point_rule WHERE code = 'ATTENDANCE'", Integer.class)).isZero();
    }
}
