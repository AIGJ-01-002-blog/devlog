package com.team.blog.mcp.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** 권장 길이(30~45초) 안이면 안내 없이 통과하고, 벗어나면 늘릴지 줄일지 알려 준다 (#431). */
class ShortsToolsLengthHintTest {

    @Test
    void 권장_길이_안이면_안내하지_않는다() {
        assertThat(ShortsTools.lengthHint(30)).isEmpty();
        assertThat(ShortsTools.lengthHint(29.6)).isEmpty();
        assertThat(ShortsTools.lengthHint(45.4)).isEmpty();
    }

    @Test
    void 너무_짧으면_근거_장면을_더하라고_한다() {
        assertThat(ShortsTools.lengthHint(22)).startsWith("길이가 22초예요.").contains("30~45초").contains("더해");
    }

    @Test
    void 길면_줄이라고_한다() {
        assertThat(ShortsTools.lengthHint(45.6)).startsWith("길이가 46초예요.").contains("줄이고");
        assertThat(ShortsTools.lengthHint(80)).startsWith("길이가 80초예요.");
    }
}
