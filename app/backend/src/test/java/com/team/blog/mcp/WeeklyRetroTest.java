package com.team.blog.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import tools.jackson.databind.JsonNode;

import com.team.blog.mcp.application.WeeklyRetro;
import com.team.blog.support.IntegrationTest;

/** spec 107: 그 주 AI 일기를 묶은 주간 회고 초안. 사람이 도입·배운 점을 써야 발행되고, AI 도구로는 고치거나 발행할 수 없다 */
class WeeklyRetroTest extends IntegrationTest {
    @Autowired WeeklyRetro retro;

    LocalDate monday() {
        return LocalDate.now(ZoneId.of("Asia/Seoul")).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
    }

    long publishDiary(Session s, LocalDate day, String body) throws Exception {
        String title = day + " 개발 일기";
        long id = read(s.http().perform(asJson(post("/api/posts"), Map.of("title", title, "contentMd", "")))
                .andExpect(status().isCreated()).andReturn()).path("id").asLong();
        s.http().perform(asJson(post("/api/posts/" + id + "/publish"), Map.of("title", title, "contentMd", body,
                        "visibility", "PUBLIC", "baseVersion", 0, "tags", List.of()))
                .header("Idempotency-Key", UUID.randomUUID().toString())).andExpect(status().isOk());
        jdbc.update("UPDATE post SET ai_diary = true WHERE id = ?", id);
        return id;
    }

    org.springframework.test.web.servlet.ResultActions publish(Session s, long id, String content) throws Exception {
        long version = jdbc.queryForObject("SELECT edit_version FROM post WHERE id = ?", Long.class, id);
        String title = jdbc.queryForObject("SELECT title FROM post WHERE id = ?", String.class, id);
        return s.http().perform(asJson(post("/api/posts/" + id + "/publish"), Map.of("title", title, "contentMd", content,
                        "visibility", "PUBLIC", "baseVersion", version, "tags", List.of("회고")))
                .header("Idempotency-Key", UUID.randomUUID().toString()));
    }

