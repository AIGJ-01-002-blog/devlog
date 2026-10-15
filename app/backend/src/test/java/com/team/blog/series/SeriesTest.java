package com.team.blog.series;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import tools.jackson.databind.JsonNode;

import com.team.blog.account.application.WithdrawalPurgeStep;
import com.team.blog.shared.config.BlogProperties;
import com.team.blog.support.IntegrationTest;

/** spec 024 인수 시나리오: 묶기(US1), 따라 읽기(US2), 관리(US3). */
class SeriesTest extends IntegrationTest {
    @Autowired List<WithdrawalPurgeStep> purgeSteps;
    @Autowired BlogProperties props;

    long draft(Session s, String title) throws Exception {
        return read(s.http().perform(asJson(post("/api/posts"), Map.of("title", title, "contentMd", "본문")))
                .andExpect(status().isCreated()).andReturn()).path("id").asLong();
    }

    long publish(Session s, String title, String visibility) throws Exception {
        long id = draft(s, title);
        s.http().perform(asJson(post("/api/posts/" + id + "/publish"), Map.of("title", title, "contentMd", "본문",
                        "visibility", visibility, "baseVersion", 0, "tags", List.of()))
                .header("Idempotency-Key", UUID.randomUUID().toString())).andExpect(status().isOk());
        return id;
    }

    JsonNode create(Session s, String name) throws Exception {
        return read(s.http().perform(asJson(post("/api/me/series"), Map.of("name", name))).andExpect(status().isOk()).andReturn());
    }

