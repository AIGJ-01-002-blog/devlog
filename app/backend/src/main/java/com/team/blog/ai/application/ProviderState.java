package com.team.blog.ai.application;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import com.team.blog.ai.application.TagModel.ModelException;

/**
 * 외부 AI 상태 (018 FR-015~FR-017): 오늘 호출 수, 하루 한도 소진 표시, 60초 쉼 표시. 서버가 여러 대여도 같이 보도록 Redis에 둔다.
 * 날짜는 외부 AI 한도가 초기화되는 시간대로 센다. Redis 오류는 그대로 올려 부르는 쪽이 "사용할 수 없음"으로 답한다.
 */
@Component
class ProviderState {
    static final String PREFIX = "ai:gemini:";
    private static final Duration DAY_KEEP = Duration.ofDays(2);
    static final int UNKNOWN_QUOTA_STREAK = 3;
    private static final String COUNT = "count:";
    private static final String UNKNOWN = "unknown:";

    private final StringRedisTemplate redis;
    private final AiProperties props;
    private final Clock clock;

    ProviderState(StringRedisTemplate redis, AiProperties props, Clock clock) {
        this.redis = redis;
        this.props = props;
        this.clock = clock;
    }

    private String day() {
        return LocalDate.now(clock.withZone(props.gemini().resetZone())).toString();
    }

    /** 지금 외부 AI를 불러도 되는지. 우리가 센 호출 수가 한도에 이르면 한도 초과를 받기 전에 소진 표시를 한다. */
    boolean geminiUsable() {
        if (!props.gemini().configured()) return false;
        String day = day();
        if (Boolean.TRUE.equals(redis.hasKey(PREFIX + "exhausted:" + day)) || Boolean.TRUE.equals(redis.hasKey(PREFIX + "cooldown"))) {
            return false;
        }
        String count = redis.opsForValue().get(PREFIX + COUNT + day);
        if (count != null && Long.parseLong(count) >= props.gemini().dailyLimit()) {
            exhaust(day);
            return false;
        }
        return true;
    }

    static final String LOCAL_DOWN = "ai:local:down";

    /** 자체 AI를 불러 볼 만한지. 방금 연결에 실패했으면 downFor 동안 건너뛴다 */
    boolean localUsable() {
        return props.local().configured() && !Boolean.TRUE.equals(redis.hasKey(LOCAL_DOWN));
    }

    /** 연결 실패·시간 초과만 표시한다. 형식 오류는 집 PC가 켜져 있다는 뜻이라 건너뛰지 않는다 */
    void localFailed(ModelException.Kind kind) {
        if (kind == ModelException.Kind.FAILURE) redis.opsForValue().set(LOCAL_DOWN, "1", props.local().downFor());
    }

    void succeeded() {
        String day = day();
        redis.opsForValue().increment(PREFIX + COUNT + day);
        redis.expire(PREFIX + COUNT + day, DAY_KEEP);
        redis.delete(PREFIX + UNKNOWN + day);
    }

    /** 실패 종류별 처리 (FR-016). 형식 오류는 공급자 탓이 아니라 쉬지 않는다. */
    void failed(ModelException.Kind kind) {
        String day = day();
        switch (kind) {
            case DAILY_QUOTA -> exhaust(day);
            case MINUTE_QUOTA, FAILURE -> cooldown();
            case UNKNOWN_QUOTA -> {
                cooldown();
                Long streak = redis.opsForValue().increment(PREFIX + UNKNOWN + day);
                redis.expire(PREFIX + UNKNOWN + day, DAY_KEEP);
                if (streak != null && streak >= UNKNOWN_QUOTA_STREAK) exhaust(day);
            }
            case INVALID -> succeeded();
        }
    }

    private void cooldown() {
        redis.opsForValue().set(PREFIX + "cooldown", "1", props.cooldown());
    }

    /** 날짜가 든 열쇠라 다음 초기화 시각이 지나면 저절로 풀린다 */
    private void exhaust(String day) {
        redis.opsForValue().set(PREFIX + "exhausted:" + day, "1", DAY_KEEP);
    }
}
