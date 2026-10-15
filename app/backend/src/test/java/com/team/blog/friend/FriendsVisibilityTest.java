package com.team.blog.friend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;

import com.team.blog.support.IntegrationTest;

/**
 * 친구에게만 공개(FRIENDS, V4) 권한 매트릭스 (docs/06 §3·§8): 공개 범위 × 보는 사람(비회원 / 다른 회원 / 친구 / 작성자)
 * × 노출되는 곳(상세 / 홈 / 블로그 목록 / 블로그 글 수 / 태그 / 댓글 / 첫 화면 HTML).
 */
class FriendsVisibilityTest extends IntegrationTest {

    long publish(Session s, String title, String visibility, List<String> tags) throws Exception {
        long id = read(s.http().perform(asJson(post("/api/posts"), Map.of("title", title, "contentMd", "본문")))
                .andExpect(status().isCreated()).andReturn()).path("id").asLong();
        s.http().perform(asJson(post("/api/posts/" + id + "/publish"), Map.of("title", title, "contentMd", "본문",
                        "visibility", visibility, "baseVersion", 0, "tags", tags))
                .header("Idempotency-Key", UUID.randomUUID().toString())).andExpect(status().isOk());
        return id;
    }

    void befriend(Session a, Session b) throws Exception {
        a.http().perform(put("/api/me/friends/" + b.handle()).with(csrf())).andExpect(status().isOk());
        b.http().perform(post("/api/me/friends/" + a.handle() + "/accept").with(csrf())).andExpect(status().isOk());
    }

    static List<Long> ids(JsonNode page) {
        List<Long> out = new ArrayList<>();
        page.path("items").forEach(n -> out.add(n.path("id").asLong()));
        return out;
    }

    JsonNode get200(Session s, String url) throws Exception {
        return read((s == null ? browser().perform(get(url)) : s.http().perform(get(url))).andExpect(status().isOk()).andReturn());
    }

    int detailStatus(Session s, long id) throws Exception {
        var req = get("/api/posts/" + id);
        return (s == null ? browser().perform(req) : s.http().perform(req)).andReturn().getResponse().getStatus();
    }

    /** 작성자와 친구, 남. 같은 태그로 전체 공개·친구 공개·나만 보기 글을 하나씩 발행했다 */
    record Posts(Session author, Session friend, Session stranger, String tag, long pub, long fr, long pri) {
        String blog() {
            return "/api/members/" + author.handle() + "/posts";
        }

        String profile() {
            return "/api/members/" + author.handle();
        }
    }

    Posts posts() throws Exception {
        Session author = signup(uniqueLogin("fva")), friend = signup(uniqueLogin("fvb")), stranger = signup(uniqueLogin("fvc"));
        befriend(author, friend);
        String tag = "fv-" + UUID.randomUUID().toString().substring(0, 6);
        long pub = publish(author, "전체 공개", "PUBLIC", List.of(tag));
        long fr = publish(author, "친구 공개", "FRIENDS", List.of(tag));
        long pri = publish(author, "나만 보기", "PRIVATE", List.of(tag));
        return new Posts(author, friend, stranger, tag, pub, fr, pri);
    }

    @Test
    void 친구_공개_글은_작성자와_친구만_보고_홈과_태그에는_나오지_않는다() throws Exception {
        Posts p = posts();
        Session author = p.author(), friend = p.friend(), stranger = p.stranger();
        long pub = p.pub(), fr = p.fr(), pri = p.pri();

        // 상세
        assertThat(detailStatus(author, fr)).isEqualTo(200);
        assertThat(detailStatus(friend, fr)).isEqualTo(200);
        assertThat(detailStatus(stranger, fr)).isEqualTo(404);
        assertThat(detailStatus(null, fr)).isEqualTo(404);
        assertThat(detailStatus(friend, pri)).isEqualTo(404);
        String cache = friend.http().perform(get("/api/posts/" + fr)).andReturn().getResponse().getHeader("Cache-Control");
        assertThat(cache).contains("no-store");
        JsonNode detail = get200(friend, "/api/posts/" + fr);
        assertThat(detail.path("visibility").asString()).isEqualTo("FRIENDS");
        assertThat(detail.path("firstPublicAt").isNull()).isTrue();

        // 홈·태그는 누구에게나 공개 글만
        for (Session s : new Session[] {null, friend, author}) {
            assertThat(ids(get200(s, "/api/posts"))).contains(pub).doesNotContain(fr, pri);
            JsonNode t = get200(s, "/api/tags/" + p.tag() + "/posts");
            assertThat(ids(t)).containsExactly(pub);
            assertThat(t.path("postCount").asLong()).isEqualTo(1);
        }
    }

