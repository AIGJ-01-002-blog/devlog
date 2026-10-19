package com.team.blog.trending;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import tools.jackson.databind.JsonNode;

import com.team.blog.support.IntegrationTest;
import com.team.blog.trending.application.TrendingRanker;
import com.team.blog.trending.application.TrendingService;

/**
 * 017 트렌딩. 반응(좋아요·댓글·조회)은 점수 계산만 보려고 표에 바로 넣는다(각 기능의 규칙은 그 기능 시험이 맡는다).
 * 다른 시험이 만든 최근 글이 순위를 채우지 않게, 시작할 때 기존 글의 처음 공개 시각을 30일 앞으로 민다(서로의 순서는 그대로).
 */
class TrendingTest extends IntegrationTest {
    @Autowired TrendingRanker ranker;
    @Autowired TrendingService trending;

    List<Session> likers = new ArrayList<>();

    @BeforeEach
    void isolate() {
        jdbc.update("UPDATE post SET first_public_at = first_public_at - interval '30 days' WHERE first_public_at > now() - interval '8 days'");
        redis.delete(List.of("trending:v2:current"));
    }

    long publish(Session s, String title, String visibility) throws Exception {
        resetRateLimits(); // 글쓰기 요청 제한은 이 시험의 관심사가 아니다
        long id = read(s.http().perform(asJson(post("/api/posts"), Map.of("title", title, "contentMd", "본문")))
                .andExpect(status().isCreated()).andReturn()).path("id").asLong();
        s.http().perform(asJson(post("/api/posts/" + id + "/publish"), Map.of("title", title, "contentMd", "본문",
                        "visibility", visibility, "baseVersion", 0, "tags", List.of()))
                .header("Idempotency-Key", UUID.randomUUID().toString())).andExpect(status().isOk());
        return id;
    }

    Session liker(int i) throws Exception {
        while (likers.size() <= i) {
            resetRateLimits();
            likers.add(signup(uniqueLogin("tl")));
        }
        return likers.get(i);
    }

    void likes(long post, int n) throws Exception {
        for (int i = 0; i < n; i++) jdbc.update("INSERT INTO post_like (post_id, member_id) VALUES (?, ?)", post, liker(i).memberId());
    }

    void comment(long post, long authorId, int times) {
        for (int i = 0; i < times; i++) {
            jdbc.update("INSERT INTO comment (post_id, author_id, content) VALUES (?, ?, '댓글')", post, authorId);
        }
    }

    void views(long post, int n) {
        jdbc.update("INSERT INTO post_view (post_id) SELECT ? FROM generate_series(1, ?)", post, n);
    }

    void age(long post, String interval) {
        jdbc.update("UPDATE post SET first_public_at = now() - ?::interval, published_at = now() - ?::interval WHERE id = ?", interval, interval, post);
    }

    @Test
    void 점수와_대상_규칙() throws Exception {
        Session a = signup(uniqueLogin("ta")), b = signup(uniqueLogin("tb")), c = signup(uniqueLogin("tc"));
        long fresh = publish(a, "1시간 된 글", "PUBLIC");
        long dayOld = publish(b, "하루 된 글", "PUBLIC");
        long sixDays = publish(c, "6일 된 글", "PUBLIC");
        long eightDays = publish(c, "8일 된 글", "PUBLIC");
        long selfComments = publish(c, "자기 댓글만", "PUBLIC");
        long viewsOnly = publish(b, "조회만", "PUBLIC");
        long hidden = publish(b, "비공개", "PRIVATE");
        age(fresh, "1 hour");
        age(dayOld, "1 day");
        age(sixDays, "6 days");
        age(eightDays, "8 days");
        likes(fresh, 2);
        likes(dayOld, 2);
        likes(sixDays, 2);
        likes(eightDays, 5);
        likes(hidden, 5);
        comment(selfComments, c.memberId(), 20);
        views(viewsOnly, 500);

        List<Long> ranked = ranker.rank(Instant.now(), 100);
        assertThat(ranked).containsSubsequence(fresh, dayOld, sixDays)
                .doesNotContain(eightDays, selfComments, viewsOnly, hidden);
        // 같은 반응이면 1시간 된 글이 하루 된 글보다 약 25배 (26/3)^1.5
        double ratio = Math.pow(26.0 / 3.0, 1.5);
        assertThat(ratio).isBetween(24.0, 27.0);

        // 탈퇴 신청한 작성자의 글은 빠진다
        jdbc.update("UPDATE member SET withdrawn_at = now(), status = 'WITHDRAWN' WHERE id = ?", a.memberId());
        assertThat(ranker.rank(Instant.now(), 100)).doesNotContain(fresh);
    }

