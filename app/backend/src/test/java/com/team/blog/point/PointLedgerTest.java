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
import com.team.blog.point.application.PointOutbox;
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

    @Autowired
    PointOutbox outbox;

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
    void 출석은_하루_한_번이고_보상은_100_곱하기_연속일_제곱에_하루_상한까지다() throws Exception {
        Session me = signup(uniqueLogin("ptatt"));
        JsonNode before0 = summary(me);
        assertThat(before0.path("attendance").path("todayReward").asLong()).isEqualTo(100);
        assertThat(before0.path("attendance").path("maxReward").asLong()).isEqualTo(13_322_500L);
        assertThat(before0.path("attendance").path("maxStreak").asInt()).isEqualTo(365);
        JsonNode first = read(me.http().perform(post("/api/me/points/attendance").with(csrf())).andExpect(status().isOk()).andReturn());
        assertThat(first.path("already").asBoolean()).isFalse();
        assertThat(first.path("earned").asLong()).isEqualTo(100);
        assertThat(first.path("streak").asInt()).isEqualTo(1);
        assertThat(first.path("summary").path("attendance").path("attendedToday").asBoolean()).isTrue();
        assertThat(first.path("summary").path("attendance").path("nextReward").asLong()).isEqualTo(400);
        assertThat(first.path("summary").path("balance").path("shop").asLong()).isEqualTo(100);

        JsonNode again = read(me.http().perform(post("/api/me/points/attendance").with(csrf())).andExpect(status().isOk()).andReturn());
        assertThat(again.path("already").asBoolean()).isTrue();
        assertThat(again.path("earned").asLong()).isZero();
        assertThat(again.path("summary").path("balance").path("shop").asLong()).isEqualTo(100);

        // 어제까지 9일 연속이었던 회원은 오늘 10일째로 100 × 10² = 10,000을 받는다
        Session streaker = signup(uniqueLogin("ptstr"));
        LocalDate today = points.today();
        for (int i = 9; i >= 1; i--) {
            jdbc.update("INSERT INTO point_attendance (member_id, day, streak) VALUES (?, ?, ?)", streaker.memberId(), today.minusDays(i), 10 - i);
        }
        JsonNode before = summary(streaker);
        assertThat(before.path("attendance").path("streak").asInt()).isEqualTo(9);
        assertThat(before.path("attendance").path("todayReward").asLong()).isEqualTo(10_000);
        assertThat(before.path("attendance").path("recent")).hasSize(9);
        JsonNode tenth = read(streaker.http().perform(post("/api/me/points/attendance").with(csrf())).andExpect(status().isOk()).andReturn());
        assertThat(tenth.path("earned").asLong()).isEqualTo(10_000);
        assertThat(tenth.path("summary").path("attendance").path("streak").asInt()).isEqualTo(10);
        assertThat(tenth.path("summary").path("attendance").path("nextReward").asLong()).isEqualTo(12_100);

        // 19일 연속이던 회원은 오늘 20일째에 첫 휘장을 받고, 블로그에서 누구나 본다
        Session badge = signup(uniqueLogin("ptbdg"));
        jdbc.update("INSERT INTO point_attendance (member_id, day, streak) VALUES (?, ?, 19)", badge.memberId(), today.minusDays(1));
        assertThat(summary(badge).path("nextBadge").asInt()).isEqualTo(20);
        JsonNode twenty = read(badge.http().perform(post("/api/me/points/attendance").with(csrf())).andExpect(status().isOk()).andReturn());
        assertThat(twenty.path("newBadges").get(0).asInt()).isEqualTo(20);
        assertThat(twenty.path("summary").path("nextBadge").asInt()).isEqualTo(50);
        JsonNode shown = read(mvc.perform(get("/api/members/" + badge.handle() + "/badges")).andExpect(status().isOk()).andReturn());
        assertThat(shown.get(0).path("days").asInt()).isEqualTo(20);
        assertThat(shown.get(0).path("special").asBoolean()).isFalse();
        mvc.perform(get("/api/members/no-such-blog-xyz/badges")).andExpect(status().isNotFound());

        // 출석 알림은 오늘 출석했는지만 알려 준다
        JsonNode t1 = read(badge.http().perform(get("/api/me/points/today")).andExpect(status().isOk()).andReturn());
        assertThat(t1.path("attendedToday").asBoolean()).isTrue();

        // 365일을 넘기면 하루 상한(1,332만 2,500)에서 멈춘다
        Session veteran = signup(uniqueLogin("ptvet"));
        jdbc.update("INSERT INTO point_attendance (member_id, day, streak) VALUES (?, ?, 400)", veteran.memberId(), today.minusDays(1));
        JsonNode capped = read(veteran.http().perform(post("/api/me/points/attendance").with(csrf())).andExpect(status().isOk()).andReturn());
        assertThat(capped.path("earned").asLong()).isEqualTo(13_322_500L);
        // 1년을 넘긴 회원은 빠진 휘장까지 모두 받고, 365일 휘장은 특별 휘장이다
        assertThat(capped.path("newBadges")).hasSize(7);
        assertThat(capped.path("summary").path("badges").get(6).path("special").asBoolean()).isTrue();
        assertThat(capped.path("summary").path("nextBadge").isNull()).isTrue();

        // 하루 비면 연속이 끊겨 다시 100부터다
        Session gap = signup(uniqueLogin("ptgap"));
        jdbc.update("INSERT INTO point_attendance (member_id, day, streak) VALUES (?, ?, 5)", gap.memberId(), today.minusDays(2));
        assertThat(summary(gap).path("attendance").path("streak").asInt()).isZero();
        JsonNode reset = read(gap.http().perform(post("/api/me/points/attendance").with(csrf())).andExpect(status().isOk()).andReturn());
        assertThat(reset.path("earned").asLong()).isEqualTo(100);
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
    void 모의투자_수익은_전부_크레딧이_되고_손실은_적립하지_않는다() throws Exception {
        Session me = signup(uniqueLogin("pttrade"));
        assertThat(points.creditTradeProfit(me.memberId(), 1, 125_000)).isEqualTo(125_000);
        assertThat(points.creditTradeProfit(me.memberId(), 1, 125_000)).isZero();
        assertThat(points.creditTradeProfit(me.memberId(), 2, -50_000)).isZero();
        assertThat(points.balance(me.memberId()).shop()).isEqualTo(125_000);
    }

    @Test
    void 봇이_남긴_원화_모의투자_수익도_하루_상한까지_크레딧이_된다() throws Exception {
        Session me = signup(uniqueLogin("ptbot"));
        jdbc.update("INSERT INTO point_rule (code, value) VALUES ('BOT_PROFIT_DAILY_MAX', 100000)");
        java.time.Instant at = java.time.Instant.now();
        long win = botTrade(me, "bot-1", "PAPER", "SELL", "KRW", "60000", at);
        botTrade(me, "bot-2", "PAPER", "SELL", "KRW", "-30000", at);    // 손실
        botTrade(me, "bot-3", "LIVE", "SELL", "KRW", "50000", at);      // 실계좌
        botTrade(me, "bot-4", "PAPER", "SELL", "USDT", "50000", at);    // 원화가 아님
        long capped = botTrade(me, "bot-5", "PAPER", "SELL", "KRW", "70000", at);
        long next = botTrade(me, "bot-6", "PAPER", "SELL", "KRW", "5000", at);

        // 6만 + 7만 중 4만(상한 10만), 그다음 체결은 상한이 차서 다음 날로
        JsonNode s = summary(me);
        assertThat(s.path("balance").path("shop").asLong()).isEqualTo(100_000);
        assertThat(s.path("botProfitToday").asLong()).isEqualTo(100_000);
        assertThat(jdbc.queryForList("SELECT ref_id FROM point_ledger WHERE member_id = ? AND reason = 'BOT_PROFIT' ORDER BY ref_id", Long.class,
                me.memberId())).containsExactly(win, capped);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM point_ledger WHERE member_id = ? AND ref_id = ?", Integer.class, me.memberId(), next))
                .isZero();
        // 다시 열어도 같은 체결은 한 번만
        assertThat(points.settleBotProfits(me.memberId())).isZero();

        // 다음 날: 어제 들어간 줄을 하루 전으로 돌리면, 남은 3만과 다음 체결 5천이 들어간다
        jdbc.update("UPDATE point_ledger SET created_at = created_at - interval '1 day', request_key = request_key || ':y' WHERE member_id = ? AND reason = 'BOT_PROFIT' AND ref_id = ?",
                me.memberId(), capped);
        assertThat(points.settleBotProfits(me.memberId())).isEqualTo(35_000);
        assertThat(jdbc.queryForObject("SELECT SUM(amount) FROM point_ledger WHERE member_id = ? AND ref_id = ?", Long.class, me.memberId(), capped))
                .isEqualTo(70_000);
        assertThat(points.settleBotProfits(me.memberId())).isZero();
    }

    long botTrade(Session s, String id, String mode, String side, String currency, String pnl, java.time.Instant at) {
        return jdbc.queryForObject("""
                INSERT INTO trade (member_id, external_id, mode, exchange, symbol, symbol_name, side, quantity, price, currency, fee, realized_pnl,
                                   strategy, reason, executed_at, created_at)
                VALUES (?, ?, ?, 'UPBIT', 'BTC', '비트코인', ?, 0.01, 100000000, ?, 0, ?, '봇', '', ?, ?) RETURNING id
                """, Long.class, s.memberId(), id, mode, side, currency, new BigDecimal(pnl), java.sql.Timestamp.from(at), java.sql.Timestamp.from(at));
    }

    @Test
    void 글_발행_크레딧은_미지급_목록에_남았다가_작업이_지급한다() throws Exception {
        Session me = signup(uniqueLogin("ptbox"));
        long id = publish(me, "첫 글", LONG_BODY, "PUBLIC");
        drain();
        // 커밋 뒤 바로 지급하고 목록에서 지운다
        assertThat(points.balance(me.memberId()).shop()).isEqualTo(300);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM point_outbox WHERE post_id = ?", Integer.class, id)).isZero();

        // 서버가 지급 전에 멈춘 경우: 목록에만 남은 줄을 작업이 지급한다
        long lost = publish(me, "둘째 글", LONG_BODY, "PUBLIC");
        drain();
        jdbc.update("DELETE FROM point_ledger WHERE member_id = ? AND ref_id = ?", me.memberId(), lost);
        jdbc.update("INSERT INTO point_outbox (member_id, post_id) VALUES (?, ?)", me.memberId(), lost);
        assertThat(outbox.drain()).isGreaterThanOrEqualTo(1);
        assertThat(points.balance(me.memberId()).shop()).isEqualTo(600);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM point_outbox WHERE member_id = ?", Integer.class, me.memberId())).isZero();
    }

    @Test
    void 지갑마다_따로_쓰고_모자라면_거절하며_같은_요청은_한_번만_빠진다() throws Exception {
        Session me = signup(uniqueLogin("ptspend"));
        points.attend(me.memberId());
        // 첫 지급(투자 전용)은 상점 지갑 사용에 섞이지 않는다
        assertThatThrownBy(() -> points.spend(me.memberId(), Wallet.SHOP, 500, PointReason.SHOP, "shop:test:1", null, "테마"))
                .isInstanceOf(ApiException.class).hasMessageContaining("크레딧이 모자라요");
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
        assertThat(rules.findValuesAsString("code")).contains("WELCOME", "ATTENDANCE", "ATTENDANCE_MAX", "POST_DAILY_MAX", "BOT_PROFIT_DAILY_MAX");

        admin.http().perform(asJson(put("/api/admin/points/rules"), Map.of("ATTENDANCE", 999_999_999))).andExpect(status().isBadRequest());
        admin.http().perform(asJson(put("/api/admin/points/rules"), Map.of("NOPE", 1))).andExpect(status().isBadRequest());
        admin.http().perform(asJson(put("/api/admin/points/rules"), Map.of("POST_DAILY_MAX", 0))).andExpect(status().isBadRequest());
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
