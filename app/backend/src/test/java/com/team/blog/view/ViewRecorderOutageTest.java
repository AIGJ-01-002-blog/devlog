package com.team.blog.view;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import com.team.blog.account.domain.Visibility;
import com.team.blog.post.access.PostAccessPolicy;
import com.team.blog.post.access.ReadablePost;
import com.team.blog.post.domain.PostStatus;
import com.team.blog.shared.config.BlogProperties;
import com.team.blog.shared.web.RateLimiter;
import com.team.blog.view.application.ViewRecorder;
import com.team.blog.view.application.ViewRecorder.Outcome;

/** 중복 판정 저장소가 멈추면 세지 않고 지나간다 (spec 013 FR-025). 글 읽기는 막지 않는다. */
class ViewRecorderOutageTest {

    @Test
    @SuppressWarnings("unchecked")
    void 저장소가_멈추면_세지_않고_오류도_내지_않는다() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenReturn(List.of(new ReadablePost(1L, PostStatus.PUBLISHED, Visibility.PUBLIC, false, false, false)));
        PostAccessPolicy policy = mock(PostAccessPolicy.class);
        when(policy.canRead(any(), any())).thenReturn(true);
        RateLimiter limiter = mock(RateLimiter.class);
        when(limiter.tryAcquire(anyString(), org.mockito.ArgumentMatchers.anyInt(), any(Duration.class))).thenReturn(true);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .thenThrow(new RedisConnectionFailureException("테스트: Redis 장애"));
        when(redis.opsForValue()).thenThrow(new RedisConnectionFailureException("테스트: Redis 장애"));
        BlogProperties props = mock(BlogProperties.class);
        when(props.view()).thenReturn(new BlogProperties.View(Duration.ofHours(24), 1, 60, "bot"));
        ViewRecorder recorder = new ViewRecorder(jdbc, redis, policy, limiter, props, Clock.systemUTC());

        assertThat(recorder.recordView(10L, new ViewRecorder.Visit(2L, false, null, null, "203.0.113.1", "Mozilla/5.0", false)))
                .isEqualTo(Outcome.SKIPPED);
        // 쿠키 없는 비회원은 그날 비밀값을 못 읽으므로 판정 없이 건너뛴다
        assertThat(recorder.recordView(10L, new ViewRecorder.Visit(null, false, null, null, "203.0.113.1", "Mozilla/5.0", false)))
                .isEqualTo(Outcome.SKIPPED);
    }
}