    @Test
    void 댓글은_작성자를_뺀_서로_다른_사람_수로_센다() throws Exception {
        Session author = signup(uniqueLogin("td")), x = signup(uniqueLogin("te"));
        long self = publish(author, "자기 댓글 20개 + 좋아요 1", "PUBLIC");
        long three = publish(x, "남 3명 댓글 7개", "PUBLIC");
        long one = publish(x, "남 1명 + 삭제된 댓글", "PUBLIC");
        likes(self, 1);
        comment(self, author.memberId(), 20);
        for (int i = 0; i < 3; i++) comment(three, liker(i).memberId(), 2);
        comment(three, x.memberId(), 1);
        comment(one, liker(0).memberId(), 1);
        comment(one, liker(1).memberId(), 1);
        jdbc.update("UPDATE comment SET deleted_at = now() WHERE post_id = ? AND author_id = ?", one, liker(1).memberId());
        for (long p : List.of(self, three, one)) age(p, "3 hours");
        // 점수: three = 2×3 = 6, self = 3×1 = 3, one = 2×1 = 2 (자기 댓글·삭제된 댓글은 0)
        assertThat(ranker.rank(Instant.now(), 100)).containsSubsequence(three, self, one);
    }

    @Test
    void 한_작성자는_3개까지() throws Exception {
        Session prolific = signup(uniqueLogin("tf"));
        List<Long> posts = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            long p = publish(prolific, "많이 쓴 사람 " + i, "PUBLIC");
            likes(p, i + 1);
            posts.add(p);
        }
        List<Long> ranked = ranker.rank(Instant.now(), 100);
        assertThat(ranked.stream().filter(posts::contains).toList()).containsExactly(posts.get(4), posts.get(3), posts.get(2));
    }

    @Test
    void 보던_순위표로_이어_보고_볼_수_없게_된_글은_건너뛴다() throws Exception {
        List<Long> expected = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            Session w = signup(uniqueLogin("tw"));
            for (int j = 0; j < 3; j++) {
                long p = publish(w, "글 " + i + "-" + j, "PUBLIC");
                likes(p, 1 + (i * 3 + j) % 4);
                expected.add(p);
            }
            resetRateLimits();
        }
        trending.refresh();
        JsonNode first = read(browser().perform(get("/api/posts/trending")).andExpect(status().isOk()).andReturn());
        assertThat(first.path("items")).hasSize(9);
        List<Long> seen = new ArrayList<>();
        first.path("items").forEach(n -> seen.add(n.path("id").asLong()));
        List<Long> snapshot = ranker.rank(Instant.now(), 100);

        // 새 순위표가 생겨도(새 글이 1등) 보던 순위표로 이어진다. 아직 안 본 글 2개는 그 사이 비공개·휴지통이 된다
        Session late = signup(uniqueLogin("tz"));
        long newcomer = publish(late, "새 1등", "PUBLIC");
        likes(newcomer, 4);
        List<Long> unseen = snapshot.subList(9, snapshot.size());
        jdbc.update("UPDATE post SET visibility = 'PRIVATE' WHERE id = ?", unseen.get(0));
        jdbc.update("UPDATE post SET deleted_at = now() WHERE id = ?", unseen.get(1));
        trending.refresh();

        JsonNode second = read(browser().perform(get(URI.create("/api/posts/trending?cursor=" + first.path("nextCursor").asString())))
                .andExpect(status().isOk()).andReturn());
        second.path("items").forEach(n -> seen.add(n.path("id").asLong()));
        assertThat(second.path("nextCursor").isNull()).isTrue();
        assertThat(seen).doesNotHaveDuplicates().doesNotContain(newcomer, unseen.get(0), unseen.get(1)).hasSize(13);
        assertThat(expected).containsAll(seen);

        // 새로 열면 새 순위표
        JsonNode fresh = read(browser().perform(get("/api/posts/trending")).andReturn());
        assertThat(fresh.path("items").get(0).path("id").asLong()).isEqualTo(newcomer);

        // 보던 순위표가 없어지면 410, 고친 커서는 400
        redis.delete(redis.keys("trending:v2:snapshot:*"));
        browser().perform(get(URI.create("/api/posts/trending?cursor=" + first.path("nextCursor").asString()))).andExpect(status().isGone());
        browser().perform(get("/api/posts/trending?cursor=abc")).andExpect(status().isBadRequest());
        // 지금 순위표가 없으면 그 자리에서 만들어 준다
        assertThat(read(browser().perform(get("/api/posts/trending")).andExpect(status().isOk()).andReturn()).path("items")).isNotEmpty();
    }

    @Test
    void 대상이_없으면_비어_있다() throws Exception {
        trending.refresh();
        JsonNode page = read(browser().perform(get("/api/posts/trending")).andExpect(status().isOk()).andReturn());
        assertThat(page.path("items")).isEmpty();
        assertThat(page.path("nextCursor").isNull()).isTrue();
        String html = browser().perform(get("/?tab=trending")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(html).contains("<h1>트렌딩</h1>").contains("\"trending\"");
    }
}