    @Test
    void 블로그_목록과_글_수에는_친구만_친구_공개_글까지_본다() throws Exception {
        Posts p = posts();
        Session author = p.author(), friend = p.friend(), stranger = p.stranger();
        long pub = p.pub(), fr = p.fr();

        // 블로그 목록과 글 수: 친구만 친구 공개 글까지 (발행 최신순)
        String blog = p.blog();
        assertThat(ids(get200(friend, blog))).containsExactly(fr, pub);
        assertThat(ids(get200(stranger, blog))).containsExactly(pub);
        assertThat(ids(get200(null, blog))).containsExactly(pub);
        assertThat(ids(get200(author, blog))).containsExactly(pub); // 작성자도 블로그에는 공개 글만 (내 글 관리에서 봄)
        assertThat(ids(get200(friend, blog + "?tag=" + p.tag()))).containsExactly(fr, pub);
        assertThat(friend.http().perform(get(blog)).andReturn().getResponse().getHeader("Cache-Control")).contains("no-store");
        assertThat(stranger.http().perform(get(blog)).andReturn().getResponse().getHeader("Cache-Control")).doesNotContain("no-store");
        JsonNode card = get200(friend, blog).path("items").get(0);
        assertThat(card.path("visibility").asString()).isEqualTo("FRIENDS");
        assertThat(card.path("firstPublicAt").isNull()).isTrue();
        assertThat(card.path("publishedAt").isNull()).isFalse();
        assertThat(get200(friend, blog).has("friendsView")).isFalse();

        String profile = p.profile();
        assertThat(get200(friend, profile).path("publicPostCount").asLong()).isEqualTo(2);
        assertThat(get200(stranger, profile).path("publicPostCount").asLong()).isEqualTo(1);
        assertThat(get200(null, profile).path("publicPostCount").asLong()).isEqualTo(1);
    }

    @Test
    void 친구_공개_글은_첫_화면과_댓글에서도_친구만_보고_친구를_끊으면_바로_볼_수_없다() throws Exception {
        Posts p = posts();
        Session author = p.author(), friend = p.friend(), stranger = p.stranger();
        long pub = p.pub(), fr = p.fr();

        // 첫 화면 HTML
        String html = friend.http().perform(get("/@" + author.handle())).andReturn().getResponse().getContentAsString();
        assertThat(html).contains("친구 공개").doesNotContain("나만 보기");
        String guestHtml = browser().perform(get("/@" + author.handle())).andReturn().getResponse().getContentAsString();
        assertThat(guestHtml).doesNotContain("친구 공개");
        assertThat(browser().perform(get("/@" + author.handle() + "/posts/" + fr)).andReturn().getResponse().getStatus()).isEqualTo(404);
        String friendPage = friend.http().perform(get("/@" + author.handle() + "/posts/" + fr)).andReturn().getResponse().getContentAsString();
        assertThat(friendPage).contains("noindex");

        // 댓글: 친구는 쓰고, 다른 회원은 없는 글과 같다
        friend.http().perform(asJson(post("/api/posts/" + fr + "/comments"), Map.of("content", "친구 댓글"))).andExpect(status().isCreated());
        stranger.http().perform(asJson(post("/api/posts/" + fr + "/comments"), Map.of("content", "x"))).andExpect(status().isNotFound());
        stranger.http().perform(get("/api/posts/" + fr + "/comments")).andExpect(status().isNotFound());

        // 친구를 끊으면 그 순간부터 볼 수 없다
        friend.http().perform(delete("/api/me/friends/" + author.handle()).with(csrf())).andExpect(status().is2xxSuccessful());
        assertThat(detailStatus(friend, fr)).isEqualTo(404);
        assertThat(ids(get200(friend, p.blog()))).containsExactly(pub);
        assertThat(get200(friend, p.profile()).path("publicPostCount").asLong()).isEqualTo(1);
    }

