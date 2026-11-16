package com.team.blog.mcp.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.team.blog.shorts.application.ShortsScript.Scene;

/** AI 대본 끝 문장 규칙(2026-11-16): 쉼표로 끊어 첫 장면에 이어 붙이는 "루프" 끝맺음은 저장하지 않는다 */
class ShortsToolsCutOffTest {

    @Test
    void 쉼표로_끝난_AI_대본은_이유와_함께_막는다() {
        List<Scene> scenes = List.of(new Scene("a", "첫 화면이 250KB를 넘으면 빌드가 실패해요.", null),
                new Scene("b", "다시 느려진 동료한테 보내 주세요. 우리 팀 규칙은 하나,", null));
        assertThat(ShortsTools.cutOffEnding(scenes)).contains("쉼표로 끝나").contains("완결된 문장");
    }

    @Test
    void 마침표로_맺은_AI_대본은_저장한다() {
        List<Scene> scenes = List.of(new Scene("a", "첫 화면이 250KB를 넘으면 빌드가 실패해요.", null),
                new Scene("b", "다시 느려진 동료한테 보내 주세요.", null));
        assertThat(ShortsTools.cutOffEnding(scenes)).isNull();
    }

    @Test
    void 안내문에_루프_끝맺음을_권하지_않는다() {
        assertThat(ShortsTools.GUIDE).doesNotContain("다시 보기(루프)를 부른다").contains("완결된 문장");
    }
}
