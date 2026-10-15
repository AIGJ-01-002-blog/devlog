package com.team.blog.follow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;

import com.team.blog.support.IntegrationTest;

/** 016 팔로우·피드와 015의 새 팔로워·새 글 알림. */
class FollowTest extends IntegrationTest {

    long publish(Session s, String title, String visibility) throws Exception {
        long id = read(s.http().perform(asJson(post("/api/posts"), Map.of("title", title, "contentMd", "본문")))
                .andExpect(status().isCreated()).andReturn()).path("id").asLong();
        s.http().perform(asJson(post("/api/posts/" + id + "/publish"), Map.of("title", title, "contentMd", "본문",
                        "visibility", visibility, "baseVersion", 0, "tags", List.of()))
                .header("Idempotency-Key", UUID.randomUUID().toString())).andExpect(status().isOk());
        return id;
    }

    JsonNode follow(Session s, String handle, boolean on) throws Exception {
        return read(s.http().perform((on ? put("/api/members/" + handle + "/follow") : delete("/api/members/" + handle + "/follow"))
                .with(csrf())).andExpect(status().isOk()).andReturn());
    }

    JsonNode profile(Session viewer, String handle) throws Exception {
        var req = get("/api/members/" + handle);
        return read((viewer == null ? browser() : viewer.http()).perform(req).andExpect(status().isOk()).andReturn());
    }

    JsonNode feed(Session s, String cursor) throws Exception {
        String url = "/api/feed" + (cursor == null ? "" : "?cursor=" + cursor);
        return read(s.http().perform(get(URI.create(url))).andExpect(status().isOk()).andReturn());
    }

    JsonNode notifications(Session s) throws Exception {
        drain();
        return read(s.http().perform(get("/api/me/notifications")).andExpect(status().isOk()).andReturn()).path("items");
    }

    void withdraw(Session s) {
        jdbc.update("UPDATE member SET withdrawn_at = now(), status = 'WITHDRAWN' WHERE id = ?", s.memberId());
    }

    void restore(Session s) {
        jdbc.update("UPDATE member SET withdrawn_at = NULL, status = 'ACTIVE' WHERE id = ?", s.memberId());
    }

    @Test
    void 팔로우는_상태_지정이고_수는_누구나_본다() throws Exception {
        Session a = signup(uniqueLogin("fa")), b = signup(uniqueLogin("fb"));
        assertThat(follow(a, b.handle(), true).path("followerCount").asLong()).isEqualTo(1);
        assertThat(follow(a, b.handle(), true).path("following").asBoolean()).isTrue(); // 다시 보내도 같다
        assertThat(jdbc.queryForObject("SELECT count(*) FROM follow WHERE followee_id = ?", Long.class, b.memberId())).isEqualTo(1L);

        JsonNode guest = profile(null, b.handle());
        assertThat(guest.path("followerCount").asLong()).isEqualTo(1);
        assertThat(guest.path("following").asBoolean()).isFalse();
        assertThat(profile(a, b.handle()).path("following").asBoolean()).isTrue();
        assertThat(profile(a, a.handle()).path("followingCount").asLong()).isEqualTo(1);

        long p = publish(b, "B 글", "PUBLIC");
        JsonNode author = read(a.http().perform(get("/api/posts/" + p)).andReturn()).path("author");
        assertThat(author.path("following").asBoolean()).isTrue();

        JsonNode off = follow(a, b.handle(), false);
        assertThat(off.path("following").asBoolean()).isFalse();
        assertThat(off.path("followerCount").asLong()).isZero();
        follow(a, b.handle(), false); // 안 한 상태에서 언팔로우해도 그대로

        a.http().perform(put("/api/members/" + a.handle() + "/follow").with(csrf())).andExpect(status().isBadRequest());
        a.http().perform(put("/api/members/nobody-here/follow").with(csrf())).andExpect(status().isNotFound());
        withdraw(b);
        a.http().perform(put("/api/members/" + b.handle() + "/follow").with(csrf())).andExpect(status().isNotFound());
        browser().perform(put("/api/members/" + a.handle() + "/follow").with(csrf())).andExpect(status().isUnauthorized());
    }

