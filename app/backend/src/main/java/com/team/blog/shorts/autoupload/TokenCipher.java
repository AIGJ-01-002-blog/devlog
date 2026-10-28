package com.team.blog.shorts.autoupload;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * 플랫폼 토큰 암호화 (spec 108). AES-256-GCM, 12바이트 무작위 IV. 저장 모양은 "v1:" + base64(IV‖암호문‖태그).
 * 행(회원·플랫폼)을 AAD로 묶어 다른 행에 옮겨 붙인 암호문은 풀리지 않는다. 예외 메시지에 토큰을 싣지 않는다.
 */
public final class TokenCipher {
    private static final String PREFIX = "v1:";
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private final SecretKey key;
    private final SecureRandom random = new SecureRandom();

    public TokenCipher(String base64Key) {
        byte[] raw;
        try {
            raw = Base64.getDecoder().decode(base64Key == null ? "" : base64Key.strip());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("SHORTS_TOKEN_KEY는 base64여야 합니다 (openssl rand -base64 32)");
        }
        if (raw.length != 32) throw new IllegalStateException("SHORTS_TOKEN_KEY는 32바이트(base64 44자)여야 합니다 (openssl rand -base64 32)");
        this.key = new SecretKeySpec(raw, "AES");
    }

    public String encrypt(String plain, String context) {
        if (plain == null) return null;
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            c.updateAAD(context.getBytes(StandardCharsets.UTF_8));
            byte[] ct = c.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            return PREFIX + Base64.getEncoder().encodeToString(ByteBuffer.allocate(iv.length + ct.length).put(iv).put(ct).array());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("토큰을 암호화하지 못했습니다: " + e.getClass().getSimpleName());
        }
    }

    public String decrypt(String stored, String context) {
        if (stored == null) return null;
        if (!stored.startsWith(PREFIX)) throw new IllegalStateException("모르는 토큰 암호문 모양입니다");
        try {
            byte[] all = Base64.getDecoder().decode(stored.substring(PREFIX.length()));
            if (all.length <= IV_BYTES) throw new IllegalStateException("토큰 암호문이 짧습니다");
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, all, 0, IV_BYTES));
            c.updateAAD(context.getBytes(StandardCharsets.UTF_8));
            return new String(c.doFinal(all, IV_BYTES, all.length - IV_BYTES), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalStateException("토큰을 풀지 못했습니다(열쇠가 바뀌었을 수 있어요): " + e.getClass().getSimpleName());
        }
    }

    /** AAD: 이 회원·플랫폼·칸의 토큰만 풀린다 */
    public static String context(long memberId, AutoPlatform platform, String column) {
        return memberId + ":" + platform.name() + ":" + column;
    }
}
