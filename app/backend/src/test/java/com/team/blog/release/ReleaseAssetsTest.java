package com.team.blog.release;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

import com.team.blog.shared.markdown.ContentRenderer;
import com.team.blog.support.IntegrationTest;

/** 093: 릴리스 노트의 화면 전/후 사진이 사이트 릴리스 노트 화면에서도 보인다. */
class ReleaseAssetsTest extends IntegrationTest {
    private static final String RAW = "https://raw.githubusercontent.com/AIGJ-01-002-blog/devlog/main/assets/releases/";

    @Autowired ContentRenderer renderer;
    @Value("${blog.site.base-url:http://localhost:8080}") String siteBase;

    private String assetBase() {
        return (siteBase.endsWith("/") ? siteBase.substring(0, siteBase.length() - 1) : siteBase) + ContentRenderer.RELEASE_ASSETS;
    }

    @Test
    void GitHub_원본_사진_주소를_사이트_사본으로_바꾸고_안내_주석은_뺀다() {
        String md = """
                <!-- 사진은 assets/releases/v1.67.0/에 둔다 -->
                | 전 | 후 |
                |---|---|
                | ![전](%sv1.67.0/home-before.webp) | ![후](%sv1.67.0/home-after.webp) |
                """.formatted(RAW, RAW);

        String out = ReleaseNotes.forSite(md, assetBase());

        assertThat(out).doesNotContain("<!--").doesNotContain("raw.githubusercontent.com")
                .contains(assetBase() + "v1.67.0/home-before.webp").contains(assetBase() + "v1.67.0/home-after.webp");
    }

    @Test
    void 바꾼_사진은_사진으로_그리고_다른_곳_사진은_링크로만_남긴다() {
        String md = ReleaseNotes.forSite("""
                | 전 | 후 |
                |---|---|
                | ![전](%sv1.67.0/a.webp) | ![후](https://evil.example/b.webp) |
                """.formatted(RAW), assetBase());

        String html = renderer.render(md, 0L).html();

        assertThat(html).contains("<table").contains("src=\"" + assetBase() + "v1.67.0/a.webp\"")
                .doesNotContain("src=\"https://evil.example");
    }

    @Test
    void 사이트_사본_주소라도_상위_폴더로_빠지는_경로는_사진으로_그리지_않는다() {
        String html = renderer.render("![x](" + assetBase() + "../api/me.png)", 0L).html();
        assertThat(html).doesNotContain("<img");
    }

    @Test
    void 다른_저장소의_원본_주소는_바꾸지_않는다() {
        String other = "![x](https://raw.githubusercontent.com/someone/else/main/assets/releases/v1/x.webp)";
        assertThat(ReleaseNotes.forSite(other, assetBase())).isEqualTo(other);
    }

    @Test
    void 사이트가_사진_사본을_캐시와_함께_내보낸다() throws Exception {
        mvc.perform(get("/release-assets/v0.0.0/dot.png"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("max-age=2592000")));
        mvc.perform(get("/release-assets/v0.0.0/none.png")).andExpect(status().isNotFound());
    }
}
