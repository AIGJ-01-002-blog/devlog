package com.team.blog.shorts.autoupload;

import java.util.Locale;
import java.util.Optional;

import com.team.blog.shorts.application.UploadLink;

/** 자동 올리기를 하는 플랫폼 (spec 108). TikTok은 앱 심사 전 비공개로 잠겨 넣지 않았다 */
public enum AutoPlatform {
    YOUTUBE, INSTAGRAM;

    public UploadLink.Platform link() {
        return UploadLink.Platform.valueOf(name());
    }

    public static Optional<AutoPlatform> parse(String raw) {
        if (raw == null) return Optional.empty();
        try {
            return Optional.of(valueOf(raw.strip().toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
