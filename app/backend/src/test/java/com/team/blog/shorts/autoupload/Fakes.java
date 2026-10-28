package com.team.blog.shorts.autoupload;

import java.time.Duration;
import java.time.LocalTime;

/** 가짜 플랫폼 서버 주소로 만든 설정 */
final class Fakes {
    static final String KEY = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=";

    private Fakes() {}

    static AutoUploadProperties props(String base) {
        return new AutoUploadProperties(KEY, AutoUploadProperties.Audience.ADMINS, LocalTime.of(20, 0), Duration.ofMinutes(30), 3,
                Duration.ofMinutes(5), false,
                new AutoUploadProperties.YouTube("yt-client", "yt-secret", base + "/o/oauth2/v2/auth", base + "/token", base),
                new AutoUploadProperties.Instagram("ig-app", "ig-secret", base + "/oauth/authorize", base, base, "v24.0", Duration.ofMillis(5), 3));
    }
}
