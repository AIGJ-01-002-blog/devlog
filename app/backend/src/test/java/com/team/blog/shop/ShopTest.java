package com.team.blog.shop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import tools.jackson.databind.JsonNode;

import com.team.blog.point.application.PointLedger;
import com.team.blog.point.application.PointReason;
import com.team.blog.point.application.Wallet;
import com.team.blog.support.IntegrationTest;

/**
 * #268 상점: 상점 포인트로만 꾸미기 아이템을 사고, 산 아이템을 종류마다 하나씩 내 블로그에 적용하면 다른 사람도 본다.
 */
class ShopTest extends IntegrationTest {
    @Autowired
    PointLedger points;

    long itemId(String name) {
        return jdbc.queryForObject("SELECT id FROM shop_item WHERE name = ?", Long.class, name);
    }

    void giveShopPoints(Session s, long amount) {
        jdbc.update("INSERT INTO point_ledger (member_id, wallet, amount, reason, request_key) VALUES (?, 'SHOP', ?, 'ADMIN', ?)",
                s.memberId(), amount, "test:" + System.nanoTime());
    }

    Session admin() throws Exception {
        Session a = signup(uniqueLogin("shadm"));
        jdbc.update("UPDATE member SET role = 'ADMIN' WHERE id = ?", a.memberId());
        return new Session(relogin(a), a.memberId(), a.handle(), a.githubId());
    }

    @Test
    void 투자_전용_포인트로는_못_사고_상점_포인트로_사서_적용하면_블로그에_보인다() throws Exception {
        Session me = signup(uniqueLogin("shbuy"));
        long forest = itemId("숲");
        long gold = itemId("금테");

        JsonNode shop = read(me.http().perform(get("/api/shop")).andExpect(status().isOk()).andReturn());
        assertThat(shop.path("investBalance").asLong()).isEqualTo(10_000_000L);
        assertThat(shop.path("shopBalance").asLong()).isZero();
        assertThat(shop.path("items").findValuesAsString("kind")).contains("THEME", "FRAME", "BANNER");

        // 첫 지급 1,000만 P가 있어도 상점 포인트가 없으면 못 산다
        JsonNode short1 = read(me.http().perform(post("/api/shop/items/" + forest + "/purchase").with(csrf())).andExpect(status().isConflict()).andReturn());
        assertThat(short1.path("code").asString()).isEqualTo("POINTS_SHORT");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM member_item WHERE member_id = ?", Integer.class, me.memberId())).isZero();

        giveShopPoints(me, 5_500);
        JsonNode bought = read(me.http().perform(post("/api/shop/items/" + forest + "/purchase").with(csrf())).andExpect(status().isOk()).andReturn());
        assertThat(bought.path("shopBalance").asLong()).isEqualTo(2_500);
        assertThat(bought.path("investBalance").asLong()).isEqualTo(10_000_000L);
        me.http().perform(post("/api/shop/items/" + forest + "/purchase").with(csrf())).andExpect(status().isConflict());
        me.http().perform(post("/api/shop/items/" + gold + "/purchase").with(csrf())).andExpect(status().isOk());

        // 사지 않은 아이템은 적용 못 한다
        me.http().perform(put("/api/me/decorations/" + itemId("바다")).with(csrf())).andExpect(status().isConflict());
        me.http().perform(put("/api/me/decorations/" + forest).with(csrf())).andExpect(status().isOk());
        me.http().perform(put("/api/me/decorations/" + gold).with(csrf())).andExpect(status().isOk());

        // 다른 사람(비회원)도 내 블로그에서 본다
        JsonNode looks = read(mvc.perform(get("/api/members/" + me.handle() + "/decorations")).andExpect(status().isOk()).andReturn());
        assertThat(looks.path("theme").path("value").asString()).isEqualTo("#2f9e44");
        assertThat(looks.path("frame").path("value").asString()).isEqualTo("gold");
        assertThat(looks.path("banner").isNull()).isTrue();

        // 해제하면 기본 모습
        me.http().perform(delete("/api/me/decorations/theme").with(csrf())).andExpect(status().isOk());
        looks = read(mvc.perform(get("/api/members/" + me.handle() + "/decorations")).andReturn());
        assertThat(looks.path("theme").isNull()).isTrue();
        assertThat(looks.path("frame").path("value").asString()).isEqualTo("gold");

        // 원장에 사용 두 줄
        assertThat(jdbc.queryForObject("SELECT COALESCE(SUM(amount), 0) FROM point_ledger WHERE member_id = ? AND reason = 'SHOP'", Long.class,
                me.memberId())).isEqualTo(-5_000L);
        mvc.perform(get("/api/members/nobody-here/decorations")).andExpect(status().isNotFound());
    }

