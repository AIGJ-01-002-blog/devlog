package com.team.blog.paper.infra;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;

import com.team.blog.paper.application.PaperProperties;
import com.team.blog.paper.application.Quotes.QuotesUnavailable;

/** 업비트가 실패하면 캐시 시간 동안은 다시 부르지 않고 바로 실패한다 (#267, 요청이 줄 서서 기다리지 않게). */
class UpbitQuotesTest {

    /** 테스트에서 시간을 앞으로 돌리는 시계. */
    static final class MovableClock extends Clock {
        Instant now = Instant.parse("2026-10-14T00:00:00Z");
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    @Test
    void 실패한_뒤_캐시_시간_동안은_업비트를_다시_부르지_않는다() {
        MovableClock clock = new MovableClock();
        // 1번 포트는 닫혀 있어 바로 연결이 거부된다
        UpbitQuotes quotes = new UpbitQuotes(new PaperProperties("http://127.0.0.1:1", new BigDecimal("0.0005"), 5000, Duration.ofSeconds(5)), clock);

        assertThatThrownBy(quotes::latest).isInstanceOf(QuotesUnavailable.class).hasMessageNotContaining("잠시");
        clock.now = clock.now.plusSeconds(2);
        assertThatThrownBy(quotes::latest).isInstanceOf(QuotesUnavailable.class).hasMessageContaining("잠시");
        clock.now = clock.now.plusSeconds(4);
        assertThatThrownBy(quotes::latest).isInstanceOf(QuotesUnavailable.class).hasMessageNotContaining("잠시");
    }
}
