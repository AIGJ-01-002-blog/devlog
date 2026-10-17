package com.team.blog.shared.web;

import java.time.Duration;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/**
 * Redis 고정 창 카운터. Redis 장애 중에는 통과시킨다 (docs/02 §2-1: 요청 제한은 장애 시 통과 + 경고 로그).
 */
@Component
public class RateLimiter {
    private static final Logger log = LoggerFactory.getLogger(RateLimiter.class);
    private static final RedisScript<Long> INCR_EXPIRE = RedisScript.of("""
            local c = redis.call('INCR', KEYS[1])
            if c == 1 then redis.call('PEXPIRE', KEYS[1], ARGV[1]) end
            return c
            """, Long.class);

    /** 센 수를 하나 되돌린다. 키가 없거나 0이면 그대로 둔다 (창 길이는 그대로) */
    private static final RedisScript<Long> DECR_IF_POSITIVE = RedisScript.of("""
            local c = tonumber(redis.call('GET', KEYS[1]) or '0')
            if c > 0 then return redis.call('DECR', KEYS[1]) end
            return 0
            """, Long.class);

    private final StringRedisTemplate redis;

    public RateLimiter(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /** @return 허용되면 true */
    public boolean tryAcquire(String key, int limit, Duration window) {
        try {
            Long count = redis.execute(INCR_EXPIRE, List.of("rl:" + key), String.valueOf(window.toMillis()));
            return count == null || count <= limit;
        } catch (RuntimeException e) {
            log.warn("요청 제한 카운터를 쓸 수 없어 통과시킵니다: {}", e.getMessage());
            return true;
        }
    }

    /** 지금까지 센 수. 모르면(Redis 장애) 0. */
    public long count(String key) {
        try {
            String v = redis.opsForValue().get("rl:" + key);
            return v == null ? 0 : Long.parseLong(v);
        } catch (RuntimeException e) {
            return 0;
        }
    }

    /** tryAcquire로 미리 잡은 한 번을 돌려준다 (그 일이 실패해 세지 않을 때). Redis 장애면 창이 끝날 때 저절로 맞춰진다 */
    public void release(String key) {
        try {
            redis.execute(DECR_IF_POSITIVE, List.of("rl:" + key));
        } catch (RuntimeException e) {
            log.warn("요청 제한 카운터를 되돌리지 못했습니다: {}", e.getMessage());
        }
    }

    /** 센 수를 지운다 (탈퇴 정리 020 FR-025). Redis 장애면 창이 끝날 때 저절로 사라진다. */
    public void forget(String key) {
        try {
            redis.delete("rl:" + key);
        } catch (RuntimeException e) {
            // 키는 창 길이만큼만 산다
        }
    }

    public void check(String key, int limit, Duration window) {
        if (!tryAcquire(key, limit, window)) throw new TooManyRequestsException(window.toSeconds());
    }
}
