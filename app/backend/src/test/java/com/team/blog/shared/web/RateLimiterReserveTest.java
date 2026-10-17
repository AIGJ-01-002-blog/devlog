package com.team.blog.shared.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.team.blog.support.IntegrationTest;

/** 자리를 잡고 돌려주는 카운터 (#382 리뷰): 돌려줄 때 창이 바뀌었으면 새 창을 건드리지 않고, 0이 되면 창을 지운다. */
class RateLimiterReserveTest extends IntegrationTest {
    @Autowired RateLimiter limiter;

    @Test
    void 돌려준_자리는_다시_쓸_수_있고_0이_되면_창이_사라진다() {
        RateLimiter.Slot a = limiter.reserve("t:reserve", 2, Duration.ofHours(1));
        RateLimiter.Slot b = limiter.reserve("t:reserve", 2, Duration.ofHours(1));
        RateLimiter.Slot c = limiter.reserve("t:reserve", 2, Duration.ofHours(1));
        assertThat(a.allowed()).isTrue();
        assertThat(b.allowed()).isTrue();
        assertThat(c.allowed()).isFalse();

        limiter.release(c);
        limiter.release(b);
        assertThat(limiter.count("t:reserve")).isEqualTo(1);
        limiter.release(a);
        assertThat(redis.hasKey("rl:t:reserve")).isFalse();
    }

    @Test
    void 창이_바뀐_뒤에_돌려주면_새_창의_수는_그대로다() {
        RateLimiter.Slot old = limiter.reserve("t:window", 5, Duration.ofHours(1));
        // 그 사이 창이 끝나고 다른 요청이 새 창을 열었다
        redis.delete("rl:t:window");
        limiter.reserve("t:window", 5, Duration.ofHours(1));
        limiter.reserve("t:window", 5, Duration.ofHours(1));

        limiter.release(old);
        assertThat(limiter.count("t:window")).isEqualTo(2);
    }
}