    JsonNode mcp(String token, String tool, Map<String, Object> args) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("jsonrpc", "2.0");
        body.put("id", 1);
        body.put("method", "tools/call");
        body.put("params", Map.of("name", tool, "arguments", args));
        return read(mvc.perform(post("/api/mcp").contentType(MediaType.APPLICATION_JSON).header("Authorization", "Bearer " + token)
                .content(json.writeValueAsString(body))).andExpect(status().isOk()).andReturn()).path("result");
    }

    @Test
    void 그_주_일기를_묶은_초안을_한_번만_만들고_사람이_쓴_문단이_없으면_발행되지_않는다() throws Exception {
        Session me = staff(signup(uniqueLogin("retro")));
        LocalDate monday = monday();
        long first = publishDiary(me, monday, "## 배포\n\n월요일에는 배포 스크립트를 고쳤어요.\n\n```sh\n# 주석은 소제목이 아니다\n```");
        publishDiary(me, monday.minusDays(1), "지난주 일기라 빠져야 해요."); // 지난주 일요일

        assertThat(retro.createDrafts()).isPositive();
        Long id = jdbc.queryForObject("SELECT id FROM post WHERE author_id = ? AND retro_week = ?", Long.class, me.memberId(), monday);
        Map<String, Object> row = jdbc.queryForMap("SELECT title, content_md, status, ai_assisted, ai_diary FROM post WHERE id = ?", id);
        assertThat(row.get("title")).isEqualTo(monday + " ~ " + monday.plusDays(6) + " 주간 회고");
        assertThat(row.get("status")).isEqualTo("DRAFT");
        assertThat(row.get("ai_assisted")).isEqualTo(true);
        assertThat(row.get("ai_diary")).isEqualTo(false);
        String md = (String) row.get("content_md");
        assertThat(md).contains("## 도입", "## 배운 점", "### " + monday + " 개발 일기 ([원문](/@" + me.handle() + "/posts/" + first + "))",
                "#### 배포", "# 주석은 소제목이 아니다").doesNotContain("지난주 일기라");

        // 다시 돌려도 같은 주 초안은 하나
        retro.createDrafts();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM post WHERE author_id = ? AND retro_week = ?", Long.class, me.memberId(), monday))
                .isEqualTo(1L);

        // 안내 줄만 있으면 발행 거절
        String refused = publish(me, id, md).andExpect(status().isBadRequest()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(refused).contains("RETRO_NEEDS_HUMAN");

        // 도입만 쓰면 배운 점 때문에 여전히 거절
        String intro = "이번 주에는 검색 수집 규칙을 정리했다. 외부 회원 글을 검수하게 했고, 일기는 수집에서 빼되 링크는 따라가게 했다. ".repeat(3);
        String halfDone = md.replace("## 도입\n\n", "## 도입\n\n" + intro + "\n\n");
        publish(me, id, halfDone).andExpect(status().isBadRequest());

        String learned = "noindex만으로는 부족하고 follow까지 정해야 연결된 글이 발견된다는 것을 배웠다. 다음에는 처음부터 규칙을 한곳에 둔다. ".repeat(3);
        String done = halfDone.replace("## 배운 점\n\n", "## 배운 점\n\n" + learned + "\n\n") + "\n" + "회고 본문을 늘리는 정리 문장. ".repeat(60);
        publish(me, id, done).andExpect(status().isOk());
        String page = browser().perform(get("/@" + me.handle() + "/posts/" + id)).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        assertThat(page).doesNotContain("<meta name=\"robots\"").contains("AI 보조 작성");
    }

    @Test
    void 회고는_AI_도구로_고치거나_발행할_수_없다() throws Exception {
        Session me = signup(uniqueLogin("retromcp"));
        me.http().perform(asJson(put("/api/me/ai-publish"), Map.of("allowed", true))).andExpect(status().isOk());
        String token = read(me.http().perform(asJson(post("/api/me/tokens"), Map.of("name", "Claude Code", "scope", "WRITE")))
                .andExpect(status().isCreated()).andReturn()).path("secret").asString();
        publishDiary(me, monday(), "화요일 일기");
        retro.createDrafts();
        Long id = jdbc.queryForObject("SELECT id FROM post WHERE author_id = ? AND retro_week IS NOT NULL", Long.class, me.memberId());

        JsonNode edit = mcp(token, "update_draft", Map.of("post_id", id, "content_md", "AI가 채운 도입"));
        assertThat(edit.path("isError").asBoolean()).isTrue();
        assertThat(edit.path("content").get(0).path("text").asString()).contains("사람이 도입·배운 점을 직접 써서");
        JsonNode pub = mcp(token, "publish_post", Map.of("post_id", id));
        assertThat(pub.path("isError").asBoolean()).isTrue();
        assertThat(jdbc.queryForObject("SELECT status FROM post WHERE id = ?", String.class, id)).isEqualTo("DRAFT");
    }

    @Test
    void MCP로_만들고_고치고_발행한_글은_AI_보조_작성으로_남고_웹으로_쓴_글은_아니다() throws Exception {
        Session me = staff(signup(uniqueLogin("aiassist")));
        String token = read(me.http().perform(asJson(post("/api/me/tokens"), Map.of("name", "Claude Code", "scope", "WRITE")))
                .andExpect(status().isCreated()).andReturn()).path("secret").asString();
        JsonNode made = mcp(token, "create_draft", Map.of("title", "AI 초안", "content_md", "본문"));
        long aiPost = Long.parseLong(made.path("content").get(0).path("text").asString().replaceAll("(?s).*글 번호 (\\d+).*", "$1"));
        assertThat(jdbc.queryForObject("SELECT ai_assisted FROM post WHERE id = ?", Boolean.class, aiPost)).isTrue();

        long webPost = read(me.http().perform(asJson(post("/api/posts"), Map.of("title", "직접 쓴 글", "contentMd", "본문")))
                .andExpect(status().isCreated()).andReturn()).path("id").asLong();
        assertThat(jdbc.queryForObject("SELECT ai_assisted FROM post WHERE id = ?", Boolean.class, webPost)).isFalse();
        // 사람이 쓴 글이라도 AI가 고치면 표시한다
        mcp(token, "update_draft", Map.of("post_id", webPost, "content_md", "AI가 다듬은 본문"));
        assertThat(jdbc.queryForObject("SELECT ai_assisted FROM post WHERE id = ?", Boolean.class, webPost)).isTrue();

        JsonNode api = read(browser().perform(get("/api/posts/" + aiPost)).andReturn());
        assertThat(api.path("aiAssisted").isMissingNode() || api.path("aiAssisted").asBoolean()).isTrue();
    }
}
