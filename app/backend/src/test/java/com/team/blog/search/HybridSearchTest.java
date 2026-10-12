package com.team.blog.search;

import static org.assertj.core.api.Assertions.assertThat;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

import tools.jackson.databind.JsonNode;

import com.team.blog.search.semantic.EmbeddingIndexJob;
import com.team.blog.support.Browser;
import com.team.blog.support.FakeAi;
import com.team.blog.support.IntegrationTest;

/**
 * spec 054 하이브리드 검색. 임베딩은 가짜 Ollama(/api/embed)가 주제 단어로 만든 벡터라, "롤백"으로 검색하면 글자는 없어도
 * 트랜잭션 글이 가깝게 나온다. 다른 시험의 글과 섞이지 않게 블로그 안 검색으로 본다.
 */
@TestPropertySource(properties = {"blog.search.semantic.enabled=true", "blog.search.semantic.index-batch=2",
        "blog.search.semantic.candidates=3", "blog.search.semantic.keyword-candidates=5"})
class HybridSearchTest extends IntegrationTest {
    private static final FakeAi AI = FakeAi.INSTANCE;

    @Autowired EmbeddingIndexJob indexJob;

    @BeforeEach
    void cleanEmbedding() {
        AI.reset();
        Set<String> keys = redis.keys("search:*");
        if (keys != null && !keys.isEmpty()) redis.delete(keys);
    }

    /** 밀린 글을 모두 임베딩한다 (다른 시험이 남긴 공개 글까지) */
    void index() {
        int guard = 0;
        while (indexJob.runOnce() > 0 && guard++ < 1000) { /* 다음 묶음 */ }
    }

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

    JsonNode search(Browser b, String url) throws Exception {
        return read(b.perform(get(java.net.URI.create(url))).andExpect(status().isOk()).andReturn());
    }

    static List<Long> ids(JsonNode page) {
        List<Long> ids = new ArrayList<>();
        page.path("items").forEach(i -> ids.add(i.path("id").asLong()));
        return ids;
    }

    /** [더 보기]로 끝까지 넘긴 번호 목록. 빈 페이지를 받는 클릭이 없어야 한다 */
    List<Long> all(String url) throws Exception {
        List<Long> ids = new ArrayList<>();
        Browser b = browser();
        JsonNode page = search(b, url);
        ids.addAll(ids(page));
        int guard = 0;
        while (page.has("nextCursor") && !page.path("nextCursor").isNull() && guard++ < 20) {
            page = search(b, url + "&cursor=" + page.path("nextCursor").asString());
            assertThat(page.path("items").size()).isPositive();
            ids.addAll(ids(page));
        }
        return ids;
    }

    static String enc(String s) {
        return java.net.URLEncoder.encode(s, java.nio.charset.StandardCharsets.UTF_8).replace("+", "%20");
    }

    JsonNode inBlog(Session s, String q) throws Exception {
        return search(browser(), "/api/search/posts?blog=" + s.handle() + "&q=" + enc(q));
    }

    List<Boolean> similar(JsonNode page) {
        List<Boolean> out = new ArrayList<>();
        page.path("items").forEach(i -> out.add(i.path("similar").asBoolean()));
        return out;
    }

    @Test
    void 검색어가_글에_없어도_뜻이_가까운_글을_찾는다() throws Exception {
        Session a = signup(uniqueLogin("hya"));
        long tx = publish(a, "스프링 트랜잭션 정리", "커밋 시점과 전파 속성을 정리합니다.");
        long docker = publish(a, "도커 입문", "컨테이너 이미지를 만드는 방법");
        index();

        JsonNode r = inBlog(a, "롤백 처리");
        assertThat(ids(r)).as("키워드로는 0건이지만 의미가 가까운 글").containsExactly(tx).doesNotContain(docker);
        assertThat(similar(r)).containsExactly(true);
        assertThat(r.path("items").get(0).path("snippetHtml").asString()).doesNotContain("<mark>");
    }

