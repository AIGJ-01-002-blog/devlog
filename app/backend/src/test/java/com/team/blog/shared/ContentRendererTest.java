package com.team.blog.shared;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.stream.IntStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.team.blog.shared.config.BlogProperties;
import com.team.blog.shared.markdown.ContentRenderer;
import com.team.blog.shared.markdown.ContentTooComplexException;
import com.team.blog.shared.markdown.ImageOwnership;
import com.team.blog.shared.markdown.ImageUrls;
import com.team.blog.shared.markdown.RenderedContent;
import com.team.blog.support.HtmlSafety;
import com.team.blog.support.IntegrationTest;

/** docs/12 §9·§11 본문 변환·정화 기준. scripts/sanitize/Pipeline.java의 검증 항목을 실제 렌더러로 다시 확인한다. */
class ContentRendererTest extends IntegrationTest {
    private static final long AUTHOR = 7L;

    @Autowired ImageUrls imageUrls;
    @Autowired BlogProperties props;

    ContentRenderer renderer;
    String cdn;
    String myKey = "images/2026/10/11111111-2222-4333-8444-555555555555.webp";
    String othersKey = "images/2026/10/aaaaaaaa-2222-4333-8444-555555555555.webp";
    String unsizedKey = "images/2026/10/bbbbbbbb-2222-4333-8444-555555555555.webp";

    @BeforeEach
    void setUp() {
        // 작성자 7이 올린 사진은 myKey(1200x800)와 크기를 모르는 unsizedKey뿐이라고 가정한다
        renderer = new ContentRenderer(imageUrls, (uploader, keys) -> {
            java.util.Map<String, ImageOwnership.OwnedImage> owned = new java.util.HashMap<>();
            if (uploader == AUTHOR && keys.contains(myKey)) owned.put(myKey, new ImageOwnership.OwnedImage(null, 1200, 800));
            if (uploader == AUTHOR && keys.contains(unsizedKey)) owned.put(unsizedKey, new ImageOwnership.OwnedImage(null, null, null));
            return owned;
        }, props, "http://localhost:8080");
        cdn = imageUrls.publicBaseUrl() + "/";
    }

    @Test
    void 공격_문자열_32개_모두_위험한_HTML을_만들지_않는다() {
        String[] attacks = HtmlSafety.attacks(cdn);
        assertThat(attacks).hasSize(32);
        for (String attack : attacks) {
            String html = renderer.render(attack, AUTHOR).html();
            assertThat(HtmlSafety.dangerous(html)).as(attack + " → " + html).isFalse();
        }
    }

    @Test
    void 정상_문법은_살아_있다() {
        String md = """
                # 원인
                ## 해결 방법
                **굵게** *기울임* ~~취소~~ `inline`

                | 이름 | 값 |
                |:---|---:|
                | a | 1 |

                - [x] 완료
                - [ ] 할 일

                ```java
                List<String> xs = new ArrayList<>();
                ```

                [내부](/@kim/posts/1) [외부](https://spring.io) https://autolink.example

                <b>직접 쓴 HTML</b>
                """;
        String out = renderer.render(md, AUTHOR).html();
        assertThat(out).contains("<h2 id=\"h-원인\">원인</h2>").contains("<h3 id=\"h-해결-방법\">").doesNotContain("<h1")
                .contains("<strong>굵게</strong>", "<em>기울임</em>", "<del>취소</del>")
                .contains("<table>").contains("align=\"right\"")
                .contains("type=\"checkbox\"").contains("disabled")
                .contains("<code class=\"language-java\">").contains("List&lt;String&gt;");
        assertThat(HtmlSafety.decode(out)).contains("<a href=\"/@kim/posts/1\">내부</a>");
        assertThat(out).containsPattern("<a rel=\"noopener noreferrer nofollow ugc\" href=\"https://spring.io\" target=\"_blank\">외부</a>")
                .contains("href=\"https://autolink.example\"")
                .contains("&lt;b&gt;직접 쓴 HTML&lt;/b&gt;");
    }

    @Test
    void 내가_올린_사진만_이미지로_보이고_외부_남의_사진은_링크가_된다() {
        String md = "![내 사진](" + cdn + myKey + ") ![남 사진](" + cdn + othersKey + ") ![배지](https://img.shields.io/badge/x-y-green)";
        RenderedContent r = renderer.render(md, AUTHOR);
        assertThat(r.html()).contains("<img src=\"" + cdn + myKey + "\"").contains("loading=\"lazy\"");
        assertThat(r.html()).doesNotContain("<img src=\"" + cdn + othersKey).doesNotContain("<img src=\"https://img.shields.io");
        assertThat(r.html()).contains("[이미지] 배지").contains("[이미지] 남 사진");
        assertThat(r.imageKeys()).containsExactly(myKey);
        // 같은 본문도 다른 사람이 쓰면 이미지가 아니다
        assertThat(renderer.render(md, 99L).imageKeys()).isEmpty();
    }

    @Test
    void 크기를_아는_사진에는_가로세로를_적어_읽는_중에_글이_밀리지_않게_한다() {
        String html = renderer.render("![내 사진](" + cdn + myKey + ")\n\n![크기 모름](" + cdn + unsizedKey + ")", AUTHOR).html();
        assertThat(html).contains("<img src=\"" + cdn + myKey + "\"").contains("width=\"1200\"").contains("height=\"800\"");
        String unsized = html.substring(html.indexOf("<img src=\"" + cdn + unsizedKey));
        assertThat(unsized.substring(0, unsized.indexOf('>'))).doesNotContain("width=").doesNotContain("height=");
    }

    @Test
    void 요약은_코드_이미지_표를_빼고_200자에서_자른다() {
        String md = "첫 문단입니다.\n\n```\nsecret code\n```\n\n| a |\n|---|\n| 표내용 |\n\n" + "가나다라 ".repeat(100);
        RenderedContent r = renderer.render(md, AUTHOR);
        assertThat(r.excerpt()).startsWith("첫 문단입니다.").doesNotContain("secret").doesNotContain("표내용");
        assertThat(r.excerpt().length()).isLessThanOrEqualTo(200);
    }

    @Test
    void 중첩_20단계를_넘으면_거부하고_15단계는_허용한다() {
        String deepQuote = "> ".repeat(25) + "깊은 인용";
        assertThatThrownBy(() -> renderer.render(deepQuote, AUTHOR)).isInstanceOf(ContentTooComplexException.class);
        String deepList = String.join("\n", IntStream.range(0, 25).mapToObj(i -> "  ".repeat(i) + "- x").toList());
        assertThatThrownBy(() -> renderer.render(deepList, AUTHOR)).isInstanceOf(ContentTooComplexException.class);
        assertThat(renderer.render("> ".repeat(15) + "적당한 인용", AUTHOR).html()).contains("적당한 인용");
    }

    @Test
    void 본문_10만_자도_1초_안에_변환한다() {
        long t0 = System.nanoTime();
        renderer.render("x ".repeat(50_000), AUTHOR);
        assertThat((System.nanoTime() - t0) / 1_000_000).isLessThan(1000);
    }
}
