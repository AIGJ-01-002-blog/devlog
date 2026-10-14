package com.team.blog.shorts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import tools.jackson.databind.JsonNode;

import com.team.blog.support.IntegrationTest;

/**
 * #276: 글이나 MCP로 받은 맥락 하나를 작은 주제 여러 개로 나눠(plan_shorts) 주제마다 쇼츠 한 편씩 쓰고, 묶음째 영상 만들기 대기열에 넣는다.
 */
class ShortsBatchTest extends IntegrationTest {
    private static final String CONTEXT = """
            오늘 배포 파이프라인을 손봤다. 이미지 빌드가 매번 7분씩 걸려서 원인을 찾아보니 의존성 캐시가 매번 날아가고 있었다.
            캐시 키를 잠금 파일 해시로 바꾸자 빌드가 2분으로 줄었다. 그다음 시험 단계가 가끔 실패했는데 포트가 겹치는 문제였다.
            시험마다 빈 포트를 받게 바꾸니 실패가 사라졌다. 마지막으로 배포 알림이 두 번씩 오던 것도 고쳤다.
            """;

    private static final List<Map<String, String>> CACHE = List.of(
            Map.of("caption", "빌드 7분, 왜?", "narration", "왜 이미지 빌드가 매번 7분이나 걸렸을까요?"),
            Map.of("caption", "캐시가 날아감", "narration", "제가 빌드 기록을 열어 보니 의존성을 받을 때마다 처음부터 다시 내려받고 있었어요."),
            Map.of("caption", "키를 바꿨어요", "narration", "그래서 캐시 이름을 잠금 파일 내용으로 정했더니 바뀐 게 없을 땐 그대로 재사용돼요."),
            Map.of("caption", "7분 → 2분", "narration", "결과는 빌드 하나에 5분을 아꼈고, 하루 열 번 배포면 50분이 남아요."));

    private static final List<Map<String, String>> COPIED = List.of(
            Map.of("caption", "포트 문제", "narration", "그다음 시험 단계가 가끔 실패했는데 포트가 겹치는 문제였다."),
            Map.of("caption", "빈 포트", "narration", "시험마다 빈 포트를 받게 바꾸니 실패가 사라졌다."),
            Map.of("caption", "알림", "narration", "마지막으로 배포 알림이 두 번씩 오던 것도 고쳤다."));

