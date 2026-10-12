package com.team.blog.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import tools.jackson.databind.JsonNode;

import com.team.blog.mcp.application.TopicSuggester;
import com.team.blog.support.IntegrationTest;

/**
 * spec 075: suggest_topics. 메모·AI 일기·글 제안·임시글에서 전공자 관점이 보이는 글감을 고르고, 발행한 글과 겹침을 알리고, 비밀값처럼 보이는 값은 가린다.
 */
class McpTopicsTest extends IntegrationTest {

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

    long draft(Session s, String title, String content) throws Exception {
        return read(s.http().perform(asJson(post("/api/posts"), Map.of("title", title, "contentMd", content)))
                .andExpect(status().isCreated()).andReturn()).path("id").asLong();
    }

    long publish(Session s, String title, List<String> tags) throws Exception {
        long id = draft(s, title, "");
        Map<String, Object> body = new HashMap<>(Map.of("title", title, "contentMd", "본문 " + title, "visibility", "PUBLIC",
                "tags", tags, "baseVersion", 0));
        s.http().perform(asJson(post("/api/posts/" + id + "/publish"), body).header("Idempotency-Key", UUID.randomUUID().toString()))
                .andExpect(status().isOk());
        return id;
    }

    void note(long memberId, String topic, String content, Instant at) {
        jdbc.update("INSERT INTO ai_note (member_id, topic, content, tags, created_at) VALUES (?, ?, ?, ?, ?)",
                memberId, topic, content, "", Timestamp.from(at));
    }

    @Test
    void 메모_일기_제안_임시글에서_전공자_글감을_고르고_겹침과_가릴_것을_알린다() throws Exception {
        Session me = signup(uniqueLogin("mcptopic"));
        String write = token(me, "WRITE");
        String readOnly = token(me, "READ");
        Instant now = Instant.now();

        note(me.memberId(), "자동 저장", "탭 두 개에서 같은 글을 고치면 버전 충돌이 나서 Redis Lua로 버전 관문을 뒀다", now.minus(Duration.ofHours(2)));
        note(me.memberId(), "자동 저장", "중복 요청은 Idempotency-Key로 막았다", now.minus(Duration.ofHours(1)));
        note(me.memberId(), "점심", "점심 메뉴를 골랐다", now.minus(Duration.ofHours(1)));
        // 작업을 알리는 "배포했다"와 디자인 "색 토큰"은 관점이 아니다 (1.51.1)
        note(me.memberId(), "선택 상자 화살표", "색 토큰으로 그린 꺾쇠를 상자 안쪽에 두고 v1.48.1로 배포했다", now.minus(Duration.ofHours(1)));
        note(me.memberId(), "오래된 일", "Redis 잠금 이야기", now.minus(Duration.ofDays(40)));
        draft(me, "2026-10-08 개발 일기", """
                ## 무중단 배포

                - 10:00 rollout.sh가 실패하면 롤백한다. 서버 10.0.0.5 에 password=hunter2 로 붙었다

                ## 산책

                - 12:00 공원을 걸었다
                """);
        draft(me, "Redis 캐시 무효화", "캐시 키를 원문 해시로 바꿨다");
        assertThat(call(write, "propose_post", Map.of("title", "MCP OAuth 직접 만들기", "scope", "- PKCE와 토큰 범위")).path("isError").asBoolean())
                .isFalse();
        long redis = publish(me, "Redis 캐시 이야기", List.of("redis"));

        // 남의 메모는 보이지 않는다
        Session other = signup(uniqueLogin("mcptopic2"));
        note(other.memberId(), "남의 비밀 주제", "Kubernetes 배포 장애", now);

        JsonNode listed = read(rpc(readOnly, "tools/list", null).andExpect(status().isOk()).andReturn()).path("result").path("tools");
        JsonNode tool = null;
        for (JsonNode t : listed) if ("suggest_topics".equals(t.path("name").asString())) tool = t;
        assertThat(tool).as("읽기 토큰에도 보인다").isNotNull();
        assertThat(tool.path("annotations").path("readOnlyHint").asBoolean()).isTrue();

        JsonNode r = call(readOnly, "suggest_topics", Map.of());
        String out = text(r);
        assertThat(r.path("isError").asBoolean()).as(out).isFalse();
        assertThat(out).contains("메모 4개", "AI 일기 1편", "글 제안 1개", "임시글 1편")
                .contains("자동 저장", "동시성", "무중단 배포", "운영·배포", "MCP OAuth 직접 만들기", "보안")
                .contains("관점이 안 보이는 기록 3개")  // 점심 메모, 선택 상자 메모, 산책 소제목
                .doesNotContain("점심 메뉴", "꺾쇠", "공원", "남의 비밀 주제", "오래된 일")
                .contains("propose_post")
                // 겹침: 임시글 "Redis 캐시 무효화"는 발행한 "Redis 캐시 이야기"와 겹친다
                .contains("겹치는 글: [" + redis + "] Redis 캐시 이야기")
                // 가릴 것: IP와 비밀번호 값은 돌려주지 않는다
                .doesNotContain("10.0.0.5", "hunter2").contains("password=****", "가릴 것");

        String focused = text(call(readOnly, "suggest_topics", Map.of("focus", "배포", "limit", 3)));
        assertThat(focused).contains("무중단 배포").doesNotContain("자동 저장", "MCP OAuth");

        assertThat(call(readOnly, "suggest_topics", Map.of("days", "많이")).path("isError").asBoolean()).isTrue();
    }

    @Test
    void 자료가_없으면_남기는_방법을_알려_준다() throws Exception {
        Session me = signup(uniqueLogin("mcptopic3"));
        String out = text(call(token(me, "READ"), "suggest_topics", Map.of("days", 500)));
        assertThat(out).contains("최근 90일 동안 읽을 자료", "add_note");
    }

    @Test
    void 비밀값처럼_보이는_값을_가린다() {
        assertThat(TopicSuggester.words("Redis 캐시 이야기")).contains("redis", "캐시").doesNotContain("이야기");
        String masked = TopicSuggester.mask("me@corp.io 로 token: abc123 보냄, Authorization: Bearer abcdefghijkl, 192.168.0.10").text();
        assertThat(masked).doesNotContain("me@corp.io", "abc123", "abcdefghijkl", "192.168.0.10");
    }
}
