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

    @Test
    void 경로_앞부분만_보고_이름을_붙인다() {
        assertThat(EvidenceLinks.label("github.com", "/o/devlog/compare/v1...v2")).isEqualTo("변경 비교 (devlog)");
        assertThat(EvidenceLinks.label("github.com", "/o/devlog/pull/12?tab=files#x")).isEqualTo("PR #12 (devlog)");
        assertThat(EvidenceLinks.label("github.com", "/o/devlog/pull/")).isEqualTo("github.com");
        assertThat(EvidenceLinks.label("github.com", "/o/devlog/tree/main")).isEqualTo("github.com");
        // gitlab은 하위 그룹이 몇 단이든 처음 맞는 /-/ 뒤를 본다
        assertThat(EvidenceLinks.label("gitlab.com", "/g/a/b/c/app/-/issues/9/edit")).isEqualTo("이슈 #9");
        assertThat(EvidenceLinks.label("gitlab.com", "/g/-/tree/-/commit/abcdef123")).isEqualTo("커밋 abcdef1");
        assertThat(EvidenceLinks.label("gitlab.com", "/-/issues/9")).isEqualTo("gitlab.com");
    }

    @Test
    void 긴_경로에도_한_번만_훑는다() {
        long started = System.nanoTime();
        assertThat(EvidenceLinks.label("gitlab.com", "/" + "a/-/".repeat(20_000))).isEqualTo("gitlab.com");
        assertThat(EvidenceLinks.label("github.com", "/o/r/pull/" + "1".repeat(100_000) + "/")).startsWith("PR #1");
        assertThat(java.time.Duration.ofNanos(System.nanoTime() - started)).isLessThan(java.time.Duration.ofSeconds(5));
    }
}
