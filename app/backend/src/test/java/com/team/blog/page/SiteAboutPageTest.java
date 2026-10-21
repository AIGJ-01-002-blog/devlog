package com.team.blog.page;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.team.blog.support.IntegrationTest;

/** 077: 사이트 소개는 비회원도 열고 검색 엔진이 읽으며, 문의·약관·처리방침으로 잇는다. */
class SiteAboutPageTest extends IntegrationTest {

    @Test
    void 사이트_소개는_공개_화면이고_연락처와_처리방침을_잇는다() throws Exception {
        String page = mvc.perform(get("/about")).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        assertThat(page).contains("<link rel=\"canonical\" href=\"http://localhost:8080/about\">")
                .contains("<h1>devlog 소개</h1>")
                .contains("href=\"/support\"").contains("href=\"/privacy\"").contains("href=\"/terms\"")
                .contains("\"page\":\"about\"")
                .doesNotContain("noindex");
    }

    @Test
    void 운영자_주소_API는_비회원도_읽는다() throws Exception {
        mvc.perform(get("/api/site/about")).andExpect(status().isOk()).andExpect(jsonPath("$.ownerHandle").hasJsonPath())
                .andExpect(jsonPath("$.contactUrl").value("https://github.com/AIGJ-01-002-blog/devlog/issues"))
                .andExpect(jsonPath("$.contactEmail").value("devlogauth@gmail.com")); // 기본은 블로그 운영 메일 (spec 107)
    }

    @Test
    void 로그인하지_않아도_쓸_수_있는_연락_방법이_스크립트_없이도_보인다() throws Exception {
        String page = mvc.perform(get("/about")).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        assertThat(page).contains("로그인하지 않았다면 메일 <a href=\"mailto:devlogauth@gmail.com\">devlogauth@gmail.com</a>이나 "
                        + "<a href=\"https://github.com/AIGJ-01-002-blog/devlog/issues\" rel=\"noopener\">공개 문의 창구</a>로 연락해 주세요.")
                .contains("\"contactUrl\":\"https://github.com/AIGJ-01-002-blog/devlog/issues\"");
    }

    @Test
    void 소개는_글_쓰는_방식과_AI_사용_범위를_스크립트_없이도_보여_준다() throws Exception {
        String page = mvc.perform(get("/about")).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        assertThat(page).contains("<h2 id=\"ai\">글 쓰는 방식과 AI 사용 범위</h2>")
                .contains("AI 보조 작성")
                .contains("href=\"/portfolio\"").contains("href=\"/contact\"");
        for (String line : PageController.AI_SCOPE) assertThat(page).contains("<li>" + line + "</li>");
    }

    @Test
    void 연락처는_공개_화면이고_메일과_공개_문의_창구를_보여_준다() throws Exception {
        String page = mvc.perform(get("/contact")).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        assertThat(page).contains("<h1>연락처</h1>")
                .contains("<a href=\"mailto:devlogauth@gmail.com\">devlogauth@gmail.com</a>")
                .contains("href=\"https://github.com/AIGJ-01-002-blog/devlog/issues\"")
                .contains("<link rel=\"canonical\" href=\"http://localhost:8080/contact\">")
                .contains("\"page\":\"contact\"").contains(SpaShell.SITE_FOOTER).doesNotContain("noindex");
    }

    @Test
    void 포트폴리오는_사례_다섯_개와_근거_글을_서버_HTML로_보여_준다() throws Exception {
        String page = mvc.perform(get("/portfolio")).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        assertThat(page).contains("<h1>포트폴리오</h1>").contains("<link rel=\"canonical\" href=\"http://localhost:8080/portfolio\">")
                .contains("\"page\":\"site-portfolio\"").doesNotContain("noindex").doesNotContain("{{owner}}")
                .doesNotContain("숫자는 공개 글·PR에 적힌 값만"); // 맨 앞 안내 주석은 화면에 내지 않는다
        // 사례 5개 (초기 데이터 안의 같은 HTML은 따옴표가 이스케이프돼 세지 않는다)
        assertThat(page.split("class=\"portfolio-case\"", -1)).hasSize(5 + 1);
        mvc.perform(get("/api/site/portfolio")).andExpect(status().isOk())
                .andExpect(jsonPath("$.html").value(org.hamcrest.Matchers.containsString("문제")));
    }

    @Test
    void 운영자_주소가_없으면_포트폴리오_근거_링크는_글자로만_남는다() {
        String html = new SitePortfolio(new SiteOwner("")).html();
        assertThat(html).doesNotContain("{{owner}}").doesNotContain("href=\"/@").contains("무중단 배포·자동 롤백·터널");
        assertThat(new SitePortfolio(new SiteOwner("kim")).html()).contains("href=\"/@kim/posts/17\"");
    }

    @Test
    void 문의_화면도_로그인_없는_연락_방법을_서버_HTML로_보여_주고_수집은_거부한다() throws Exception {
        String page = mvc.perform(get("/support")).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        assertThat(page).contains("<h1>문의·신고</h1>")
                .contains("로그인하지 않았다면 메일 <a href=\"mailto:devlogauth@gmail.com\">devlogauth@gmail.com</a>이나 <a href=\"https://github.com/AIGJ-01-002-blog/devlog/issues\" rel=\"noopener\">공개 문의 창구</a>로 연락해 주세요.")
                .contains("noindex").doesNotContain(SpaShell.SITE_FOOTER);
    }

    @Test
    void 수집하는_화면의_서버_HTML에는_사이트_바닥글이_있고_개인_화면에는_없다() throws Exception {
        for (String path : List.of("/", "/about", "/privacy")) {
            assertThat(mvc.perform(get(path)).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8))
                    .as(path).contains(SpaShell.SITE_FOOTER);
        }
        assertThat(mvc.perform(get("/login")).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8))
                .doesNotContain(SpaShell.SITE_FOOTER);
    }

    @Test
    void 검색_엔진_소유_확인_메타는_설정값으로_넣고_비면_뺀다() throws Exception {
        String home = mvc.perform(get("/")).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(home).contains("<meta name=\"naver-site-verification\" content=\"9153d9806ad2cd0c40b392cd0afcfa866deb9a55\">")
                .doesNotContain("google-site-verification");
        assertThat(new SiteVerification("g-1", "").meta()).isEqualTo("<meta name=\"google-site-verification\" content=\"g-1\">\n");
        assertThat(new SiteVerification("", null).meta()).isEmpty();
    }

    @Test
    void 운영자_주소는_골뱅이와_대문자를_정리한다() {
        assertThat(new SiteOwner(" @Kim_Blog ").handle()).contains("kim_blog");
        assertThat(new SiteOwner("").handle()).isEmpty();
        assertThat(new SiteOwner(null).handle()).isEmpty();
    }
}
