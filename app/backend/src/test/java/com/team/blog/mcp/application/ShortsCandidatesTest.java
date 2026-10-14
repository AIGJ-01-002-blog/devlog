package com.team.blog.mcp.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ShortsCandidatesTest {
    private static final String LONG = "가".repeat(ShortsCandidates.MIN_TEXT);

    @Test
    void 문제_숫자_코드_가운데_둘_이상이고_글이_충분히_길면_후보다() {
        ShortsCandidates.Fit f = ShortsCandidates.fit(LONG + " 빌드가 느려지는 문제가 있었는데 3분에서 40초로 줄였어요.");
        assertThat(f.fit()).isTrue();
        assertThat(f.reasons()).containsExactly("직접 겪은 문제와 해결", "숫자로 된 결과");
        assertThat(ShortsCandidates.fit(LONG + " 원인을 찾아 고쳤어요.\n```java\nint x = 1;\n```").reasons())
                .containsExactly("직접 겪은 문제와 해결", "화면에 보일 코드");
    }

    @Test
    void 한_가지뿐이거나_짧으면_후보가_아니다() {
        assertThat(ShortsCandidates.fit(LONG + " 오늘은 회고를 썼어요. 버전 2.0을 준비해요.").fit()).isFalse();
        assertThat(ShortsCandidates.fit("오류를 고쳐 3초 빨라졌어요.").fit()).isFalse();
        // 코드 블록 안의 글자는 길이와 숫자에 세지 않는다
        assertThat(ShortsCandidates.fit("문제를 고쳤어요.\n```\n" + "sleep 3초\n".repeat(80) + "```").fit()).isFalse();
        assertThat(ShortsCandidates.fit(null).fit()).isFalse();
    }
}
