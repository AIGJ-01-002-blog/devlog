package com.team.blog.shorts.application;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * 직접 올린 쇼츠의 영상 주소 (099 2단계, #238). 회원이 붙여 넣은 주소가 고른 플랫폼의 https 주소인지만 본다.
 * 그 영상이 정말 있는지는 확인하지 않는다(플랫폼 API를 쓰지 않는다). 엉뚱한 사이트 주소가 대시보드 링크가 되지 않게 도메인만 막는다.
 */
public final class UploadLink {
    public static final int URL_MAX = 300;

    public enum Platform {
        YOUTUBE(Set.of("youtube.com", "www.youtube.com", "m.youtube.com", "youtu.be")),
        INSTAGRAM(Set.of("instagram.com", "www.instagram.com")),
        TIKTOK(Set.of("tiktok.com", "www.tiktok.com", "m.tiktok.com", "vm.tiktok.com", "vt.tiktok.com"));

        private final Set<String> hosts;

        Platform(Set<String> hosts) {
            this.hosts = hosts;
        }

        public static Optional<Platform> parse(String raw) {
            if (raw == null) return Optional.empty();
            try {
                return Optional.of(valueOf(raw.trim().toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException e) {
                return Optional.empty();
            }
        }
    }

    private UploadLink() {}

    /** 고른 플랫폼의 영상 주소면 다듬은 주소를, 아니면 비어 있는 값을 돌려준다 */
    public static Optional<String> clean(Platform platform, String raw) {
        if (raw == null) return Optional.empty();
        String url = raw.strip();
        if (url.isEmpty() || url.length() > URL_MAX) return Optional.empty();
        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException e) {
            return Optional.empty();
        }
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getRawUserInfo() != null || uri.getPort() != -1) return Optional.empty();
        String host = uri.getHost();
        if (host == null || !platform.hosts.contains(host.toLowerCase(Locale.ROOT))) return Optional.empty();
        String path = uri.getRawPath();
        if (path == null || path.length() <= 1) return Optional.empty();
        return Optional.of(url);
    }
}