    ResultActions assign(Session s, long postId, Long seriesId) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("seriesId", seriesId);
        return s.http().perform(asJson(put("/api/posts/" + postId + "/series"), body));
    }

    ResultActions as(Session s, MockHttpServletRequestBuilder req) throws Exception {
        return s == null ? browser().perform(req) : s.http().perform(req);
    }

    JsonNode ok(Session s, String url) throws Exception {
        return read(as(s, get(url)).andExpect(status().isOk()).andReturn());
    }

    static List<Long> ids(JsonNode arr) {
        List<Long> out = new ArrayList<>();
        arr.forEach(n -> out.add(n.path("id").asLong()));
        return out;
    }

    void befriend(Session a, Session b) throws Exception {
        a.http().perform(put("/api/me/friends/" + b.handle()).with(csrf())).andExpect(status().isOk());
        b.http().perform(post("/api/me/friends/" + a.handle() + "/accept").with(csrf())).andExpect(status().isOk());
    }

    // ---------------------------------------------------------------- US1

    @Test
    void 글을_시리즈에_넣으면_맨_뒤에_붙고_한_시리즈에만_든다() throws Exception {
        Session s = signup(uniqueLogin("sr"));
        JsonNode a = create(s, "Spring  입문!");
        assertThat(a.path("slug").asString()).isEqualTo("spring-입문");
        long sa = a.path("id").asLong(), sb = create(s, "레디스").path("id").asLong();
        long p1 = publish(s, "하나", "PUBLIC"), p2 = publish(s, "둘", "PUBLIC"), p3 = publish(s, "셋", "PUBLIC");
        for (long p : List.of(p1, p2, p3)) assign(s, p, sa).andExpect(status().isNoContent());
        assertThat(ids(ok(null, "/api/members/" + s.handle() + "/series/spring-입문").path("posts"))).containsExactly(p1, p2, p3);

        // 다른 시리즈로 옮기면 원래 시리즈에서 빠지고 나머지 순서는 그대로
        assign(s, p2, sb).andExpect(status().isNoContent());
        assertThat(ids(ok(null, "/api/members/" + s.handle() + "/series/spring-입문").path("posts"))).containsExactly(p1, p3);
        assign(s, p2, null).andExpect(status().isNoContent());
        assertThat(as(null, get("/api/posts/" + p2 + "/series")).andReturn().getResponse().getStatus()).isEqualTo(204);

        // 같은 이름(주소)은 409, 빈 이름·기호뿐인 이름은 400
        s.http().perform(asJson(post("/api/me/series"), Map.of("name", "spring 입문"))).andExpect(status().isConflict());
        s.http().perform(asJson(post("/api/me/series"), Map.of("name", " !! "))).andExpect(status().isBadRequest());

        // 남의 글·남의 시리즈는 404
        Session other = signup(uniqueLogin("sro"));
        assign(other, p1, null).andExpect(status().isNotFound());
        long op = publish(other, "남", "PUBLIC");
        assign(other, op, sa).andExpect(status().isNotFound());
        other.http().perform(delete("/api/me/series/" + sa).with(csrf())).andExpect(status().isNotFound());

        JsonNode mine = ok(s, "/api/me/series");
        assertThat(ids(mine)).containsExactly(sb, sa); // 최근에 바뀐 시리즈가 위
        assertThat(mine.get(1).path("postCount").asInt()).isEqualTo(2);
    }

    // ---------------------------------------------------------------- US2

    /** 공개·친구·비공개·공개2·임시글이 이 순서로 든 시리즈 "모음" */
    record Reading(Session author, Session friend, Session stranger, long sid, long pub, long fr, long priv, long pub2, long draft) {
        String url() {
            return "/api/members/" + author.handle() + "/series/모음";
        }
    }

    Reading reading() throws Exception {
        // 회원 번호가 Long 캐시(-128~127) 밖이어야 번호 비교 실수를 잡는다
        jdbc.execute("SELECT setval(pg_get_serial_sequence('member', 'id'), GREATEST(5000, (SELECT max(id) FROM member)))");
        Session author = signup(uniqueLogin("srr")), friend = signup(uniqueLogin("srf")), stranger = signup(uniqueLogin("srs"));
        befriend(author, friend);
        long sid = create(author, "모음").path("id").asLong();
        long pub = publish(author, "공개", "PUBLIC"), fr = publish(author, "친구", "FRIENDS"), priv = publish(author, "비공개", "PRIVATE");
        long pub2 = publish(author, "공개2", "PUBLIC"), draft = draft(author, "임시");
        for (long p : List.of(pub, fr, priv, pub2, draft)) assign(author, p, sid).andExpect(status().isNoContent());
        return new Reading(author, friend, stranger, sid, pub, fr, priv, pub2, draft);
    }

    @Test
    void 독자는_읽을_수_있는_글만으로_순서와_목록을_본다() throws Exception {
        Reading r = reading();
        Session author = r.author(), friend = r.friend(), stranger = r.stranger();
        long pub = r.pub(), fr = r.fr(), priv = r.priv(), pub2 = r.pub2();
        String url = r.url();

        assertThat(ids(ok(null, url).path("posts"))).containsExactly(pub, pub2);
        assertThat(ids(ok(stranger, url).path("posts"))).containsExactly(pub, pub2);
        assertThat(ids(ok(friend, url).path("posts"))).containsExactly(pub, fr, pub2);
        JsonNode own = ok(author, url);
        assertThat(own.path("mine").asBoolean()).isTrue();
        assertThat(ids(own.path("posts"))).containsExactly(pub, fr, priv, pub2);

        // 친구·주인 응답은 저장하지 않는다
        assertThat(friend.http().perform(get(url)).andReturn().getResponse().getHeader("Cache-Control")).contains("no-store");

        JsonNode nav = ok(null, "/api/posts/" + pub2 + "/series");
        assertThat(nav.path("name").asString()).isEqualTo("모음");
        assertThat(nav.path("index").asInt()).isEqualTo(2);
        assertThat(ids(nav.path("posts"))).containsExactly(pub, pub2);
        assertThat(nav.path("posts").get(0).path("url").asString()).isEqualTo("/@" + author.handle() + "/posts/" + pub);
        assertThat(ok(friend, "/api/posts/" + pub2 + "/series").path("index").asInt()).isEqualTo(3);
        // 읽을 수 없는 글의 시리즈는 드러나지 않는다
        assertThat(as(stranger, get("/api/posts/" + fr + "/series")).andReturn().getResponse().getStatus()).isEqualTo(204);
        // 작성자는 임시글에서도 시리즈를 안다(순서는 없음)
        JsonNode draftNav = ok(author, "/api/posts/" + r.draft() + "/series");
        assertThat(draftNav.path("id").asLong()).isEqualTo(r.sid());
        assertThat(draftNav.path("index").isNull()).isTrue();
    }

    @Test
    void 블로그_시리즈_목록과_첫_화면은_읽을_수_있는_글이_있는_시리즈만_보인다() throws Exception {
        Reading r = reading();
        Session author = r.author();

        JsonNode list = ok(null, "/api/members/" + author.handle() + "/series");
        assertThat(list.get(0).path("postCount").asInt()).isEqualTo(2);
        assertThat(ok(author, "/api/members/" + author.handle() + "/series").get(0).path("postCount").asInt()).isEqualTo(4);

        // 읽을 수 있는 글이 없는 시리즈는 남에게 없다
        create(author, "빈 시리즈");
        long onlyPriv = create(author, "비밀").path("id").asLong();
        assign(author, publish(author, "혼자", "PRIVATE"), onlyPriv).andExpect(status().isNoContent());
        assertThat(ok(null, "/api/members/" + author.handle() + "/series")).hasSize(1);
        assertThat(ok(author, "/api/members/" + author.handle() + "/series")).hasSize(3);
        as(null, get("/api/members/" + author.handle() + "/series/비밀")).andExpect(status().isNotFound());
        ok(author, "/api/members/" + author.handle() + "/series/비밀");
        // 첫 화면(SSR)도 같은 조건: 남에게 없는 시리즈는 404, 있는 시리즈는 순서대로 링크
        String page = as(null, get("/@" + author.handle() + "/series/모음")).andExpect(status().isOk()).andReturn()
                .getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(page).contains("<h1>모음</h1>").contains("/posts/" + r.pub()).doesNotContain("/posts/" + r.priv());
        as(null, get("/@" + author.handle() + "/series/비밀")).andExpect(status().isNotFound());
        as(null, get("/@" + author.handle() + "/series")).andExpect(status().isOk());
        as(null, get("/api/members/nobody-" + UUID.randomUUID().toString().substring(0, 6) + "/series")).andExpect(status().isNotFound());
    }

    // ---------------------------------------------------------------- US3

    @Test
    void 순서를_바꾸면_휴지통_글과_임시글은_뒤에_남는다() throws Exception {
        Session s = signup(uniqueLogin("srm"));
        long sid = create(s, "정리").path("id").asLong();
        long p1 = publish(s, "1", "PUBLIC"), p2 = publish(s, "2", "PUBLIC"), p3 = publish(s, "3", "PUBLIC"), d = draft(s, "임시");
        for (long p : List.of(p1, p2, p3, d)) assign(s, p, sid).andExpect(status().isNoContent());
        String url = "/api/members/" + s.handle() + "/series/정리";

        // 휴지통 글은 보이지 않다가 복구하면 돌아온다 (FR-005)
        s.http().perform(delete("/api/posts/" + p2).with(csrf())).andExpect(status().isOk());
        assertThat(ids(ok(null, url).path("posts"))).containsExactly(p1, p3);

        // 보이는 글만 다시 늘어놓는다. 휴지통 글·임시글은 빠지지 않고 뒤에 남는다
        s.http().perform(asJson(put("/api/me/series/" + sid + "/posts"), Map.of("postIds", List.of(p3, p1))))
                .andExpect(status().isNoContent());
        assertThat(ids(ok(null, url).path("posts"))).containsExactly(p3, p1);
        s.http().perform(post("/api/posts/" + p2 + "/restore").with(csrf())).andExpect(status().isOk());
        assertThat(ids(ok(null, url).path("posts"))).containsExactly(p3, p1, p2);
        assertThat(ok(s, "/api/posts/" + d + "/series").path("id").asLong()).isEqualTo(sid);
    }

    @Test
    void 목록에서_뺀_글은_빠지고_이름을_바꾸고_지울_수_있다() throws Exception {
        Session s = signup(uniqueLogin("srm"));
        long sid = create(s, "정리").path("id").asLong();
        long p1 = publish(s, "1", "PUBLIC"), p2 = publish(s, "2", "PUBLIC"), p3 = publish(s, "3", "PUBLIC"), d = draft(s, "임시");
        for (long p : List.of(p1, p2, p3, d)) assign(s, p, sid).andExpect(status().isNoContent());
        String url = "/api/members/" + s.handle() + "/series/정리";

        // 빼기: 목록에 없는 보이는 글은 빠진다
        s.http().perform(asJson(put("/api/me/series/" + sid + "/posts"), Map.of("postIds", List.of(p1, p2))))
                .andExpect(status().isNoContent());
        assertThat(ids(ok(null, url).path("posts"))).containsExactly(p1, p2);
        assertThat(as(null, get("/api/posts/" + p3 + "/series")).andReturn().getResponse().getStatus()).isEqualTo(204);
        // 시리즈에 없는 글·중복은 400
        s.http().perform(asJson(put("/api/me/series/" + sid + "/posts"), Map.of("postIds", List.of(p1, p3))))
                .andExpect(status().isBadRequest());
        s.http().perform(asJson(put("/api/me/series/" + sid + "/posts"), Map.of("postIds", List.of(p1, p1))))
                .andExpect(status().isBadRequest());

        // 이름을 바꾸면 주소도 바뀐다
        JsonNode renamed = read(s.http().perform(asJson(patch("/api/me/series/" + sid), Map.of("name", "Redis 정리")))
                .andExpect(status().isOk()).andReturn());
        assertThat(renamed.path("slug").asString()).isEqualTo("redis-정리");
        as(null, get(url)).andExpect(status().isNotFound());
        assertThat(ids(ok(null, "/api/members/" + s.handle() + "/series/redis-정리").path("posts"))).containsExactly(p1, p2);

        // 지우면 시리즈만 지워지고 글은 남는다
        s.http().perform(delete("/api/me/series/" + sid).with(csrf())).andExpect(status().isNoContent());
        ok(null, "/api/posts/" + p1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM series_post WHERE series_id = ?", Long.class, sid)).isZero();
    }

    @Test
    void 동시에_만들어도_시리즈_개수_제한을_넘지_않는다() throws Exception {
        Session s = signup(uniqueLogin("src"));
        for (int i = 0; i < props.series().maxPerMember() - 2; i++) {
            jdbc.update("INSERT INTO series (member_id, name, slug) VALUES (?, ?, ?)", s.memberId(), "s" + i, "s" + i);
        }
        var pool = java.util.concurrent.Executors.newFixedThreadPool(5);
        try {
            List<java.util.concurrent.Future<Integer>> results = new ArrayList<>();
            for (int i = 0; i < 5; i++) {
                String name = "동시" + i;
                results.add(pool.submit(() -> s.http().perform(asJson(post("/api/me/series"), Map.of("name", name)))
                        .andReturn().getResponse().getStatus()));
            }
            List<Integer> codes = new ArrayList<>();
            for (var r : results) codes.add(r.get());
            assertThat(codes).filteredOn(c -> c == 200).hasSize(2);
            assertThat(codes).filteredOn(c -> c == 400).hasSize(3);
        } finally {
            pool.shutdown();
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM series WHERE member_id = ?", Long.class, s.memberId()))
                .isEqualTo(props.series().maxPerMember());
    }

    @Test
    void 탈퇴_정리_때_시리즈를_지운다() throws Exception {
        Session s = signup(uniqueLogin("srw"));
        long sid = create(s, "남길까").path("id").asLong();
        assign(s, publish(s, "글", "PUBLIC"), sid).andExpect(status().isNoContent());
        purgeSteps.stream().filter(p -> p.order() == 15).findFirst().orElseThrow().purge(s.memberId());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM series WHERE member_id = ?", Long.class, s.memberId())).isZero();
    }
}
