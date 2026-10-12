package com.team.blog.discovery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

import tools.jackson.databind.JsonNode;

import com.team.blog.shared.markdown.ImageUrls;
import com.team.blog.shared.markdown.RenderedHtmlCache;
import com.team.blog.support.IntegrationTest;

/** V3 정규화: 파생 값을 저장하지 않고 계산해도 화면 값이 같고, DB가 구조 규칙을 강제한다. */
class NormalizedSchemaTest extends IntegrationTest {
    @Autowired ImageUrls imageUrls;

    long publish(Session s, String title, String content) throws Exception {
        return publish(s, title, content, List.of());
    }

    long publish(Session s, String title, String content, List<String> tags) throws Exception {
        long id = read(s.http().perform(asJson(post("/api/posts"), Map.of())).andExpect(status().isCreated()).andReturn()).path("id").asLong();
        s.http().perform(asJson(post("/api/posts/" + id + "/publish"),
                Map.of("title", title, "contentMd", content, "visibility", "PUBLIC", "baseVersion", 0, "tags", tags)))
                .andExpect(status().isOk());
        return id;
    }

    String imageKey(long uploaderId, String kind) {
        String key = "images/2026/10/" + UUID.randomUUID() + ".webp";
        long rid = jdbc.queryForObject("""
                INSERT INTO resource (uploader_id, storage_key, content_type, size_bytes, kind)
                VALUES (?, ?, ?, 1000, ?) RETURNING id""", Long.class, uploaderId, key,
                kind.equals("IMAGE") ? "image/webp" : "application/pdf", kind);
        if (kind.equals("IMAGE")) {
            jdbc.update("INSERT INTO resource_image (resource_id, width, height, thumb_storage_key) VALUES (?, 800, 600, ?)",
                    rid, key.replace(".webp", "_thumb.webp"));
        } else {
            jdbc.update("INSERT INTO resource_file (resource_id, original_name) VALUES (?, 'a.pdf')", rid);
        }
        return key;
    }

    long resourceId(String key) {
        return jdbc.queryForObject("SELECT id FROM resource WHERE storage_key = ?", Long.class, key);
    }

    @Test
    void 발행하면_본문_사진이_순서대로_연결되고_첫_사진이_목록_대표_사진이다() throws Exception {
        Session s = signup(uniqueLogin("photo"));
        Session other = signup(uniqueLogin("photo2"));
        String cdn = imageUrls.publicBaseUrl() + "/";
        String first = imageKey(s.memberId(), "IMAGE");
        String second = imageKey(s.memberId(), "IMAGE");
        String stranger = imageKey(other.memberId(), "IMAGE");
        long id = publish(s, "사진 글", "글\n\n![a](" + cdn + first + ")\n\n![b](" + cdn + stranger + ")\n\n![c](" + cdn + second + ")");

        List<Map<String, Object>> links = jdbc.queryForList("SELECT resource_id, position FROM post_image WHERE post_id = ? ORDER BY position", id);
        assertThat(links).extracting(m -> ((Number) m.get("resource_id")).longValue()).containsExactly(resourceId(first), resourceId(second));
        assertThat(links).extracting(m -> ((Number) m.get("position")).intValue()).containsExactly(0, 1);

        JsonNode feed = read(mvc.perform(get("/api/members/" + s.handle() + "/posts")).andExpect(status().isOk()).andReturn());
        assertThat(feed.path("items").get(0).path("thumbnailUrl").asString()).isEqualTo(cdn + first.replace(".webp", "_thumb.webp"));

        // 다시 발행하며 첫 사진을 빼면 대표 사진이 바뀌고 빠진 사진은 연결 해제 시각이 남는다
        long version = 1;
        s.http().perform(asJson(post("/api/posts/" + id + "/publish"),
                Map.of("title", "사진 글", "contentMd", "![c](" + cdn + second + ")", "visibility", "PUBLIC", "baseVersion", version, "tags", List.of())))
                .andExpect(status().isOk());
        assertThat(jdbc.queryForList("SELECT resource_id FROM post_image WHERE post_id = ?", Long.class, id)).containsExactly(resourceId(second));
        assertThat(jdbc.queryForObject("SELECT detached_at FROM resource WHERE storage_key = ?", java.sql.Timestamp.class, first)).isNotNull();
        JsonNode d = read(mvc.perform(get("/api/posts/" + id)).andExpect(status().isOk()).andReturn());
        assertThat(d.path("thumbnailUrl").asString()).isEqualTo(cdn + second.replace(".webp", "_thumb.webp"));
    }

