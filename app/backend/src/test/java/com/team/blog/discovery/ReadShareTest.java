package com.team.blog.discovery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.test.web.servlet.MvcResult;

import tools.jackson.databind.JsonNode;

import com.team.blog.support.IntegrationTest;

/** spec 003 인수 시나리오, docs/10 §9, docs/40 §7, docs/06 R-2b. */
class ReadShareTest extends IntegrationTest {

    long publish(Session s, String title, String content, String visibility) throws Exception {
        long id = read(s.http().perform(asJson(post("/api/posts"), Map.of())).andExpect(status().isCreated()).andReturn()).path("id").asLong();
        s.http().perform(asJson(post("/api/posts/" + id + "/publish"),
                Map.of("title", title, "contentMd", content, "visibility", visibility, "baseVersion", 0, "tags", List.of())))
                .andExpect(status().isOk());
        return id;
    }

    long draft(Session s) throws Exception {
        return read(s.http().perform(asJson(post("/api/posts"), Map.of())).andExpect(status().isCreated()).andReturn()).path("id").asLong();
    }

    String html(MvcResult r) throws Exception {
        return r.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
    }

    // ---------------------------------------------------------------- 상세

    @Test
    void 공개_글은_비회원도_읽고_링크_미리보기_정보가_들어_있다() throws Exception {
        Session s = signup(uniqueLogin("detail"));
        long id = publish(s, "제목 <script>", "첫 문단 \"요약\"\n\n## 소제목\n\n" + ARTICLE_BODY, "PUBLIC");
        MvcResult r = mvc.perform(get("/@" + s.handle() + "/posts/" + id + "?utm_source=x"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("private")))
                .andReturn();
        String page = html(r);
        assertThat(page).contains("<link rel=\"canonical\" href=\"http://localhost:8080/@" + s.handle() + "/posts/" + id + "\">")
                .contains("<meta property=\"og:title\" content=\"제목 &lt;script&gt; - ")
                .contains("og:description\" content=\"첫 문단 &quot;요약&quot;")
                .contains("<h1>제목 &lt;script&gt;</h1>").contains("<h3")
                .doesNotContain("noindex").doesNotContain("<script>")
                .contains("<script id=\"initial-data\" type=\"application/json\">")
                // spec 097: X·슬랙 큰 미리보기와 검색 엔진 구조화 데이터. 제목의 <는 데이터 블록 안에서도 이스케이프한다
                .contains("<meta name=\"twitter:card\" content=\"summary_large_image\">")
                .contains("<script type=\"application/ld+json\">{\"@context\":\"https://schema.org\",\"@type\":\"BlogPosting\"")
                .contains("\"headline\":\"제목 \\u003cscript\\u003e - ")
                .contains("\"author\":{\"@type\":\"Person\",\"name\":");
        mvc.perform(get("/api/posts/" + id)).andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("제목 <script>"))
                .andExpect(jsonPath("$.author.handle").value(s.handle()))
                .andExpect(jsonPath("$.mine").value(false))
                .andExpect(jsonPath("$.owner").doesNotExist());
    }

    @Test
    void 어느_주소에도_맞지_않는_화면_주소는_앱_404_화면이고_API는_JSON이다() throws Exception {
        // spec 097 FR-001: 브라우저로 잘못된 주소에 들어오면 JSON 글자 대신 [홈으로]가 있는 화면을 본다
        String page = html(mvc.perform(get("/this-does-not-exist").header("Accept", "text/html,application/xhtml+xml,*/*;q=0.8"))
                .andExpect(status().isNotFound())
                .andExpect(header().string("Content-Type", org.hamcrest.Matchers.containsString("text/html"))).andReturn());
        assertThat(page).contains("<h1>볼 수 없는 페이지예요</h1>").contains("href=\"/\"").contains("noindex")
                .contains("\"page\":\"notFound\"");
        mvc.perform(get("/this-does-not-exist").header("Accept", "application/json")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        // /api 아래와 파일 주소는 브라우저가 text/html을 달라고 해도 화면이 아니라 JSON 404다
        mvc.perform(get("/api/no-such-endpoint").header("Accept", "text/html")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        mvc.perform(get("/missing.css").header("Accept", "text/html,*/*")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void 없는_글_남의_비공개_임시_글은_모두_같은_찾을_수_없음이다() throws Exception {
        Session owner = signup(uniqueLogin("hide"));
        Session other = signup(uniqueLogin("hideo"));
        long priv = publish(owner, "비밀", "본문", "PRIVATE");
        long draft = draft(owner);
        String nonexist = html(mvc.perform(get("/@" + owner.handle() + "/posts/999999999")).andExpect(status().isNotFound()).andReturn());
        String privPage = html(other.http().perform(get("/@" + owner.handle() + "/posts/" + priv)).andExpect(status().isNotFound()).andReturn());
        String draftPage = html(mvc.perform(get("/@" + owner.handle() + "/posts/" + draft)).andExpect(status().isNotFound()).andReturn());
        assertThat(privPage).isEqualTo(nonexist).isEqualTo(draftPage).contains("noindex").doesNotContain("비밀");
        mvc.perform(get("/@" + owner.handle() + "/posts/abc")).andExpect(status().isNotFound());
        other.http().perform(get("/api/posts/" + priv)).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        // 다른 블로그 주소로 요청해도 판정이 먼저라 작성자가 드러나지 않는다
        mvc.perform(get("/@" + other.handle() + "/posts/" + priv)).andExpect(status().isNotFound());
    }

    @Test
    void 작성자는_비공개_글을_보되_수집되지_않고_저장되지_않는다() throws Exception {
        Session s = signup(uniqueLogin("mypriv"));
        long id = publish(s, "나만", "본문", "PRIVATE");
        String page = html(s.http().perform(get("/@" + s.handle() + "/posts/" + id))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
                .andReturn());
        assertThat(page).contains("noindex").doesNotContain("canonical").contains("<h1>나만</h1>");
        s.http().perform(get("/api/posts/" + id)).andExpect(status().isOk())
                .andExpect(jsonPath("$.mine").value(true))
                .andExpect(jsonPath("$.owner.editing").value(false));
    }

    @Test
    void 다른_블로그_주소나_대문자_주소는_올바른_주소로_영구_이동한다() throws Exception {
        Session s = signup(uniqueLogin("move"));
        Session other = signup(uniqueLogin("moveo"));
        long id = publish(s, "글", "본문", "PUBLIC");
        mvc.perform(get("/@" + other.handle() + "/posts/" + id)).andExpect(status().isMovedPermanently())
                .andExpect(header().string("Location", "/@" + s.handle() + "/posts/" + id));
        mvc.perform(get("/@" + s.handle().toUpperCase() + "/posts/" + id)).andExpect(status().isMovedPermanently())
                .andExpect(header().string("Location", "/@" + s.handle() + "/posts/" + id));
    }

    @Test
    void 작성자가_자기_임시글_주소를_열면_에디터로_간다() throws Exception {
        Session s = signup(uniqueLogin("mydraft"));
        long id = draft(s);
        s.http().perform(get("/@" + s.handle() + "/posts/" + id)).andExpect(status().isFound())
                .andExpect(header().string("Location", "/write/" + id));
    }

    @Test
    void 수정_중인_글은_발행본을_보이고_작성자에게만_수정_중_정보를_준다() throws Exception {
        Session s = signup(uniqueLogin("editing"));
        long id = publish(s, "발행본", "발행 본문", "PUBLIC");
        s.http().perform(asJson(put("/api/posts/" + id), Map.of("title", "고치는 중", "contentMd", "새 본문", "baseVersion", 1)))
                .andExpect(status().isOk());
        assertThat(html(mvc.perform(get("/@" + s.handle() + "/posts/" + id)).andReturn())).contains("발행 본문").doesNotContain("새 본문");
        s.http().perform(get("/api/posts/" + id)).andExpect(jsonPath("$.title").value("발행본"))
                .andExpect(jsonPath("$.owner.editing").value(true));
    }

    @Test
    void DB에_반영_전인_자동_저장만_있어도_작성자에게_수정_중으로_보인다() throws Exception {
        Session s = signup(uniqueLogin("autoedit"));
        long id = publish(s, "발행본", "발행 본문", "PUBLIC");
        s.http().perform(asJson(put("/api/posts/" + id + "/autosave"), Map.of("title", "발행본", "contentMd", "자동 저장 본문", "baseVersion", 1)))
                .andExpect(status().isOk());
        s.http().perform(get("/api/posts/" + id)).andExpect(jsonPath("$.owner.editing").value(true))
                .andExpect(jsonPath("$.owner.editingSavedAt").isNotEmpty());
        mvc.perform(get("/api/posts/" + id)).andExpect(jsonPath("$.owner").doesNotExist());
    }

    @Test
    void 관리자가_숨긴_글은_작성자에게만_보이고_목록에는_없다() throws Exception {
        Session s = signup(uniqueLogin("hidden"));
        long id = publish(s, "숨김", "본문", "PUBLIC");
        jdbc.update("UPDATE post SET hidden_at = now(), hidden_reason = 'SPAM' WHERE id = ?", id);
        mvc.perform(get("/api/posts/" + id)).andExpect(status().isNotFound());
        s.http().perform(get("/api/posts/" + id)).andExpect(status().isOk()).andExpect(jsonPath("$.owner.hidden").value(true));
        assertThat(ids(read(mvc.perform(get("/api/members/" + s.handle() + "/posts")).andReturn()))).doesNotContain(id);
    }

    // ---------------------------------------------------------------- 홈·블로그 목록

    List<Long> ids(JsonNode page) {
        List<Long> ids = new ArrayList<>();
        page.path("items").forEach(n -> ids.add(n.path("id").asLong()));
        return ids;
    }

    @Test
    void 홈은_공개_발행_글만_최초_공개_순으로_9개씩_빈_클릭_없이_보인다() throws Exception {
        Session s = signup(uniqueLogin("feed"));
        List<Long> mine = new ArrayList<>();
        for (int i = 0; i < 18; i++) mine.add(publish(s, "글" + i, "본문" + i, "PUBLIC"));
        long priv = publish(s, "비공개", "x", "PRIVATE");
        long draft = draft(s);
        // 모든 글의 공개 시각을 아주 먼 미래로 옮겨, 다른 테스트의 글보다 앞에 오게 한다(같은 시각이면 id 큰 순)
        Timestamp future = Timestamp.from(Instant.parse("2999-01-01T00:00:00Z"));
        jdbc.update("UPDATE post SET first_public_at = ? WHERE author_id = ? AND first_public_at IS NOT NULL", future, s.memberId());

        JsonNode p1 = read(mvc.perform(get("/api/posts?size=50")).andExpect(status().isOk()).andReturn());
        JsonNode p2 = read(mvc.perform(get("/api/posts").param("cursor", p1.path("nextCursor").asString())).andExpect(status().isOk()).andReturn());
        List<Long> expected = new ArrayList<>(mine);
        java.util.Collections.reverse(expected);
        assertThat(ids(p1)).containsExactlyElementsOf(expected.subList(0, 9));
        assertThat(ids(p2)).containsExactlyElementsOf(expected.subList(9, 18));
        assertThat(ids(p1)).doesNotContain(priv, draft);
        assertThat(p1.path("items").get(0).path("author").path("handle").asString()).isEqualTo(s.handle());
        assertThat(p1.path("items").get(0).has("contentHtml")).isFalse();
        jdbc.update("UPDATE post SET first_public_at = now() WHERE author_id = ? AND first_public_at IS NOT NULL", s.memberId());
    }

    @Test
    void 개인_블로그는_그_사람의_공개_글만_보이고_마지막_페이지에는_다음_커서가_없다() throws Exception {
        Session s = signup(uniqueLogin("blog"));
        List<Long> pub = new ArrayList<>();
        for (int i = 0; i < 9; i++) pub.add(publish(s, "글" + i, i == 0 ? ARTICLE_BODY : "본문", "PUBLIC"));
        publish(s, "비공개", "x", "PRIVATE");
        // 작성자 본인이 봐도 비공개 글은 블로그에 나오지 않는다
        JsonNode page = read(s.http().perform(get("/api/members/" + s.handle() + "/posts")).andExpect(status().isOk()).andReturn());
        assertThat(ids(page)).containsExactlyInAnyOrderElementsOf(pub);
        assertThat(page.path("nextCursor").isNull()).isTrue();
        s.http().perform(get("/api/members/" + s.handle())).andExpect(jsonPath("$.publicPostCount").value(9))
                .andExpect(jsonPath("$.mine").value(true));
        String blogPage = html(mvc.perform(get("/@" + s.handle())).andExpect(status().isOk()).andReturn());
        assertThat(blogPage).contains("<link rel=\"canonical\" href=\"http://localhost:8080/@" + s.handle() + "\">");
        mvc.perform(get("/api/members/zz_nobody_here/posts")).andExpect(status().isNotFound());
        mvc.perform(get("/@zz_nobody_here")).andExpect(status().isNotFound());
    }

    @Test
    void 공개_범위를_껐다_켜도_목록_위치가_그대로다() throws Exception {
        Session s = signup(uniqueLogin("order"));
        long a = publish(s, "먼저", "x", "PUBLIC");
        long b = publish(s, "나중", "x", "PUBLIC");
        s.http().perform(asJson(patch("/api/posts/" + a + "/visibility"), Map.of("visibility", "PRIVATE"))).andExpect(status().isOk());
        s.http().perform(asJson(patch("/api/posts/" + a + "/visibility"), Map.of("visibility", "PUBLIC"))).andExpect(status().isOk());
        assertThat(ids(read(mvc.perform(get("/api/members/" + s.handle() + "/posts")).andReturn()))).containsExactly(b, a);
    }

    @Test
    void 고친_커서나_다른_목록의_커서는_거부한다() throws Exception {
        Session s = signup(uniqueLogin("cursor"));
        for (int i = 0; i < 10; i++) publish(s, "글" + i, "x", "PUBLIC");
        String blogCursor = read(mvc.perform(get("/api/members/" + s.handle() + "/posts")).andReturn()).path("nextCursor").asString();
        mvc.perform(get("/api/posts").param("cursor", blogCursor)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_CURSOR"));
        mvc.perform(get("/api/posts").param("cursor", blogCursor.substring(0, blogCursor.length() - 2) + "AA"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/posts").param("cursor", "not-base64!")).andExpect(status().isBadRequest());
    }

    @Test
    void 탈퇴_신청한_작성자의_글은_목록과_상세에서_사라진다() throws Exception {
        Session s = signup(uniqueLogin("gone"));
        long id = publish(s, "글", "x", "PUBLIC");
        jdbc.update("UPDATE member SET withdrawn_at = ?, status = 'WITHDRAWN' WHERE id = ?",
                Timestamp.from(Instant.now().minus(1, ChronoUnit.MINUTES)), s.memberId());
        mvc.perform(get("/api/posts/" + id)).andExpect(status().isNotFound());
        mvc.perform(get("/@" + s.handle())).andExpect(status().isNotFound());
        assertThat(ids(read(mvc.perform(get("/api/posts")).andReturn()))).doesNotContain(id);
    }

    @Test
    void 공개_목록_쿼리는_공개_목록_인덱스를_쓴다() {
        // 테스트 데이터는 적어서 비용만으로는 어느 인덱스든 고를 수 있다. 전체 읽기·따로 정렬하기를 막으면
        // 조건과 정렬을 모두 맞춘 부분 인덱스만 남는다. 설정과 EXPLAIN은 같은 연결에서 해야 한다
        List<String> plans = jdbc.execute((ConnectionCallback<List<String>>) c -> {
            try (Statement st = c.createStatement()) {
                st.execute("SET enable_seqscan = off");
                st.execute("SET enable_sort = off");
                try {
                    return List.of(explain(st, """
                            SELECT p.id FROM post p JOIN member m ON m.id = p.author_id
                            WHERE p.status = 'PUBLISHED' AND p.visibility = 'PUBLIC' AND p.deleted_at IS NULL AND p.hidden_at IS NULL
                              AND m.withdrawn_at IS NULL ORDER BY p.first_public_at DESC, p.id DESC LIMIT 10"""),
                            explain(st, """
                            SELECT p.id FROM post p JOIN member m ON m.id = p.author_id
                            WHERE p.status = 'PUBLISHED' AND p.visibility = 'PUBLIC' AND p.deleted_at IS NULL AND p.hidden_at IS NULL
                              AND m.withdrawn_at IS NULL AND p.author_id = 1 ORDER BY p.first_public_at DESC, p.id DESC LIMIT 10"""));
                } finally {
                    st.execute("RESET enable_seqscan");
                    st.execute("RESET enable_sort");
                }
            }
        });
        assertThat(plans.get(0)).containsAnyOf("ix_post_feed", "ix_post_blog").doesNotContain("Sort  (");
        assertThat(plans.get(1)).contains("ix_post_blog").doesNotContain("Sort  (");
    }

    private static String explain(Statement st, String sql) throws SQLException {
        StringBuilder plan = new StringBuilder();
        try (ResultSet rs = st.executeQuery("EXPLAIN " + sql)) {
            while (rs.next()) plan.append(rs.getString(1)).append('\n');
        }
        return plan.toString();
    }
}
