package com.team.blog.mcp.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** 짧은 판(30~45초)과 긴 판(60~90초)은 안내 없이 통과하고, 그 밖이면 어느 쪽으로 맞출지 알려 준다. */
class ShortsToolsLengthHintTest {

    @Test
    void 짧은_판과_긴_판_길이는_안내하지_않는다() {
        assertThat(ShortsTools.lengthHint(30)).isEmpty();
        assertThat(ShortsTools.lengthHint(45.4)).isEmpty();
        assertThat(ShortsTools.lengthHint(59.6)).isEmpty();
        assertThat(ShortsTools.lengthHint(88)).isEmpty();
    }

    @Test
    void 너무_짧으면_근거_장면을_더하라고_한다() {
        assertThat(ShortsTools.lengthHint(22)).startsWith("길이가 22초예요.").contains("30~45초");
    }

    @Test
    void 두_판_사이면_한쪽으로_맞추라고_한다() {
        assertThat(ShortsTools.lengthHint(52)).startsWith("길이가 52초로").contains("30~45초").contains("60~90초");
        assertThat(ShortsTools.lengthHint(45.6)).startsWith("길이가 46초로");
    }
}