    @Test
    void 친구_목록_커서는_공개_목록에서_쓸_수_없고_페이지가_이어진다() throws Exception {
        Session author = signup(uniqueLogin("fvd")), friend = signup(uniqueLogin("fve"));
        befriend(author, friend);
        List<Long> made = new ArrayList<>();
        for (int i = 0; i < 12; i++) made.add(publish(author, "글 " + i, i % 2 == 0 ? "FRIENDS" : "PUBLIC", List.of()));
        String blog = "/api/members/" + author.handle() + "/posts";
        JsonNode first = get200(friend, blog);
        String next = first.path("nextCursor").asString();
        assertThat(next).isNotBlank();
        JsonNode second = get200(friend, blog + "?cursor=" + java.net.URLEncoder.encode(next, java.nio.charset.StandardCharsets.UTF_8));
        List<Long> all = new ArrayList<>(ids(first));
        all.addAll(ids(second));
        assertThat(all).containsExactlyElementsOf(made.reversed());
        int status = browser().perform(get(blog + "?cursor=" + java.net.URLEncoder.encode(next, java.nio.charset.StandardCharsets.UTF_8)))
                .andReturn().getResponse().getStatus();
        assertThat(status).isEqualTo(400);
    }

    @Test
    void 공개_범위와_기본값에_FRIENDS를_쓸_수_있고_전체_공개로_바꾸면_그때가_첫_공개다() throws Exception {
        Session author = signup(uniqueLogin("fvf"));
        author.http().perform(asJson(patch("/api/me/settings"), Map.of("defaultVisibility", "FRIENDS"))).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT default_visibility FROM member WHERE id = ?", String.class, author.memberId()))
                .isEqualTo("FRIENDS");
        long draft = read(author.http().perform(asJson(post("/api/posts"), Map.of("title", "t", "contentMd", "b")))
                .andExpect(status().isCreated()).andReturn()).path("id").asLong();
        assertThat(jdbc.queryForObject("SELECT visibility FROM post WHERE id = ?", String.class, draft)).isEqualTo("FRIENDS");

        long id = publish(author, "바꾸기", "FRIENDS", List.of());
        JsonNode r = read(author.http().perform(asJson(patch("/api/posts/" + id + "/visibility"), Map.of("visibility", "PUBLIC")))
                .andExpect(status().isOk()).andReturn());
        assertThat(r.path("firstPublicAt").isNull()).isFalse();
        author.http().perform(asJson(patch("/api/posts/" + id + "/visibility"), Map.of("visibility", "FRIENDS"))).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT first_public_at IS NOT NULL FROM post WHERE id = ?", Boolean.class, id)).isTrue();
        author.http().perform(asJson(patch("/api/posts/" + id + "/visibility"), Map.of("visibility", "GROUP"))).andExpect(status().isBadRequest());

        JsonNode manage = get200(author, "/api/me/posts?tab=PUBLISHED&visibility=FRIENDS");
        assertThat(ids(manage)).containsExactly(id);
    }

    @Test
    void 친구가_보는_블로그_목록은_친구_공개_인덱스를_쓴다() {
        jdbc.execute("ANALYZE post");
        jdbc.execute("SET enable_seqscan = off");
        try {
            String plan = String.join("\n", jdbc.queryForList("""
                    EXPLAIN SELECT p.id FROM post p JOIN member m ON m.id = p.author_id
                    WHERE p.status = 'PUBLISHED' AND p.visibility IN ('PUBLIC', 'FRIENDS') AND p.deleted_at IS NULL AND p.hidden_at IS NULL
                      AND m.withdrawn_at IS NULL AND p.author_id = 1 ORDER BY p.published_at DESC, p.id DESC LIMIT 10""", String.class));
            assertThat(plan).contains("ix_post_blog_friends");
        } finally {
            jdbc.execute("SET enable_seqscan = on");
        }
    }
}
