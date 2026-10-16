package com.team.blog.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;

import com.team.blog.support.Browser;
import com.team.blog.support.IntegrationTest;

/**
 * spec 014 인수 시나리오 (docs/33 §7 공통 완료 기준 1~7). 시험 설정은 최근창을 5개로 줄여(application-test.yml)
 * 최근창에서 끝나는 경로와 인덱스 후보로 넘어가는 경로를 함께 탄다.
 */
class SearchTest extends IntegrationTest {

    long publish(Session s, String title, String body, String visibility, List<String> tags) throws Exception {
        long id = read(s.http().perform(asJson(post("/api/posts"), Map.of("title", title, "contentMd", body)))
                .andExpect(status().isCreated()).andReturn()).path("id").asLong();
        s.http().perform(asJson(post("/api/posts/" + id + "/publish"), Map.of("title", title, "contentMd", body,
                        "visibility", visibility, "baseVersion", 0, "tags", tags))
                .header("Idempotency-Key", UUID.randomUUID().toString())).andExpect(status().isOk());
        return id;
    }

    long publish(Session s, String title, String body) throws Exception {
        return publish(s, title, body, "PUBLIC", List.of());
    }

    /** 단계 안 최신순을 확인하려고 처음 공개된 시각을 정해 둔다. */
    void publicAt(long id, String at) {
        jdbc.update("UPDATE post SET first_public_at = ?::timestamptz, published_at = ?::timestamptz WHERE id = ?", at, at, id);
    }

    JsonNode search(String query) throws Exception {
        return search(browser(), "/api/search/posts?q=" + enc(query));
    }

    JsonNode search(Browser b, String url) throws Exception {
        return read(b.perform(uri(url)).andExpect(status().isOk()).andReturn());
    }

    List<Long> ids(JsonNode page) {
        List<Long> ids = new ArrayList<>();
        page.path("items").forEach(i -> ids.add(i.path("id").asLong()));
        return ids;
    }

    /** [더 보기]로 끝까지 넘긴 번호 목록. */
    List<Long> all(String url) throws Exception {
        List<Long> ids = new ArrayList<>();
        Browser b = browser();
        JsonNode page = search(b, url);
        ids.addAll(ids(page));
        int guard = 0;
        while (!page.path("nextCursor").isNull() && page.has("nextCursor") && guard++ < 20) {
            page = search(b, url + "&cursor=" + page.path("nextCursor").asString());
            assertThat(page.path("items").size()).as("빈 결과를 받는 클릭이 없다 (FR-015)").isPositive();
            ids.addAll(ids(page));
        }
        return ids;
    }

    /** 이미 인코딩한 주소를 그대로 보낸다 (문자열 템플릿은 %를 한 번 더 인코딩한다). */
    static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder uri(String url) {
        return get(java.net.URI.create(url));
    }

    static String enc(String s) {
        return java.net.URLEncoder.encode(s, java.nio.charset.StandardCharsets.UTF_8).replace("+", "%20");
    }

