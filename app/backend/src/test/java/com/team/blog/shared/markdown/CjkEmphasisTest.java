package com.team.blog.shared.markdown;

import static org.assertj.core.api.Assertions.assertThat;

import org.commonmark.node.Node;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;
import org.junit.jupiter.api.Test;

/** spec 097 FR-002: 한글 조사에 붙은 굵게 표시를 살리고, 영어·코드·의도한 별표는 그대로 둔다. */
class CjkEmphasisTest {
    private static final Parser PARSER = Parser.builder().build();
    private static final HtmlRenderer HTML = HtmlRenderer.builder().build();

    private static String render(String md) {
        Node doc = PARSER.parse(md);
        CjkEmphasis.fix(doc);
        return HTML.render(doc).strip();
    }

    @Test
    void 따옴표로_끝나는_굵은_글에_조사가_붙어도_굵게_보인다() {
        assertThat(render("먼저 **\"이 사람이 이 일을 해도 되나?\"**를 묻는다"))
                .isEqualTo("<p>먼저 <strong>&quot;이 사람이 이 일을 해도 되나?&quot;</strong>를 묻는다</p>");
    }

    @Test
    void 괄호로_끝나는_굵은_글과_한_줄에_여러_개도_고친다() {
        assertThat(render("**설정(config)**은 **값(value)**과 다르다"))
                .isEqualTo("<p><strong>설정(config)</strong>은 <strong>값(value)</strong>과 다르다</p>");
    }

    @Test
    void 안에_코드가_있어도_묶는다() {
        assertThat(render("**`@Transactional`(트랜잭션)**이 필요하다"))
                .isEqualTo("<p><strong><code>@Transactional</code>(트랜잭션)</strong>이 필요하다</p>");
    }

    @Test
    void 원래_되던_굵게와_영어는_그대로다() {
        assertThat(render("**굵게**와 **bold** text")).isEqualTo("<p><strong>굵게</strong>와 <strong>bold</strong> text</p>");
        assertThat(render("a **\"quoted\"**s here")).isEqualTo("<p>a **&quot;quoted&quot;**s here</p>");
    }

    @Test
    void 안쪽이_공백인_별표와_코드_안의_별표는_건드리지_않는다() {
        assertThat(render("곱하기 ** 두 번 ** 이다")).isEqualTo("<p>곱하기 ** 두 번 ** 이다</p>");
        assertThat(render("`**\"코드\"**를`")).isEqualTo("<p><code>**&quot;코드&quot;**를</code></p>");
    }
}
