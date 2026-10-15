package com.team.blog.shared.cursor;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.List;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import com.team.blog.shared.error.ApiException;

/**
 * 목록 커서 (docs/10 §4-2): {@code {"v":1,"l":목록,"k":[정렬 키…],"s":서명}}을 Base64URL(패딩 없음)로 감싼다.
 * 서명은 고친 커서와 다른 목록의 커서를 거부하기 위한 것이다 (003 FR-015).
 */
@Component
public class CursorCodec {
    private static final int VERSION = 1;
    private static final Base64.Encoder ENC = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DEC = Base64.getUrlDecoder();
    private final JsonMapper json = JsonMapper.builder().build();
    private final byte[] secret;

    public CursorCodec(@Value("${blog.cursor.secret:local-cursor-secret-change-me}") String secret) {
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
    }

    public String encode(String list, long... keys) {
        ObjectNode node = json.createObjectNode();
        node.put("v", VERSION);
        node.put("l", list);
        ArrayNode k = node.putArray("k");
        for (long key : keys) k.add(key);
        node.put("s", sign(list, keys));
        return ENC.encodeToString(json.writeValueAsBytes(node));
    }

    /** @return 정렬 키 목록. 커서가 없으면 빈 배열 */
    public long[] decode(String cursor, String list, int keyCount) {
        if (cursor == null || cursor.isEmpty()) return new long[0];
        try {
            if (cursor.length() > 512) throw invalid();
            JsonNode node = json.readTree(DEC.decode(cursor));
            if (!node.isObject() || node.path("v").asInt(-1) != VERSION) throw invalid();
            if (!list.equals(node.path("l").asString(null))) throw invalid();
            JsonNode k = node.path("k");
            if (!k.isArray() || k.size() != keyCount) throw invalid();
            long[] keys = new long[keyCount];
            for (int i = 0; i < keyCount; i++) {
                if (!k.get(i).isIntegralNumber()) throw invalid();
                keys[i] = k.get(i).asLong();
            }
            String sig = node.path("s").asString("");
            if (!MessageDigest.isEqual(sig.getBytes(StandardCharsets.UTF_8), sign(list, keys).getBytes(StandardCharsets.UTF_8))) {
                throw invalid();
            }
            return keys;
        } catch (ApiException e) {
            throw e;
        } catch (RuntimeException e) {
            throw invalid();
        }
    }

    private String sign(String list, long[] keys) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            StringBuilder sb = new StringBuilder().append(VERSION).append('|').append(list);
            for (long key : keys) sb.append('|').append(key);
            byte[] full = mac.doFinal(sb.toString().getBytes(StandardCharsets.UTF_8));
            byte[] cut = new byte[12];
            System.arraycopy(full, 0, cut, 0, cut.length);
            return ENC.encodeToString(cut);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static ApiException invalid() {
        return ApiException.badRequest("INVALID_CURSOR", "목록 위치 값이 올바르지 않아요. 처음부터 다시 불러와 주세요.");
    }

    public static <T> Page<T> page(List<T> fetched, int size, java.util.function.Function<T, String> cursorOf) {
        boolean hasNext = fetched.size() > size;
        List<T> items = hasNext ? fetched.subList(0, size) : fetched;
        String next = hasNext ? cursorOf.apply(items.get(items.size() - 1)) : null;
        return new Page<>(List.copyOf(items), next);
    }

    public record Page<T>(List<T> items, String nextCursor) {}
}
