package com.team.blog.tag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;

import com.team.blog.support.IntegrationTest;
import com.team.blog.tag.application.TagBrowse;

/** spec 089 태그 둘러보기: 검색·정렬, 몇 줄씩 이어 받기, 줄마다 인기 글. */
class TagBrowseTest extends IntegrationTest {

    long published(Session s, String visibility, String... tags) throws Exception {
        long id = read(s.http().perform(asJson(post("/api/posts"), Map.of("title", "글", "contentMd", "본문")))
                .andExpect(status().isCreated()).andReturn()).path("id").asLong();
        s.http().perform(asJson(post("/api/posts/" + id + "/publish"), Map.of("title", "글 " + id, "contentMd", "본문",
                "visibility", visibility, "baseVersion", 0, "tags", List.of(tags)))
                .header("Idempotency-Key", UUID.randomUUID().toString())).andExpect(status().isOk());
        return id;
    }

    JsonNode browse(String q, String sort, String cursor) throws Exception {
        var req = get("/api/tags/browse");
        if (q != null) req = req.param("q", q);
        if (sort != null) req = req.param("sort", sort);
        if (cursor != null) req = req.param("cursor", cursor);
        return read(browser().perform(req).andExpect(status().isOk()).andReturn());
    }

    static List<String> names(JsonNode page) {
        List<String> out = new ArrayList<>();
        page.path("items").forEach(n -> out.add(n.path("name").asString()));
        return out;
    }

    void like(long postId, long memberId) {
        jdbc.update("INSERT INTO post_like (post_id, member_id) VALUES (?, ?)", postId, memberId);
    }

    @Test
    void 검색어가_이름_안에_있는_태그만_공개_글_수_많은_순으로_나오고_줄마다_인기_글이_먼저다() throws Exception {
        Session a = signup(uniqueLogin("tba")), b = signup(uniqueLogin("tbb")), c = signup(uniqueLogin("tbc"));
        String key = TagTest.unique("brw");
        String big = "x" + key + "z", small = key + "_y";
        long plain = published(a, "PUBLIC", big, small);
        long loved = published(a, "PUBLIC", big);
        published(a, "PRIVATE", small); // 비공개 글은 세지 않는다
        like(loved, b.memberId());
        like(loved, c.memberId());

        // 앞의 #과 대문자는 태그처럼 다듬고, 이름 가운데에 있어도 찾는다. _는 와일드카드가 아니다
        JsonNode page = browse("#" + key.toUpperCase(), null, null);
        assertThat(names(page)).containsExactly(big, small);
        assertThat(page.path("total").asLong()).isEqualTo(2);
        assertThat(page.path("nextCursor").isNull()).isTrue();
        JsonNode first = page.path("items").get(0);
        assertThat(first.path("postCount").asLong()).isEqualTo(2);
        List<Long> ids = new ArrayList<>();
        first.path("posts").forEach(n -> ids.add(n.path("id").asLong()));
        assertThat(ids).containsExactly(loved, plain);
        assertThat(page.path("items").get(1).path("postCount").asLong()).isEqualTo(1);

        assertThat(names(browse(key + "_", null, null))).containsExactly(small);
        assertThat(names(browse("a".repeat(31), null, null))).isEmpty();
    }

    @Test
    void 이름순_최근순으로_정렬하고_커서로_다음_줄을_이어_받는다() throws Exception {
        Session a = signup(uniqueLogin("tbd"));
        String key = TagTest.unique("srt");
        List<String> tags = new ArrayList<>();
        for (int i = 0; i < TagBrowse.PAGE_SIZE + 2; i++) tags.add(key + "-" + (char) ('a' + i));
        for (String t : tags) published(a, "PUBLIC", t); // 뒤에 단 태그일수록 최근에 쓰였다

        JsonNode byName = browse(key, "name", null);
        assertThat(names(byName)).containsExactlyElementsOf(tags.subList(0, TagBrowse.PAGE_SIZE));
        assertThat(byName.path("total").asLong()).isEqualTo(tags.size());
        JsonNode next = browse(key, "name", byName.path("nextCursor").asString());
        assertThat(names(next)).containsExactlyElementsOf(tags.subList(TagBrowse.PAGE_SIZE, tags.size()));
        assertThat(next.path("total").isNull()).isTrue();
        assertThat(next.path("nextCursor").isNull()).isTrue();

        assertThat(names(browse(key, "recent", null)).get(0)).isEqualTo(tags.get(tags.size() - 1));
        // 모르는 정렬·망가진 커서는 기본(인기순, 처음부터)이다
        assertThat(names(browse(key, "weird", "zz"))).hasSize(TagBrowse.PAGE_SIZE);
    }

    @Test
    void 태그_화면은_첫_줄을_함께_내려주고_검색어로_거른_주소는_수집하지_않는다() throws Exception {
        Session a = signup(uniqueLogin("tbe"));
        String key = TagTest.unique("ssr");
        published(a, "PUBLIC", key);
        String html = browser().perform(get("/tags")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(html).contains("\"browse\"").contains("noindex, follow"); // 태그 목록은 링크만 따라간다 (spec 107)
        String filtered = browser().perform(get("/tags").param("q", key)).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString();
        assertThat(filtered).contains("noindex").contains(key);
    }
}
