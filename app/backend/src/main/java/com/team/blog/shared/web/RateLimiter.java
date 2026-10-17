package com.team.blog.shared.web;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

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

    /**
     * tryAcquire와 같고, 잡은 자리가 어느 창인지 알 수 있게 창 번호도 돌려준다. 창 번호는 창을 연 요청이 정한 임의 값으로
     * 옆 키(KEYS[2])에 같은 수명으로 둔다. 끝나는 시각(PEXPIRETIME)은 같은 밀리초에 열린 두 창을 가르지 못해 쓰지 않는다
     */
    @SuppressWarnings("rawtypes")
    private static final RedisScript<List> RESERVE = RedisScript.of("""
            local c = redis.call('INCR', KEYS[1])
            if c == 1 then
              redis.call('PEXPIRE', KEYS[1], ARGV[1])
              redis.call('SET', KEYS[2], ARGV[2], 'PX', ARGV[1])
            end
            return {c, redis.call('GET', KEYS[2]) or ''}
            """, List.class);

    /**
     * 잡은 자리를 돌려준다. 자리를 잡은 창이 아직 그대로일 때만 줄여서, 그새 창이 바뀌었으면 새 창의 수를 건드리지 않는다.
     * 0이 되면 키를 지워 다음 첫 요청이 새 창을 연다(아무도 세지 않은 창이 남아 늦게 시작한 요청의 창을 줄이지 않게)
     */
    private static final RedisScript<Long> RELEASE = RedisScript.of("""
            if redis.call('EXISTS', KEYS[1]) == 0 or redis.call('GET', KEYS[2]) ~= ARGV[1] then return -1 end
            local c = redis.call('DECR', KEYS[1])
            if c <= 0 then redis.call('DEL', KEYS[1], KEYS[2]) end
            return c
            """, Long.class);

    /**
     * reserve로 잡은 자리. windowId는 그 창의 번호이고, 모르면(Redis 장애) 빈 문자열.
     * @param allowed 한도 안이면 true
     */
    public record Slot(String key, boolean allowed, String windowId) {}

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

    /**
     * 일을 하기 전에 한 자리를 잡는다. 일이 실패해 세지 않을 때는 release로 돌려준다.
     * 동시에 들어온 요청도 INCR 한 번으로 자리를 나눠 가져 한도를 함께 넘지 않는다. Redis 장애 중에는 통과시킨다
     */
    public Slot reserve(String key, int limit, Duration window) {
        try {
            List<?> r = redis.execute(RESERVE, List.of("rl:" + key, "rl:" + key + "#w"),
                    String.valueOf(window.toMillis()), UUID.randomUUID().toString());
            long count = ((Number) r.get(0)).longValue();
            return new Slot(key, count <= limit, String.valueOf(r.get(1)));
        } catch (RuntimeException e) {
            log.warn("요청 제한 카운터를 쓸 수 없어 통과시킵니다: {}", e.getMessage());
            return new Slot(key, true, "");
        }
    }

    /** reserve로 잡은 자리를 돌려준다. 창이 이미 바뀌었거나 Redis 장애면 그대로 둔다(창이 끝날 때 저절로 맞춰진다) */
    public void release(Slot slot) {
        if (slot.windowId().isEmpty()) return;
        try {
            redis.execute(RELEASE, List.of("rl:" + slot.key(), "rl:" + slot.key() + "#w"), slot.windowId());
        } catch (RuntimeException e) {
            log.warn("요청 제한 카운터를 되돌리지 못했습니다: {}", e.getMessage());
        }
    }

    /** 센 수를 지운다 (탈퇴 정리 020 FR-025). Redis 장애면 창이 끝날 때 저절로 사라진다. */
    public void forget(String key) {
        try {
            redis.delete(List.of("rl:" + key, "rl:" + key + "#w"));
        } catch (RuntimeException e) {
            // 키는 창 길이만큼만 산다
        }
    }

    public void check(String key, int limit, Duration window) {
        if (!tryAcquire(key, limit, window)) throw new TooManyRequestsException(window.toSeconds());
    }
}
