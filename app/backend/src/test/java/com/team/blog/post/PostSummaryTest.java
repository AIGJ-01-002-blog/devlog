package com.team.blog.post;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;


import com.team.blog.support.IntegrationTest;

/** spec 045: 발행할 때 쓰는 짧은 소개. 쓰면 목록·RSS·공유 설명에 그대로, 비우면 본문 앞부분으로 요약한다. */
class PostSummaryTest extends IntegrationTest {
    long create(Session s) throws Exception {
        return read(s.http().perform(asJson(post("/api/posts"), Map.of("title", "글", "contentMd", "")))
                .andExpect(status().isCreated()).andReturn()).path("id").asLong();
    }

    ResultActions publish(Session s, long id, String summary, long baseVersion) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("title", "소개 글");
        body.put("contentMd", "본문 첫 문장입니다. 이어지는 설명.");
        body.put("summary", summary);
        body.put("visibility", "PUBLIC");
        body.put("tags", List.of());
        body.put("baseVersion", baseVersion);
        return s.http().perform(asJson(post("/api/posts/" + id + "/publish"), body)
                .header("Idempotency-Key", UUID.randomUUID().toString()));
    }

    String cardExcerpt(Session s) throws Exception {
        return read(browser().perform(get("/api/members/" + s.handle() + "/posts")).andExpect(status().isOk()).andReturn())
                .path("items").get(0).path("excerpt").asString();
    }

    @Test
    void 쓴_소개가_목록_상세_RSS_공유_설명에_보이고_비우면_본문_요약으로_돌아간다() throws Exception {
        Session me = signup(uniqueLogin("sum"));
        long id = create(me);

        // 줄바꿈과 연속 공백은 한 칸으로 줄인다
        long v = read(publish(me, id, "  짧게\n\n소개하는   글  ", 0).andExpect(status().isOk()).andReturn()).path("version").asLong();
        assertThat(cardExcerpt(me)).isEqualTo("짧게 소개하는 글");
        assertThat(read(browser().perform(get("/api/posts/" + id)).andReturn()).path("excerpt").asString()).isEqualTo("짧게 소개하는 글");
        assertThat(browser().perform(get("/@" + me.handle() + "/rss")).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8))
                .contains("<description>짧게 소개하는 글</description>");
        assertThat(browser().perform(get("/@" + me.handle() + "/posts/" + id)).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8))
                .contains("content=\"짧게 소개하는 글\"");
        // 다시 발행할 때 발행 설정 창을 미리 채운다
        assertThat(read(me.http().perform(get("/api/posts/" + id + "/edit")).andReturn()).path("summary").asString()).isEqualTo("짧게 소개하는 글");

        // 비우면 행에 저장하지 않고 본문 앞부분으로 요약한다
        publish(me, id, " ", v).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT summary FROM post WHERE id = ?", String.class, id)).isNull();
        assertThat(cardExcerpt(me)).startsWith("본문 첫 문장입니다.");
        assertThat(read(me.http().perform(get("/api/posts/" + id + "/edit")).andReturn()).path("summary").isNull()).isTrue();
    }

    @Test
    void 소개가_150자를_넘으면_다른_오류와_함께_알려주고_발행하지_않는다() throws Exception {
        Session me = signup(uniqueLogin("suml"));
        long id = create(me);

        // 코드 포인트로 센다: 이모지 150개는 받는다
        String emoji150 = "😀".repeat(150);
        Map<String, Object> body = new HashMap<>();
        body.put("title", "");
        body.put("contentMd", "본문");
        body.put("summary", "가".repeat(151));
        body.put("visibility", "PUBLIC");
        body.put("tags", List.of());
        body.put("baseVersion", 0);
        String err = me.http().perform(asJson(post("/api/posts/" + id + "/publish"), body))
                .andExpect(status().isBadRequest()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(err).contains("SUMMARY_TOO_LONG", "TITLE_REQUIRED");
        assertThat(jdbc.queryForObject("SELECT status FROM post WHERE id = ?", String.class, id)).isEqualTo("DRAFT");

        publish(me, id, emoji150, 0).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT summary FROM post WHERE id = ?", String.class, id)).isEqualTo(emoji150);
    }
}
