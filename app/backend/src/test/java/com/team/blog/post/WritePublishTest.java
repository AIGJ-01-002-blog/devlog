package com.team.blog.post;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.ResultActions;

import tools.jackson.databind.JsonNode;

import com.team.blog.account.domain.Visibility;
import com.team.blog.post.application.AutosaveFlushJob;
import com.team.blog.post.application.EmptyDraftCleanupJob;
import com.team.blog.post.application.PostCommandService;
import com.team.blog.post.application.PublishCommand;
import com.team.blog.shared.error.ApiException;
import com.team.blog.shared.markdown.ImageUrls;
import com.team.blog.support.HtmlSafety;
import com.team.blog.support.IntegrationTest;

/** spec 002 인수 시나리오, docs/05 §10, docs/06 §8(PUBLIC/PRIVATE), docs/04 §2. */
class WritePublishTest extends IntegrationTest {
    @Autowired PostCommandService commands;
    @Autowired AutosaveFlushJob flushJob;
    @Autowired EmptyDraftCleanupJob cleanupJob;
    @Autowired ImageUrls imageUrls;

    // ---------------------------------------------------------------- helpers

    long newPost(Session s) throws Exception {
        return read(s.http().perform(asJson(post("/api/posts"), Map.of())).andExpect(status().isCreated()).andReturn())
                .path("id").asLong();
    }

    /** 제목만 있는 임시글 (빈 임시글은 [새 글]이 다시 쓰므로 여러 개가 필요할 때). */
    long newPost(Session s, String title) throws Exception {
        return read(s.http().perform(asJson(post("/api/posts"), Map.of("title", title, "contentMd", ""))).andExpect(status().isCreated()).andReturn())
                .path("id").asLong();
    }

    /** 오래된 빈 임시글 (정리 작업 확인용). */
    long insertEmptyDraft(Session s) {
        return jdbc.queryForObject("INSERT INTO post (author_id, title, content_md, status, visibility) VALUES (?, '', '', 'DRAFT', 'PUBLIC') RETURNING id",
                Long.class, s.memberId());
    }

    ResultActions autosave(Session s, long id, String title, String content, long base) throws Exception {
        return s.http().perform(asJson(put("/api/posts/" + id + "/autosave"),
                Map.of("title", title, "contentMd", content, "baseVersion", base)));
    }

    ResultActions save(Session s, long id, String title, String content, long base) throws Exception {
        return s.http().perform(asJson(put("/api/posts/" + id), Map.of("title", title, "contentMd", content, "baseVersion", base)));
    }

    ResultActions publish(Session s, long id, String title, String content, String visibility, long base, String key) throws Exception {
        var b = asJson(post("/api/posts/" + id + "/publish"),
                Map.of("title", title, "contentMd", content, "visibility", visibility, "baseVersion", base, "tags", List.of()));
        if (key != null) b.header("Idempotency-Key", key);
        return s.http().perform(b);
    }

    JsonNode edit(Session s, long id) throws Exception {
        return read(s.http().perform(get("/api/posts/" + id + "/edit")).andExpect(status().isOk()).andReturn());
    }

    long version(ResultActions r) throws Exception {
        return read(r.andReturn()).path("version").asLong();
    }

    Map<String, Object> row(long id) {
        return jdbc.queryForMap("SELECT * FROM post WHERE id = ?", id);
    }

    /** 독자가 받는 발행본 (V3: HTML·요약은 저장하지 않고 읽을 때 만든다). */
    JsonNode detail(long id) throws Exception {
        return read(browser().perform(get("/api/posts/" + id)).andExpect(status().isOk()).andReturn());
    }

    // ---------------------------------------------------------------- US1 쓰고 발행

    @Test
    void 새_글은_기본_공개_범위로_시작하는_임시글이다() throws Exception {
        Session s = signup(uniqueLogin("newpost"));
        jdbc.update("UPDATE member SET default_visibility = 'PRIVATE' WHERE id = ?", s.memberId());
        long id = newPost(s);
        assertThat(row(id)).containsEntry("status", "DRAFT").containsEntry("visibility", "PRIVATE").containsEntry("edit_version", 0L);
        JsonNode e = edit(s, id);
        assertThat(e.path("version").asLong()).isZero();
        assertThat(e.path("url").isNull()).isTrue();
    }