    @Test
    void 맥락을_주제로_나누고_주제마다_대본을_써서_묶음째_영상_만들기에_넣는다() throws Exception {
        Session me = signup(uniqueLogin("batch"));
        String token = token(me);
        assertThat(rpc(token, "tools/list", null).path("tools").findValuesAsString("name")).contains("plan_shorts");

        // 근거가 없거나 주제가 하나면 막는다
        JsonNode noSource = call(token, "plan_shorts", Map.of("title", "배포 손본 날", "topics", topics(3)));
        assertThat(text(noSource)).contains("근거가 필요해요");
        JsonNode one = call(token, "plan_shorts", Map.of("title", "배포 손본 날", "source_text", CONTEXT, "topics", topics(1)));
        assertThat(text(one)).contains("주제는 2~5개");

        JsonNode planned = call(token, "plan_shorts", Map.of("title", "배포 손본 날", "source_text", CONTEXT, "topics", topics(3)));
        assertThat(planned.path("isError").asBoolean()).as(text(planned)).isFalse();
        assertThat(text(planned)).contains("주제 3개", "1. 주제 1 · 첫 질문: 왜 1번은 느렸을까요?", "/studio?batch=");
        long batchId = Long.parseLong(text(planned).replaceAll("(?s).*묶음 번호 (\\d+).*", "$1"));

        // 주제 1: 제 말로 쓴 대본. 제목을 비우면 주제 제목을 쓴다
        JsonNode first = call(token, "create_shorts_script", Map.of("batch_id", batchId, "topic", 1, "scenes", CACHE));
        assertThat(text(first)).contains("영상으로 만들 수 있어요").doesNotContain("원본 글이나 맥락 근거가 없어요");
        // 주제 2: 맥락 문장을 그대로 읽으면 막힌다 (근거로 맥락을 비교한다)
        JsonNode second = call(token, "create_shorts_script", Map.of("batch_id", batchId, "topic", 2, "title", "포트가 겹친 시험", "scenes", COPIED));
        assertThat(text(second)).contains("[막힘]", "원본 글 문장을 그대로");
        // 같은 주제를 또 쓰거나 없는 주제면 안내한다
        assertThat(text(call(token, "create_shorts_script", Map.of("batch_id", batchId, "topic", 1, "scenes", CACHE)))).contains("이미 대본이 있어요");
        assertThat(text(call(token, "create_shorts_script", Map.of("batch_id", batchId, "topic", 4, "scenes", CACHE)))).contains("1~3 사이");
        assertThat(text(call(token, "list_shorts", Map.of()))).contains("묶음 " + batchId + "의 1번 주제", "대본이 없는 주제: 3번 주제 3");

        JsonNode batches = read(me.http().perform(get("/api/me/shorts/batches")).andExpect(status().isOk()).andReturn());
        JsonNode batch = batches.get(0);
        assertThat(batch.path("id").asLong()).isEqualTo(batchId);
        assertThat(batch.path("hasSource").asBoolean()).isTrue();
        assertThat(batch.path("topics")).hasSize(3);
        assertThat(batch.path("topics").get(0).path("status").asString()).isEqualTo("DRAFT");
        assertThat(batch.path("topics").get(2).path("shortsId").isNull()).isTrue();

        // 묶음째 영상 만들기: 통과한 편만 대기열에, 막힌 편은 이유와 함께 건너뛴다
        JsonNode result = read(me.http().perform(post("/api/me/shorts/batches/" + batchId + "/render").with(csrf())).andExpect(status().isOk()).andReturn());
        long firstId = batch.path("topics").get(0).path("shortsId").asLong();
        assertThat(result.path("queued").get(0).asLong()).isEqualTo(firstId);
        assertThat(result.path("skipped")).hasSize(1);
        assertThat(result.path("skipped").get(0).path("reason").asString()).contains("양산형");

        // 남의 묶음은 없는 것과 같다
        Session other = signup(uniqueLogin("batch2"));
        other.http().perform(post("/api/me/shorts/batches/" + batchId + "/render").with(csrf())).andExpect(status().isNotFound());
        assertThat(text(call(token(other), "create_shorts_script", Map.of("batch_id", batchId, "topic", 3, "scenes", CACHE)))).contains("찾을 수 없어요");

        // 묶음을 풀면 대본은 남고 묶음 표시만 사라진다
        me.http().perform(delete("/api/me/shorts/batches/" + batchId).with(csrf())).andExpect(status().isNoContent());
        JsonNode kept = read(me.http().perform(get("/api/me/shorts/" + firstId)).andExpect(status().isOk()).andReturn());
        assertThat(kept.path("batchId").isNull()).isTrue();
        assertThat(kept.path("topic").isNull()).isTrue();
        assertThat(read(me.http().perform(get("/api/me/shorts/batches")).andExpect(status().isOk()).andReturn())).isEmpty();
    }

    @Test
    void 맥락에서_바로_쓴_대본도_맥락을_근거로_검사한다() throws Exception {
        Session me = signup(uniqueLogin("ctx"));
        String token = token(me);
        JsonNode r = call(token, "create_shorts_script", Map.of("title", "포트가 겹친 시험", "source_text", CONTEXT, "scenes", COPIED));
        assertThat(text(r)).contains("[막힘]", "원본 글 문장을 그대로").doesNotContain("원본 글이나 맥락 근거가 없어요");
    }

