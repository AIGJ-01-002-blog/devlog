package com.team.blog.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import tools.jackson.databind.JsonNode;

import com.team.blog.account.application.RoleService;
import com.team.blog.account.infra.MemberRepository;
import com.team.blog.shared.security.SessionTerminator;
import com.team.blog.support.Browser;
import com.team.blog.support.IntegrationTest;

/** spec 062: 관리자 페이지. 관리자·매니저만 들어오고, 권한은 관리자만 바꾸며, 운영자 계정은 앱이 뜰 때 관리자가 된다. */
class AdminConsoleTest extends IntegrationTest {
    @Autowired MemberRepository members;
    @Autowired SessionTerminator sessions;
    @Autowired Clock clock;

    private long publish(Session s, String title, String visibility) throws Exception {
        long id = read(s.http().perform(asJson(post("/api/posts"), Map.of("title", title, "contentMd", "본문 " + title)))
                .andExpect(status().isCreated()).andReturn()).path("id").asLong();
        s.http().perform(asJson(post("/api/posts/" + id + "/publish"), Map.of("title", title, "contentMd", "본문 " + title,
                        "visibility", visibility, "baseVersion", 0, "tags", List.of()))
                .header("Idempotency-Key", UUID.randomUUID().toString())).andExpect(status().isOk());
        return id;
    }

    private Browser as(Session s, String role) throws Exception {
        jdbc.update("UPDATE member SET role = ? WHERE id = ?", role, s.memberId());
        return relogin(s);
    }

    private JsonNode ok(Browser b, String url) throws Exception {
        return read(b.perform(get(url)).andExpect(status().isOk()).andReturn());
    }

    @Test
    void 관리자_페이지는_관리자와_매니저만_열리고_일반_회원은_없는_페이지다() throws Exception {
        Session user = signup(uniqueLogin("adu"));
        Session manager = signup(uniqueLogin("adm"));
        Session admin = signup(uniqueLogin("ada"));
        Browser m = as(manager, "MANAGER");
        Browser a = as(admin, "ADMIN");

        for (String url : List.of("/api/admin/dashboard", "/api/admin/members", "/api/admin/posts", "/api/admin/reports",
                "/api/admin/inquiries", "/api/admin/members/" + user.handle() + "/stats")) {
            user.http().perform(get(url)).andExpect(status().isNotFound());
            browser().perform(get(url)).andExpect(status().isUnauthorized());
            m.perform(get(url)).andExpect(status().isOk());
            a.perform(get(url)).andExpect(status().isOk());
        }
        user.http().perform(get("/admin")).andExpect(status().isNotFound());
        m.perform(get("/admin")).andExpect(status().isOk());
        assertThat(ok(m, "/api/auth/me").path("member").path("role").asString()).isEqualTo("MANAGER");
    }

    @Test
    void 대시보드는_기간_합계와_날짜별_추이와_많이_본_공개_글을_준다() throws Exception {
        Session author = signup(uniqueLogin("adw"));
        Session admin = signup(uniqueLogin("adb"));
        long open = publish(author, "대시보드 공개 글", "PUBLIC");
        long secret = publish(author, "대시보드 비공개 글", "PRIVATE");
        jdbc.update("INSERT INTO post_view (post_id) SELECT ? FROM generate_series(1, 500)", open);
        jdbc.update("INSERT INTO post_view (post_id) SELECT ? FROM generate_series(1, 900)", secret);
        Browser a = as(admin, "ADMIN");

        JsonNode d = ok(a, "/api/admin/dashboard?days=7");
        assertThat(d.path("days").asInt()).isEqualTo(7);
        assertThat(d.path("daily").size()).isEqualTo(7);
        assertThat(d.path("current").path("signups").asLong()).isGreaterThanOrEqualTo(2);
        assertThat(d.path("current").path("posts").asLong()).isGreaterThanOrEqualTo(2);
        assertThat(d.path("current").path("views").asLong()).isGreaterThanOrEqualTo(1400);
        JsonNode last = d.path("daily").get(6);
        assertThat(last.path("date").asString()).isEqualTo(d.path("to").asString());
        assertThat(last.path("views").asLong()).isGreaterThanOrEqualTo(1400);
        assertThat(d.path("totals").path("members").path("total").asLong()).isGreaterThanOrEqualTo(2);
        // 비공개 글은 조회수가 더 많아도 많이 본 글에 나오지 않는다 (제목을 관리자에게도 보이지 않음)
        List<Long> top = d.path("topPosts").valueStream().map(p -> p.path("id").asLong()).toList();
        assertThat(top).contains(open).doesNotContain(secret);
        JsonNode first = d.path("topPosts").valueStream().filter(p -> p.path("id").asLong() == open).findFirst().orElseThrow();
        assertThat(first.path("title").asString()).isEqualTo("대시보드 공개 글");
        assertThat(first.path("views").asLong()).isEqualTo(500);
        // 정해 둔 기간이 아니면 30일
        assertThat(ok(a, "/api/admin/dashboard?days=12").path("days").asInt()).isEqualTo(30);
    }