    @Test
    void 공개로_발행하면_고유_주소가_생기고_시각이_기록된다() throws Exception {
        Session s = signup(uniqueLogin("pub"));
        long id = newPost(s);
        JsonNode r = read(publish(s, id, "  JPA N+1 정리  ", "## 문제\n지연 로딩", "PUBLIC", 0, UUID.randomUUID().toString())
                .andExpect(status().isOk()).andReturn());
        assertThat(r.path("url").asString()).isEqualTo("/@" + s.handle() + "/posts/" + id);
        assertThat(r.path("version").asLong()).isEqualTo(1);
        Map<String, Object> p = row(id);
        assertThat(p).containsEntry("status", "PUBLISHED").containsEntry("title", "JPA N+1 정리");
        assertThat(p.get("published_at")).isNotNull();
        assertThat(p).containsEntry("first_public_at", p.get("published_at"));
        assertThat(p.get("edited_at")).isNull();
        assertThat(p).doesNotContainKeys("content_html", "excerpt", "thumbnail_url", "view_count");
        JsonNode d = detail(id);
        assertThat(d.path("contentHtml").asString()).contains("<h3").contains("지연 로딩");
        assertThat(d.path("excerpt").asString()).contains("지연 로딩");
    }

    @Test
    void 제목과_본문이_비면_모든_이유를_한_번에_알려주고_임시글로_남는다() throws Exception {
        Session s = signup(uniqueLogin("empty"));
        long id = newPost(s);
        publish(s, id, "  ", "   ", "PUBLIC", 0, null)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].code").value("TITLE_REQUIRED"))
                .andExpect(jsonPath("$.errors[1].code").value("CONTENT_REQUIRED"));
        publish(s, id, "x".repeat(101), "![a](local:abc)", "PUBLIC", 0, null)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].code").value("TITLE_TOO_LONG"))
                .andExpect(jsonPath("$.errors[1].code").value("PENDING_IMAGES"));
        publish(s, id, "제목", "본문", "GROUP", 0, null)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].code").value("INVALID_VISIBILITY"));
        assertThat(row(id)).containsEntry("status", "DRAFT");
    }

    @Test
    void 본문이_100000자를_넘으면_저장되지_않는다() throws Exception {
        Session s = signup(uniqueLogin("long"));
        long id = newPost(s);
        autosave(s, id, "t", "가".repeat(100_001), 0)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].code").value("CONTENT_TOO_LONG"));
        save(s, id, "t", "가".repeat(100_000), 0).andExpect(status().isOk());
    }

    @Test
    void 지나치게_깊은_본문은_발행되지_않는다() throws Exception {
        Session s = signup(uniqueLogin("deep"));
        long id = newPost(s);
        publish(s, id, "깊음", "> ".repeat(25) + "x", "PUBLIC", 0, null)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CONTENT_TOO_COMPLEX"));
        assertThat(row(id)).containsEntry("status", "DRAFT");
    }

    @Test
    void 같은_발행_요청을_동시에_20번_보내도_한_번만_발행된다() throws Exception {
        Session s = signup(uniqueLogin("idem"));
        long id = newPost(s);
        String key = UUID.randomUUID().toString();
        PublishCommand cmd = new PublishCommand(id, s.memberId(), "동시", "본문", null, Visibility.PUBLIC, List.of(), 0, null, false);
        ExecutorService pool = Executors.newFixedThreadPool(20);
        CountDownLatch start = new CountDownLatch(1);
        Map<String, Integer> outcomes = new ConcurrentHashMap<>();
        List<Future<?>> fs = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            fs.add(pool.submit(() -> {
                start.await();
                try {
                    commands.publish(cmd, s.handle(), key);
                    outcomes.merge("ok", 1, Integer::sum);
                } catch (ApiException e) {
                    outcomes.merge(e.code(), 1, Integer::sum);
                }
                return null;
            }));
        }
        start.countDown();
        for (Future<?> f : fs) f.get();
        pool.shutdown();
        assertThat(row(id)).containsEntry("edit_version", 1L);
        assertThat(outcomes.getOrDefault("ok", 0)).isPositive();
        assertThat(outcomes.keySet()).allMatch(k -> k.equals("ok") || k.equals("IN_PROGRESS"));
        // 끝난 뒤 같은 키로 다시 보내면 저장된 응답을 그대로 돌려준다 (다시 발행하지 않음)
        JsonNode again = read(publish(s, id, "동시", "본문", "PUBLIC", 0, key).andExpect(status().isOk()).andReturn());
        assertThat(again.path("version").asLong()).isEqualTo(1);
        assertThat(row(id)).containsEntry("edit_version", 1L);
        // 같은 키로 다른 내용을 보내면 422
        publish(s, id, "다른 제목", "본문", "PUBLIC", 0, key)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
    }

    @Test
    void 비공개로_발행한_글은_남에게_없는_글과_같다() throws Exception {
        Session s = signup(uniqueLogin("priv"));
        Session other = signup(uniqueLogin("privo"));
        long id = newPost(s);
        publish(s, id, "비밀", "본문", "PRIVATE", 0, null).andExpect(status().isOk());
        assertThat(row(id).get("first_public_at")).isNull();
        other.http().perform(get("/api/posts/" + id + "/edit")).andExpect(status().isNotFound());
    }

    @Test
    void 본문과_제목의_공격_문자열은_발행해도_실행되지_않는다() throws Exception {
        Session s = signup(uniqueLogin("xss"));
        String cdn = imageUrls.publicBaseUrl() + "/";
        String[] attacks = HtmlSafety.attacks(cdn);
        long id = newPost(s);
        long version = 0;
        for (String attack : attacks) {
            JsonNode r = read(publish(s, id, attack.length() > 100 ? attack.substring(0, 100) : attack, attack, "PUBLIC", version, null)
                    .andExpect(status().isOk()).andReturn());
            version = r.path("version").asLong();
            String html = detail(id).path("contentHtml").asString();
            assertThat(HtmlSafety.dangerous(html)).as(attack + " → " + html).isFalse();
        }
    }

    @Test
    void 제목의_보이지_않는_글자와_방향_뒤집기_문자는_지워진다() throws Exception {
        Session s = signup(uniqueLogin("rlo"));
        long id = newPost(s);
        publish(s, id, "invoice\u202Efdp.exe 관\u200B리\u200D자", "본문", "PUBLIC", 0, null).andExpect(status().isOk());
        assertThat(row(id)).containsEntry("title", "invoicefdp.exe 관리자");
    }

    // ---------------------------------------------------------------- US2 저장·이어 쓰기·충돌

    @Test
    void 자동_저장은_버전을_올리고_다시_열면_마지막_내용이_보이며_1분_반영_후_DB에_남는다() throws Exception {
        Session s = signup(uniqueLogin("auto"));
        long id = newPost(s);
        long v1 = version(autosave(s, id, "제목1", "본문1", 0).andExpect(status().isOk()));
        long v2 = version(autosave(s, id, "제목2", "본문2", v1).andExpect(status().isOk()));
        assertThat(v2).isEqualTo(2);
        assertThat(row(id)).containsEntry("title", "").containsEntry("edit_version", 0L); // 아직 Redis에만 있음
        JsonNode e = edit(s, id);
        assertThat(e.path("title").asString()).isEqualTo("제목2");
        assertThat(e.path("version").asLong()).isEqualTo(2);
        flushJob.flushAll();
        assertThat(row(id)).containsEntry("title", "제목2").containsEntry("content_md", "본문2").containsEntry("edit_version", 2L);
        // Redis 키가 사라져도(TTL) DB에서 이어 쓸 수 있다
        redis.delete("autosave:post:" + id);
        assertThat(edit(s, id).path("contentMd").asString()).isEqualTo("본문2");
    }

    @Test
    void 두_탭에서_고치면_나중_저장은_409와_서버_내용을_받는다() throws Exception {
        Session s = signup(uniqueLogin("conflict"));
        long id = newPost(s);
        autosave(s, id, "탭A", "A 내용", 0).andExpect(status().isOk());
        autosave(s, id, "탭B", "B 내용", 0)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VERSION_CONFLICT"))
                .andExpect(jsonPath("$.details.server.title").value("탭A"))
                .andExpect(jsonPath("$.details.server.contentMd").value("A 내용"))
                .andExpect(jsonPath("$.details.server.version").value(1));
        // 수동 저장·발행도 같은 기준으로 막힌다
        save(s, id, "탭B", "B 내용", 0).andExpect(status().isConflict());
        publish(s, id, "탭B", "B 내용", "PUBLIC", 0, null).andExpect(status().isConflict());
        // 사용자가 [편집 중인 내용으로 저장]을 고르면 서버 버전을 기준으로 덮어쓴다
        save(s, id, "탭B", "B 내용", 1).andExpect(status().isOk());
        assertThat(row(id)).containsEntry("title", "탭B").containsEntry("edit_version", 2L);
        // 수동 저장으로 DB 버전이 오른 뒤 예전 버전의 자동 저장은 통과하지 않는다
        autosave(s, id, "탭A", "늦은 저장", 1).andExpect(status().isConflict());
    }

    @Test
    void 남의_글은_어떤_쓰기_요청도_404이고_바뀌지_않는다() throws Exception {
        Session owner = signup(uniqueLogin("owner"));
        Session other = signup(uniqueLogin("intruder"));
        long id = newPost(owner);
        publish(owner, id, "원래", "원래 본문", "PUBLIC", 0, null).andExpect(status().isOk());
        Map<String, Object> before = row(id);
        autosave(other, id, "x", "x", 1).andExpect(status().isNotFound());
        save(other, id, "x", "x", 1).andExpect(status().isNotFound());
        publish(other, id, "x", "x", "PUBLIC", 1, null).andExpect(status().isNotFound());
        other.http().perform(asJson(patch("/api/posts/" + id + "/visibility"), Map.of("visibility", "PRIVATE")))
                .andExpect(status().isNotFound());
        other.http().perform(delete("/api/posts/" + id + "/draft").with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf()))
                .andExpect(status().isNotFound());
        assertThat(row(id)).isEqualTo(before);
        assertThat(redis.hasKey("autosave:post:" + id)).isFalse();
    }

    @Test
    void 비회원은_글을_만들_수_없다() throws Exception {
        browser().perform(asJson(post("/api/posts"), Map.of())).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("LOGIN_REQUIRED"));
    }

    @Test
    void 새_글을_여러_번_열어도_아무것도_안_쓴_임시글은_하나다() throws Exception {
        Session s = signup(uniqueLogin("reuse"));
        long first = newPost(s);
        assertThat(newPost(s)).isEqualTo(first);
        // 자동 저장 중인 글은 빈 글이 아니다
        autosave(s, first, "", "쓰는 중", 0).andExpect(status().isOk());
        long second = newPost(s);
        assertThat(second).isNotEqualTo(first);
        // 다른 사람의 빈 임시글은 쓰지 않는다
        Session other = signup(uniqueLogin("reuse2"));
        assertThat(newPost(other)).isNotEqualTo(second);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM post WHERE author_id = ?", Integer.class, s.memberId())).isEqualTo(2);
    }

    @Test
    void 비어_있고_24시간_지난_임시글만_정리된다() throws Exception {
        Session s = signup(uniqueLogin("cleanup"));
        long empty = insertEmptyDraft(s);
        long recent = insertEmptyDraft(s);
        long written = insertEmptyDraft(s);
        long autosaving = insertEmptyDraft(s);
        save(s, written, "", "내용 있음", 0).andExpect(status().isOk());
        autosave(s, autosaving, "", "자동 저장만 있음", 0).andExpect(status().isOk());
        Timestamp old = Timestamp.from(Instant.now().minus(25, ChronoUnit.HOURS));
        jdbc.update("UPDATE post SET created_at = ?, updated_at = ? WHERE id IN (?, ?, ?)", old, old, empty, written, autosaving);
        cleanupJob.cleanup();
        assertThat(jdbc.queryForList("SELECT id FROM post WHERE author_id = ?", Long.class, s.memberId()))
                .containsExactlyInAnyOrder(recent, written, autosaving);
    }

    // ---------------------------------------------------------------- US3 발행 글 고치기

    @Test
    void 발행_글을_고치는_동안_독자는_발행본을_보고_다시_발행하면_주소와_최초_공개일이_그대로다() throws Exception {
        Session s = signup(uniqueLogin("repub"));
        long id = newPost(s);
        publish(s, id, "v1 제목", "v1 본문", "PUBLIC", 0, null).andExpect(status().isOk());
        Map<String, Object> first = row(id);
        jdbc.update("INSERT INTO post_view (post_id) SELECT ?::bigint FROM generate_series(1, 120)", id);

        long v = version(save(s, id, "v2 제목", "v2 본문", 1).andExpect(status().isOk()));
        assertThat(row(id)).containsEntry("title", "v1 제목").containsEntry("content_md", "v1 본문");
        assertThat(jdbc.queryForMap("SELECT * FROM post_draft WHERE post_id = ?", id)).containsEntry("title", "v2 제목");
        JsonNode e = edit(s, id);
        assertThat(e.path("editing").asBoolean()).isTrue();
        assertThat(e.path("title").asString()).isEqualTo("v2 제목");
        JsonNode list = read(s.http().perform(get("/api/me/posts?tab=published")).andExpect(status().isOk()).andReturn());
        assertThat(list.path("items").get(0).path("editing").asBoolean()).isTrue();

        // 자동 저장 반영도 작업본으로 간다
        long v3 = version(autosave(s, id, "v3 제목", "v3 본문", v).andExpect(status().isOk()));
        flushJob.flushAll();
        assertThat(row(id)).containsEntry("title", "v1 제목");
        assertThat(jdbc.queryForObject("SELECT title FROM post_draft WHERE post_id = ?", String.class, id)).isEqualTo("v3 제목");

        Thread.sleep(5);
        JsonNode r = read(publish(s, id, "v3 제목", "v3 본문", "PUBLIC", v3, UUID.randomUUID().toString())
                .andExpect(status().isOk()).andReturn());
        assertThat(r.path("url").asString()).isEqualTo("/@" + s.handle() + "/posts/" + id);
        Map<String, Object> after = row(id);
        assertThat(after).containsEntry("title", "v3 제목");
        assertThat(detail(id).path("viewCount").asLong()).isEqualTo(120);
        assertThat(detail(id).path("contentHtml").asString()).contains("v3 본문").doesNotContain("v1 본문");
        assertThat(after).containsEntry("published_at", first.get("published_at"));
        assertThat(after).containsEntry("first_public_at", first.get("first_public_at"));
        assertThat(after.get("edited_at")).isNotNull();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM post_draft WHERE post_id = ?", Integer.class, id)).isZero();
        assertThat(redis.hasKey("autosave:post:" + id)).isFalse();
        assertThat(edit(s, id).path("editing").asBoolean()).isFalse();
    }

    @Test
    void 변경_취소는_작업본만_지우고_예전_탭의_저장을_막는다() throws Exception {
        Session s = signup(uniqueLogin("discard"));
        long id = newPost(s);
        publish(s, id, "발행본", "발행 본문", "PUBLIC", 0, null).andExpect(status().isOk());
        long v = version(autosave(s, id, "고치는 중", "작업", 1).andExpect(status().isOk()));
        flushJob.flushAll();
        long after = version(s.http().perform(delete("/api/posts/" + id + "/draft")
                .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf())).andExpect(status().isOk()));
        assertThat(after).isGreaterThan(v);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM post_draft WHERE post_id = ?", Integer.class, id)).isZero();
        assertThat(row(id)).containsEntry("title", "발행본");
        JsonNode e = edit(s, id);
        assertThat(e.path("title").asString()).isEqualTo("발행본");
        assertThat(e.path("editing").asBoolean()).isFalse();
        autosave(s, id, "예전 탭", "예전", v).andExpect(status().isConflict());
        // 임시글에는 변경 취소가 없다
        long draft = newPost(s);
        s.http().perform(delete("/api/posts/" + draft + "/draft")
                .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf()))
                .andExpect(status().isBadRequest());
    }

    // ---------------------------------------------------------------- US4 공개 범위

    @Test
    void 공개_범위를_껐다_켜도_최초_공개_시각은_그대로이고_수정됨이_생기지_않는다() throws Exception {
        Session s = signup(uniqueLogin("vis"));
        long id = newPost(s);
        publish(s, id, "글", "본문", "PUBLIC", 0, null).andExpect(status().isOk());
        Object firstPublic = row(id).get("first_public_at");
        s.http().perform(asJson(patch("/api/posts/" + id + "/visibility"), Map.of("visibility", "PRIVATE")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.visibility").value("PRIVATE"));
        s.http().perform(asJson(patch("/api/posts/" + id + "/visibility"), Map.of("visibility", "PUBLIC")))
                .andExpect(status().isOk());
        assertThat(row(id)).containsEntry("first_public_at", firstPublic);
        assertThat(row(id).get("edited_at")).isNull();
        s.http().perform(asJson(patch("/api/posts/" + id + "/visibility"), Map.of("visibility", "GROUP")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void 비공개로_발행한_글을_나중에_공개하면_그때가_최초_공개_시각이다() throws Exception {
        Session s = signup(uniqueLogin("late"));
        long id = newPost(s);
        publish(s, id, "글", "본문", "PRIVATE", 0, null).andExpect(status().isOk());
        Thread.sleep(5);
        JsonNode r = read(s.http().perform(asJson(patch("/api/posts/" + id + "/visibility"), Map.of("visibility", "PUBLIC")))
                .andExpect(status().isOk()).andReturn());
        Map<String, Object> p = row(id);
        assertThat(p.get("first_public_at")).isNotNull();
        assertThat(((Timestamp) p.get("first_public_at")).after((Timestamp) p.get("published_at"))).isTrue();
        assertThat(r.path("firstPublicAt").isNull()).isFalse();
    }

    // ---------------------------------------------------------------- 미리보기·내 글 관리

    @Test
    void 미리보기는_발행_결과와_같다() throws Exception {
        Session s = signup(uniqueLogin("preview"));
        String md = "# 제목\n\n- [x] 완료\n\n```java\nint a = 1;\n```\n\n[외부](https://spring.io) <b>x</b>";
        JsonNode p = read(s.http().perform(asJson(post("/api/markdown/preview"), Map.of("contentMd", md)))
                .andExpect(status().isOk()).andReturn());
        long id = newPost(s);
        publish(s, id, "제목", md, "PUBLIC", 0, null).andExpect(status().isOk());
        assertThat(p.path("html").asString()).isEqualTo(detail(id).path("contentHtml").asString());
        browser().perform(asJson(post("/api/markdown/preview"), Map.of("contentMd", md))).andExpect(status().isUnauthorized());
    }

    @Test
    void 내_글_관리는_탭별로_20개씩_넘기고_첫_요청에만_개수를_준다() throws Exception {
        Session s = signup(uniqueLogin("manage"));
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < 23; i++) ids.add(newPost(s, "임시 " + i));
        long pub = ids.get(0);
        publish(s, pub, "발행", "본문", "PRIVATE", 0, null).andExpect(status().isOk());
        JsonNode first = read(s.http().perform(get("/api/me/posts")).andExpect(status().isOk()).andReturn());
        assertThat(first.path("items")).hasSize(20);
        assertThat(first.path("counts").path("drafts").asLong()).isEqualTo(22);
        assertThat(first.path("counts").path("published").asLong()).isEqualTo(1);
        assertThat(first.path("items").get(0).path("visibility").isNull()).isTrue();
        String cursor = first.path("nextCursor").asString();
        JsonNode second = read(s.http().perform(get("/api/me/posts").param("cursor", cursor)).andExpect(status().isOk()).andReturn());
        assertThat(second.path("items")).hasSize(2);
        assertThat(second.path("counts").isNull()).isTrue();
        assertThat(second.path("nextCursor").isNull()).isTrue();
        List<Long> seen = new ArrayList<>();
        first.path("items").forEach(n -> seen.add(n.path("id").asLong()));
        second.path("items").forEach(n -> seen.add(n.path("id").asLong()));
        assertThat(seen).doesNotHaveDuplicates().hasSize(22);

        JsonNode published = read(s.http().perform(get("/api/me/posts?tab=published&visibility=private")).andExpect(status().isOk()).andReturn());
        assertThat(published.path("items").get(0).path("id").asLong()).isEqualTo(pub);
        assertThat(published.path("items").get(0).path("visibility").asString()).isEqualTo("PRIVATE");
        assertThat(read(s.http().perform(get("/api/me/posts?tab=published&visibility=public")).andReturn()).path("items")).isEmpty();
        // 다른 목록의 커서는 받지 않는다
        s.http().perform(get("/api/me/posts?tab=published").param("cursor", cursor)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_CURSOR"));
        // 남의 목록은 볼 수 없다 (작성자 고정)
        Session other = signup(uniqueLogin("manageo"));
        assertThat(read(other.http().perform(get("/api/me/posts")).andReturn()).path("counts").path("drafts").asLong()).isZero();
    }
}
