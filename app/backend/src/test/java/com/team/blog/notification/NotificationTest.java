package com.team.blog.notification;

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
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import tools.jackson.databind.JsonNode;

import com.team.blog.notification.application.NotificationCleanupJob;
import com.team.blog.support.IntegrationTest;

/**
 * spec 015 인수 시나리오 (docs/25 §9). 시험 설정은 알림을 같은 스레드에서 만든다(blog.notification.async=false).
 * 이번 범위는 댓글·답글·좋아요다. 팔로우·새 글(016)과 신고·숨김(019) 알림은 그 기능과 함께 붙는다.
 */
class NotificationTest extends IntegrationTest {
    @Autowired NotificationCleanupJob cleanup;

    long publish(Session s, String title, String visibility) throws Exception {
        long id = read(s.http().perform(asJson(post("/api/posts"), Map.of("title", title, "contentMd", "본문")))
                .andExpect(status().isCreated()).andReturn()).path("id").asLong();
        s.http().perform(asJson(post("/api/posts/" + id + "/publish"), Map.of("title", title, "contentMd", "본문",
                        "visibility", visibility, "baseVersion", 0, "tags", List.of()))
                .header("Idempotency-Key", UUID.randomUUID().toString())).andExpect(status().isOk());
        return id;
    }

    long comment(Session s, long postId, String content, Long replyTo) throws Exception {
        Map<String, Object> body = new HashMap<>(Map.of("content", content));
        if (replyTo != null) body.put("replyToCommentId", replyTo);
        return read(s.http().perform(asJson(post("/api/posts/" + postId + "/comments"), body)
                .header("Idempotency-Key", UUID.randomUUID().toString())).andReturn()).path("id").asLong();
    }

    void like(Session s, long postId, boolean on) throws Exception {
        s.http().perform((on ? put("/api/posts/" + postId + "/like") : delete("/api/posts/" + postId + "/like")).with(csrf()))
                .andExpect(status().isOk());
    }

    JsonNode list(Session s) throws Exception {
        drain();
        return read(s.http().perform(get("/api/me/notifications")).andExpect(status().isOk()).andReturn()).path("items");
    }

    long unread(Session s) throws Exception {
        drain();
        return read(s.http().perform(get("/api/me/notifications/unread-count")).andExpect(status().isOk()).andReturn()).path("count").asLong();
    }

    void resetLimits() {
        var keys = redis.keys("rl:*");
        if (keys != null && !keys.isEmpty()) redis.delete(keys);
    }

    @Test
    void 댓글과_답글_알림은_받는_사람마다_하나고_본인_행동은_알리지_않는다() throws Exception {
        Session a = signup(uniqueLogin("nta")), b = signup(uniqueLogin("ntb")), c = signup(uniqueLogin("ntc"));
        long p = publish(a, "JPA N+1 정리", "PUBLIC");

        long bc = comment(b, p, "좋은 글이에요 ".repeat(10), null);
        comment(a, p, "내 글에 내 댓글", null);
        JsonNode items = list(a);
        assertThat(items).hasSize(1);
        JsonNode n = items.get(0);
        assertThat(n.path("type").asString()).isEqualTo("COMMENT");
        assertThat(n.path("read").asBoolean()).isFalse();
        assertThat(n.path("actor").path("handle").asString()).isEqualTo(b.handle());
        assertThat(n.path("post").path("title").asString()).isEqualTo("JPA N+1 정리");
        assertThat(n.path("commentPreview").asString()).hasSize(51).endsWith("…");
        assertThat(n.path("link").asString()).isEqualTo("/@" + a.handle() + "/posts/" + p + "?comment=" + bc + "#comment-" + bc);

        // C가 B의 댓글에 답글: 글 작성자 A에게 댓글 알림, B에게 답글 알림
        comment(c, p, "동의해요", bc);
        assertThat(list(a)).hasSize(2);
        assertThat(list(b)).hasSize(1);
        assertThat(list(b).get(0).path("type").asString()).isEqualTo("REPLY");

        // A의 댓글에 C가 답글: A는 글 작성자이자 답글 대상 → 답글 알림 하나만 (FR-003)
        long ac = comment(a, p, "작성자 댓글", null);
        comment(c, p, "작성자님께 답글", ac);
        JsonNode aItems = list(a);
        assertThat(aItems).hasSize(3);
        assertThat(aItems.get(0).path("type").asString()).isEqualTo("REPLY");
        assertThat(list(c)).isEmpty();
    }