    @Test
    void 사진_연결에_첨부파일_리소스를_넣을_수_없다() throws Exception {
        Session s = signup(uniqueLogin("kind"));
        long id = publish(s, "글", "본문");
        long file = resourceId(imageKey(s.memberId(), "FILE"));
        assertThatThrownBy(() -> jdbc.update("INSERT INTO post_image (post_id, resource_id, position) VALUES (?, ?, 0)", id, file))
                .isInstanceOf(DataIntegrityViolationException.class);
        jdbc.update("INSERT INTO post_file (post_id, resource_id, position) VALUES (?, ?, 0)", id, file);
    }

    @Test
    void 조회수_좋아요_댓글_수는_행을_세어_보여_준다() throws Exception {
        Session s = signup(uniqueLogin("stat"));
        Session fan = signup(uniqueLogin("statfan"));
        long id = publish(s, "통계", "본문", List.of("자바", "spring", "db", "redis"));
        jdbc.update("INSERT INTO post_view (post_id) SELECT ?::bigint FROM generate_series(1, 7)", id);
        jdbc.update("INSERT INTO post_like (post_id, member_id) VALUES (?, ?)", id, fan.memberId());
        jdbc.update("INSERT INTO comment (post_id, author_id, content) VALUES (?, ?, '좋아요')", id, fan.memberId());
        jdbc.update("INSERT INTO comment (post_id, author_id, content, deleted_at) VALUES (?, ?, '지움', now())", id, fan.memberId());

        JsonNode d = read(mvc.perform(get("/api/posts/" + id)).andExpect(status().isOk()).andReturn());
        assertThat(d.path("viewCount").asLong()).isEqualTo(7);
        assertThat(d.path("likeCount").asInt()).isEqualTo(1);
        assertThat(d.path("commentCount").asInt()).isEqualTo(1);
        JsonNode card = read(mvc.perform(get("/api/members/" + s.handle() + "/posts")).andReturn()).path("items").get(0);
        assertThat(card.path("likeCount").asInt()).isEqualTo(1);
        assertThat(card.path("commentCount").asInt()).isEqualTo(1);
        // 카드에도 조회수와 태그(입력 순서 그대로, 전부)가 실린다
        assertThat(card.path("viewCount").asLong()).isEqualTo(7);
        assertThat(card.path("tags").valueStream().map(JsonNode::asString).toList()).containsExactly("자바", "spring", "db", "redis");
    }

    @Test
    void 답글에는_답글을_달_수_없고_다른_글의_댓글도_부모가_될_수_없다() throws Exception {
        Session s = signup(uniqueLogin("reply"));
        long id = publish(s, "댓글", "본문");
        long other = publish(s, "다른 글", "본문");
        long root = jdbc.queryForObject("INSERT INTO comment (post_id, author_id, content) VALUES (?, ?, '최상위') RETURNING id", Long.class, id, s.memberId());
        long reply = jdbc.queryForObject("INSERT INTO comment (post_id, author_id, parent_id, content) VALUES (?, ?, ?, '답글') RETURNING id",
                Long.class, id, s.memberId(), root);
        assertThat(reply).isPositive();
        long me = s.memberId();
        assertThatThrownBy(() -> jdbc.update("INSERT INTO comment (post_id, author_id, parent_id, content) VALUES (?, ?, ?, '답글의 답글')", id, me, reply))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO comment (post_id, author_id, parent_id, content) VALUES (?, ?, ?, '다른 글')", other, me, root))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void 본문_HTML은_버전별로_캐시되고_다시_발행하면_새_내용을_보여_준다() throws Exception {
        Session s = signup(uniqueLogin("cache"));
        long id = publish(s, "캐시", "처음 본문");
        assertThat(read(mvc.perform(get("/api/posts/" + id)).andReturn()).path("contentHtml").asString()).contains("처음 본문");
        assertThat(redis.hasKey(RenderedHtmlCache.key(id, 1))).isTrue();
        s.http().perform(asJson(post("/api/posts/" + id + "/publish"),
                Map.of("title", "캐시", "contentMd", "고친 본문", "visibility", "PUBLIC", "baseVersion", 1, "tags", List.of())))
                .andExpect(status().isOk());
        assertThat(read(mvc.perform(get("/api/posts/" + id)).andReturn()).path("contentHtml").asString())
                .contains("고친 본문").doesNotContain("처음 본문");
    }
}