    @Test
    void 제목에_그대로_있는_글이_먼저고_의미로_찾은_글이_뒤에_붙는다() throws Exception {
        Session a = signup(uniqueLogin("hyb"));
        long rollbackOnly = publish(a, "롤백 전략", "배포를 되돌리는 순서");
        long tx = publish(a, "트랜잭션 격리 수준", "커밋과 커밋 사이");
        long docker = publish(a, "도커 볼륨", "컨테이너 데이터");
        index();

        JsonNode r = inBlog(a, "롤백");
        assertThat(ids(r).get(0)).isEqualTo(rollbackOnly);
        assertThat(ids(r)).contains(tx).doesNotContain(docker);
        assertThat(similar(r).get(0)).isFalse();
        assertThat(similar(r).get(ids(r).indexOf(tx))).isTrue();
    }

    @Test
    void 공급자가_꺼져_있으면_키워드_검색으로_답하고_잠시_건너뛴다() throws Exception {
        Session a = signup(uniqueLogin("hyc"));
        long tx = publish(a, "트랜잭션 메모", "커밋");
        long kw = publish(a, "롤백 메모", "되돌리기");
        index();

        AI.embedFailing = true;
        JsonNode r = inBlog(a, "롤백");
        assertThat(ids(r)).containsExactly(kw).doesNotContain(tx);
        assertThat(similar(r)).containsExactly(false);
        assertThat(redis.hasKey("search:embed:down")).isTrue();

        // 건너뛰는 동안은 공급자를 부르지 않는다
        int before = AI.embedRequests.size();
        inBlog(a, "다른 검색어");
        assertThat(AI.embedRequests).hasSize(before);
    }

    @Test
    void 공개_글만_묶어서_임베딩하고_고친_글은_다시_임베딩한다() throws Exception {
        Session a = signup(uniqueLogin("hyd"));
        long p1 = publish(a, "고양이 일기", "반려동물");
        long p2 = publish(a, "강아지 일기", "산책");
        long p3 = publish(a, "트랜잭션", "커밋");
        long priv = publish(a, "비공개 롤백", "롤백 커밋", "PRIVATE", List.of());
        index();

        assertThat(AI.embedRequests).as("한 요청에 여러 글(묶음 2)").contains(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM post_embedding WHERE post_id IN (?, ?, ?)", Integer.class, p1, p2, p3)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM post_embedding WHERE post_id = ?", Integer.class, priv)).isZero();

        // 고양이 글을 트랜잭션 글로 고치면 다음 차례에 다시 임베딩된다
        jdbc.update("UPDATE post SET title = '롤백 일기', content_md = '커밋 되돌리기', edit_version = edit_version + 1 WHERE id = ?", p1);
        AI.embedRequests.clear();
        index();
        assertThat(AI.embedRequests).containsExactly(1);
        assertThat(ids(inBlog(a, "트랜잭션 이야기"))).contains(p1, p3).doesNotContain(p2, priv);
    }

    @Test
    void 섞은_목록_뒤로_키워드_결과가_빠지거나_겹치지_않고_이어진다() throws Exception {
        // 키워드 앞쪽 5개 + 의미 3개를 섞고, 남은 키워드 결과(이미 보여 준 의미 결과 제외)가 이어진다
        Session a = signup(uniqueLogin("hye"));
        List<Long> rollbacks = new ArrayList<>();
        for (int i = 0; i < 12; i++) rollbacks.add(publish(a, "롤백 " + i, "되돌리기"));
        for (int i = 0; i < 4; i++) publish(a, "트랜잭션 " + i, "커밋");
        publish(a, "도커", "컨테이너");
        index();

        List<Long> all = all("/api/search/posts?blog=" + a.handle() + "&q=" + enc("롤백"));
        assertThat(all).doesNotHaveDuplicates().containsAll(rollbacks)
                .hasSizeLessThanOrEqualTo(rollbacks.size() + 3);
    }
}