    @Test
    void 좋아요는_글마다_안_읽은_묶음_하나이고_취소하면_빠진다() throws Exception {
        Session a = signup(uniqueLogin("ntd"));
        long p = publish(a, "묶음 글", "PUBLIC");
        List<Session> likers = new ArrayList<>();
        for (int i = 0; i < 3; i++) likers.add(signup(uniqueLogin("ntl" + i)));
        for (Session s : likers) like(s, p, true);

        JsonNode items = list(a);
        assertThat(items).hasSize(1);
        assertThat(items.get(0).path("type").asString()).isEqualTo("LIKE");
        assertThat(items.get(0).path("actor").path("handle").asString()).isEqualTo(likers.get(2).handle());
        assertThat(items.get(0).path("othersCount").asInt()).isEqualTo(2);
        assertThat(items.get(0).path("link").asString()).isEqualTo("/@" + a.handle() + "/posts/" + p);

        // 대표가 취소하면 남은 사람 중 가장 최근이 대표 (FR-012)
        like(likers.get(2), p, false);
        items = list(a);
        assertThat(items.get(0).path("actor").path("handle").asString()).isEqualTo(likers.get(1).handle());
        assertThat(items.get(0).path("othersCount").asInt()).isEqualTo(1);
        // 같은 사람이 취소·재클릭을 반복해도 알림은 늘지 않는다 (SC-002)
        for (int i = 0; i < 5; i++) {
            like(likers.get(0), p, false);
            like(likers.get(0), p, true);
        }
        assertThat(list(a)).hasSize(1);
    }

    @Test
    void 읽은_좋아요_묶음_뒤의_새_좋아요는_새_묶음이고_모두_취소되면_안_읽은_묶음은_사라진다() throws Exception {
        Session a = signup(uniqueLogin("ntd"));
        long p = publish(a, "묶음 글", "PUBLIC");
        List<Session> likers = new ArrayList<>();
        for (int i = 0; i < 3; i++) likers.add(signup(uniqueLogin("ntl" + i)));
        for (Session s : likers) like(s, p, true);
        like(likers.get(2), p, false);

        // 읽은 뒤 새 좋아요는 새 묶음, 읽은 묶음에 있던 사람은 다시 알리지 않는다 (FR-008·FR-009)
        long id = list(a).get(0).path("id").asLong();
        a.http().perform(post("/api/me/notifications/" + id + "/read").with(csrf())).andExpect(status().isNoContent());
        like(likers.get(0), p, false);
        like(likers.get(0), p, true);
        assertThat(list(a)).hasSize(1);
        like(likers.get(2), p, true);
        JsonNode items = list(a);
        assertThat(items).hasSize(2);
        assertThat(items.get(0).path("read").asBoolean()).isFalse();
        assertThat(items.get(1).path("read").asBoolean()).isTrue();

        // 모두 취소되면 안 읽은 묶음은 사라진다
        like(likers.get(2), p, false);
        assertThat(list(a)).hasSize(1);
    }