    @Test
    void 동시에_20번_팔로우해도_관계와_알림은_하나다() throws Exception {
        Session a = signup(uniqueLogin("fc")), b = signup(uniqueLogin("fd"));
        ExecutorService pool = Executors.newFixedThreadPool(20);
        try {
            List<Callable<Void>> calls = new ArrayList<>();
            for (int i = 0; i < 20; i++) calls.add(() -> { follow(a, b.handle(), true); return null; });
            for (Future<Void> f : pool.invokeAll(calls)) f.get();
        } finally {
            pool.shutdown();
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM follow WHERE followee_id = ?", Long.class, b.memberId())).isEqualTo(1L);
        JsonNode items = notifications(b);
        assertThat(items).hasSize(1);
        assertThat(items.get(0).path("type").asString()).isEqualTo("FOLLOW");
        assertThat(items.get(0).path("link").asString()).isEqualTo("/@" + a.handle());
    }

    @Test
    void 새_팔로워_알림은_묶이고_7일에_한_번이다() throws Exception {
        Session target = signup(uniqueLogin("fe"));
        List<Session> fans = new ArrayList<>();
        for (int i = 0; i < 3; i++) fans.add(signup(uniqueLogin("ff" + i)));
        for (Session f : fans) follow(f, target.handle(), true);
        JsonNode items = notifications(target);
        assertThat(items).hasSize(1);
        assertThat(items.get(0).path("othersCount").asInt()).isEqualTo(2);
        assertThat(items.get(0).path("actor").path("handle").asString()).isEqualTo(fans.get(2).handle());

        // 안 읽은 동안 언팔로우하면 묶음에서 빠진다
        follow(fans.get(2), target.handle(), false);
        items = notifications(target);
        assertThat(items.get(0).path("othersCount").asInt()).isEqualTo(1);

        // 읽은 뒤 같은 사람이 다시 팔로우해도 7일 안에는 새 알림이 없다
        target.http().perform(post("/api/me/notifications/read-all").with(csrf())).andExpect(status().isOk());
        follow(fans.get(0), target.handle(), false);
        follow(fans.get(0), target.handle(), true);
        assertThat(notifications(target)).hasSize(1);
        // 8일이 지났으면 다시 알린다
        jdbc.update("UPDATE notification_actor SET created_at = now() - interval '8 days' WHERE actor_id = ?", fans.get(0).memberId());
        follow(fans.get(0), target.handle(), false);
        follow(fans.get(0), target.handle(), true);
        assertThat(notifications(target)).hasSize(2);
    }

    @Test
    void 혼자_팔로우했다가_취소하고_다시_팔로우해도_알림은_하나다() throws Exception {
        Session target = signup(uniqueLogin("fg")), fan = signup(uniqueLogin("fh"));
        follow(fan, target.handle(), true);
        assertThat(notifications(target)).hasSize(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notification WHERE receiver_id = ? AND type = 'FOLLOW'", Long.class,
                target.memberId())).isEqualTo(1L);

        // 안 읽은 동안 혼자 있던 사람이 빠지면 지우지 않고 읽음으로 돌린다. 텔레그램으로 이미 나간 알림을 다시 보내지 않기 위해서다
        follow(fan, target.handle(), false);
        JsonNode items = notifications(target);
        assertThat(items).hasSize(1);
        assertThat(items.get(0).path("read").asBoolean()).isTrue();

        follow(fan, target.handle(), true);
        drain();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notification WHERE receiver_id = ? AND type = 'FOLLOW'", Long.class,
                target.memberId())).isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notification WHERE receiver_id = ? AND read_at IS NULL", Long.class,
                target.memberId())).isZero();
    }

    @Test
    void 처음_전체_공개될_때_팔로워에게_새_글_알림이_한_번_간다() throws Exception {
        Session author = signup(uniqueLogin("fg")), fan = signup(uniqueLogin("fh")), muted = signup(uniqueLogin("fi")),
                gone = signup(uniqueLogin("fj"));
        for (Session s : List.of(fan, muted, gone)) follow(s, author.handle(), true);
        muted.http().perform(asJson(put("/api/me/notification-settings"), Map.of("muted", List.of("NEW_POST")))).andExpect(status().isOk());
        withdraw(gone);

        long p = publish(author, "비공개로 시작", "PRIVATE");
        assertThat(notifications(fan)).isEmpty();
        author.http().perform(asJson(patch("/api/posts/" + p + "/visibility"), Map.of("visibility", "PUBLIC"))).andExpect(status().isOk());
        JsonNode items = notifications(fan);
        assertThat(items).hasSize(1);
        assertThat(items.get(0).path("type").asString()).isEqualTo("NEW_POST");
        assertThat(items.get(0).path("actor").path("handle").asString()).isEqualTo(author.handle());
        assertThat(items.get(0).path("post").path("title").asString()).isEqualTo("비공개로 시작");

        // 껐다 켜도 다시 가지 않는다
        author.http().perform(asJson(patch("/api/posts/" + p + "/visibility"), Map.of("visibility", "PRIVATE"))).andExpect(status().isOk());
        author.http().perform(asJson(patch("/api/posts/" + p + "/visibility"), Map.of("visibility", "PUBLIC"))).andExpect(status().isOk());
        publish(author, "바로 공개", "PUBLIC");
        assertThat(notifications(fan)).hasSize(2);
        assertThat(notifications(muted)).isEmpty();
        drain();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notification WHERE receiver_id = ?", Long.class, gone.memberId())).isZero();
    }

    @Test
    void 피드는_팔로우한_사람의_공개_글만_최신순으로_빠짐없이_넘긴다() throws Exception {
        Session me = signup(uniqueLogin("fk")), b = signup(uniqueLogin("fl")), c = signup(uniqueLogin("fm")), d = signup(uniqueLogin("fn"));
        assertThat(feed(me, null).path("followsAnyone").asBoolean()).isFalse();
        follow(me, b.handle(), true);
        follow(me, c.handle(), true);
        JsonNode empty = feed(me, null);
        assertThat(empty.path("items")).isEmpty();
        assertThat(empty.path("followsAnyone").asBoolean()).isTrue();

        Set<Long> expected = new HashSet<>();
        for (int i = 0; i < 6; i++) {
            expected.add(publish(b, "B" + i, "PUBLIC"));
            expected.add(publish(c, "C" + i, "PUBLIC"));
            resetRateLimits();
        }
        publish(b, "B 비공개", "PRIVATE");
        publish(c, "C 친구", "FRIENDS");
        publish(d, "D 공개", "PUBLIC");
        read(b.http().perform(asJson(post("/api/posts"), Map.of("title", "B 임시", "contentMd", "x"))).andReturn());

        JsonNode first = feed(me, null);
        assertThat(first.path("items")).hasSize(9);
        List<Long> seen = new ArrayList<>();
        first.path("items").forEach(n -> seen.add(n.path("id").asLong()));
        // 넘기는 중에 새 글이 생겨도 다음 쪽에 중복·누락이 없다
        long added = publish(b, "B 새 글", "PUBLIC");
        JsonNode second = feed(me, first.path("nextCursor").asString());
        second.path("items").forEach(n -> seen.add(n.path("id").asLong()));
        assertThat(second.path("nextCursor").isNull()).isTrue();
        assertThat(second.has("followsAnyone") && !second.path("followsAnyone").isNull()).isFalse();
        assertThat(seen).doesNotHaveDuplicates().containsExactlyInAnyOrderElementsOf(expected).doesNotContain(added);
        List<Long> sorted = new ArrayList<>(seen);
        sorted.sort((x, y) -> Long.compare(y, x));
        assertThat(seen).isEqualTo(sorted); // 같은 시각이 없으면 처음 공개 순서 = 번호 순서
    }

    @Test
    void 피드는_팔로우를_끊거나_탈퇴한_사람의_글을_빼고_잘못된_커서는_막는다() throws Exception {
        Session me = signup(uniqueLogin("fk")), b = signup(uniqueLogin("fl")), c = signup(uniqueLogin("fm"));
        follow(me, b.handle(), true);
        follow(me, c.handle(), true);
        for (int i = 0; i < 2; i++) {
            publish(b, "B" + i, "PUBLIC");
            publish(c, "C" + i, "PUBLIC");
        }

        follow(me, c.handle(), false);
        assertThat(feed(me, null).path("items")).allSatisfy(n -> assertThat(n.path("author").path("handle").asString()).isEqualTo(b.handle()));
        withdraw(b);
        assertThat(feed(me, null).path("items")).isEmpty();
        restore(b);
        assertThat(feed(me, null).path("items")).isNotEmpty();

        browser().perform(get("/api/feed")).andExpect(status().isUnauthorized());
        me.http().perform(get("/api/feed?cursor=abc")).andExpect(status().isBadRequest());
        String otherCursor = read(me.http().perform(get("/api/posts")).andReturn()).path("nextCursor").asString(null);
        if (otherCursor != null) me.http().perform(get(URI.create("/api/feed?cursor=" + otherCursor))).andExpect(status().isBadRequest());
    }

    @Test
    void 팔로워_목록은_최근순이고_탈퇴_신청한_회원은_빠졌다가_돌아온다() throws Exception {
        Session owner = signup(uniqueLogin("fo")), viewer = signup(uniqueLogin("fp"));
        List<Session> fans = new ArrayList<>();
        for (int i = 0; i < 22; i++) {
            if (i % 8 == 7) resetRateLimits();
            Session f = signup(uniqueLogin("fq" + i));
            follow(f, owner.handle(), true);
            fans.add(f);
        }
        follow(viewer, fans.get(21).handle(), true);
        JsonNode page = read(viewer.http().perform(get("/api/members/" + owner.handle() + "/followers")).andExpect(status().isOk()).andReturn());
        assertThat(page.path("items")).hasSize(20);
        assertThat(page.path("items").get(0).path("handle").asString()).isEqualTo(fans.get(21).handle());
        assertThat(page.path("items").get(0).path("following").asBoolean()).isTrue();
        assertThat(page.path("items").get(1).path("following").asBoolean()).isFalse();
        // 팔로워 목록의 커서는 팔로잉 목록에 쓸 수 없다
        browser().perform(get(URI.create("/api/members/" + owner.handle() + "/following?cursor=" + page.path("nextCursor").asString())))
                .andExpect(status().isBadRequest());
        JsonNode next = read(viewer.http().perform(get(URI.create("/api/members/" + owner.handle() + "/followers?cursor="
                + page.path("nextCursor").asString()))).andExpect(status().isOk()).andReturn());
        assertThat(next.path("items")).hasSize(2);
        assertThat(next.path("nextCursor").isNull()).isTrue();

        withdraw(fans.get(0));
        assertThat(profile(null, owner.handle()).path("followerCount").asLong()).isEqualTo(21);
        restore(fans.get(0));
        assertThat(profile(null, owner.handle()).path("followerCount").asLong()).isEqualTo(22);

        JsonNode following = read(browser().perform(get("/api/members/" + fans.get(0).handle() + "/following")).andExpect(status().isOk()).andReturn());
        assertThat(following.path("items")).hasSize(1);
        assertThat(following.path("items").get(0).path("handle").asString()).isEqualTo(owner.handle());
        assertThat(read(browser().perform(get("/api/members/" + viewer.handle() + "/followers")).andReturn()).path("items")).isEmpty();
        browser().perform(get("/api/members/nobody-here/followers")).andExpect(status().isNotFound());

        String html = browser().perform(get("/@" + owner.handle() + "/followers")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(html).contains("noindex").contains(fans.get(21).handle());
        browser().perform(get("/@nobody-here/following")).andExpect(status().isNotFound());
        assertThat(browser().perform(get("/feed")).andExpect(status().isOk()).andReturn().getResponse().getHeader("Cache-Control")).contains("no-store");
    }

    @Test
    void 목록을_비공개로_두면_본인과_관리자만_보고_수는_그대로다() throws Exception {
        Session owner = signup(uniqueLogin("fp")), fan = signup(uniqueLogin("fq")), other = signup(uniqueLogin("fr")),
                admin = signup(uniqueLogin("fs"));
        jdbc.update("UPDATE member SET role = 'ADMIN' WHERE id = ?", admin.memberId());
        var adminHttp = relogin(admin);
        follow(fan, owner.handle(), true);
        follow(owner, fan.handle(), true);
        assertThat(read(owner.http().perform(get("/api/me/settings")).andReturn()).path("followListPublic").asBoolean()).isTrue();

        owner.http().perform(asJson(patch("/api/me/settings"), Map.of("followListPublic", false))).andExpect(status().isOk());
        assertThat(read(owner.http().perform(get("/api/me/settings")).andReturn()).path("followListPublic").asBoolean()).isFalse();
        for (String list : List.of("/followers", "/following")) {
            String url = "/api/members/" + owner.handle() + list;
            for (var http : List.of(browser(), other.http(), fan.http())) {
                JsonNode hidden = read(http.perform(get(url)).andExpect(status().isOk()).andReturn());
                assertThat(hidden.path("hidden").asBoolean()).isTrue();
                assertThat(hidden.path("items")).isEmpty();
            }
            for (var http : List.of(owner.http(), adminHttp)) {
                var res = http.perform(get(url)).andExpect(status().isOk()).andReturn();
                assertThat(res.getResponse().getHeader("Cache-Control")).contains("no-store");
                JsonNode open = read(res);
                assertThat(open.path("hidden").asBoolean()).isFalse();
                assertThat(open.path("items")).hasSize(1);
            }
        }
        JsonNode counts = profile(other, owner.handle());
        assertThat(counts.path("followerCount").asLong()).isEqualTo(1);
        assertThat(counts.path("followingCount").asLong()).isEqualTo(1);
        // 다른 사람의 목록에는 그대로 나온다: 비공개는 내 목록에만 건다
        assertThat(read(browser().perform(get("/api/members/" + fan.handle() + "/followers")).andReturn()).path("items")).hasSize(1);

        String html = browser().perform(get("/@" + owner.handle() + "/followers")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(html).contains("비공개 계정입니다").doesNotContain(">" + fan.handle());

        owner.http().perform(asJson(patch("/api/me/settings"), Map.of("followListPublic", true))).andExpect(status().isOk());
        assertThat(read(browser().perform(get("/api/members/" + owner.handle() + "/followers")).andReturn()).path("items")).hasSize(1);
    }
}
