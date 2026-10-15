package com.team.blog.shorts.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.team.blog.shorts.application.TradeReplay.Interval;
import com.team.blog.shorts.application.TradeReplay.Window;

/** #265 매매 재현 영상의 차트 구간: 봉 고르기와 앞뒤 여유 */
class TradeReplayTest {
    static final Instant NOW = Instant.parse("2026-10-14T12:00:00Z");

    @Test
    void 봉을_고르지_않으면_체결_사이가_60봉_안에_드는_가장_짧은_봉을_쓴다() {
        Instant a = Instant.parse("2026-10-13T01:15:00Z");
        Window.Ok w = (Window.Ok) TradeReplay.window(a, a.plusSeconds(3 * 3600 + 15 * 60), Optional.empty(), NOW);
        assertThat(w.interval()).isEqualTo(Interval.M5);
        // 39봉 구간: 앞은 max(20, 13) = 20봉, 뒤는 max(10, 6) = 10봉 + 마지막 체결 봉
        assertThat(w.from()).isEqualTo(Instant.parse("2026-10-13T01:15:00Z").minusSeconds(20 * 300));
        assertThat(w.to()).isEqualTo(Instant.parse("2026-10-13T04:30:00Z").plusSeconds(11 * 300));
        assertThat((w.to().getEpochSecond() - w.from().getEpochSecond()) / 300).isLessThanOrEqualTo(150);
    }

    @Test
    void 체결이_하나면_1분봉으로_앞뒤만_보여_준다() {
        Instant a = Instant.parse("2026-10-13T01:15:42Z");
        Window.Ok w = (Window.Ok) TradeReplay.window(a, a, Optional.empty(), NOW);
        assertThat(w.interval()).isEqualTo(Interval.M1);
        assertThat(w.from()).isEqualTo(Instant.parse("2026-10-13T00:55:00Z"));
        assertThat(w.to()).isEqualTo(Instant.parse("2026-10-13T01:26:00Z"));
    }

    @Test
    void 고른_봉으로_너무_길면_알맞은_봉을_알려_준다() {
        Instant a = Instant.parse("2026-10-01T00:00:00Z");
        Window w = TradeReplay.window(a, a.plusSeconds(10 * 86_400), Optional.of(Interval.M15), NOW);
        assertThat(w).isInstanceOf(Window.TooLong.class);
        assertThat(((Window.TooLong) w).message()).contains("15분봉 960개", "4시간봉 이상");
        assertThat(TradeReplay.window(a, a.plusSeconds(10 * 86_400), Optional.of(Interval.H4), NOW)).isInstanceOf(Window.Ok.class);
        assertThat(TradeReplay.window(a.minusSeconds(200L * 86_400), a, Optional.empty(), NOW)).isInstanceOf(Window.TooLong.class);
    }

    @Test
    void 끝은_아직_오지_않은_봉까지_가지_않는다() {
        Instant a = NOW.minusSeconds(120);
        Window.Ok w = (Window.Ok) TradeReplay.window(a, a, Optional.empty(), NOW);
        assertThat(w.to()).isEqualTo(NOW.plusSeconds(60));
    }

    @Test
    void 봉_이름을_읽는다() {
        assertThat(Interval.parse("4H")).contains(Interval.H4);
        assertThat(Interval.parse(" 1d ")).contains(Interval.D1);
        assertThat(Interval.parse("2h")).isEmpty();
    }
}
