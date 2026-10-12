package com.team.blog.shared.scheduling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

/** 예약 작업 잠금: 잡으면 한 번 돌고 풀고, 못 잡거나 Redis가 죽으면 건너뛴다. */
class JobLockTest {
    final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    final ValueOperations<String, String> ops = mock(ValueOperations.class);
    final JobLock lock = new JobLock(redis);
    final AtomicInteger runs = new AtomicInteger();

    @BeforeEach
    void setUp() {
        when(redis.opsForValue()).thenReturn(ops);
    }

    void acquire(Boolean result) {
        when(ops.setIfAbsent(eq("lock:job:purge"), anyString(), eq(Duration.ofMinutes(5)))).thenReturn(result);
    }

    @Test
    void runsOnceAndReleasesItsOwnToken() {
        acquire(true);
        assertThat(lock.runExclusively("purge", Duration.ofMinutes(5), runs::incrementAndGet)).isTrue();
        assertThat(runs).hasValue(1);
        verify(redis).execute(any(RedisScript.class), eq(List.of("lock:job:purge")), anyString());
    }

    @Test
    void skipsWhenAnotherServerHoldsTheLock() {
        acquire(false);
        assertThat(lock.runExclusively("purge", Duration.ofMinutes(5), runs::incrementAndGet)).isFalse();
        assertThat(runs).hasValue(0);
        verify(redis, never()).execute(any(RedisScript.class), anyList(), any());
    }

    @Test
    void skipsWhenRedisAnswersNothing() {
        acquire(null);
        assertThat(lock.runExclusively("purge", Duration.ofMinutes(5), runs::incrementAndGet)).isFalse();
        assertThat(runs).hasValue(0);
    }

    @Test
    void skipsWhenRedisIsDown() {
        when(ops.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenThrow(new RedisConnectionFailureException("down"));
        assertThat(lock.runExclusively("purge", Duration.ofMinutes(5), runs::incrementAndGet)).isFalse();
        assertThat(runs).hasValue(0);
    }

    @Test
    void releasesEvenWhenTheJobFails() {
        acquire(true);
        Duration ttl = Duration.ofMinutes(5);
        assertThatThrownBy(() -> lock.runExclusively("purge", ttl, () -> { throw new IllegalStateException("job"); }))
                .isInstanceOf(IllegalStateException.class).hasMessage("job");
        verify(redis).execute(any(RedisScript.class), eq(List.of("lock:job:purge")), anyString());
    }

    @Test
    void stillReportsSuccessWhenReleaseFails() {
        acquire(true);
        when(redis.execute(any(RedisScript.class), anyList(), any())).thenThrow(new RedisConnectionFailureException("down"));
        assertThat(lock.runExclusively("purge", Duration.ofMinutes(5), runs::incrementAndGet)).isTrue();
        assertThat(runs).hasValue(1);
    }
}
