package com.team.blog.mcp.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.team.blog.shorts.application.ShortsScript;
import com.team.blog.shorts.application.ShortsScript.Scene;

class ShortsToolsTooLongTest {

    @Test
    void 오십초_안의_AI_대본은_저장한다() {
        // 장면마다 40자 ÷ 6.5 + 0.6 ≈ 6.75초, 여섯 장면이면 약 40.5초
        List<Scene> six = Collections.nCopies(6, new Scene("요점", "가".repeat(40), null));
        assertThat(ShortsTools.tooLong(six)).isNull();
    }

    @Test
    void 오십초를_넘는_AI_대본은_이유와_함께_막는다() {
        // 장면마다 50자 ÷ 6.5 + 0.6 ≈ 8.3초, 열 장면이면 약 83초: 지금까지 AI가 쓰던 길이
        List<Scene> ten = Collections.nCopies(10, new Scene("요점", "가".repeat(50), null));
        assertThat(ShortsTools.tooLong(ten)).startsWith("예상 길이가 83초라 저장하지 않았어요.")
                .contains(ShortsScript.AI_SECONDS_MAX + "초까지").contains("30~45초");
    }

    @Test
    void 경계는_반올림한_초로_본다() {
        // 두 장면 틈 1.2초 + 320자 ÷ 6.5 ≈ 50.4초 → 50초라 저장하고, 324자 ≈ 51.0초 → 51초라 막는다
        assertThat(ShortsTools.tooLong(List.of(new Scene("요점", "가".repeat(160), null), new Scene("요점", "가".repeat(160), null)))).isNull();
        assertThat(ShortsTools.tooLong(List.of(new Scene("요점", "가".repeat(162), null), new Scene("요점", "가".repeat(162), null)))).contains("51초");
    }
}
