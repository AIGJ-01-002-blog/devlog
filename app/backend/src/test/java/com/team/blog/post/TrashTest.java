package com.team.blog.post;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
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

import com.team.blog.post.application.TrashPurgeJob;
import com.team.blog.support.IntegrationTest;

/** spec 007 인수 시나리오: 삭제는 휴지통으로, 30일 뒤 또는 [영구 삭제]로 완전히 지워진다. */
class TrashTest extends IntegrationTest {
    @Autowired TrashPurgeJob purgeJob;

    long draft(Session s, String title, String content) throws Exception {
        return read(s.http().perform(asJson(post("/api/posts"), Map.of("title", title, "contentMd", content)))
                .andExpect(status().isCreated()).andReturn()).path("id").asLong();
    }

    long published(Session s, String title) throws Exception {
        long id = draft(s, title, "본문");
        s.http().perform(asJson(post("/api/posts/" + id + "/publish"), Map.of("title", title, "contentMd", "본문",
                "visibility", "PUBLIC", "baseVersion", 0, "tags", List.of()))
                .header("Idempotency-Key", UUID.randomUUID().toString())).andExpect(status().isOk());
        return id;
    }

    JsonNode trash(Session s, long id) throws Exception {
        return read(s.http().perform(delete("/api/posts/" + id).with(csrf())).andExpect(status().isOk()).andReturn());
    }

    JsonNode myPosts(Session s, String tab) throws Exception {
        return read(s.http().perform(get("/api/me/posts").param("tab", tab)).andExpect(status().isOk()).andReturn());
    }

    boolean exists(long id) {
        return jdbc.queryForObject("SELECT count(*) FROM post WHERE id = ?", Long.class, id) > 0;
    }

    @Test
    void 삭제한_글은_휴지통으로_가고_어디에서도_보이지_않는다() throws Exception {
        Session s = signup(uniqueLogin("trash"));
        long id = published(s, "지울 글");
        JsonNode r = trash(s, id);
        assertThat(r.path("result").asString()).isEqualTo("TRASHED");
        Instant deletedAt = Instant.parse(r.path("deletedAt").asString());
        assertThat(Instant.parse(r.path("purgeAt").asString())).isEqualTo(deletedAt.plus(30, ChronoUnit.DAYS));

        browser().perform(get("/api/posts/" + id)).andExpect(status().isNotFound());
        s.http().perform(get("/api/posts/" + id)).andExpect(status().isNotFound());
        s.http().perform(get("/api/posts/" + id + "/edit")).andExpect(status().isNotFound());

        JsonNode trashTab = myPosts(s, "TRASH");
        assertThat(trashTab.path("items").get(0).path("id").asLong()).isEqualTo(id);
        assertThat(trashTab.path("counts").path("trash").asLong()).isEqualTo(1);
        assertThat(trashTab.path("counts").path("published").asLong()).isZero();
    }

    @Test
    void 다시_삭제해도_결과가_같다() throws Exception {
        Session s = signup(uniqueLogin("trash2"));
        long id = published(s, "두 번");
        JsonNode first = trash(s, id);
        JsonNode second = trash(s, id);
        assertThat(second.path("result").asString()).isEqualTo("ALREADY_TRASHED");
        assertThat(second.path("deletedAt").asString()).isEqualTo(first.path("deletedAt").asString());
    }

