package com.team.blog.page;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

import com.team.blog.support.IntegrationTest;

/** 051: AI 연결 안내 화면과 첫 화면 설명에 슬로건이 들어간다. */
class McpGuidePageTest extends IntegrationTest {

    @Test
    void AI_연결_안내는_비회원도_열고_검색_엔진이_읽는다() throws Exception {
        String page = mvc.perform(get("/mcp")).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        assertThat(page).contains("<link rel=\"canonical\" href=\"http://localhost:8080/mcp\">")
                .contains("<h1>당신의 AI가 개발 일지를 씁니다</h1>")
                .contains(PageController.SLOGAN)
                .doesNotContain("noindex");
    }

    @Test
    void 첫_화면_설명은_슬로건이다() throws Exception {
        String page = mvc.perform(get("/")).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        assertThat(page).contains("og:description\" content=\"" + PageController.SLOGAN);
    }
}
