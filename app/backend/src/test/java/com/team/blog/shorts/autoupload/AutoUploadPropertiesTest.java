package com.team.blog.shorts.autoupload;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.LocalTime;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

/** 설정 읽기 (spec 108 FR-001): 값이 없으면 꺼져 있고, 비밀값은 toString에 찍히지 않는다 */
class AutoUploadPropertiesTest {
    private static AutoUploadProperties bind(Map<String, String> values) {
        return new Binder(new MapConfigurationPropertySource(values)).bindOrCreate("blog.shorts.auto-upload", AutoUploadProperties.class);
    }

    @Test
    void 아무것도_없으면_꺼져_있고_기본은_관리자만_20시() {
        AutoUploadProperties p = bind(Map.of());
        assertThat(p.hasKey()).isFalse();
        assertThat(p.anyConfigured()).isFalse();
        assertThat(p.audience()).isEqualTo(AutoUploadProperties.Audience.ADMINS);
        assertThat(p.publishTime()).isEqualTo(LocalTime.of(20, 0));
        assertThat(p.minLead()).isEqualTo(Duration.ofMinutes(30));
        assertThat(p.maxAttempts()).isEqualTo(3);
        assertThat(p.instagram().apiVersion()).isEqualTo("v24.0");
    }

    @Test
    void 열쇠와_앱_값이_있는_플랫폼만_설정됨이고_비밀값은_찍히지_않는다() {
        AutoUploadProperties p = bind(Map.of("blog.shorts.auto-upload.token-key", Fakes.KEY,
                "blog.shorts.auto-upload.youtube.client-id", "yt-id", "blog.shorts.auto-upload.youtube.client-secret", "yt-very-secret",
                "blog.shorts.auto-upload.publish-time", "20:00"));
        assertThat(p.configured(AutoPlatform.YOUTUBE)).isTrue();
        assertThat(p.configured(AutoPlatform.INSTAGRAM)).isFalse();
        assertThat(p.toString()).doesNotContain("yt-very-secret").doesNotContain(Fakes.KEY).contains("configured=true");
        AutoUploadProperties noKey = bind(Map.of("blog.shorts.auto-upload.youtube.client-id", "yt-id",
                "blog.shorts.auto-upload.youtube.client-secret", "s"));
        assertThat(noKey.configured(AutoPlatform.YOUTUBE)).as("열쇠가 없으면 잇지 않는다").isFalse();
    }
}
