package com.team.blog.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import tools.jackson.databind.JsonNode;

import com.team.blog.support.IntegrationTest;

/**
 * spec 060: MCP 도구 강화. 발행한 글 고치기·다시 발행, 사진 올리기(base64·한 번 쓰는 주소), 내 글 전체 검색.
 */
class McpToolsPlusTest extends IntegrationTest {

    String token(Session s, String scope) throws Exception {
        return read(s.http().perform(asJson(post("/api/me/tokens"), Map.of("name", "Claude Code", "scope", scope)))
                .andExpect(status().isCreated()).andReturn()).path("secret").asString();
    }

    ResultActions rpc(String token, String method, Object params) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("jsonrpc", "2.0");
        body.put("id", 1);
        body.put("method", method);
        if (params != null) body.put("params", params);
        return mvc.perform(post("/api/mcp").contentType(MediaType.APPLICATION_JSON).header("Authorization", "Bearer " + token)
                .content(json.writeValueAsString(body)));
    }

    JsonNode call(String token, String tool, Map<String, Object> args) throws Exception {
        return read(rpc(token, "tools/call", Map.of("name", tool, "arguments", args)).andExpect(status().isOk()).andReturn()).path("result");
    }

    static String text(JsonNode result) {
        return result.path("content").get(0).path("text").asString();
    }

    long publish(Session s, String title, String visibility) throws Exception {
        long id = read(s.http().perform(asJson(post("/api/posts"), Map.of("title", title, "contentMd", "")))
                .andExpect(status().isCreated()).andReturn()).path("id").asLong();
        Map<String, Object> body = new HashMap<>(Map.of("title", title, "contentMd", "본문 " + title, "visibility", visibility,
                "tags", List.of("spring"), "baseVersion", 0));
        s.http().perform(asJson(post("/api/posts/" + id + "/publish"), body).header("Idempotency-Key", UUID.randomUUID().toString()))
                .andExpect(status().isOk());
        return id;
    }

    static byte[] png(int w, int h) throws Exception {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.ORANGE);
        g.fillRect(0, 0, w, h / 2);
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }

    /** SOI 바로 뒤에 APP1(Exif) 조각을 끼운 jpg */
    static byte[] jpegWithExif() throws Exception {
        BufferedImage img = new BufferedImage(200, 100, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "jpg", out);
        byte[] plain = out.toByteArray();
        byte[] exif = "Exif\0\0GPS-37.5,127.0".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        ByteArrayOutputStream withExif = new ByteArrayOutputStream();
        withExif.write(plain, 0, 2);
        withExif.write(new byte[]{(byte) 0xFF, (byte) 0xE1, 0, (byte) (exif.length + 2)});
        withExif.write(exif);
        withExif.write(plain, 2, plain.length - 2);
        return withExif.toByteArray();
    }

    @Test
    void 발행한_글은_작업본에만_고쳐지고_독자는_발행본을_본다() throws Exception {
        Session me = signup(uniqueLogin("mcped"));
        String t = token(me, "WRITE");
        long id = publish(me, "처음 제목", "PUBLIC");

        // 고치지 않은 발행 글은 다시 발행할 것이 없다
        jdbc.update("UPDATE member SET ai_publish_allowed = true WHERE id = ?", me.memberId());
        JsonNode nothing = call(t, "publish_post", Map.of("post_id", id));
        assertThat(nothing.path("isError").asBoolean()).isTrue();
        assertThat(text(nothing)).contains("고친 내용이 없어요");
        jdbc.update("UPDATE member SET ai_publish_allowed = false WHERE id = ?", me.memberId());

        assertThat(call(t, "update_draft", Map.of("post_id", id)).path("isError").asBoolean()).isTrue();
        JsonNode edit = call(t, "update_draft", Map.of("post_id", id, "title", "고친 제목", "content_md", "고친 본문"));
        assertThat(edit.path("isError").asBoolean()).as(text(edit)).isFalse();
        assertThat(text(edit)).contains("아직 독자에게는 이전 내용").contains("/write/" + id).doesNotContain("publish_post");
        // 독자는 그대로 발행본을 본다
        assertThat(jdbc.queryForObject("SELECT title FROM post WHERE id = ?", String.class, id)).isEqualTo("처음 제목");
        assertThat(jdbc.queryForObject("SELECT content_md FROM post WHERE id = ?", String.class, id)).isEqualTo("본문 처음 제목");
        assertThat(jdbc.queryForObject("SELECT title FROM post_draft WHERE post_id = ?", String.class, id)).isEqualTo("고친 제목");
        // 본인이 읽으면 고치는 중인 내용이 보인다
        assertThat(text(call(t, "get_post", Map.of("post_id", id)))).contains("고친 본문");

        // 남의 글은 고칠 수 없다
        Session other = signup(uniqueLogin("mcped2"));
        assertThat(call(token(other, "WRITE"), "update_draft", Map.of("post_id", id, "title", "남의 글")).path("isError").asBoolean()).isTrue();
    }

    @Test
    void 고친_발행_글은_허용했을_때만_AI가_다시_발행한다() throws Exception {
        Session me = signup(uniqueLogin("mcpre"));
        String t = token(me, "WRITE");
        long id = publish(me, "처음 제목", "PUBLIC");
        JsonNode edit = call(t, "update_draft", Map.of("post_id", id, "title", "고친 제목", "content_md", "고친 본문"));
        assertThat(edit.path("isError").asBoolean()).as(text(edit)).isFalse();

        // 허용하지 않았으면 다시 발행도 막힌다
        assertThat(call(t, "publish_post", Map.of("post_id", id)).path("isError").asBoolean()).isTrue();
        assertThat(jdbc.queryForObject("SELECT title FROM post WHERE id = ?", String.class, id)).isEqualTo("처음 제목");

        jdbc.update("UPDATE member SET ai_publish_allowed = true WHERE id = ?", me.memberId());
        JsonNode again = call(t, "publish_post", Map.of("post_id", id));
        assertThat(again.path("isError").asBoolean()).as(text(again)).isFalse();
        assertThat(text(again)).contains("다시 발행했어요").contains("전체 공개").contains("태그: spring");
        assertThat(jdbc.queryForObject("SELECT title FROM post WHERE id = ?", String.class, id)).isEqualTo("고친 제목");
        assertThat(jdbc.queryForObject("SELECT content_md FROM post WHERE id = ?", String.class, id)).isEqualTo("고친 본문");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM post_draft WHERE post_id = ?", Integer.class, id)).isZero();
    }

    @Test
    void 내_글_검색은_비공개와_임시글까지_찾고_남의_글은_찾지_않는다() throws Exception {
        Session me = signup(uniqueLogin("mcpsr"));
        String t = token(me, "READ");
        String word = "쿠버네티스" + UUID.randomUUID().toString().substring(0, 6);
        long priv = publish(me, word + " 비공개 메모", "PRIVATE");
        long pub = publish(me, word + " 공개 글", "PUBLIC");
        String wt = token(me, "WRITE");
        long draft = Long.parseLong(text(call(wt, "create_draft", Map.of("title", "쓰다 만 글", "content_md", "본문에 " + word)))
                .replaceAll("(?s).*글 번호 (\\d+).*", "$1"));
        long trashed = publish(me, word + " 지운 글", "PUBLIC");
        jdbc.update("UPDATE post SET deleted_at = now() WHERE id = ?", trashed);
        Session other = signup(uniqueLogin("mcpsr2"));
        long others = publish(other, word + " 남의 글", "PUBLIC");

        String mine = text(call(t, "search_posts", Map.of("query", word, "mine", true)));
        assertThat(mine).contains("[" + priv + "]").contains("비공개").contains("[" + pub + "]").contains("[" + draft + "]").contains("임시글")
                .doesNotContain("[" + trashed + "]").doesNotContain("[" + others + "]");

        // 공개 검색은 그대로 공개 글만
        String all = text(call(t, "search_posts", Map.of("query", word)));
        assertThat(all).doesNotContain("[" + priv + "]").doesNotContain("[" + draft + "]");
        assertThat(call(t, "search_posts", Map.of("query", "a", "mine", true)).path("isError").asBoolean()).isTrue();
    }

    @Test
    void 사진은_줄이고_다시_저장해서_올리고_Markdown을_돌려준다() throws Exception {
        Session me = signup(uniqueLogin("mcpimg"));
        String t = token(me, "WRITE");
        assertThat(rpc(t, "tools/list", null).andReturn().getResponse().getContentAsString()).contains("upload_image", "create_image_upload_link");

        String b64 = "data:image/png;base64," + Base64.getEncoder().encodeToString(png(3000, 1000));
        JsonNode up = call(t, "upload_image", Map.of("image_base64", b64, "alt", "배포 [화면]\n캡처"));
        assertThat(up.path("isError").asBoolean()).as(text(up)).isFalse();
        assertThat(text(up)).contains("1920×640").containsPattern("!\\[배포 화면 캡처\\]\\(\\S+\\.png\\)");
        assertThat(jdbc.queryForObject("""
                SELECT ri.width FROM resource r JOIN resource_image ri ON ri.resource_id = r.id WHERE r.uploader_id = ?
                """, Integer.class, me.memberId())).isEqualTo(1920);

        // 위치 같은 사진 정보(EXIF)가 든 jpg도 서버가 다시 저장해 지운 뒤 올린다 (웹은 브라우저가 지운다)
        JsonNode exif = call(t, "upload_image", Map.of("image_base64", Base64.getEncoder().encodeToString(jpegWithExif())));
        assertThat(exif.path("isError").asBoolean()).as(text(exif)).isFalse();
        assertThat(text(exif)).containsPattern("\\.jpg\\)");

        JsonNode bad = call(t, "upload_image", Map.of("image_base64", Base64.getEncoder().encodeToString("not an image at all".getBytes())));
        assertThat(bad.path("isError").asBoolean()).isTrue();

        // 읽기 전용 토큰은 올리지 못한다
        Session reader = signup(uniqueLogin("mcpimg2"));
        assertThat(call(token(reader, "READ"), "upload_image", Map.of("image_base64", b64)).path("isError").asBoolean()).isTrue();
    }

    @Test
    void 올리기_주소는_한_번만_쓸_수_있다() throws Exception {
        Session me = signup(uniqueLogin("mcplnk"));
        String t = token(me, "WRITE");
        JsonNode link = call(t, "create_image_upload_link", Map.of("alt", "구조도"));
        assertThat(link.path("isError").asBoolean()).as(text(link)).isFalse();
        String path = text(link).replaceAll("(?s).*(/api/mcp/uploads/[A-Za-z0-9_-]+).*", "$1");
        assertThat(path).startsWith("/api/mcp/uploads/");

        // 쿠키·CSRF 없이 바이트만 보낸다
        JsonNode r = read(mvc.perform(put(path).contentType(MediaType.IMAGE_PNG).content(png(800, 600)))
                .andExpect(status().isCreated()).andReturn());
        assertThat(r.path("markdown").asString()).startsWith("![구조도](").endsWith(".png)");
        assertThat(r.path("width").asInt()).isEqualTo(800);

        mvc.perform(put(path).contentType(MediaType.IMAGE_PNG).content(png(10, 10))).andExpect(status().isNotFound());
        mvc.perform(put("/api/mcp/uploads/" + "x".repeat(32)).content(png(10, 10))).andExpect(status().isNotFound());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM resource WHERE uploader_id = ?", Integer.class, me.memberId())).isEqualTo(1);
    }

    @Test
    void 같은_올리기_주소로_동시에_보내도_하나만_올라간다() throws Exception {
        Session me = signup(uniqueLogin("mcpcon"));
        String t = token(me, "WRITE");
        String path = text(call(t, "create_image_upload_link", Map.of())).replaceAll("(?s).*(/api/mcp/uploads/[A-Za-z0-9_-]+).*", "$1");
        byte[] img = png(400, 300);
        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(4);
        try {
            List<java.util.concurrent.Future<Integer>> results = new java.util.ArrayList<>();
            for (int i = 0; i < 4; i++) {
                results.add(pool.submit(() -> {
                    start.await();
                    return mvc.perform(put(path).contentType(MediaType.IMAGE_PNG).content(img)).andReturn().getResponse().getStatus();
                }));
            }
            start.countDown();
            List<Integer> codes = new java.util.ArrayList<>();
            for (var f : results) codes.add(f.get(30, java.util.concurrent.TimeUnit.SECONDS));
            assertThat(codes).containsOnlyOnce(201).containsOnly(201, 404);
        } finally {
            pool.shutdownNow();
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM resource WHERE uploader_id = ?", Integer.class, me.memberId())).isEqualTo(1);
    }
}
