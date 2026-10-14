package com.team.blog.mcp.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class EvidenceLinksTest {
    @Test
    void 깃허브_주소는_종류와_번호로_이름을_붙인다() {
        assertThat(EvidenceLinks.of("https://github.com/o/devlog/pull/12").markdown()).isEqualTo("[PR #12 (devlog)](https://github.com/o/devlog/pull/12)");
        assertThat(EvidenceLinks.of("https://github.com/o/devlog/pull/12/files#diff-1").label()).isEqualTo("PR #12 (devlog)");
        assertThat(EvidenceLinks.of("https://github.com/o/devlog/commit/abcdef1234567").label()).isEqualTo("커밋 abcdef1 (devlog)");
        assertThat(EvidenceLinks.of("https://github.com/o/devlog/issues/3").label()).isEqualTo("이슈 #3 (devlog)");
        assertThat(EvidenceLinks.of("https://github.com/o/devlog/releases/tag/v1.2.0").label()).isEqualTo("릴리스 v1.2.0 (devlog)");
        assertThat(EvidenceLinks.of("https://gitlab.com/g/sub/app/-/merge_requests/7").label()).isEqualTo("PR #7");
        assertThat(EvidenceLinks.of("https://www.github.com/o").label()).isEqualTo("github.com");
        assertThat(EvidenceLinks.of("https://docs.spring.io/spring-session/").label()).isEqualTo("docs.spring.io");
    }

    @Test
    void https가_아니거나_링크를_깨뜨리는_주소는_받지_않는다() {
        assertThat(EvidenceLinks.of("http://github.com/o/r/pull/1")).isNull();
        assertThat(EvidenceLinks.of("https://user:pw@github.com/o/r")).isNull();
        assertThat(EvidenceLinks.of("https://github.com:8443/o/r")).isNull();
        assertThat(EvidenceLinks.of("https://github.com/o/r/pull/1) [x](javascript:alert(1)")).isNull();
        assertThat(EvidenceLinks.of("javascript:alert(1)")).isNull();
        assertThat(EvidenceLinks.of("https://x.dev/" + "a".repeat(300))).isNull();
        assertThat(EvidenceLinks.of("  ")).isNull();
    }

    @Test
    void 저장한_값에서_다시_읽는다() {
        var links = java.util.List.of(EvidenceLinks.of("https://github.com/o/r/pull/1"), EvidenceLinks.of("https://x.dev/a"));
        assertThat(EvidenceLinks.fromStored(EvidenceLinks.toStored(links))).isEqualTo(links);
        assertThat(EvidenceLinks.fromStored("")).isEmpty();
    }
}
