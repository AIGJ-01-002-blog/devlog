package com.team.blog.mcp.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** 글감 근거 조각에서 비밀값처럼 보이는 값을 가린다 (075). 이름과 구분자는 남기고 값만 **** */
class TopicSuggesterMaskTest {

    static String mask(String raw) {
        return TopicSuggester.mask(raw).text();
    }

    @Test
    void 비밀값_이름_뒤의_값만_가린다() {
        assertThat(mask("password=hunter2 다음")).isEqualTo("password=**** 다음");
        assertThat(mask("API-Key : abc def")).isEqualTo("API-Key : **** def");
        assertThat(mask("비밀번호: \"a b c\" 끝")).isEqualTo("비밀번호: **** 끝");
        assertThat(mask("token='x y'z")).isEqualTo("token=****z");
        // 따옴표가 닫히지 않으면 공백 전까지
        assertThat(mask("secret=\"abc def")).isEqualTo("secret=**** def");
        assertThat(mask("pwd=a token=b")).isEqualTo("pwd=**** token=****");
    }

    @Test
    void 값이_없으면_그대로_두고_다음_이름을_찾는다() {
        assertThat(mask("token:")).isEqualTo("token:");
        assertThat(mask("password=\"열린 따옴표 token:")).isEqualTo("password=**** 따옴표 token:");
        // 공백에는 줄바꿈도 들어가서 다음 줄 첫 덩어리가 값이 된다
        assertThat(mask("token: \npassword=x")).isEqualTo("token: \n****");
        assertThat(mask("토큰 이야기")).isEqualTo("토큰 이야기");
        assertThat(TopicSuggester.mask("그냥 메모").changed()).isFalse();
    }

    @Test
    void IP_메일_Bearer도_가린다() {
        TopicSuggester.Masked m = TopicSuggester.mask("me@corp.io 로 token: abc123 보냄, Authorization: Bearer abcdefghijkl, 192.168.0.10");
        assertThat(m.changed()).isTrue();
        assertThat(m.text()).doesNotContain("me@corp.io", "abc123", "abcdefghijkl", "192.168.0.10");
    }

    @Test
    void 이름만_길게_이어져도_한_번만_훑는다() {
        long started = System.nanoTime();
        assertThat(mask("token:".repeat(30_000))).startsWith("token:****");
        assertThat(java.time.Duration.ofNanos(System.nanoTime() - started)).isLessThan(java.time.Duration.ofSeconds(5));
    }
}