    @Test
    void 회원_목록과_회원별_통계는_글_수와_받은_조회수를_보이고_비공개_글_제목은_감춘다() throws Exception {
        Session author = signup(uniqueLogin("adsa"), "통계작가");
        Session admin = signup(uniqueLogin("adsb"));
        long open = publish(author, "통계 공개 글", "PUBLIC");
        long secret = publish(author, "통계 비공개 글", "PRIVATE");
        jdbc.update("INSERT INTO post_view (post_id) SELECT ? FROM generate_series(1, 3)", open);
        jdbc.update("INSERT INTO post_view (post_id) SELECT ? FROM generate_series(1, 4)", secret);
        jdbc.update("INSERT INTO post_like (post_id, member_id) VALUES (?, ?)", open, admin.memberId());
        Browser a = as(admin, "ADMIN");

        JsonNode list = ok(a, "/api/admin/members?q=" + author.handle());
        assertThat(list.path("total").asLong()).isEqualTo(1);
        JsonNode line = list.path("items").get(0);
        assertThat(line.path("member").path("handle").asString()).isEqualTo(author.handle());
        assertThat(line.path("member").path("provider").asString()).isEqualTo("GITHUB");
        assertThat(line.path("posts").asLong()).isEqualTo(2);
        assertThat(ok(a, "/api/admin/members?q=통계작가").path("total").asLong()).isEqualTo(1);
        assertThat(ok(a, "/api/admin/members?role=staff").path("items").valueStream()
                .map(i -> i.path("member").path("role").asString())).allMatch(r -> !r.equals("USER"));

        JsonNode s = ok(a, "/api/admin/members/" + author.handle() + "/stats");
        assertThat(s.path("posts").path("published").asLong()).isEqualTo(2);
        assertThat(s.path("posts").path("publicPosts").asLong()).isEqualTo(1);
        assertThat(s.path("viewsReceived").asLong()).isEqualTo(7);
        assertThat(s.path("likesReceived").asLong()).isEqualTo(1);
        assertThat(s.path("monthly").size()).isEqualTo(12);
        assertThat(s.path("monthly").get(11).path("posts").asLong()).isEqualTo(2);
        JsonNode best = s.path("topPosts").get(0);
        assertThat(best.path("id").asLong()).isEqualTo(secret);
        assertThat(best.path("title").isNull()).isTrue();
        a.perform(get("/api/admin/members/nobody_here_x/stats")).andExpect(status().isNotFound());
    }

    @Test
    void 권한은_관리자만_바꾸고_관리자_권한은_줄_수_없다() throws Exception {
        Session target = signup(uniqueLogin("adra"));
        Session manager = signup(uniqueLogin("adrb"));
        Session admin = signup(uniqueLogin("adrc"));
        Browser m = as(manager, "MANAGER");
        Browser a = as(admin, "ADMIN");

        assertThat(read(m.perform(asJson(put("/api/admin/members/" + target.handle() + "/role"), Map.of("role", "MANAGER")))
                .andExpect(status().isForbidden()).andReturn()).path("code").asString()).isEqualTo("ADMIN_ONLY");
        a.perform(asJson(put("/api/admin/members/" + target.handle() + "/role"), Map.of("role", "ADMIN"))).andExpect(status().isBadRequest());
        a.perform(asJson(put("/api/admin/members/" + admin.handle() + "/role"), Map.of("role", "USER"))).andExpect(status().isBadRequest());

        // 매니저로 올리면 그 회원의 로그인이 끊기고, 다시 로그인하면 관리자 페이지가 열린다
        target.http().perform(get("/api/admin/dashboard")).andExpect(status().isNotFound());
        assertThat(read(a.perform(asJson(put("/api/admin/members/" + target.handle() + "/role"), Map.of("role", "manager")))
                .andExpect(status().isOk()).andReturn()).path("role").asString()).isEqualTo("MANAGER");
        assertThat(ok(target.http(), "/api/auth/me").path("authenticated").asBoolean()).isFalse();
        Browser again = relogin(target);
        again.perform(get("/api/admin/dashboard")).andExpect(status().isOk());

        // 매니저는 정지할 수 없다 (권한을 먼저 거둔다)
        a.perform(asJson(post("/api/admin/members/" + target.handle() + "/suspension"), Map.of("days", 1, "reason", "테스트")))
                .andExpect(status().isBadRequest());
        a.perform(asJson(put("/api/admin/members/" + target.handle() + "/role"), Map.of("role", "USER"))).andExpect(status().isOk());
        relogin(target).perform(get("/api/admin/dashboard")).andExpect(status().isNotFound());
    }

