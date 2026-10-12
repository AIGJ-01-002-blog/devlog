package com.team.blog.ai.application;

import java.util.List;
import java.util.Optional;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * 추천 결과 보관 (018 FR-022~FR-026). ① 같은 내용: 식별값 → 태그·공급자, 30일. ② 같은 글: 지난번 정리한 입력·태그·공급자, 글당 1개 7일.
 * 저장은 이미 붙인 태그를 빼기 전 결과로 한다. 실패·빈 결과는 저장하지 않는다(부르는 쪽 책임).
 */
@Component
class SuggestionStore {
    static final String EXACT = "ai:tags:exact:";
    static final String POST = "ai:tags:post:";

    private final StringRedisTemplate redis;
    private final AiProperties props;
    private final JsonMapper json = JsonMapper.builder().build();

    SuggestionStore(StringRedisTemplate redis, AiProperties props) {
        this.redis = redis;
        this.props = props;
    }

    record Stored(List<String> tags, TagModel.Provider provider) {}

    record ForPost(String input, List<String> tags, TagModel.Provider provider) {}

    Optional<Stored> findExact(String key) {
        return read(EXACT + key, new TypeReference<Stored>() {});
    }

    Optional<ForPost> forPost(long postId) {
        return read(POST + postId, new TypeReference<ForPost>() {});
    }

    void save(String key, long postId, String input, List<String> tags, TagModel.Provider provider) {
        redis.opsForValue().set(EXACT + key, json.writeValueAsString(new Stored(tags, provider)), props.exactKeep());
        redis.opsForValue().set(POST + postId, json.writeValueAsString(new ForPost(input, tags, provider)), props.similarKeep());
    }

    private <T> Optional<T> read(String key, TypeReference<T> type) {
        String v = redis.opsForValue().get(key);
        if (v == null) return Optional.empty();
        try {
            return Optional.ofNullable(json.readValue(v, type));
        } catch (RuntimeException e) {
            // 형식이 바뀐 옛 값은 없는 것으로 본다
            return Optional.empty();
        }
    }
}
