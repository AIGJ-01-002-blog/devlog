package com.team.blog.comment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.ResultActions;

import tools.jackson.databind.JsonNode;

import com.team.blog.comment.application.CommentText;
import com.team.blog.comment.application.CommentEvents.CommentCreated;
import com.team.blog.comment.application.CommentEvents.CommentDeleted;
import com.team.blog.support.HtmlSafety;
import com.team.blog.support.IntegrationTest;

/** spec 011 인수 시나리오: 댓글(US1), 답글(US2), 목록(US3), 수정·삭제(US4), 볼 수 없는 글(US5). */
@RecordApplicationEvents
class CommentTest extends IntegrationTest {
    @Autowired ApplicationEvents events;

    long postOf(Session s, String visibility) throws Exception {
        long id = read(s.http().perform(asJson(post("/api/posts"), Map.of("title", "댓글 글", "contentMd", "본문")))
                .andExpect(status().isCreated()).andReturn()).path("id").asLong();
        s.http().perform(asJson(post("/api/posts/" + id + "/publish"), Map.of("title", "댓글 글", "contentMd", "본문",
                        "visibility", visibility, "baseVersion", 0, "tags", List.of()))
                .header("Idempotency-Key", UUID.randomUUID().toString())).andExpect(status().isOk());
        return id;
    }

