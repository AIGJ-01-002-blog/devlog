package com.team.blog.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import tools.jackson.databind.JsonNode;

import com.team.blog.mcp.application.AiJournal;
import com.team.blog.support.IntegrationTest;

/**
 * #240: 일지·메모에 근거 링크를 남기고, 글을 쓰거나 발행할 때 쇼츠로 만들 만한지 알려 주고, 쇼츠 후보 목록을 MCP로 본다.
 */
class McpRecordTest extends IntegrationTest {
    static final ZoneId KST = ZoneId.of("Asia/Seoul");

    /** 겪은 문제 + 숫자로 된 결과 + 코드가 있는 글 (쇼츠 후보) */
    static final String STORY = """
            배포할 때마다 로그인한 사용자가 모두 로그아웃되는 문제가 있었어요. 처음에는 Redis 연결이 끊긴 줄 알고 연결 수를 늘려 봤지만 그대로였어요.
            로그를 하나씩 따라가 보니 원인은 세션을 저장하는 클래스 모양이 바뀌면서 예전 세션을 읽지 못하는 것이었어요.
            자바 기본 직렬화 대신 JSON으로 저장하도록 바꾸고 타입 이름을 고정했어요. 바꾼 뒤에는 배포해도 세션이 그대로 남았고,
            다시 로그인하던 사람이 하루 40명에서 0명으로 줄었어요. 세션처럼 오래 남는 데이터는 클래스 구조에 묶이지 않게 저장해야 한다는 걸 배웠어요.

            ```yaml
            spring.session.redis.serializer: json
            ```
            """;

    @Autowired
    AiJournal journal;

    String token(Session s) throws Exception {
        return read(s.http().perform(asJson(post("/api/me/tokens"), Map.of("name", "Claude Code", "scope", "WRITE")))
                .andExpect(status().isCreated()).andReturn()).path("secret").asString();
    }

