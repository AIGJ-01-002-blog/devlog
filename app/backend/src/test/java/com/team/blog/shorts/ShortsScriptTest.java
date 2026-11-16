package com.team.blog.shorts;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.team.blog.shared.error.FieldErrorItem;
import com.team.blog.shorts.application.ShortsScript;
import com.team.blog.shorts.application.ShortsScript.Scene;

/** spec 099 FR-002: 대본 저장 규칙과 영상 만들기 규칙 */
class ShortsScriptTest {

    private static List<String> codes(List<FieldErrorItem> errors) {
        return errors.stream().map(e -> e.field() + ":" + e.code()).toList();
    }

    @Test
    void 쓰는_중인_대본은_장면이_적어도_저장되지만_영상으로는_못_만든다() {
        List<Scene> one = List.of(new Scene("왜?", "왜 그럴까요?", null));
        assertThat(ShortsScript.checkSave("제목", one)).isEmpty();
        assertThat(codes(ShortsScript.checkRender("제목", one))).contains("scenes:TOO_FEW");
    }

    @Test
    void 길이_상한과_빈_칸을_본다() {
        List<Scene> scenes = List.of(
                new Scene("x".repeat(41), "y".repeat(201), "z".repeat(301)),
                new Scene("", "", null),
                new Scene("ok", "괜찮아요", null));
        assertThat(codes(ShortsScript.checkSave("t".repeat(61), scenes)))
                .containsExactly("title:TOO_LONG", "scenes[0].caption:TOO_LONG", "scenes[0].narration:TOO_LONG", "scenes[0].code:TOO_LONG");
        assertThat(codes(ShortsScript.checkRender("", scenes)))
                .contains("title:REQUIRED", "scenes[1].caption:REQUIRED", "scenes[1].narration:REQUIRED");
        assertThat(codes(ShortsScript.checkSave("t", Collections.nCopies(17, new Scene("a", "b", null))))).contains("scenes:TOO_MANY");
    }

    @Test
    void 예상_길이가_90초를_넘으면_영상으로_못_만든다() {
        List<Scene> ten = Collections.nCopies(10, new Scene("길다", "가".repeat(60), null));
        // 장면마다 60자 ÷ 6.5 + 0.6 ≈ 9.8초, 열 장면이면 약 98초
        assertThat(ShortsScript.estimateSeconds(ten)).isBetween(97.0, 99.0);
        assertThat(codes(ShortsScript.checkRender("제목", ten))).contains("scenes:TOO_LONG");
    }

    @Test
    void 일분_이십초_대본은_영상으로_만들_수_있다() {
        // 장면마다 75자 ÷ 6.5 + 0.6 ≈ 12.1초, 여섯 장면이면 약 73초
        List<Scene> six = Collections.nCopies(6, new Scene("요점", "가".repeat(75), null));
        assertThat(ShortsScript.estimateSeconds(six)).isBetween(72.0, 74.0);
        assertThat(codes(ShortsScript.checkRender("제목", six))).doesNotContain("scenes:TOO_LONG");
    }

    @Test
    void 마지막_내레이션이_쉼표로_끝나면_잘린_말로_본다() {
        // 2026-11-16: "루프" 규칙으로 "처음 돌렸을 땐 저도,"처럼 끝낸 대본이 영상에서 말이 잘린 채 끝났다
        List<Scene> cut = List.of(new Scene("a", "분석기가 말한 34건, 대부분 오탐이었어요.", null),
                new Scene("b", "도우미로 코드를 바꿨어요.", null), new Scene("c", "전체 코드는 블로그 글에서 보세요. 처음 돌렸을 땐 저도,", null));
        assertThat(ShortsScript.endsCutOff(cut)).isTrue();
        // 가운데 장면의 쉼표, 끝 공백, 말 없는 끝 장면은 보지 않거나 건너뛴다
        List<Scene> ok = List.of(new Scene("a", "34건, 대부분 오탐이었어요.", null),
                new Scene("b", "도우미로 코드를 바꿨어요.  ", null), new Scene("c", "", null));
        assertThat(ShortsScript.endsCutOff(ok)).isFalse();
    }

    @Test
    void 영상으로_읽을_끝_문장은_매달린_이음_구절을_뗀다() {
        assertThat(ShortsScript.completeEnding("전체 코드는 블로그 글에서 보세요. 처음 돌렸을 땐 저도,")).isEqualTo("전체 코드는 블로그 글에서 보세요.");
        assertThat(ShortsScript.completeEnding("정말 그럴까요? 그래서 결국 다시, ")).isEqualTo("정말 그럴까요?");
        // 완결 문장이 없으면 끝 쉼표만 마침표로
        assertThat(ShortsScript.completeEnding("이렇게 해서,")).isEqualTo("이렇게 해서.");
        assertThat(ShortsScript.completeEnding("v2.25에서 바꿨는데,")).isEqualTo("v2.25에서 바꿨는데.");
        // 쉼표로 끝나지 않으면 그대로(가운데 쉼표·소수점 포함)
        assertThat(ShortsScript.completeEnding("응답 1.5초, 다 됐어요")).isEqualTo("응답 1.5초, 다 됐어요");
    }

    @Test
    void 보이지_않는_글자와_앞뒤_공백을_지운다() {
        Scene s = new Scene("  자막​ ", " 내레이션 ", "  ");
        assertThat(s.caption()).isEqualTo("자막");
        assertThat(s.narration()).isEqualTo("내레이션");
        assertThat(s.code()).isNull();
    }

    @Test
    void 캐릭터는_소문자로_다듬고_워커가_아는_이름만_받는다() {
        assertThat(ShortsScript.cleanCharacter(" Dubu ")).isEqualTo("dubu");
        assertThat(ShortsScript.cleanCharacter("  ")).isNull();
        assertThat(ShortsScript.cleanCharacter(null)).isNull();
        assertThat(ShortsScript.checkCharacter("dubu")).isEmpty();
        assertThat(ShortsScript.checkCharacter(null)).isEmpty();
        assertThat(ShortsScript.checkCharacter("pikachu")).extracting(FieldErrorItem::field).containsExactly("character");
    }
}
