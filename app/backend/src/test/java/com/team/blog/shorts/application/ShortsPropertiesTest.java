package com.team.blog.shorts.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.springframework.util.unit.DataSize;

/** 쇼츠 영상 직접 받기 주소 (2026-11-03: 한국 사용자가 Cloudflare LA 지점을 거쳐 내려받기가 느리던 문제) */
class ShortsPropertiesTest {
    private static ShortsProperties props(String direct) {
        return new ShortsProperties(true, "", "", DataSize.ofMegabytes(20), 10, Duration.ofMinutes(15), 3, 60, 6,
                Instant.parse("2026-10-21T06:00:00Z"), direct);
    }

    @Test
    void 설정이_있으면_키를_붙인_직접_주소를_준다() {
        assertThat(props("https://media.devlog.life/").videoDirectUrl("shorts/2026/11/a.mp4"))
                .isEqualTo("https://media.devlog.life/shorts/2026/11/a.mp4");
    }

    @Test
    void 설정이_비었거나_영상이_없으면_null() {
        assertThat(props("").videoDirectUrl("shorts/2026/11/a.mp4")).isNull();
        assertThat(props(" ").videoDirectUrl("shorts/2026/11/a.mp4")).isNull();
        assertThat(props("https://media.devlog.life").videoDirectUrl(null)).isNull();
    }
}