    JsonNode rpc(String token, String method, Object params) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("jsonrpc", "2.0");
        body.put("id", 1);
        body.put("method", method);
        if (params != null) body.put("params", params);
        return read(mvc.perform(post("/api/mcp").contentType(MediaType.APPLICATION_JSON).header("Authorization", "Bearer " + token)
                .content(json.writeValueAsString(body))).andExpect(status().isOk()).andReturn()).path("result");
    }

    JsonNode call(String token, String tool, Map<String, Object> args) throws Exception {
        return rpc(token, "tools/call", Map.of("name", tool, "arguments", args));
    }

    static String text(JsonNode result) {
        return result.path("content").get(0).path("text").asString();
    }

    static long postId(JsonNode r) {
        return Long.parseLong(text(r).replaceAll("(?s).*글 번호 (\\d+).*", "$1"));
    }

    @Test
    void 일지에_근거_링크를_붙이고_쇼츠로_만들_만한_글은_발행한_뒤_물어보라고_알린다() throws Exception {
        Session me = signup(uniqueLogin("mcprec"));
        String t = token(me);
        assertThat(rpc(t, "initialize", Map.of("protocolVersion", "2025-06-18")).path("instructions").asString())
                .contains("links", "쇼츠 후보", "list_shorts_candidates");

        JsonNode r = call(t, "write_devlog", Map.of("title", "배포하면 로그아웃되던 문제", "content_md", STORY, "links", List.of(
                "https://github.com/AIGJ-01-002-blog/devlog/pull/246",
                "https://github.com/AIGJ-01-002-blog/devlog/commit/d525981077c669380608bf232775c29733c10fc1",
                "https://github.com/AIGJ-01-002-blog/devlog/pull/246",
                "http://example.com/plain",
                "https://user:pw@example.com/x")));
        assertThat(r.path("isError").asBoolean()).as(text(r)).isFalse();
        assertThat(text(r)).contains("근거 링크 2개를 남겼어요", "근거 링크 2개는", "쇼츠 후보", "발행한 뒤");
        long id = postId(r);
        String body = jdbc.queryForObject("SELECT content_md FROM post WHERE id = ?", String.class, id);
        assertThat(body).endsWith("""
                ## 근거

                - [PR #246 (devlog)](https://github.com/AIGJ-01-002-blog/devlog/pull/246)
                - [커밋 d525981 (devlog)](https://github.com/AIGJ-01-002-blog/devlog/commit/d525981077c669380608bf232775c29733c10fc1)
                """);

        // 짧은 일지는 쇼츠 후보가 아니다
        JsonNode plain = call(t, "write_devlog", Map.of("title", "README 오타", "content_md", "README 오타를 고쳤어요."));
        assertThat(text(plain)).doesNotContain("쇼츠 후보");

        // 공개로 발행하면 대본을 만들지 물어보라고 알린다
        JsonNode allowed = read(me.http().perform(asJson(put("/api/me/ai-publish"), Map.of("allowed", true)))
                .andExpect(status().isOk()).andReturn());
        assertThat(allowed.path("allowed").asBoolean()).isTrue();
        JsonNode published = call(t, "publish_post", Map.of("post_id", id, "visibility", "PUBLIC"));
        assertThat(published.path("isError").asBoolean()).as(text(published)).isFalse();
        assertThat(text(published)).contains("쇼츠 후보", "post_id " + id, "다시 묻지 마세요");

        JsonNode candidates = call(t, "list_shorts_candidates", Map.of());
        assertThat(text(candidates)).contains("글 " + id + " · 배포하면 로그아웃되던 문제", "직접 겪은 문제와 해결", "숫자로 된 결과", "화면에 보일 코드");

        // 이 글로 쇼츠를 만들면 후보에서 빠진다
        JsonNode shorts = call(t, "create_shorts_script", Map.of("post_id", id, "title", "배포하면 로그아웃", "scenes", List.of(
                Map.of("caption", "배포하면 다 로그아웃?", "narration", "왜 배포만 하면 사용자가 전부 로그아웃될까요?"),
                Map.of("caption", "범인은 직렬화", "narration", "로그를 따라가 보니 세션 클래스 모양이 바뀌어 예전 세션을 못 읽고 있었어요."),
                Map.of("caption", "결과", "narration", "JSON으로 바꾸니 다시 로그인하던 사람이 하루 마흔 명에서 한 명도 없게 됐어요."))));
        assertThat(shorts.path("isError").asBoolean()).as(text(shorts)).isFalse();
        assertThat(text(call(t, "list_shorts_candidates", Map.of()))).doesNotContain("글 " + id + " ·").contains("쇼츠로 만들 만한 글이 없어요");
    }

    @Test
    void 메모의_근거_링크는_일기의_주제_문단_아래에_한_번씩_붙는다() throws Exception {
        Session me = signup(uniqueLogin("mcpnote"));
        String t = token(me);
        read(me.http().perform(asJson(put("/api/me/ai-diary"), Map.of("enabled", true))).andExpect(status().isOk()).andReturn());
        String pr = "https://github.com/AIGJ-01-002-blog/devlog/pull/246";
        JsonNode a = call(t, "add_note", Map.of("topic", "대시보드", "content", "내 대시보드에 기간 비교를 넣었어요", "links", List.of(pr)));
        assertThat(a.path("isError").asBoolean()).as(text(a)).isFalse();
        assertThat(text(a)).contains("근거 링크 1개를 남겼어요");
        call(t, "add_note", Map.of("topic", "대시보드", "content", "조회수는 본인 방문을 빼고 셌어요",
                "links", List.of(pr, "https://github.com/AIGJ-01-002-blog/devlog/issues/239")));
        // 어제 남긴 것처럼 옮겨서 오늘 자정에 묶는다
        jdbc.update("UPDATE ai_note SET created_at = created_at - interval '1 day' WHERE member_id = ?", me.memberId());

        journal.compileDiariesAt(LocalDate.now(KST).atStartOfDay(KST).toInstant().plusSeconds(3));
        String body = jdbc.queryForObject("SELECT content_md FROM post WHERE author_id = ? AND ai_diary", String.class, me.memberId());
        assertThat(body).isEqualTo("""
                ## 대시보드

                내 대시보드에 기간 비교를 넣었어요. 조회수는 본인 방문을 빼고 셌어요.

                근거: [PR #246 (devlog)](https://github.com/AIGJ-01-002-blog/devlog/pull/246) · [이슈 #239 (devlog)](https://github.com/AIGJ-01-002-blog/devlog/issues/239)
                """);
    }
}