    @Test
    void 같은_아이템을_동시에_사도_한_번만_빠진다() throws Exception {
        Session me = signup(uniqueLogin("shcon"));
        giveShopPoints(me, 10_000);
        long ocean = itemId("깊은 바다");
        var pool = java.util.concurrent.Executors.newFixedThreadPool(4);
        try {
            var jobs = new java.util.ArrayList<java.util.concurrent.Callable<Integer>>();
            for (int i = 0; i < 4; i++) jobs.add(() -> me.http().perform(post("/api/shop/items/" + ocean + "/purchase").with(csrf())).andReturn().getResponse().getStatus());
            var codes = new java.util.ArrayList<Integer>();
            for (var f : pool.invokeAll(jobs)) codes.add(f.get());
            assertThat(codes).containsOnly(200, 409).containsOnlyOnce(200);
        } finally {
            pool.shutdown();
        }
        assertThat(points.balance(me.memberId()).shop()).isEqualTo(5_000);
    }

    @Test
    void 관리자가_아이템을_추가하고_가격을_바꾸고_내리면_이미_산_사람은_계속_쓴다() throws Exception {
        Session a = admin();
        Session member = signup(uniqueLogin("shmem"));
        member.http().perform(asJson(post("/api/admin/shop/items"), Map.of("kind", "THEME", "name", "x", "price", 1, "value", "#000000")))
                .andExpect(status().isNotFound());

        // 임의 CSS나 바깥 주소는 받지 않는다
        a.http().perform(asJson(post("/api/admin/shop/items"), Map.of("kind", "THEME", "name", "빨강", "price", 100, "value", "red; background:url(x)")))
                .andExpect(status().isBadRequest());
        a.http().perform(asJson(post("/api/admin/shop/items"), Map.of("kind", "BANNER", "name", "사진", "price", 100, "imageUrl", "https://evil.example/a.png")))
                .andExpect(status().isBadRequest());
        a.http().perform(asJson(post("/api/admin/shop/items"), Map.of("kind", "FRAME", "name", "테", "price", 0, "value", "gold")))
                .andExpect(status().isBadRequest());

        JsonNode created = read(a.http().perform(asJson(post("/api/admin/shop/items"),
                Map.of("kind", "FRAME", "name", "민트 테", "description", "민트색 테두리", "price", 1500, "value", "#20C997", "sort", 5)))
                .andExpect(status().isCreated()).andReturn());
        long id = created.path("id").asLong();
        assertThat(created.path("active").asBoolean()).isTrue();

        giveShopPoints(member, 2_000);
        member.http().perform(post("/api/shop/items/" + id + "/purchase").with(csrf())).andExpect(status().isOk());
        member.http().perform(put("/api/me/decorations/" + id).with(csrf())).andExpect(status().isOk());

        JsonNode updated = read(a.http().perform(asJson(put("/api/admin/shop/items/" + id), Map.of("price", 9999, "active", false)))
                .andExpect(status().isOk()).andReturn());
        assertThat(updated.path("price").asLong()).isEqualTo(9999);
        assertThat(updated.path("value").asString()).isEqualTo("#20C997");

        // 내려간 아이템은 남은 사람 상점에는 안 보이고, 산 사람 보관함에는 남아 계속 적용된다
        Session other = signup(uniqueLogin("shoth"));
        assertThat(read(other.http().perform(get("/api/shop")).andReturn()).path("items").findValuesAsString("name")).doesNotContain("민트 테");
        JsonNode mine = read(member.http().perform(get("/api/shop")).andReturn());
        assertThat(mine.path("items").findValuesAsString("name")).contains("민트 테");
        assertThat(read(mvc.perform(get("/api/members/" + member.handle() + "/decorations")).andReturn()).path("frame").path("name").asString())
                .isEqualTo("민트 테");
        giveShopPoints(other, 20_000);
        other.http().perform(post("/api/shop/items/" + id + "/purchase").with(csrf())).andExpect(status().isNotFound());

        assertThat(read(a.http().perform(get("/api/admin/shop/items")).andExpect(status().isOk()).andReturn()).findValuesAsString("name"))
                .contains("민트 테", "숲", "밤하늘");
        // 직접 빼도 원장 규칙(잔액 부족 거절)은 같다
        assertThat(points.spend(other.memberId(), Wallet.SHOP, 1, PointReason.SHOP, "shop:direct", null, "x")).isTrue();
    }
}