    @Test
    void 연결한_AI는_장면_연출을_이름이나_action_객체로_주고_효과음을_넣을_수_있다() throws Exception {
        Session me = signup(uniqueLogin("visual"));
        String token = token(me);
        List<Map<String, Object>> scenes = List.of(
                Map.of("caption", "빌드 7분, 왜?", "narration", CACHE.get(0).get("narration"), "visual", Map.of("action", "wait"),
                        "effects", List.of(Map.of("sound", "tick", "at", 0.2))),
                Map.of("caption", "캐시가 날아감", "narration", CACHE.get(1).get("narration"), "visual", "shock"),
                Map.of("caption", "7분 → 2분", "narration", CACHE.get(3).get("narration"), "visual", "fast",
                        "effects", List.of(Map.of("sound", "ding", "at", 1.5, "volume", 0.8))));
        JsonNode r = call(token, "create_shorts_script", Map.of("title", "빌드가 느렸던 이유", "source_text", CONTEXT, "scenes", scenes));
        assertThat(r.path("isError").asBoolean()).as(text(r)).isFalse();
        long id = Long.parseLong(jdbc.queryForObject("SELECT max(id)::text FROM shorts WHERE member_id = ?", String.class, me.memberId()));
        JsonNode item = read(me.http().perform(get("/api/me/shorts/" + id)).andExpect(status().isOk()).andReturn());
        assertThat(item.path("scenes").get(0).path("visual").asString()).isEqualTo("wait");
        assertThat(item.path("scenes").get(0).path("effects").get(0).path("sound").asString()).isEqualTo("tick");
        assertThat(item.path("scenes").get(2).path("effects").get(0).path("volume").asDouble()).isEqualTo(0.8);

        JsonNode bad = call(token, "create_shorts_script", Map.of("title", "x", "scenes", List.of(Map.of("caption", "a", "narration", "b", "visual", "explode"))));
        assertThat(text(bad)).contains("연출은", "celebrate");

        // 설명 도식: 모양이 맞으면 그대로 저장하고, type·칸 수·이름표가 틀리면 막는다
        Map<String, Object> flow = Map.of("type", "flow", "items", List.of("빌드", "캐시", Map.of("label", "잠금 파일", "icon", "file", "note", "해시")));
        JsonNode drawn = call(token, "create_shorts_script", Map.of("title", "캐시 키 그림", "source_text", CONTEXT, "scenes", List.of(
                Map.of("caption", "빌드 7분, 왜?", "narration", CACHE.get(0).get("narration")),
                Map.of("caption", "캐시가 날아감", "narration", CACHE.get(1).get("narration"), "diagram", flow),
                Map.of("caption", "7분 → 2분", "narration", CACHE.get(3).get("narration")))));
        assertThat(drawn.path("isError").asBoolean()).as(text(drawn)).isFalse();
        long drawnId = Long.parseLong(jdbc.queryForObject("SELECT max(id)::text FROM shorts WHERE member_id = ?", String.class, me.memberId()));
        JsonNode saved = read(me.http().perform(get("/api/me/shorts/" + drawnId)).andExpect(status().isOk()).andReturn());
        assertThat(saved.path("scenes").get(1).path("diagram").path("items").get(2).path("note").asString()).isEqualTo("해시");
        for (Object wrong : List.of(
                Map.of(), "flow", List.of("a", "b"),
                Map.<String, Object>of("type", "pie", "items", List.of("a", "b")),
                Map.<String, Object>of("type", "flow", "items", List.of("a", "b", "c", "d", "e")),
                Map.<String, Object>of("type", "steps", "items", List.of("하나", "x".repeat(21))),
                Map.<String, Object>of("type", "compare", "left", Map.of("title", "전", "items", List.of("느림"))))) {
            JsonNode r2 = call(token, "create_shorts_script", Map.of("title", "x", "scenes", List.of(Map.of("caption", "a", "narration", "b", "diagram", wrong))));
            assertThat(text(r2)).as(wrong.toString()).contains("설명 그림");
        }
    }

    private static List<Map<String, String>> topics(int n) {
        return java.util.stream.IntStream.rangeClosed(1, n)
                .mapToObj(i -> Map.of("title", "주제 " + i, "hook", "왜 " + i + "번은 느렸을까요?", "scope", "원인과 결과"))
                .toList();
    }

    private String token(Session s) throws Exception {
        return read(s.http().perform(asJson(post("/api/me/tokens"), Map.of("name", "Claude", "scope", "WRITE")))
                .andExpect(status().isCreated()).andReturn()).path("secret").asString();
    }

    private JsonNode call(String token, String tool, Map<String, Object> args) throws Exception {
        return rpc(token, "tools/call", Map.of("name", tool, "arguments", args));
    }

    private static String text(JsonNode result) {
        return result.path("content").get(0).path("text").asString();
    }

    private JsonNode rpc(String token, String method, Object params) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("jsonrpc", "2.0");
        body.put("id", 1);
        body.put("method", method);
        if (params != null) body.put("params", params);
        return read(mvc.perform(post("/api/mcp").contentType(MediaType.APPLICATION_JSON).header("Authorization", "Bearer " + token)
                .content(json.writeValueAsString(body))).andExpect(status().isOk()).andReturn()).path("result");
    }
}