    ResultActions write(Session s, long postId, String content, Long replyTo) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("content", content);
        if (replyTo != null) body.put("replyToCommentId", replyTo);
        return s.http().perform(asJson(post("/api/posts/" + postId + "/comments"), body));
    }

    long comment(Session s, long postId, String content) throws Exception {
        return read(write(s, postId, content, null).andExpect(status().isCreated()).andReturn()).path("id").asLong();
    }

    long reply(Session s, long postId, long to, String content) throws Exception {
        return read(write(s, postId, content, to).andExpect(status().isCreated()).andReturn()).path("id").asLong();
    }

    JsonNode list(Session s, long postId, String query) throws Exception {
        var b = get("/api/posts/" + postId + "/comments" + (query == null ? "" : "?" + query));
        return read((s == null ? browser().perform(b) : s.http().perform(b)).andExpect(status().isOk()).andReturn());
    }

    long count(long postId) {
        return jdbc.queryForObject("SELECT comment_count FROM post_stat WHERE post_id = ?", Long.class, postId);
    }

    long visible(long postId) {
        return jdbc.queryForObject("SELECT count(*) FROM comment WHERE post_id = ? AND deleted_at IS NULL AND hidden_at IS NULL",
                Long.class, postId);
    }

    void backdate(long commentId, String createdAt) {
        jdbc.update("UPDATE comment SET created_at = ?::timestamptz, updated_at = ?::timestamptz WHERE id = ?", createdAt, createdAt, commentId);
    }

    static List<Long> ids(JsonNode items) {
        List<Long> out = new ArrayList<>();
        items.forEach(n -> out.add(n.path("id").asLong()));
        return out;
    }

    // ---------------------------------------------------------------- US1

    @Test
    void 회원이_쓴_댓글이_작성자와_함께_보이고_댓글_수가_는다() throws Exception {
        Session owner = signup(uniqueLogin("cma")), reader = signup(uniqueLogin("cmb"));
        long p = postOf(owner, "PUBLIC");
        JsonNode created = read(write(reader, p, "  좋은 글이에요\r\n\n\n\n고마워요  ", null).andExpect(status().isCreated()).andReturn());
        assertThat(created.path("content").asString()).isEqualTo("좋은 글이에요\n\n고마워요");
        assertThat(created.path("author").path("handle").asString()).isEqualTo(reader.handle());
        assertThat(created.path("author").path("isPostAuthor").asBoolean()).isFalse();
        assertThat(created.path("mine").asBoolean()).isTrue();
        assertThat(events.stream(CommentCreated.class).anyMatch(e -> e.postId() == p && e.authorId() == reader.memberId()
                && e.postAuthorId() == owner.memberId() && e.rootId() == null)).isTrue();

        long mine = comment(owner, p, "작성자 댓글");
        JsonNode page = list(null, p, null);
        assertThat(page.path("commentCount").asLong()).isEqualTo(2);
        assertThat(page.path("canWrite").asBoolean()).isFalse();
        assertThat(page.path("items").get(1).path("id").asLong()).isEqualTo(mine);
        assertThat(page.path("items").get(1).path("author").path("isPostAuthor").asBoolean()).isTrue();
        assertThat(page.path("items").get(0).path("mine").asBoolean()).isFalse();
        assertThat(read(browser().perform(get("/api/posts/" + p)).andReturn()).path("commentCount").asLong()).isEqualTo(2);

        browser().perform(asJson(post("/api/posts/" + p + "/comments"), Map.of("content", "비회원"))).andExpect(status().isUnauthorized());
    }

    @Test
    void 내용은_정리한_뒤_1자에서_1000자이고_글자_그대로_저장된다() throws Exception {
        Session s = signup(uniqueLogin("cmc"));
        long p = postOf(s, "PUBLIC");
        write(s, p, " ​\n\t ", null).andExpect(status().isBadRequest())
                .andExpect(r -> assertThat(r.getResponse().getContentAsString()).contains("COMMENT_REQUIRED"));
        write(s, p, "가".repeat(1001), null).andExpect(status().isBadRequest())
                .andExpect(r -> assertThat(r.getResponse().getContentAsString()).contains("COMMENT_TOO_LONG"));
        // 이모지 하나는 1자다
        write(s, p, "😀".repeat(1000), null).andExpect(status().isCreated());
        for (String attack : HtmlSafety.attacks("https://cdn.example.com")) {
            if (CommentText.clean(attack).isEmpty()) continue;
            redis.delete(redis.keys("rl:*")); // 1분 10개 제한과 무관하게 공격 문자열을 모두 넣어 본다
            JsonNode c = read(write(s, p, attack, null).andReturn());
            // 글자 그대로 저장하고 돌려준다. 화면은 글자로만 그린다(텍스트 노드)
            assertThat(c.path("content").asString()).isEqualTo(CommentText.clean(attack));
        }
    }

    @Test
    void 같은_요청을_10초_안에_다시_보내면_댓글은_하나다() throws Exception {
        Session s = signup(uniqueLogin("cmd"));
        long p = postOf(s, "PUBLIC");
        ExecutorService pool = Executors.newFixedThreadPool(5);
        try {
            List<Callable<Long>> jobs = new ArrayList<>();
            for (int i = 0; i < 5; i++) jobs.add(() -> read(write(s, p, "한 번만", null).andReturn()).path("id").asLong());
            List<Long> got = new ArrayList<>();
            for (Future<Long> f : pool.invokeAll(jobs)) got.add(f.get());
            assertThat(got).containsOnly(got.get(0));
        } finally {
            pool.shutdown();
        }
        assertThat(count(p)).isOne();
        write(s, p, "한 번만", null).andExpect(status().isOk());
        assertThat(count(p)).isOne();
    }

    @Test
    void 작성은_1분에_10개까지다() throws Exception {
        Session s = signup(uniqueLogin("cme"));
        long p = postOf(s, "PUBLIC");
        for (int i = 0; i < 10; i++) write(s, p, "댓글 " + i, null).andExpect(status().isCreated());
        write(s, p, "열한 번째", null).andExpect(status().isTooManyRequests())
                .andExpect(r -> assertThat(r.getResponse().getContentAsString()).contains("RATE_LIMITED"));
        // 제한은 글이 있는지와 무관하게 같다
        write(s, 999_999_999L, "없는 글", null).andExpect(status().isTooManyRequests());
    }

    // ---------------------------------------------------------------- US2

    @Test
    void 답글은_한_단계이고_답글에_답하면_대상_회원이_남는다() throws Exception {
        Session a = signup(uniqueLogin("cmf")), b = signup(uniqueLogin("cmg")), c = signup(uniqueLogin("cmh"));
        long p = postOf(a, "PUBLIC");
        long root = comment(a, p, "질문");
        long r1 = reply(b, p, root, "답");
        long r2 = reply(c, p, r1, "답의 답");
        long r3 = reply(b, p, r1, "내 답에 내가");

        assertThat(jdbc.queryForObject("SELECT parent_id FROM comment WHERE id = ?", Long.class, r2)).isEqualTo(root);
        assertThat(jdbc.queryForObject("SELECT reply_to_member_id FROM comment WHERE id = ?", Long.class, r2)).isEqualTo(b.memberId());
        assertThat(jdbc.queryForObject("SELECT reply_to_member_id FROM comment WHERE id = ?", Long.class, r3)).isEqualTo(b.memberId());
        assertThat(jdbc.queryForObject("SELECT reply_to_member_id FROM comment WHERE id = ?", Long.class, r1)).isNull();
        assertThat(events.stream(CommentCreated.class).anyMatch(e -> e.commentId() == r2 && e.rootId() == root
                && e.rootAuthorId() == a.memberId() && e.replyToMemberId() == b.memberId())).isTrue();

        JsonNode replies = list(null, p, null).path("items").get(0).path("replies");
        assertThat(ids(replies)).containsExactly(r1, r2, r3);
        assertThat(replies.get(1).path("replyTo").path("nickname").asString()).isNotEmpty();
        // 화면이 가지 선을 그리도록 답한 답글 번호를 준다. 최상위에 바로 단 답글은 없다 (FR-011a)
        assertThat(replies.get(0).path("replyToCommentId").isNull()).isTrue();
        assertThat(replies.get(1).path("replyToCommentId").asLong()).isEqualTo(r1);
        assertThat(replies.get(2).path("replyToCommentId").asLong()).isEqualTo(r1);
        // 대상이 탈퇴하면 "탈퇴한 사용자에게"
        jdbc.update("UPDATE member SET withdrawn_at = now(), status = 'WITHDRAWN' WHERE id = ?", b.memberId());
        JsonNode after = list(null, p, null).path("items").get(0).path("replies");
        assertThat(after.get(1).path("replyTo").path("withdrawn").asBoolean()).isTrue();
        assertThat(after.get(1).path("replyTo").path("handle").isNull()).isTrue();
        assertThat(after.get(0).path("state").asString()).isEqualTo("WITHDRAWN_AUTHOR");
        assertThat(after.get(0).has("content") && !after.get(0).path("content").isNull()).isFalse();
    }

    @Test
    void 정상이_아닌_댓글이나_다른_글의_댓글에는_답글을_달_수_없다() throws Exception {
        Session a = signup(uniqueLogin("cmi")), b = signup(uniqueLogin("cmj"));
        long p = postOf(a, "PUBLIC"), other = postOf(a, "PUBLIC");
        long hidden = comment(b, p, "숨김");
        jdbc.update("UPDATE comment SET hidden_at = now(), hidden_reason = 'SPAM' WHERE id = ?", hidden);
        long elsewhere = comment(b, other, "다른 글");
        long root = comment(a, p, "자리");
        reply(b, p, root, "답");
        a.http().perform(delete("/api/comments/" + root).with(csrf())).andExpect(status().isNoContent());
        for (long target : List.of(hidden, elsewhere, root, 999_999_999L)) {
            write(b, p, "답글 " + target, target).andExpect(status().isBadRequest())
                    .andExpect(r -> assertThat(r.getResponse().getContentAsString()).contains("REPLY_TARGET_UNAVAILABLE"));
        }
    }

    // ---------------------------------------------------------------- US3

    @Test
    void 최상위는_20개씩_답글은_처음_3개와_나머지를_이어_읽는다() throws Exception {
        Session a = signup(uniqueLogin("cmk"));
        long p = postOf(a, "PUBLIC");
        List<Long> roots = new ArrayList<>();
        for (int i = 0; i < 45; i++) {
            long id = jdbc.queryForObject("INSERT INTO comment (post_id, author_id, content, created_at, updated_at)"
                    + " VALUES (?, ?, ?, '2026-10-01T00:00:00Z', '2026-10-01T00:00:00Z') RETURNING id", Long.class, p, a.memberId(), "c" + i);
            roots.add(id); // 모두 같은 시각: 경계에서 번호로 나뉜다
        }
        List<Long> replies = new ArrayList<>();
        for (int i = 0; i < 8; i++) replies.add(reply(a, p, roots.get(0), "r" + i));

        JsonNode first = list(null, p, null);
        assertThat(ids(first.path("items"))).isEqualTo(roots.subList(0, 20));
        JsonNode r0 = first.path("items").get(0);
        assertThat(ids(r0.path("replies"))).isEqualTo(replies.subList(0, 3));
        assertThat(r0.path("replyCount").asInt()).isEqualTo(8);
        JsonNode second = list(null, p, "cursor=" + first.path("nextCursor").asString());
        JsonNode third = list(null, p, "cursor=" + second.path("nextCursor").asString());
        assertThat(ids(second.path("items"))).isEqualTo(roots.subList(20, 40));
        assertThat(ids(third.path("items"))).isEqualTo(roots.subList(40, 45));
        assertThat(third.path("nextCursor").isNull()).isTrue();

        JsonNode more = read(browser().perform(get("/api/comments/" + roots.get(0) + "/replies")
                .param("cursor", r0.path("repliesNextCursor").asString())).andExpect(status().isOk()).andReturn());
        assertThat(ids(more.path("items"))).isEqualTo(replies.subList(3, 8));
        // 다른 목록의 커서·고친 커서는 거부한다
        browser().perform(get("/api/posts/" + p + "/comments").param("cursor", r0.path("repliesNextCursor").asString()))
                .andExpect(status().isBadRequest());

        // 답글이 5개 이하면 접지 않고 모두 보인다
        List<Long> few = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            few.add(jdbc.queryForObject("INSERT INTO comment (post_id, author_id, parent_id, content, created_at, updated_at)"
                    + " VALUES (?, ?, ?, ?, now(), now()) RETURNING id", Long.class, p, a.memberId(), roots.get(1), "f" + i)); // 쓰기 한도를 피해 바로 넣는다
        }
        JsonNode r1 = list(null, p, null).path("items").get(1);
        assertThat(ids(r1.path("replies"))).isEqualTo(few);
        assertThat(r1.path("replyCount").asInt()).isEqualTo(5);
        assertThat(r1.path("repliesNextCursor").isNull()).isTrue();

        // 특정 댓글부터: 그 최상위부터 20개, 앞이 있으면 이전 커서, 6번째 답글이면 거기까지 펼친다
        JsonNode around = list(null, p, "around=" + replies.get(5));
        assertThat(around.path("items").get(0).path("id").asLong()).isEqualTo(roots.get(0));
        assertThat(ids(around.path("items").get(0).path("replies"))).isEqualTo(replies.subList(0, 6));
        JsonNode aroundLater = list(null, p, "around=" + roots.get(30));
        assertThat(aroundLater.path("items").get(0).path("id").asLong()).isEqualTo(roots.get(30));
        JsonNode prev = list(null, p, "before=" + aroundLater.path("prevCursor").asString());
        assertThat(ids(prev.path("items"))).isEqualTo(roots.subList(10, 30));
        assertThat(prev.path("prevCursor").isNull()).isFalse();
        // 다른 글의 댓글·없는 댓글이면 처음 페이지
        assertThat(ids(list(null, p, "around=999999999").path("items"))).isEqualTo(roots.subList(0, 20));
    }

    // ---------------------------------------------------------------- US4

    @Test
    void 수정은_본인만_내용과_수정_시각만_바꾼다() throws Exception {
        Session a = signup(uniqueLogin("cml")), b = signup(uniqueLogin("cmm"));
        long p = postOf(a, "PUBLIC");
        long id = comment(b, p, "처음");
        backdate(id, "2026-10-01T00:00:00Z");
        b.http().perform(asJson(patch("/api/comments/" + id), Map.of("content", " 처음 "))).andExpect(status().isOk())
                .andExpect(r -> assertThat(read(r).path("edited").asBoolean()).isFalse());
        JsonNode edited = read(b.http().perform(asJson(patch("/api/comments/" + id), Map.of("content", "고침")))
                .andExpect(status().isOk()).andReturn());
        assertThat(edited.path("content").asString()).isEqualTo("고침");
        assertThat(edited.path("edited").asBoolean()).isTrue();
        assertThat(edited.path("createdAt").asString()).startsWith("2026-10-01");

        // 글 작성자도 남의 댓글은 고치거나 지울 수 없다 (없는 댓글과 같은 404)
        a.http().perform(asJson(patch("/api/comments/" + id), Map.of("content", "남이 고침"))).andExpect(status().isNotFound());
        a.http().perform(delete("/api/comments/" + id).with(csrf())).andExpect(status().isNotFound());
        a.http().perform(delete("/api/comments/999999999").with(csrf())).andExpect(status().isNotFound());
        assertThat(jdbc.queryForObject("SELECT content FROM comment WHERE id = ?", String.class, id)).isEqualTo("고침");

        jdbc.update("UPDATE comment SET hidden_at = now(), hidden_reason = 'SPAM' WHERE id = ?", id);
        b.http().perform(asJson(patch("/api/comments/" + id), Map.of("content", "숨김 중"))).andExpect(status().isConflict())
                .andExpect(r -> assertThat(r.getResponse().getContentAsString()).contains("COMMENT_HIDDEN"));
        JsonNode mine = list(b, p, null).path("items").get(0);
        assertThat(mine.path("state").asString()).isEqualTo("HIDDEN");
        assertThat(mine.path("content").asString()).isEqualTo("고침");
        JsonNode others = list(a, p, null).path("items").get(0);
        assertThat(others.path("content").isNull()).isTrue();
        assertThat(others.path("author").isNull()).isTrue();
        assertThat(count(p)).isZero();
        b.http().perform(delete("/api/comments/" + id).with(csrf())).andExpect(status().isNoContent());
    }

    @Test
    void 가지의_시작_답글이_지워지면_그_아래_답글은_최상위에서_갈라진다() throws Exception {
        Session a = signup(uniqueLogin("cmx")), b = signup(uniqueLogin("cmy"));
        long p = postOf(a, "PUBLIC");
        long root = comment(a, p, "질문");
        long r1 = reply(b, p, root, "답");
        long r2 = reply(a, p, r1, "답의 답");

        b.http().perform(delete("/api/comments/" + r1).with(csrf())).andExpect(status().isNoContent());
        JsonNode replies = list(null, p, null).path("items").get(0).path("replies");
        assertThat(ids(replies)).containsExactly(r2);
        assertThat(replies.get(0).path("replyToCommentId").isNull()).isTrue();
        assertThat(replies.get(0).path("replyTo").path("nickname").asString()).isNotEmpty();
    }

    @Test
    void 답글이_있는_최상위는_자리로_남고_마지막_답글이_지워지면_자리도_사라진다() throws Exception {
        Session a = signup(uniqueLogin("cmn")), b = signup(uniqueLogin("cmo"));
        long p = postOf(a, "PUBLIC");
        long lonely = comment(a, p, "혼자");
        long root = comment(a, p, "답글 있음");
        long r1 = reply(b, p, root, "답1");
        long r2 = reply(b, p, root, "답2");
        assertThat(count(p)).isEqualTo(4);

        a.http().perform(delete("/api/comments/" + lonely).with(csrf())).andExpect(status().isNoContent());
        a.http().perform(delete("/api/comments/" + root).with(csrf())).andExpect(status().isNoContent());
        assertThat(count(p)).isEqualTo(2);
        JsonNode place = list(b, p, null).path("items").get(0);
        assertThat(place.path("state").asString()).isEqualTo("DELETED");
        assertThat(place.path("content").isNull()).isTrue();
        assertThat(place.path("author").isNull()).isTrue();
        assertThat(jdbc.queryForObject("SELECT content FROM comment WHERE id = ?", String.class, root)).isEmpty();
        a.http().perform(delete("/api/comments/" + root).with(csrf())).andExpect(status().isNotFound());

        b.http().perform(delete("/api/comments/" + r1).with(csrf())).andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM comment WHERE id = ?", Long.class, root)).isOne();
        b.http().perform(delete("/api/comments/" + r2).with(csrf())).andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM comment WHERE post_id = ?", Long.class, p)).isZero();
        assertThat(count(p)).isZero();
        assertThat(events.stream(CommentDeleted.class).filter(e -> e.postId() == p).count()).isEqualTo(4);
    }

    @Test
    void 삭제와_답글이_동시에_와도_댓글_수와_보이는_댓글이_어긋나지_않는다() throws Exception {
        Session a = signup(uniqueLogin("cmp"));
        List<Session> others = new ArrayList<>();
        for (int i = 0; i < 4; i++) others.add(signup(uniqueLogin("cmq" + i)));
        long p = postOf(a, "PUBLIC");
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            for (int round = 0; round < 5; round++) {
                long root = comment(a, p, "라운드 " + round);
                List<Callable<Integer>> jobs = new ArrayList<>();
                jobs.add(() -> a.http().perform(delete("/api/comments/" + root).with(csrf())).andReturn().getResponse().getStatus());
                for (Session o : others) jobs.add(() -> write(o, p, "답 " + root, root).andReturn().getResponse().getStatus());
                for (Future<Integer> f : pool.invokeAll(jobs)) assertThat(f.get()).isIn(201, 204, 400);
                // 답글이 남았으면 최상위는 자리, 없으면 행이 없다
                long replies = jdbc.queryForObject("SELECT count(*) FROM comment WHERE parent_id = ?", Long.class, root);
                long rootRows = jdbc.queryForObject("SELECT count(*) FROM comment WHERE id = ?", Long.class, root);
                assertThat(rootRows).isEqualTo(replies > 0 ? 1 : 0);
            }
        } finally {
            pool.shutdown();
        }
        assertThat(count(p)).isEqualTo(visible(p));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM comment c JOIN comment r ON r.id = c.parent_id"
                + " WHERE c.post_id = ? AND r.parent_id IS NOT NULL", Long.class, p)).isZero();
    }

    // ---------------------------------------------------------------- US5

    @Test
    void 볼_수_없는_글의_댓글은_없는_글과_같다() throws Exception {
        Session a = signup(uniqueLogin("cmr")), b = signup(uniqueLogin("cms"));
        long p = postOf(a, "PUBLIC");
        long id = comment(b, p, "공개일 때");
        a.http().perform(asJson(patch("/api/posts/" + p + "/visibility"), Map.of("visibility", "PRIVATE"))).andExpect(status().isOk());

        String missing = b.http().perform(get("/api/posts/999999999/comments")).andExpect(status().isNotFound()).andReturn()
                .getResponse().getContentAsString();
        String privatePost = b.http().perform(get("/api/posts/" + p + "/comments")).andExpect(status().isNotFound()).andReturn()
                .getResponse().getContentAsString();
        assertThat(privatePost).isEqualTo(missing);
        write(b, p, "", null).andExpect(status().isNotFound());
        b.http().perform(asJson(patch("/api/comments/" + id), Map.of("content", "몰래"))).andExpect(status().isNotFound());

        JsonNode own = read(a.http().perform(get("/api/posts/" + p + "/comments")).andExpect(status().isOk())
                .andExpect(r -> assertThat(r.getResponse().getHeader("Cache-Control")).contains("no-store")).andReturn());
        assertThat(own.path("items").size()).isOne();
        assertThat(own.path("canWrite").asBoolean()).isTrue();

        a.http().perform(asJson(patch("/api/posts/" + p + "/visibility"), Map.of("visibility", "PUBLIC"))).andExpect(status().isOk());
        assertThat(ids(list(b, p, null).path("items"))).containsExactly(id);

        // 휴지통 글·숨긴 글·임시글
        a.http().perform(delete("/api/posts/" + p).with(csrf())).andExpect(status().isOk());
        b.http().perform(get("/api/posts/" + p + "/comments")).andExpect(status().isNotFound());
        long hiddenPost = postOf(a, "PUBLIC");
        jdbc.update("UPDATE post SET hidden_at = now(), hidden_reason = 'SPAM' WHERE id = ?", hiddenPost);
        write(a, hiddenPost, "숨긴 글", null).andExpect(status().isNotFound());
        long draft = read(a.http().perform(asJson(post("/api/posts"), Map.of("title", "임시", "contentMd", "본문")))
                .andExpect(status().isCreated()).andReturn()).path("id").asLong();
        write(a, draft, "임시글", null).andExpect(status().isNotFound());
        a.http().perform(get("/api/posts/" + draft + "/comments")).andExpect(status().isNotFound());
    }

    @Test
    void 글_상세_첫_화면에_첫_댓글_페이지가_함께_온다() throws Exception {
        Session a = signup(uniqueLogin("cmt"));
        long p = postOf(a, "PUBLIC");
        comment(a, p, "<b>첫</b> 댓글");
        String html = browser().perform(get("/@" + a.handle() + "/posts/" + p)).andExpect(status().isOk()).andReturn()
                .getResponse().getContentAsString();
        assertThat(html).contains("\"comments\"").doesNotContain("<b>첫</b>");
    }
}
