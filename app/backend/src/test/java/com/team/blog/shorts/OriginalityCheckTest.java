package com.team.blog.shorts;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.team.blog.shorts.application.OriginalityCheck;
import com.team.blog.shorts.application.OriginalityCheck.Issue;
import com.team.blog.shorts.application.OriginalityCheck.Level;
import com.team.blog.shorts.application.ShortsScript.Scene;

/** spec 099 FR-003·FR-004 (#242): 양산형 방지 검사가 통과 예시는 통과시키고 차단 예시는 막는지 확인한다. */
class OriginalityCheckTest {

    private static final String POST = """
            # Redis 세션이 갑자기 풀리는 문제
            배포할 때마다 로그인한 사용자가 모두 로그아웃되는 문제가 있었다. 원인은 세션 직렬화 클래스가 바뀌면서
            기존 세션을 읽지 못한 것이었다. Spring Session의 직렬화를 JSON으로 바꾸고 클래스 이름 대신 타입 정보를 고정했다.
            그 뒤로는 배포해도 세션이 유지된다. 재배포 후 다시 로그인하는 사람이 하루 40명에서 0명이 되었다.
            """;

    private static final List<Scene> GOOD = List.of(
            new Scene("배포하면 다 로그아웃?", "왜 배포만 하면 사용자가 전부 로그아웃될까요?", null),
            new Scene("범인은 직렬화", "제가 로그를 뒤져 보니 세션을 저장한 클래스 모양이 바뀌어서 예전 세션을 못 읽고 있었어요.", null),
            new Scene("JSON으로 바꾸기", "세션 저장 방식을 자바 기본 직렬화에서 JSON으로 바꾸고, 타입 이름을 고정해 뒀어요.", "spring.session.redis.serializer: json"),
            new Scene("결과", "덕분에 재배포 뒤 다시 로그인하던 사람이 하루 마흔 명에서 한 명도 없게 줄었어요.", null));

    private static List<String> codes(OriginalityCheck.Report r, Level level) {
        return r.issues().stream().filter(i -> i.level() == level).map(Issue::code).toList();
    }

    @Test
    void 내_경험과_결과를_말로_풀어낸_대본은_막히지_않고_경고도_없다() {
        OriginalityCheck.Report r = OriginalityCheck.check(GOOD, POST, List.of());
        assertThat(r.passed()).isTrue();
        assertThat(r.issues()).isEmpty();
        assertThat(r.score()).isEqualTo(100);
    }

    @Test
    void 원본_글을_그대로_낭독하면_막는다() {
        List<Scene> copied = List.of(
                new Scene("문제", "배포할 때마다 로그인한 사용자가 모두 로그아웃되는 문제가 있었다.", null),
                new Scene("원인", "원인은 세션 직렬화 클래스가 바뀌면서 기존 세션을 읽지 못한 것이었다.", null),
                new Scene("해결", "Spring Session의 직렬화를 JSON으로 바꾸고 클래스 이름 대신 타입 정보를 고정했다.", null),
                new Scene("결과", "그 뒤로는 배포해도 세션이 유지된다.", null));
        OriginalityCheck.Report r = OriginalityCheck.check(copied, POST, List.of());
        assertThat(r.passed()).isFalse();
        assertThat(codes(r, Level.BLOCK)).contains("COPIED");
        assertThat(r.issues().stream().filter(i -> i.code().equals("COPIED")).findFirst().orElseThrow().message()).contains("원본 글");
    }

    @Test
    void 띄어쓰기와_문장_부호만_바꾼_내_다른_쇼츠는_복사본으로_본다() {
        List<Scene> respaced = GOOD.stream()
                .map(s -> new Scene(s.caption(), s.narration().replace(" ", "  ").replace("?", "!"), s.code())).toList();
        OriginalityCheck.Report r = OriginalityCheck.check(respaced, POST,
                List.of(new OriginalityCheck.OtherScript(7, "세션 로그아웃 이야기", GOOD)));
        assertThat(codes(r, Level.BLOCK)).contains("DUPLICATE");
        assertThat(r.score()).isLessThanOrEqualTo(60);
    }

    @Test
    void 주제가_다른_내_쇼츠와는_겹치지_않는다() {
        List<Scene> other = List.of(
                new Scene("N+1 쿼리", "왜 글 목록 하나 여는 데 쿼리가 백 번 나갈까요?", null),
                new Scene("페치 조인", "저는 작성자를 따로 불러오던 부분을 페치 조인 한 번으로 묶었어요.", null),
                new Scene("배치 크기", "태그처럼 여러 개 딸린 것은 배치 크기를 정해서 한꺼번에 읽게 했어요.", null),
                new Scene("결과", "목록 응답이 1.2초에서 0.15초로 빨라졌어요.", null));
        OriginalityCheck.Report r = OriginalityCheck.check(GOOD, POST, List.of(new OriginalityCheck.OtherScript(3, "N+1", other)));
        assertThat(r.passed()).isTrue();
    }

    @Test
    void 장면마다_같은_틀로_시작하면_막는다() {
        List<Scene> template = List.of(
                new Scene("1", "오늘 알아볼 내용은 레디스 세션이 왜 풀리는지예요?", null),
                new Scene("2", "오늘 알아볼 내용은 직렬화 클래스가 바뀌는 상황이에요.", null),
                new Scene("3", "오늘 알아볼 내용은 JSON 직렬화로 바꾸는 방법이에요.", null),
                new Scene("4", "제가 바꿔 보니 로그아웃이 사라졌어요.", null));
        assertThat(codes(OriginalityCheck.check(template, POST, List.of()), Level.BLOCK)).contains("TEMPLATE_OPENING");
    }

    @Test
    void 같은_말을_되풀이하는_장면과_내용_없는_장면을_막는다() {
        List<Scene> repeated = List.of(
                new Scene("왜?", "왜 배포만 하면 사용자가 전부 로그아웃될까요?", null),
                new Scene("다시", "왜 배포만 하면 사용자가 전부 로그아웃되는 걸까요?", null),
                new Scene("끝", "끝!", null));
        OriginalityCheck.Report r = OriginalityCheck.check(repeated, POST, List.of());
        assertThat(codes(r, Level.BLOCK)).contains("REPEATED_SCENE", "EMPTY_SCENE");
    }

    @Test
    void 경험이_없고_첫_장면이_약하고_원본이_없으면_경고만_하고_통과시킨다() {
        List<Scene> plain = List.of(
                new Scene("세션", "스프링 세션은 레디스에 사용자 로그인 정보를 저장하는 기능이에요.", null),
                new Scene("직렬화", "객체를 저장하려면 바이트나 문자열로 바꾸는 직렬화가 필요해요.", null),
                new Scene("JSON", "JSON 직렬화를 쓰면 클래스가 달라져도 저장된 값을 읽기 쉬워요.", null));
        OriginalityCheck.Report r = OriginalityCheck.check(plain, null, List.of());
        assertThat(r.passed()).isTrue();
        assertThat(codes(r, Level.WARN)).containsExactly("NO_EXPERIENCE", "WEAK_HOOK", "NO_SOURCE");
        assertThat(r.score()).isEqualTo(70);
    }

    @Test
    void 같은_입력이면_늘_같은_결과다() {
        assertThat(OriginalityCheck.check(GOOD, POST, List.of())).isEqualTo(OriginalityCheck.check(GOOD, POST, List.of()));
    }
}