    @Test
    void 동시에_좋아요_10개도_안_읽은_묶음은_하나다() throws Exception {
        Session a = signup(uniqueLogin("nte"));
        long p = publish(a, "동시 글", "PUBLIC");
        List<Session> likers = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            if (i % 8 == 7) resetLimits();
            likers.add(signup(uniqueLogin("ntm" + i)));
        }
        ExecutorService pool = Executors.newFixedThreadPool(10);
        try {
            List<Callable<Void>> calls = new ArrayList<>();
            for (Session s : likers) calls.add(() -> { like(s, p, true); return null; });
            for (Future<Void> f : pool.invokeAll(calls)) f.get();
        } finally {
            pool.shutdown();
        }
        JsonNode items = list(a);
        assertThat(items).hasSize(1);
        assertThat(items.get(0).path("othersCount").asInt()).isEqualTo(9);
        drain();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notification_actor x JOIN notification n ON n.id = x.notification_id WHERE n.receiver_id = ?",
                Long.class, a.memberId())).isEqualTo(10L);
    }

    @Test
    void 볼_수_없게_된_글은_제목_없이_보이고_다시_공개하면_돌아온다() throws Exception {
        Session a = signup(uniqueLogin("ntf")), b = signup(uniqueLogin("ntg"));
        long p = publish(b, "B의 글", "PUBLIC");
        long ac = comment(a, p, "A의 댓글", null);
        comment(b, p, "작성자 답글", ac);
        assertThat(list(a).get(0).path("post").path("title").asString()).isEqualTo("B의 글");

        b.http().perform(asJson(patch("/api/posts/" + p + "/visibility"), Map.of("visibility", "PRIVATE"))).andExpect(status().isOk());
        JsonNode hidden = list(a).get(0);
        assertThat(hidden.path("post").path("readable").asBoolean()).isFalse();
        assertThat(hidden.path("post").path("title").isNull()).isTrue();
        assertThat(hidden.path("link").isNull()).isTrue();
        assertThat(hidden.path("commentPreview").isNull()).isTrue();

        b.http().perform(asJson(patch("/api/posts/" + p + "/visibility"), Map.of("visibility", "PUBLIC"))).andExpect(status().isOk());
        assertThat(list(a).get(0).path("post").path("title").asString()).isEqualTo("B의 글");

        // 행동한 사람이 탈퇴 신청하면 "탈퇴한 사용자"
        jdbc.update("UPDATE member SET withdrawn_at = now(), status = 'WITHDRAWN' WHERE id = ?", b.memberId());
        JsonNode actor = list(a).get(0).path("actor");
        assertThat(actor.path("withdrawn").asBoolean()).isTrue();
        assertThat(actor.path("nickname").isNull()).isTrue();
    }

    @Test
    void 지워진_댓글과_완전_삭제된_글의_알림은_사라진다() throws Exception {
        Session a = signup(uniqueLogin("nth")), b = signup(uniqueLogin("nti"));
        long p = publish(a, "지울 글", "PUBLIC");
        long bc = comment(b, p, "곧 지울 댓글", null);
        like(b, p, true);
        assertThat(list(a)).hasSize(2);

        b.http().perform(delete("/api/comments/" + bc).with(csrf())).andExpect(status().isNoContent());
        assertThat(list(a)).hasSize(1);

        // 완전 삭제: 대상 행이 지워지면 공통 행도 함께 (V5 트리거)
        jdbc.update("DELETE FROM post WHERE id = ?", p);
        assertThat(list(a)).isEmpty();
        drain();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notification WHERE receiver_id = ?", Long.class, a.memberId())).isZero();
    }

    @Test
    void 끈_종류는_새로_만들지_않고_운영_알림은_끌_수_없다() throws Exception {
        Session a = signup(uniqueLogin("ntj")), b = signup(uniqueLogin("ntk"));
        long p = publish(a, "설정 글", "PUBLIC");
        comment(b, p, "끄기 전 댓글", null);
        drain(); // 끄기 전에 처리된 알림

        JsonNode s = read(a.http().perform(asJson(put("/api/me/notification-settings"),
                Map.of("muted", List.of("LIKE", "COMMENT", "REPORT_RESOLVED")))).andExpect(status().isOk()).andReturn());
        assertThat(s.path("muted").toString()).contains("LIKE", "COMMENT").doesNotContain("REPORT_RESOLVED");
        like(b, p, true);
        comment(b, p, "끈 뒤 댓글", null);
        assertThat(list(a)).hasSize(1); // 이미 받은 알림은 그대로

        a.http().perform(asJson(put("/api/me/notification-settings"), Map.of("muted", List.of()))).andExpect(status().isOk());
        assertThat(read(a.http().perform(get("/api/me/notification-settings")).andReturn()).path("muted")).isEmpty();
    }

    @Test
    void 읽음_모두_읽음_삭제는_본인_것만이다() throws Exception {
        Session a = signup(uniqueLogin("ntn")), b = signup(uniqueLogin("nto")), admin = signup(uniqueLogin("ntp"));
        long p = publish(a, "읽음 글", "PUBLIC");
        for (int i = 0; i < 3; i++) comment(b, p, "댓글 " + i, null);
        assertThat(unread(a)).isEqualTo(3);
        var res = a.http().perform(get("/api/me/notifications/unread-count")).andReturn().getResponse();
        assertThat(res.getHeader("Cache-Control")).contains("no-store");

        long first = list(a).get(0).path("id").asLong();
        jdbc.update("UPDATE member SET role = 'ADMIN' WHERE id = ?", admin.memberId());
        var adminBrowser = relogin(admin);
        for (var other : List.of(b.http(), adminBrowser)) {
            other.perform(post("/api/me/notifications/" + first + "/read").with(csrf())).andExpect(status().isNotFound());
            other.perform(delete("/api/me/notifications/" + first).with(csrf())).andExpect(status().isNotFound());
        }
        a.http().perform(post("/api/me/notifications/" + first + "/read").with(csrf())).andExpect(status().isNoContent());
        assertThat(unread(a)).isEqualTo(2);
        JsonNode all = read(a.http().perform(post("/api/me/notifications/read-all").with(csrf())).andExpect(status().isOk()).andReturn());
        assertThat(all.path("updated").asInt()).isEqualTo(2);
        assertThat(unread(a)).isZero();
        a.http().perform(delete("/api/me/notifications/" + first).with(csrf())).andExpect(status().isNoContent());
        assertThat(list(a)).hasSize(2);
        a.http().perform(delete("/api/me/notifications/abc").with(csrf())).andExpect(status().isNotFound());

        browser().perform(get("/api/me/notifications")).andExpect(status().isUnauthorized());
        browser().perform(get("/api/me/notifications/unread-count")).andExpect(status().isUnauthorized());
    }

    @Test
    void 목록은_20개씩_넘기고_묶음이_올라가도_중복이_없다() throws Exception {
        Session a = signup(uniqueLogin("ntq")), b = signup(uniqueLogin("ntr"));
        long p = publish(a, "많은 댓글", "PUBLIC");
        for (int i = 0; i < 23; i++) {
            if (i % 5 == 4) resetLimits(); // 댓글 요청 제한을 피한다
            comment(b, p, "댓글 " + i, null);
        }
        JsonNode first = read(a.http().perform(get("/api/me/notifications?size=10")).andReturn());
        assertThat(first.path("items")).hasSize(10);
        JsonNode page1 = read(a.http().perform(get("/api/me/notifications")).andReturn());
        assertThat(page1.path("items")).hasSize(20);
        JsonNode page2 = read(a.http().perform(get("/api/me/notifications?cursor=" + page1.path("nextCursor").asString())).andReturn());
        assertThat(page2.path("items")).hasSize(3);
        assertThat(page2.has("nextCursor") && !page2.path("nextCursor").isNull()).isFalse();
        a.http().perform(get("/api/me/notifications?cursor=broken")).andExpect(status().isBadRequest());
    }

    @Test
    void 정리_작업은_90일_지난_알림과_1000개_넘는_알림을_지운다() throws Exception {
        Session a = signup(uniqueLogin("nts")), b = signup(uniqueLogin("ntt"));
        long p = publish(a, "정리 글", "PUBLIC");
        comment(b, p, "오래된 댓글", null);
        jdbc.update("UPDATE notification SET created_at = now() - interval '91 days', updated_at = now() - interval '91 days' WHERE receiver_id = ?",
                a.memberId());
        long c = comment(b, p, "최근 댓글", null);
        // 1,000개를 넘기려고 같은 댓글을 가리키는 알림을 직접 만든다
        jdbc.update("""
                WITH ins AS (
                    INSERT INTO notification (receiver_id, type, created_at, updated_at)
                    SELECT ?, 'COMMENT', now() - make_interval(mins => g), now() - make_interval(mins => g) FROM generate_series(1, 1005) g
                    RETURNING id)
                INSERT INTO notification_comment (notification_id, type, comment_id) SELECT id, 'COMMENT', ? FROM ins
                """, a.memberId(), c);
        cleanup.run();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notification WHERE receiver_id = ?", Long.class, a.memberId())).isEqualTo(1000L);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notification WHERE receiver_id = ? AND updated_at < now() - interval '90 days'",
                Long.class, a.memberId())).isZero();
    }
}
