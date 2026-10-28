package com.team.blog.shorts.autoupload;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** 토큰 암호화 (spec 108 FR-002): 되돌려 풀리고, 평문이 남지 않고, 다른 행·다른 열쇠로는 풀리지 않는다 */
class TokenCipherTest {
    static final String KEY = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=";
    static final String OTHER = "HxwdGhsYGRYXFBUSExARDg8MDQoLCAkGBwQFAgMAAQ8=";

    @Test
    void 암호화한_토큰은_같은_행에서_그대로_풀린다() {
        TokenCipher c = new TokenCipher(KEY);
        String ctx = TokenCipher.context(7, AutoPlatform.YOUTUBE, "refresh");
        String token = "1//0g-refresh-토큰-" + "x".repeat(200);
        String enc = c.encrypt(token, ctx);
        assertThat(enc).startsWith("v1:").doesNotContain("refresh-").doesNotContain("x".repeat(20));
        assertThat(c.decrypt(enc, ctx)).isEqualTo(token);
        assertThat(c.encrypt(token, ctx)).as("IV가 매번 달라 같은 토큰도 다른 암호문").isNotEqualTo(enc);
        assertThat(c.encrypt(null, ctx)).isNull();
        assertThat(c.decrypt(null, ctx)).isNull();
    }

    @Test
    void 다른_행이나_다른_열쇠_고친_암호문은_풀리지_않는다() {
        TokenCipher c = new TokenCipher(KEY);
        String enc = c.encrypt("secret-access", TokenCipher.context(7, AutoPlatform.YOUTUBE, "access"));
        assertThatThrownBy(() -> c.decrypt(enc, TokenCipher.context(8, AutoPlatform.YOUTUBE, "access")))
                .isInstanceOf(IllegalStateException.class).message().doesNotContain("secret-access");
        assertThatThrownBy(() -> c.decrypt(enc, TokenCipher.context(7, AutoPlatform.INSTAGRAM, "access"))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new TokenCipher(OTHER).decrypt(enc, TokenCipher.context(7, AutoPlatform.YOUTUBE, "access")))
                .isInstanceOf(IllegalStateException.class);
        String tampered = enc.substring(0, enc.length() - 3) + (enc.endsWith("A==") ? "B==" : "A==");
        assertThatThrownBy(() -> c.decrypt(tampered, TokenCipher.context(7, AutoPlatform.YOUTUBE, "access"))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> c.decrypt("plain-token", "x")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 열쇠는_base64_32바이트여야_한다() {
        assertThatThrownBy(() -> new TokenCipher("short")).isInstanceOf(IllegalStateException.class).hasMessageContaining("openssl rand -base64 32");
        assertThatThrownBy(() -> new TokenCipher("AAEC")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new TokenCipher(null)).isInstanceOf(IllegalStateException.class);
    }
}