    @Test
    void 동시에_삭제해도_한_번만_휴지통에_들어간다() throws Exception {
        Session s = signup(uniqueLogin("trash3"));
        long id = published(s, "동시");
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            Callable<String> call = () -> trash(s, id).path("result").asString();
            List<Future<String>> results = pool.invokeAll(List.of(call, call, call, call));
            long trashed = 0;
            for (Future<String> f : results) if (f.get().equals("TRASHED")) trashed++;
            assertThat(trashed).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void 제목과_본문이_빈_임시글은_바로_지워진다() throws Exception {
        Session s = signup(uniqueLogin("trashempty"));
        long id = draft(s, "  ", "");
        assertThat(trash(s, id).path("result").asString()).isEqualTo("DELETED_EMPTY");
        assertThat(exists(id)).isFalse();
        assertThat(myPosts(s, "TRASH").path("counts").path("trash").asLong()).isZero();
    }

    @Test
    void 삭제_직전의_자동_저장분이_남는다() throws Exception {
        Session s = signup(uniqueLogin("trashflush"));
        long id = draft(s, "처음", "");
        s.http().perform(asJson(put("/api/posts/" + id + "/autosave"),
                Map.of("title", "처음", "contentMd", "마지막 문장", "baseVersion", 0))).andExpect(status().isOk());
        assertThat(trash(s, id).path("result").asString()).isEqualTo("TRASHED");
        assertThat(jdbc.queryForObject("SELECT content_md FROM post WHERE id = ?", String.class, id)).isEqualTo("마지막 문장");
    }

    @Test
    void 남의_글과_없는_글은_404다() throws Exception {
        Session owner = signup(uniqueLogin("trashowner"));
        Session other = signup(uniqueLogin("trashother"));
        long id = published(owner, "내 글");
        other.http().perform(delete("/api/posts/" + id).with(csrf())).andExpect(status().isNotFound());
        other.http().perform(post("/api/posts/" + id + "/restore").with(csrf())).andExpect(status().isNotFound());
        other.http().perform(delete("/api/posts/" + id + "/permanent").with(csrf())).andExpect(status().isNotFound());
        owner.http().perform(delete("/api/posts/987654321").with(csrf())).andExpect(status().isNotFound());
        browser().perform(delete("/api/posts/" + id).with(csrf())).andExpect(status().is4xxClientError());
        assertThat(jdbc.queryForObject("SELECT deleted_at IS NULL FROM post WHERE id = ?", Boolean.class, id)).isTrue();
    }

    @Test
    void 복구하면_원래_상태와_목록_자리로_돌아온다() throws Exception {
        Session s = signup(uniqueLogin("restore"));
        long older = published(s, "먼저 쓴 글");
        long newer = published(s, "나중 쓴 글");
        jdbc.update("UPDATE post SET updated_at = now() - interval '1 day' WHERE id = ?", older);
        Timestamp before = jdbc.queryForObject("SELECT updated_at FROM post WHERE id = ?", Timestamp.class, older);

        trash(s, older);
        JsonNode r = read(s.http().perform(post("/api/posts/" + older + "/restore").with(csrf())).andExpect(status().isOk()).andReturn());
        assertThat(r.path("status").asString()).isEqualTo("PUBLISHED");
        assertThat(r.path("visibility").asString()).isEqualTo("PUBLIC");

        assertThat(jdbc.queryForObject("SELECT updated_at FROM post WHERE id = ?", Timestamp.class, older)).isEqualTo(before);
        JsonNode list = myPosts(s, "PUBLISHED");
        assertThat(list.path("items").get(0).path("id").asLong()).isEqualTo(newer);
        assertThat(list.path("items").get(1).path("id").asLong()).isEqualTo(older);
        browser().perform(get("/api/posts/" + older)).andExpect(status().isOk());

        // 휴지통에 없는 글은 복구할 것이 없다
        s.http().perform(post("/api/posts/" + older + "/restore").with(csrf())).andExpect(status().isNotFound());
    }

    @Test
    void 영구_삭제는_휴지통의_글만_되고_딸린_기록도_함께_지운다() throws Exception {
        Session s = signup(uniqueLogin("purge"));
        Session reader = signup(uniqueLogin("purgereader"));
        long id = published(s, "완전히 지울 글");
        s.http().perform(delete("/api/posts/" + id + "/permanent").with(csrf())).andExpect(status().isNotFound());

        long commentId = jdbc.queryForObject("INSERT INTO comment (post_id, author_id, content) VALUES (?, ?, '댓글') RETURNING id",
                Long.class, id, reader.memberId());
        jdbc.update("INSERT INTO post_like (post_id, member_id) VALUES (?, ?)", id, reader.memberId());
        long postCase = jdbc.queryForObject("""
                INSERT INTO report_case (target_type, post_id, target_author_id) VALUES ('POST', ?, ?) RETURNING id
                """, Long.class, id, s.memberId());
        long commentCase = jdbc.queryForObject("""
                INSERT INTO report_case (target_type, comment_id, target_author_id) VALUES ('COMMENT', ?, ?) RETURNING id
                """, Long.class, commentId, reader.memberId());

        trash(s, id);
        s.http().perform(delete("/api/posts/" + id + "/permanent").with(csrf())).andExpect(status().isNoContent());

        assertThat(exists(id)).isFalse();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM comment WHERE post_id = ?", Long.class, id)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM post_like WHERE post_id = ?", Long.class, id)).isZero();
        for (long c : List.of(postCase, commentCase)) {
            Map<String, Object> row = jdbc.queryForMap("SELECT status, handled_by, handled_at FROM report_case WHERE id = ?", c);
            assertThat(row).containsEntry("status", "CLOSED_NO_TARGET");
            assertThat(row.get("handled_by")).isNull();
            assertThat(row.get("handled_at")).isNotNull();
        }
        s.http().perform(delete("/api/posts/" + id + "/permanent").with(csrf())).andExpect(status().isNotFound());
    }

    @Test
    void 영구_삭제한_글이_쓰던_사진은_다른_글이_쓰지_않으면_정리_대상이_된다() throws Exception {
        Session s = signup(uniqueLogin("purgeimg"));
        long id = published(s, "사진 글");
        long other = published(s, "다른 글");
        long onlyHere = insertImage(s, "only");
        long shared = insertImage(s, "shared");
        jdbc.update("INSERT INTO post_image (post_id, resource_id, position) VALUES (?, ?, 0), (?, ?, 1)", id, onlyHere, id, shared);
        jdbc.update("INSERT INTO post_image (post_id, resource_id, position) VALUES (?, ?, 0)", other, shared);

        trash(s, id);
        s.http().perform(delete("/api/posts/" + id + "/permanent").with(csrf())).andExpect(status().isNoContent());

        assertThat(jdbc.queryForObject("SELECT detached_at IS NOT NULL FROM resource WHERE id = ?", Boolean.class, onlyHere)).isTrue();
        assertThat(jdbc.queryForObject("SELECT detached_at IS NULL FROM resource WHERE id = ?", Boolean.class, shared)).isTrue();
    }

    long insertImage(Session s, String name) {
        long id = jdbc.queryForObject("""
                INSERT INTO resource (uploader_id, storage_key, content_type, size_bytes, kind)
                VALUES (?, ?, 'image/png', 10, 'IMAGE') RETURNING id
                """, Long.class, s.memberId(), "posts/test/" + name + "-" + UUID.randomUUID() + ".png");
        jdbc.update("INSERT INTO resource_image (resource_id, kind) VALUES (?, 'IMAGE')", id);
        return id;
    }

    @Test
    void 휴지통에서_30일_지난_글만_정리_작업이_지운다() throws Exception {
        Session s = signup(uniqueLogin("purgejob"));
        long expired = published(s, "오래된 글");
        long recent = published(s, "최근 글");
        trash(s, expired);
        trash(s, recent);
        jdbc.update("UPDATE post SET deleted_at = now() - interval '31 days' WHERE id = ?", expired);
        jdbc.update("UPDATE post SET deleted_at = now() - interval '29 days' WHERE id = ?", recent);

        assertThat(purgeJob.run()).isPositive();
        assertThat(exists(expired)).isFalse();
        assertThat(exists(recent)).isTrue();
    }
}