    @Test
    void 공개_발행_글만_나오고_3글자_일부로_본문까지_찾는다() throws Exception {
        Session a = signup(uniqueLogin("sra")), friend = signup(uniqueLogin("srb")), gone = signup(uniqueLogin("src"));
        a.http().perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/me/friends/" + friend.handle())
                .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf())).andExpect(status().isOk());
        friend.http().perform(post("/api/me/friends/" + a.handle() + "/accept")
                .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf())).andExpect(status().isOk());

        long pub = publish(a, "정리 노트", "스프링 트랜잭션전파 이야기");
        long priv = publish(a, "비공개", "트랜잭션전파 비공개", "PRIVATE", List.of());
        long friends = publish(a, "친구", "트랜잭션전파 친구", "FRIENDS", List.of());
        long trashed = publish(a, "휴지통", "트랜잭션전파 휴지통");
        long hidden = publish(a, "숨김", "트랜잭션전파 숨김");
        long withdrawn = publish(gone, "탈퇴", "트랜잭션전파 탈퇴");
        long draft = read(a.http().perform(asJson(post("/api/posts"), Map.of("title", "임시 트랜잭션전파", "contentMd", "트랜잭션전파")))
                .andReturn()).path("id").asLong();
        jdbc.update("UPDATE post SET deleted_at = now() WHERE id = ?", trashed);
        jdbc.update("UPDATE post SET hidden_at = now(), hidden_reason = 'SPAM' WHERE id = ?", hidden);
        jdbc.update("UPDATE member SET withdrawn_at = now(), status = 'WITHDRAWN' WHERE id = ?", gone.memberId());

        // 친구가 검색해도, 작성자 본인이 검색해도 공개 글만
        for (Browser b : List.of(browser(), friend.http(), a.http())) {
            JsonNode r = search(b, "/api/search/posts?q=" + enc("트랜잭션전"));
            assertThat(ids(r)).containsExactly(pub).doesNotContain(priv, friends, trashed, hidden, withdrawn, draft);
            assertThat(r.path("notice").isNull() || !r.has("notice")).isTrue();
        }
        // 블로그 안 검색: 그 작성자의 공개 글만
        assertThat(ids(search(a.http(), "/api/search/posts?blog=" + a.handle() + "&q=" + enc("트랜잭션전")))).containsExactly(pub);
        assertThat(ids(search(browser(), "/api/search/posts?blog=" + friend.handle() + "&q=" + enc("트랜잭션전")))).isEmpty();
        browser().perform(uri("/api/search/posts?blog=no-such-blog-x&q=abc")).andExpect(status().isNotFound());
    }

    @Test
    void 두_글자는_제목_태그만_찾고_한_글자는_무시한다() throws Exception {
        Session a = signup(uniqueLogin("srd"));
        long title1 = publish(a, "롬복 설정", "본문");
        long title2 = publish(a, "Lombok 롬복 정리", "본문");
        long tag1 = publish(a, "어노테이션", "본문", "PUBLIC", List.of("롬복"));
        long bodyOnly = publish(a, "자바 이야기", "롬복을 쓰면 편하다");
        publicAt(title1, "2026-01-02T00:00:00Z");
        publicAt(title2, "2026-01-01T00:00:00Z");
        publicAt(tag1, "2026-01-05T00:00:00Z");

        JsonNode r = search("롬복 a");
        assertThat(ids(r)).containsExactly(title1, title2, tag1).doesNotContain(bodyOnly);
        assertThat(r.path("notice").asString()).isEqualTo("TWO_CHAR_TITLE_TAG_ONLY");
        assertThat(r.path("query").asString()).isEqualTo("롬복 a");

        JsonNode none = search(" a  b ");
        assertThat(none.path("items").size()).isZero();
        assertThat(none.path("notice").asString()).isEqualTo("TOO_SHORT");
    }

    @Test
    void 여러_단어는_모두_있어야_하고_특수기호는_글자_그대로다() throws Exception {
        Session a = signup(uniqueLogin("sre"));
        long both = publish(a, "트랜잭션격리 정리", "본문");
        publish(a, "트랜잭션격리 노트", "본문");
        long percent = publish(a, "할인", "오늘만 100% 할인합니다");
        publish(a, "할인2", "1000원 할인합니다");
        long snake = publish(a, "naming", "변수는 snake_case 로 쓴다");
        publish(a, "naming2", "변수는 snakeXcase 로 쓴다");
        long backslash = publish(a, "경로", "C:\\temp\\dir 경로");

        assertThat(ids(search("트랜잭션격리 정리"))).containsExactly(both);
        assertThat(ids(search("100%"))).containsExactly(percent);
        assertThat(ids(search("snake_case"))).containsExactly(snake);
        assertThat(ids(search("temp\\dir"))).containsExactly(backslash);
        // 대소문자 무시
        assertThat(ids(search("SNAKE_CASE"))).containsExactly(snake);
    }

    @Test
    void 결과_문장은_이스케이프하고_검색어만_강조한다() throws Exception {
        Session a = signup(uniqueLogin("srf"));
        String body = "앞부분 ".repeat(20) + "<script>alert(1)</script> 강조어휘검사 <img src=x onerror=alert(2)> 뒤 " + "끝부분 ".repeat(20);
        publish(a, "제목", body);
        long codeOnly = publish(a, "코드", "설명\n\n```java\nint 코드안단어 = 1;\n```\n");

        JsonNode hit = search("강조어휘검사").path("items").get(0);
        String snippet = hit.path("snippetHtml").asString();
        assertThat(snippet).contains("<mark>강조어휘검사</mark>").contains("&lt;script&gt;")
                .startsWith("…").endsWith("…");
        assertThat(snippet.replace("<mark>", "").replace("</mark>", "")).doesNotContain("<", ">");
        assertThat(hit.has("excerpt")).isFalse();

        // 코드 블록 안 글자도 찾고 문장에 보인다 (FR-010)
        JsonNode code = search("코드안단어").path("items").get(0);
        assertThat(code.path("id").asLong()).isEqualTo(codeOnly);
        assertThat(code.path("snippetHtml").asString()).contains("<mark>코드안단어</mark>");
    }

    @Test
    void 관련도순은_단계_순서이고_끝까지_넘겨도_중복_누락이_없다() throws Exception {
        Session a = signup(uniqueLogin("srg"));
        List<Long> body = new ArrayList<>();
        for (int i = 0; i < 14; i++) {
            long id = publish(a, "글 " + i, "본문에 희귀어사전 있음 " + i);
            publicAt(id, "2026-02-%02dT00:00:00Z".formatted(i + 1));
            body.add(0, id); // 최신이 앞
        }
        long tagged = publish(a, "태그 글", "본문", "PUBLIC", List.of("희귀어사전"));
        long titled = publish(a, "희귀어사전 제목", "본문");
        publicAt(tagged, "2026-01-01T00:00:00Z");
        publicAt(titled, "2025-01-01T00:00:00Z");

        List<Long> relevance = all("/api/search/posts?q=" + enc("희귀어사전"));
        List<Long> expected = new ArrayList<>(List.of(titled, tagged));
        expected.addAll(body);
        assertThat(relevance).containsExactlyElementsOf(expected);

        List<Long> latest = all("/api/search/posts?sort=latest&q=" + enc("희귀어사전"));
        List<Long> expectedLatest = new ArrayList<>(body);
        expectedLatest.add(tagged);
        expectedLatest.add(titled);
        assertThat(latest).containsExactlyElementsOf(expectedLatest);
        Set<Long> unique = new HashSet<>(latest);
        assertThat(unique).hasSize(latest.size());
    }

    @Test
    void 보는_도중_비공개로_바뀌면_다음부터_빠지고_고친_커서는_거절한다() throws Exception {
        Session a = signup(uniqueLogin("srh"));
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < 12; i++) ids.add(publish(a, "중간변경검사 " + i, "본문"));
        JsonNode first = search("중간변경검사");
        assertThat(first.path("items").size()).isEqualTo(9);
        long later = ids.get(0); // 가장 오래된 글: 다음 페이지에 있다
        jdbc.update("UPDATE post SET visibility = 'PRIVATE' WHERE id = ?", later);
        String cursor = first.path("nextCursor").asString();
        JsonNode second = search(browser(), "/api/search/posts?q=" + enc("중간변경검사") + "&cursor=" + cursor);
        assertThat(ids(second)).hasSize(2).doesNotContain(later).doesNotContainAnyElementsOf(ids(first));

        // 다른 검색·다른 정렬의 커서, 고친 커서
        browser().perform(uri("/api/search/posts?q=" + enc("다른검색어") + "&cursor=" + cursor)).andExpect(status().isBadRequest());
        browser().perform(uri("/api/search/posts?sort=latest&q=" + enc("중간변경검사") + "&cursor=" + cursor)).andExpect(status().isBadRequest());
        browser().perform(uri("/api/search/posts?q=" + enc("중간변경검사") + "&cursor=" + cursor.substring(0, cursor.length() - 2) + "AA"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void 사람은_정확히_같은_사람이_먼저이고_탈퇴_신청은_빠진다() throws Exception {
        Session exact = signup(uniqueLogin("srpa"), "사람찾기");
        Session partial = signup(uniqueLogin("srpb"), "사람찾기왕");
        Session gone = signup(uniqueLogin("srpc"), "사람찾기봇");
        jdbc.update("UPDATE member SET withdrawn_at = now(), status = 'WITHDRAWN' WHERE id = ?", gone.memberId());
        jdbc.update("UPDATE member SET bio = ? WHERE id = ?", "첫 줄 소개\n둘째 줄", exact.memberId());

        JsonNode r = search(browser(), "/api/search/people?q=" + enc("사람찾기"));
        List<Long> people = new ArrayList<>();
        r.path("items").forEach(p -> people.add(p.path("id").asLong()));
        assertThat(people).containsExactly(exact.memberId(), partial.memberId());
        assertThat(r.path("items").get(0).path("bioFirstLine").asString()).isEqualTo("첫 줄 소개");
        assertThat(r.path("items").get(0).path("handle").asString()).isEqualTo(exact.handle());

        // @주소로도 찾는다
        JsonNode byHandle = search(browser(), "/api/search/people?q=" + enc("@" + partial.handle()));
        assertThat(byHandle.path("items").get(0).path("id").asLong()).isEqualTo(partial.memberId());
        assertThat(search(browser(), "/api/search/people?q=a").path("notice").asString()).isEqualTo("TOO_SHORT");
    }

    @Test
    void 같은_방문자는_1분에_30번까지_검색한다() throws Exception {
        Browser b = browser().from("203.0.113.77");
        for (int i = 0; i < 30; i++) b.perform(uri("/api/search/posts?q=abc").header("User-Agent", "t")).andExpect(status().isOk());
        b.perform(uri("/api/search/posts?q=abc").header("User-Agent", "t")).andExpect(status().isTooManyRequests());
        b.perform(uri("/api/search/people?q=abc").header("User-Agent", "t")).andExpect(status().isTooManyRequests());
        // 다른 방문자는 영향 없다
        browser().from("203.0.113.78").perform(uri("/api/search/posts?q=abc")).andExpect(status().isOk());
    }

    @Test
    void 비회원이_쿠키를_바꿔도_같은_IP는_1분에_60번까지_검색한다() throws Exception {
        String ip = "203.0.113.90";
        for (int i = 0; i < 60; i++) {
            browser().from(ip).perform(uri("/api/search/posts?q=abc").header("User-Agent", "t")
                    .cookie(new jakarta.servlet.http.Cookie("vid", UUID.randomUUID().toString()))).andExpect(status().isOk());
        }
        browser().from(ip).perform(uri("/api/search/posts?q=abc").header("User-Agent", "t")
                .cookie(new jakarta.servlet.http.Cookie("vid", UUID.randomUUID().toString()))).andExpect(status().isTooManyRequests());
        // 화면 주소도 같은 상한을 쓴다. 넘으면 결과 없이 빈 화면만 그리고, 결과는 화면이 API로 다시 찾는다
        String html = browser().from(ip).perform(uri("/search?q=abc")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(html).contains("<main><h1>검색</h1></main>");
        // 다른 IP는 영향 없다
        browser().from("203.0.113.91").perform(uri("/api/search/posts?q=abc")).andExpect(status().isOk());
    }

    @Test
    void 검색_화면은_수집을_거부하고_저장하지_않는다() throws Exception {
        Session a = signup(uniqueLogin("sri"));
        publish(a, "화면검사어휘 글", "본문 <b>굵게</b>");
        var res = browser().perform(uri("/search?q=" + enc("화면검사어휘"))).andExpect(status().isOk()).andReturn().getResponse();
        String html = res.getContentAsString();
        assertThat(html).contains("noindex").contains("<mark>화면검사어휘</mark>");
        assertThat(res.getHeader("Cache-Control")).contains("no-store");
        String blog = browser().perform(uri("/@" + a.handle() + "?q=" + enc("화면검사어휘"))).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(blog).contains("noindex");
        assertThat(browser().perform(uri("/@" + a.handle())).andReturn().getResponse().getContentAsString()).doesNotContain("noindex");
    }
}
