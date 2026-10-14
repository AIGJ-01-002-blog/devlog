package com.team.blog.dashboard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;

import com.team.blog.support.IntegrationTest;

/** #239: 회원 본인 대시보드. 내 글이 받은 조회·좋아요·댓글만 세고, 남의 글과 내가 단 댓글은 세지 않는다 */
class MyDashboardTest extends IntegrationTest {
    private long write(Session s, String title, String visibility) throws Exception {
        long id = read(s.http().perform(asJson(post("/api/posts"), Map.of("title", title, "contentMd", "본문")))
                .andExpect(status().isCreated()).andReturn()).path("id").asLong();
        s.http().perform(asJson(post("/api/posts/" + id + "/publish"),
                Map.of("title", title, "contentMd", "본문", "visibility", visibility, "baseVersion", 0, "tags", List.of()))).andExpect(status().isOk());
        return id;
    }

    private void views(long postId, int n, Instant at) {
        for (int i = 0; i < n; i++) jdbc.update("INSERT INTO post_view (post_id, viewed_at) VALUES (?, ?)", postId, Timestamp.from(at));
    }

    private void comment(long postId, long authorId, Instant at) {
        jdbc.update("INSERT INTO comment (post_id, author_id, content, created_at) VALUES (?, ?, '좋아요', ?)", postId, authorId, Timestamp.from(at));
    }

    @Test
    void 내_글이_받은_반응만_기간별로_모으고_많이_읽힌_글은_비공개도_제목을_보여_준다() throws Exception {
        Session me = signup(uniqueLogin("dash"));
        Session reader = signup(uniqueLogin("dashr"));
        long open = write(me, "공개 글", "PUBLIC");
        long secret = write(me, "나만 보는 글", "PRIVATE");
        long others = write(reader, "남의 글", "PUBLIC");
        Instant now = Instant.now();
        Instant tenDaysAgo = now.minus(10, ChronoUnit.DAYS);

        views(open, 3, now);
        views(secret, 5, now);
        views(open, 4, tenDaysAgo);
        views(others, 9, now);
        jdbc.update("INSERT INTO post_like (post_id, member_id) VALUES (?, ?)", open, reader.memberId());
        comment(open, reader.memberId(), now);
        comment(open, me.memberId(), now);

        JsonNode d = read(me.http().perform(get("/api/me/dashboard").param("days", "7")).andExpect(status().isOk()).andReturn());
        assertThat(d.path("days").asInt()).isEqualTo(7);
        assertThat(d.path("daily")).hasSize(7);
        assertThat(d.path("posts").asLong()).isEqualTo(2);
        assertThat(d.path("current").path("views").asLong()).isEqualTo(8);
        assertThat(d.path("previous").path("views").asLong()).isEqualTo(4);
        assertThat(d.path("current").path("likes").asLong()).isEqualTo(1);
        assertThat(d.path("current").path("comments").asLong()).as("내가 단 댓글은 빼고 센다").isEqualTo(1);
        assertThat(d.path("totals").path("views").asLong()).isEqualTo(12);

        JsonNode top = d.path("topPosts");
        assertThat(top).hasSize(2);
        assertThat(top.get(0).path("title").asString()).isEqualTo("나만 보는 글");
        assertThat(top.get(0).path("views").asLong()).isEqualTo(5);
        assertThat(top.get(1).path("link").asString()).isEqualTo("/@" + me.handle() + "/posts/" + open);
        assertThat(d.path("shorts").isNull()).as("쇼츠가 꺼져 있으면 칸이 없다").isFalse();

        // 30일로 넓히면 열흘 전 조회도 들어온다. 모르는 기간은 30일
        JsonNode month = read(me.http().perform(get("/api/me/dashboard").param("days", "13")).andExpect(status().isOk()).andReturn());
        assertThat(month.path("days").asInt()).isEqualTo(30);
        assertThat(month.path("current").path("views").asLong()).isEqualTo(12);

        browser().perform(get("/api/me/dashboard")).andExpect(status().isUnauthorized());
    }

    @Test
    void 글이_없어도_빈_대시보드를_돌려준다() throws Exception {
        Session me = signup(uniqueLogin("dashe"));
        JsonNode d = read(me.http().perform(get("/api/me/dashboard")).andExpect(status().isOk()).andReturn());
        assertThat(d.path("posts").asLong()).isZero();
        assertThat(d.path("daily")).hasSize(30);
        assertThat(d.path("topPosts")).isEmpty();
    }
}
