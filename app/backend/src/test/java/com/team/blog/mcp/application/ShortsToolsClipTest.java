package com.team.blog.mcp.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** list_shorts의 첫 장면(후킹) 미리보기: 공백을 정리하고 HOOK_PREVIEW자에서 자른다 (쇼츠 다양화 2026-10-17). */
class ShortsToolsClipTest {

    @Test
    void 첫_장면_미리보기는_공백을_정리하고_길면_자른다() {
        assertThat(ShortsTools.clip("  왜   배포만 하면\n로그아웃될까요?  ", ShortsTools.HOOK_PREVIEW)).isEqualTo("왜 배포만 하면 로그아웃될까요?");
        String longText = "  " + "가".repeat(30) + "   " + "나".repeat(40);
        assertThat(ShortsTools.clip(longText, ShortsTools.HOOK_PREVIEW)).isEqualTo("가".repeat(30) + " " + "나".repeat(29) + "…");
        assertThat(ShortsTools.clip(null, ShortsTools.HOOK_PREVIEW)).isEmpty();
    }
}
