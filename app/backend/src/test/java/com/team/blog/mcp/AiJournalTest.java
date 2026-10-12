package com.team.blog.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import tools.jackson.databind.JsonNode;

import com.team.blog.mcp.application.AiJournal;
import com.team.blog.support.IntegrationTest;

/**
 * spec 061: AI 글 제안(propose_post)과 자정 일기(add_note + 매일 00:00 묶기).
 * spec 071: 제안 알림, 일기 시각 고르기, AI 발행을 허용한 회원의 일기 자동 발행.
 */
class AiJournalTest extends IntegrationTest {
    static final ZoneId KST = ZoneId.of("Asia/Seoul");

    @Autowired
    AiJournal journal;

    String token(Session s) throws Exception {
        return read(s.http().perform(asJson(post("/api/me/tokens"), Map.of("name", "Claude Code", "scope", "WRITE")))
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

    List<String> toolNames(String token) throws Exception {
        return read(rpc(token, "tools/list", Map.of()).andExpect(status().isOk()).andReturn()).path("result").path("tools").findValuesAsString("name");
    }

    static String text(JsonNode result) {
        return result.path("content").get(0).path("text").asString();
    }

    void setDiary(Session s, boolean on) throws Exception {
        JsonNode r = read(s.http().perform(asJson(put("/api/me/ai-diary"), Map.of("enabled", on))).andExpect(status().isOk()).andReturn());
        assertThat(r.path("enabled").asBoolean()).isEqualTo(on);
    }

    @Test
    void sentenceAddsPeriodPastClosingBrackets() {
        assertThat(AiJournal.sentence("Redis로 바꿨어요 (색인)")).isEqualTo("Redis로 바꿨어요 (색인).");
        assertThat(AiJournal.sentence("\"됐다!\"")).isEqualTo("\"됐다!\"");
        assertThat(AiJournal.sentence("(빨라졌어요.)")).isEqualTo("(빨라졌어요.)");
        assertThat(AiJournal.sentence("고쳤어요")).isEqualTo("고쳤어요.");
        assertThat(AiJournal.sentence("  ")).isEmpty();
    }

    @Test
    void AI가_제안한_글은_내_글_관리에서_임시글로_만들거나_넘긴다() throws Exception {
        Session me = signup(uniqueLogin("aiprop"));
        String t = token(me);
        assertThat(toolNames(t)).contains("propose_post", "list_post_proposals").doesNotContain("add_note");

        JsonNode r = call(t, "propose_post", Map.of("title", "  Redis 분산 락으로 중복 실행 막기 ", "scope",
                "- 왜 두 번 돌았나\n- SET NX와 만료\n- 뺄 것: Redisson 비교", "tags", List.of("redis", "Spring")));
        assertThat(r.path("isError").asBoolean()).as(text(r)).isFalse();
        // 같은 제목으로 다시 제안하면 새로 만들지 않고 범위를 바꾼다
        call(t, "propose_post", Map.of("title", "Redis 분산 락으로 중복 실행 막기", "scope", "- 바뀐 범위", "tags", List.of("redis")));
        call(t, "propose_post", Map.of("title", "넘길 제안", "scope", "- 아무거나"));
        assertThat(call(t, "propose_post", Map.of("title", "", "scope", "x")).path("isError").asBoolean()).isTrue();

        JsonNode list = read(me.http().perform(get("/api/me/ai-proposals")).andExpect(status().isOk()).andReturn());
        assertThat(list).hasSize(2);
        JsonNode redis = list.get(1);
        assertThat(redis.path("title").asString()).isEqualTo("Redis 분산 락으로 중복 실행 막기");
        assertThat(redis.path("scope").asString()).isEqualTo("- 바뀐 범위");
        long redisId = redis.path("id").asLong();
        long skipId = list.get(0).path("id").asLong();

        // 남이 정할 수 없다
        Session other = signup(uniqueLogin("aiprop2"));
        other.http().perform(post("/api/me/ai-proposals/" + redisId + "/draft").with(csrf())).andExpect(status().isNotFound());

        // [임시글로 만들기]는 두 번 눌러도 임시글 하나
        long postId = read(me.http().perform(post("/api/me/ai-proposals/" + redisId + "/draft").with(csrf())).andExpect(status().isOk()).andReturn())
                .path("postId").asLong();
        long again = read(me.http().perform(post("/api/me/ai-proposals/" + redisId + "/draft").with(csrf())).andExpect(status().isOk()).andReturn())
                .path("postId").asLong();
        assertThat(again).isEqualTo(postId);
        assertThat(jdbc.queryForObject("SELECT title FROM post WHERE id = ?", String.class, postId)).isEqualTo("Redis 분산 락으로 중복 실행 막기");
        assertThat(jdbc.queryForObject("SELECT content_md FROM post WHERE id = ?", String.class, postId)).contains("## 쓸 범위").contains("- 바뀐 범위");
        assertThat(jdbc.queryForObject("SELECT tags FROM post_ai_hint WHERE post_id = ?", String.class, postId)).isEqualTo("redis");

        me.http().perform(post("/api/me/ai-proposals/" + skipId + "/dismiss").with(csrf())).andExpect(status().isNoContent());
        assertThat(read(me.http().perform(get("/api/me/ai-proposals")).andExpect(status().isOk()).andReturn())).isEmpty();

        // AI는 임시글로 만든 제안을 보고 그 글을 채운다. 같은 제안으로 새 글을 또 만들지 않는다
        String seen = text(call(t, "list_post_proposals", Map.of()));
        assertThat(seen).contains("임시글 " + postId + "번으로 만듦").doesNotContain("넘길 제안");
        JsonNode dup = call(t, "create_draft", Map.of("title", "x", "content_md", "y", "proposal_id", redisId));
        assertThat(dup.path("isError").asBoolean()).isTrue();
        assertThat(text(dup)).contains("이미 임시글 " + postId + "번");

        // 넘긴 제안으로는 글을 만들지 않는다
        int before = jdbc.queryForObject("SELECT count(*) FROM post WHERE author_id = ?", Integer.class, me.memberId());
        JsonNode dismissed = call(t, "create_draft", Map.of("title", "x", "content_md", "y", "proposal_id", skipId));
        assertThat(dismissed.path("isError").asBoolean()).isTrue();
        assertThat(text(dismissed)).contains("넘긴 제안");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM post WHERE author_id = ?", Integer.class, me.memberId())).isEqualTo(before);

        // 대화에서 바로 쓰면 create_draft의 proposal_id로 제안이 닫힌다
        call(t, "propose_post", Map.of("title", "바로 쓸 제안", "scope", "- 범위"));
        long direct = journal.proposals(me.memberId(), false).getFirst().id();
        JsonNode made = call(t, "create_draft", Map.of("title", "바로 쓸 제안", "content_md", "본문", "proposal_id", direct));
        assertThat(made.path("isError").asBoolean()).as(text(made)).isFalse();
        assertThat(jdbc.queryForObject("SELECT status FROM ai_post_proposal WHERE id = ?", String.class, direct)).isEqualTo("DRAFTED");
        assertThat(jdbc.queryForObject("SELECT post_id FROM ai_post_proposal WHERE id = ?", Long.class, direct)).isNotNull();
    }

    @Test
    void 일기를_켜야_메모를_남기고_자정에_날짜별_일기_임시글로_묶는다() throws Exception {
        Session me = signup(uniqueLogin("aidiary"));
        String t = token(me);
        // 꺼져 있으면 도구가 보이지 않고, 이름을 알아도 부를 수 없다
        assertThat(read(me.http().perform(get("/api/me/ai-diary")).andExpect(status().isOk()).andReturn()).path("enabled").asBoolean()).isFalse();
        assertThat(toolNames(t)).doesNotContain("add_note");
        JsonNode off = call(t, "add_note", Map.of("content", "x"));
        assertThat(off.path("isError").asBoolean()).isTrue();
        assertThat(text(off)).contains("AI 일기 쓰기");

        setDiary(me, true);
        assertThat(toolNames(t)).contains("add_note");
        JsonNode init = read(rpc(t, "initialize", Map.of("protocolVersion", "2025-06-18")).andReturn()).path("result");
        assertThat(init.path("instructions").asString()).contains("add_note").contains("propose_post").contains("포트폴리오용").contains("제 역할");

        JsonNode ok = call(t, "add_note", Map.of("topic", "검색", "content", "하이브리드 검색 가중치를 0.7로 정했다", "tags", List.of("search")));
        assertThat(ok.path("isError").asBoolean()).as(text(ok)).isFalse();
        assertThat(text(ok)).contains("오늘 1개").contains("다음 자정").contains("임시글");

        // 오늘 남긴 메모는 오늘 자정까지 기다린다. 어제·그제 메모는 날짜마다 일기 하나
        LocalDate today = LocalDate.now(KST);
        note(me.memberId(), "검색", "형태소 분석기를 바꿨다", "search", today.minusDays(1).atTime(10, 5));
        note(me.memberId(), "검색", "  색인을 다시 만드니 훨씬 빨라졌다!", "", today.minusDays(1).atTime(11, 0));
        note(me.memberId(), null, "리뷰 답변을 정리했다", "", today.minusDays(1).atTime(9, 0));
        note(me.memberId(), "배포", "롤백 스크립트를 고쳤다\n둘째 줄", "k8s,search", today.minusDays(1).atTime(15, 30));
        note(me.memberId(), "배포", "카나리 비율을 정했다", "", today.minusDays(2).atTime(23, 59));

        assertThat(journal.compileDiariesAt(midnight())).isGreaterThanOrEqualTo(2);
        List<Map<String, Object>> posts = jdbc.queryForList("SELECT id, title, content_md, status FROM post WHERE author_id = ? ORDER BY id",
                me.memberId());
        assertThat(posts).hasSize(2);
        assertThat(posts.get(0)).containsEntry("title", today.minusDays(2) + " 개발 일기");
        Map<String, Object> y = posts.get(1);
        assertThat(y).containsEntry("title", today.minusDays(1) + " 개발 일기");
        assertThat(y).containsEntry("status", "DRAFT");
        assertThat((String) y.get("content_md")).isEqualTo("""
                ## 검색

                형태소 분석기를 바꿨다. 색인을 다시 만드니 훨씬 빨라졌다!

                ## 배포

                롤백 스크립트를 고쳤다
                둘째 줄.

                ## 그 밖에

                리뷰 답변을 정리했다.
                """);
        assertThat(jdbc.queryForObject("SELECT tags FROM post_ai_hint WHERE post_id = ?", String.class, y.get("id"))).isEqualTo("search,k8s");
        // 묶은 메모는 지우고 오늘 메모는 남긴다. 다시 돌아도 일기를 또 만들지 않는다
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ai_note WHERE member_id = ?", Integer.class, me.memberId())).isEqualTo(1);
        journal.compileDiariesAt(midnight());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM post WHERE author_id = ?", Integer.class, me.memberId())).isEqualTo(2);

        // 끄면 남은 메모도 지운다
        setDiary(me, false);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ai_note WHERE member_id = ?", Integer.class, me.memberId())).isZero();
    }

    @Test
    void 일기를_끈_회원과_메모가_없는_날은_일기를_만들지_않는다() throws Exception {
        Session off = signup(uniqueLogin("aidoff"));
        note(off.memberId(), "x", "켜 두지 않은 회원의 메모", "", LocalDate.now(KST).minusDays(1).atTime(12, 0));
        Session quiet = signup(uniqueLogin("aidq"));
        setDiary(quiet, true);
        journal.compileDiariesAt(midnight());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM post WHERE author_id IN (?, ?)", Integer.class, off.memberId(), quiet.memberId())).isZero();
    }

    @Test
    void 자정_작업이_동시에_돌아도_일기는_하나() throws Exception {
        Session me = signup(uniqueLogin("aidcc"));
        setDiary(me, true);
        for (int i = 0; i < 5; i++) note(me.memberId(), "동시", "메모 " + i, "", LocalDate.now(KST).minusDays(1).atTime(8, i));
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            List<Callable<Integer>> jobs = new ArrayList<>();
            for (int i = 0; i < 4; i++) jobs.add(() -> journal.compileDiariesAt(midnight()));
            for (Future<Integer> f : pool.invokeAll(jobs)) f.get();
        } finally {
            pool.shutdown();
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM post WHERE author_id = ?", Integer.class, me.memberId())).isEqualTo(1);
        assertThat((String) jdbc.queryForObject("SELECT content_md FROM post WHERE author_id = ?", String.class, me.memberId()))
                .contains("메모 0").contains("메모 4");
    }

    /** 오늘 0시 정각(KST) 조금 뒤. 기본 일기 시각(0시) 회원이 묶인다 */
    static Instant midnight() {
        return LocalDate.now(KST).atStartOfDay(KST).toInstant().plusSeconds(3);
    }

    static Instant at(LocalDate day, int hour) {
        return day.atTime(hour, 0, 2).atZone(KST).toInstant();
    }

    @Test
    void 일기_시각을_고르면_그_시각에_전날_그_시각부터의_메모를_묶는다() throws Exception {
        Session me = signup(uniqueLogin("aidhr"));
        JsonNode r = read(me.http().perform(asJson(put("/api/me/ai-diary"), Map.of("enabled", true, "hour", 22))).andExpect(status().isOk()).andReturn());
        assertThat(r.path("hour").asInt()).isEqualTo(22);
        me.http().perform(asJson(put("/api/me/ai-diary"), Map.of("enabled", true, "hour", 24))).andExpect(status().isBadRequest());
        // 시각을 빼고 켜고 끄면 고른 시각은 그대로
        setDiary(me, true);
        assertThat(read(me.http().perform(get("/api/me/ai-diary")).andReturn()).path("hour").asInt()).isEqualTo(22);
        String t = token(me);
        assertThat(text(call(t, "add_note", Map.of("content", "지금 메모")))).contains("다음 오후 10시");

        LocalDate today = LocalDate.now(KST).minusDays(1); // 오늘 메모(add_note)와 섞이지 않게 하루 앞을 "오늘"로 삼는다
        note(me.memberId(), "a", "그제 밤 23시", "", today.minusDays(1).atTime(23, 0));
        note(me.memberId(), "a", "어제 21시", "", today.atTime(21, 0));
        note(me.memberId(), "a", "어제 22시 넘어", "", today.atTime(22, 30));

        // 다른 시각에는 이 회원을 묶지 않는다
        assertThat(journal.compileDiariesAt(at(today, 0))).isZero();
        journal.compileDiariesAt(at(today, 22));
        List<Map<String, Object>> posts = jdbc.queryForList("SELECT title, content_md FROM post WHERE author_id = ? ORDER BY id", me.memberId());
        // 22시 일기: 전날 22시 ~ 오늘 22시가 오늘 일기
        assertThat(posts).hasSize(1);
        assertThat(posts.get(0)).containsEntry("title", today + " 개발 일기");
        assertThat((String) posts.get(0).get("content_md")).contains("그제 밤 23시").contains("어제 21시").doesNotContain("22시 넘어");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ai_note WHERE member_id = ?", Integer.class, me.memberId())).isEqualTo(2);
    }

    @Test
    void 새벽_시각을_고르면_새벽_작업은_전날_일기에_들어간다() throws Exception {
        Session me = signup(uniqueLogin("aidhr6"));
        me.http().perform(asJson(put("/api/me/ai-diary"), Map.of("enabled", true, "hour", 6))).andExpect(status().isOk());
        LocalDate day = LocalDate.now(KST).minusDays(2);
        note(me.memberId(), null, "밤샘 작업", "", day.plusDays(1).atTime(2, 0));
        note(me.memberId(), null, "낮 작업", "", day.atTime(14, 0));
        journal.compileDiariesAt(at(day.plusDays(1), 6));
        assertThat(jdbc.queryForList("SELECT title FROM post WHERE author_id = ?", String.class, me.memberId()))
                .containsExactly(day + " 개발 일기");
    }

    @Test
    void AI_발행을_허용한_회원의_일기는_바로_발행된다() throws Exception {
        Session me = signup(uniqueLogin("aidpub"));
        setDiary(me, true);
        jdbc.update("UPDATE member SET ai_publish_allowed = true WHERE id = ?", me.memberId());
        note(me.memberId(), "배포", "카나리 배포를 마쳤다", "k8s", LocalDate.now(KST).minusDays(1).atTime(11, 0));
        String t = token(me);
        JsonNode init = read(rpc(t, "initialize", Map.of("protocolVersion", "2025-06-18")).andReturn()).path("result");
        assertThat(init.path("instructions").asString()).contains("바로 발행돼요");
        journal.compileDiariesAt(midnight());
        Map<String, Object> p = jdbc.queryForMap("SELECT status, visibility FROM post WHERE author_id = ?", me.memberId());
        assertThat(p).containsEntry("status", "PUBLISHED");
        assertThat(jdbc.queryForList("SELECT t.name FROM post_tag pt JOIN tag t ON t.id = pt.tag_id JOIN post x ON x.id = pt.post_id WHERE x.author_id = ?",
                String.class, me.memberId())).containsExactly("k8s");
    }

    @Test
    void 발행된_일기는_블로그에_보이되_검색_수집_사이트맵_광고에서_빠진다() throws Exception {
        Session me = signup(uniqueLogin("aidseo"));
        setDiary(me, true);
        jdbc.update("UPDATE member SET ai_publish_allowed = true WHERE id = ?", me.memberId());
        note(me.memberId(), "검색", "일기는 검색 수집에서 빼기로 했다", "", LocalDate.now(KST).minusDays(1).atTime(10, 0));
        journal.compileDiariesAt(midnight());
        long diary = jdbc.queryForObject("SELECT id FROM post WHERE author_id = ? AND status = 'PUBLISHED'", Long.class, me.memberId());
        assertThat(jdbc.queryForObject("SELECT ai_diary FROM post WHERE id = ?", Boolean.class, diary)).isTrue();

        String page = mvc.perform(get("/@" + me.handle() + "/posts/" + diary)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(page).contains("일기는 검색 수집에서 빼기로 했다").contains("noindex").doesNotContain("adsbygoogle");
        String sitemap = mvc.perform(get("/sitemap.xml")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(sitemap).doesNotContain("/posts/" + diary + "<");
        // 블로그 목록에는 그대로 보인다
        assertThat(mvc.perform(get("/api/members/" + me.handle() + "/posts")).andReturn().getResponse()
                .getContentAsString(java.nio.charset.StandardCharsets.UTF_8)).contains("\"id\":" + diary);
    }

    @Test
    void 비밀값이_보이는_일기는_발행하지_않고_메모도_받지_않는다() throws Exception {
        Session me = signup(uniqueLogin("aidsec"));
        setDiary(me, true);
        jdbc.update("UPDATE member SET ai_publish_allowed = true WHERE id = ?", me.memberId());
        String t = token(me);
        // 새 메모는 MCP에서 바로 거절된다
        JsonNode r = read(rpc(t, "tools/call", Map.of("name", "add_note", "arguments",
                Map.of("topic", "배포", "content", "토큰 ghp_" + "a".repeat(36) + " 로 배포했어요"))).andReturn()).path("result");
        assertThat(r.path("isError").asBoolean()).isTrue();
        assertThat(r.path("content").get(0).path("text").asString()).contains("GitHub 토큰").doesNotContain("ghp_");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ai_note WHERE member_id = ?", Integer.class, me.memberId())).isZero();
        // 이미 쌓인 메모에 섞여 있으면 일기는 임시글로 남는다
        note(me.memberId(), "배포", "DB 주소 postgres://app:s3cretPw@db:5432/blog 로 붙었다", "k8s", LocalDate.now(KST).minusDays(1).atTime(11, 0));
        journal.compileDiariesAt(midnight());
        assertThat(jdbc.queryForObject("SELECT status FROM post WHERE author_id = ?", String.class, me.memberId())).isEqualTo("DRAFT");
    }

    @Test
    void AI가_새로_제안하면_알림이_가고_같은_제목을_고쳐_쓰면_다시_알리지_않는다() throws Exception {
        Session me = signup(uniqueLogin("aipn"));
        String t = token(me);
        call(t, "propose_post", Map.of("title", "알림 받을 제안", "scope", "- 범위"));
        call(t, "propose_post", Map.of("title", "알림 받을 제안", "scope", "- 바뀐 범위"));
        JsonNode items = read(me.http().perform(get("/api/me/notifications")).andExpect(status().isOk()).andReturn()).path("items");
        assertThat(items).hasSize(1);
        JsonNode n = items.get(0);
        assertThat(n.path("type").asString()).isEqualTo("AI_PROPOSAL");
        assertThat(n.path("proposal").path("title").asString()).isEqualTo("알림 받을 제안");
        assertThat(n.path("link").asString()).isEqualTo("/manage/posts?tab=drafts#ai-proposals");

        // 끈 회원에게는 보내지 않는다
        me.http().perform(asJson(put("/api/me/notification-settings"), Map.of("muted", List.of("AI_PROPOSAL")))).andExpect(status().isOk());
        call(t, "propose_post", Map.of("title", "조용한 제안", "scope", "- 범위"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notification WHERE receiver_id = ? AND type = 'AI_PROPOSAL'", Integer.class,
                me.memberId())).isEqualTo(1);
        // 탈퇴 정리로 제안을 지우면 그 알림도 지운다
        journal.deleteAll(me.memberId());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notification WHERE receiver_id = ? AND type = 'AI_PROPOSAL'", Integer.class,
                me.memberId())).isZero();
    }

    @Test
    void 메모_한도는_일기_하나가_묶는_24시간에_센다() throws Exception {
        Session me = signup(uniqueLogin("aidlim"));
        // 다음 정각을 일기 시각으로 고르면 지금 구간은 23시간쯤 전에 시작해 대개 달력 날짜를 넘는다
        int hour = (java.time.LocalDateTime.now(KST).getHour() + 1) % 24;
        me.http().perform(asJson(put("/api/me/ai-diary"), Map.of("enabled", true, "hour", hour))).andExpect(status().isOk());
        java.time.LocalDateTime inWindow = java.time.LocalDateTime.now(KST).minusHours(22);
        for (int i = 0; i < AiJournal.NOTES_PER_DAY; i++) note(me.memberId(), "a", "메모 " + i, "", inWindow);
        assertThat(text(call(token(me), "add_note", Map.of("content", "하나 더")))).contains(AiJournal.NOTES_PER_DAY + "개까지");
        // 구간 경계: 끝은 다음 hour시, 시작은 그 24시간 전
        LocalDate day = LocalDate.of(2026, 10, 9);
        Instant at = day.atTime(22, 59).atZone(KST).toInstant();
        assertThat(AiJournal.diaryWindowStart(at, 22)).isEqualTo(day.atTime(22, 0).atZone(KST).toInstant());
        assertThat(AiJournal.diaryWindowStart(at, 6)).isEqualTo(day.atTime(6, 0).atZone(KST).toInstant());
    }

    void note(long memberId, String topic, String content, String tags, java.time.LocalDateTime at) {
        Instant when = at.atZone(KST).toInstant();
        jdbc.update("INSERT INTO ai_note (member_id, topic, content, tags, created_at) VALUES (?, ?, ?, ?, ?)",
                memberId, topic, content, tags, Timestamp.from(when));
    }
}