    @Test
    void 운영자_계정은_앱이_뜰_때_관리자가_되고_권한을_바꿀_수_없다() throws Exception {
        Session owner = signup(uniqueLogin("ado"));
        Session admin = signup(uniqueLogin("adp"));
        RoleService service = new RoleService(members, jdbc, sessions, clock, owner.githubId(), "");
        service.promoteOwner();
        assertThat(jdbc.queryForObject("SELECT role FROM member WHERE id = ?", String.class, owner.memberId())).isEqualTo("ADMIN");
        // 이미 관리자면 그대로 (로그인을 다시 끊지 않는다)
        Browser b = relogin(owner);
        service.promoteOwner();
        assertThat(ok(b, "/api/auth/me").path("member").path("role").asString()).isEqualTo("ADMIN");

        as(admin, "ADMIN");
        assertThat(org.junit.jupiter.api.Assertions.assertThrows(com.team.blog.shared.error.ApiException.class,
                () -> service.change(admin.memberId(), owner.handle(), "USER")).code()).isEqualTo("CANNOT_CHANGE_OWNER");
    }

    @Test
    void 운영자는_블로그_주소로도_정할_수_있다() throws Exception {
        Session owner = signup(uniqueLogin("adq"));
        new RoleService(members, jdbc, sessions, clock, "", "@" + owner.handle()).promoteOwner();
        assertThat(jdbc.queryForObject("SELECT role FROM member WHERE id = ?", String.class, owner.memberId())).isEqualTo("ADMIN");
    }

    @Test
    void 글_관리에서_바로_숨기면_작성자에게_알리고_처리됨_탭에_남고_다시_풀_수_있다() throws Exception {
        Session author = signup(uniqueLogin("adha"));
        Session manager = signup(uniqueLogin("adhb"));
        long open = publish(author, "숨길 글", "PUBLIC");
        long secret = publish(author, "숨긴 비공개 글", "PRIVATE");
        Browser m = as(manager, "MANAGER");

        JsonNode list = ok(m, "/api/admin/posts?author=" + author.handle());
        assertThat(list.path("total").asLong()).isEqualTo(2);
        JsonNode privateLine = list.path("items").valueStream().filter(p -> p.path("id").asLong() == secret).findFirst().orElseThrow();
        assertThat(privateLine.path("title").isNull()).isTrue();
        assertThat(ok(m, "/api/admin/posts?q=숨길").path("items").valueStream().map(p -> p.path("id").asLong())).contains(open);
        assertThat(ok(m, "/api/admin/posts?q=숨긴 비공개").path("total").asLong()).isZero();

        m.perform(asJson(post("/api/admin/posts/" + open + "/hide"), Map.of("reason", "SPAM"))).andExpect(status().isNoContent());
        m.perform(asJson(post("/api/admin/posts/" + open + "/hide"), Map.of("reason", "SPAM"))).andExpect(status().isConflict());
        assertThat(ok(m, "/api/admin/posts?filter=hidden&author=" + author.handle()).path("items").get(0).path("id").asLong()).isEqualTo(open);
        browser().perform(get("/api/posts/" + open)).andExpect(status().isNotFound());
        drain();
        JsonNode notes = ok(author.http(), "/api/me/notifications").path("items");
        assertThat(notes.valueStream().map(n -> n.path("type").asString())).contains("CONTENT_HIDDEN");
        JsonNode handled = ok(m, "/api/admin/reports?tab=handled").path("items");
        // 같은 제목 글을 신고하는 다른 테스트(ReportTest)와 DB를 같이 쓰므로 이 작성자의 글만 본다
        assertThat(handled.valueStream().filter(r -> r.path("preview").asString().equals("숨길 글") && r.path("authorHandle").asString().equals(author.handle()))
                .map(r -> r.path("reportCount").asInt())).containsExactly(0);

        m.perform(asJson(post("/api/admin/posts/" + open + "/unhide"), Map.of())).andExpect(status().isNoContent());
        m.perform(asJson(post("/api/admin/posts/" + open + "/unhide"), Map.of())).andExpect(status().isConflict());
        browser().perform(get("/api/posts/" + open)).andExpect(status().isOk());
        // 자기 글은 숨길 수 없다
        long own = publish(manager, "매니저 글", "PUBLIC");
        relogin(manager).perform(asJson(post("/api/admin/posts/" + own + "/hide"), Map.of("reason", "SPAM"))).andExpect(status().isBadRequest());
    }
}
