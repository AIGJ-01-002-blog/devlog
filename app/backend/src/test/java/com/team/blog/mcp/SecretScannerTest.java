package com.team.blog.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.team.blog.mcp.application.SecretScanner;

/** spec 086: AI가 쓴 글에서 형식이 뚜렷한 비밀값만 잡고, 흔한 자리표시자·설명 문장은 넘긴다. */
class SecretScannerTest {

    @Test
    void 형식이_뚜렷한_비밀값을_종류로_알려_준다() {
        assertThat(SecretScanner.find("token=ghp_" + "A1".repeat(18))).containsExactly("GitHub 토큰");
        assertThat(SecretScanner.find("dvl_" + "x".repeat(43))).containsExactly("devlog 접근 토큰");
        assertThat(SecretScanner.find("OPENAI=sk-proj-" + "Z9".repeat(15))).containsExactly("AI API 키");
        assertThat(SecretScanner.find("AKIAIOSFODNN7EXAMPLE")).containsExactly("AWS 접근 키");
        assertThat(SecretScanner.find("-----BEGIN OPENSSH PRIVATE KEY-----")).containsExactly("개인 키");
        assertThat(SecretScanner.find("eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.dozjgNryP4J3jVmNHl0w5N_XgL0n3I9PlFUP0THsR8U"))
                .containsExactly("JWT");
        assertThat(SecretScanner.find("주민번호 990101-1234567")).containsExactly("주민등록번호");
        assertThat(SecretScanner.find("jdbc:postgresql://x", "redis://default:Abc123xyz@cache:6379")).containsExactly("주소 속 비밀번호");
        assertThat(SecretScanner.find("ghp_" + "a".repeat(36), "ghp_" + "b".repeat(36))).containsExactly("GitHub 토큰");
    }

    @Test
    void 설명과_자리표시자는_넘긴다() {
        assertThat(SecretScanner.find(
                "토큰은 dvl_로 시작해요. GitHub 토큰은 ghp_xxx 꼴이고 키는 sk-... 처럼 생겼어요.",
                "postgres://user:${DB_PASSWORD}@db/blog, postgres://app:<password>@db, mysql://root:****@localhost",
                "https://devlog.life/@minseo/posts/12, 전화 010-1234-5678, 2026-10-11", null, "")).isEmpty();
    }
}
