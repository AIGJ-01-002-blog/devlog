package com.team.blog.shorts;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.team.blog.shorts.application.UploadLink;
import com.team.blog.shorts.application.UploadLink.Platform;

/** #238: 직접 올린 영상 주소는 고른 플랫폼 도메인의 https 주소만 받는다 */
class UploadLinkTest {
    @Test
    void 플랫폼마다_그_도메인의_https_주소만_받는다() {
        assertThat(UploadLink.clean(Platform.YOUTUBE, "  https://youtube.com/shorts/abc123  ")).contains("https://youtube.com/shorts/abc123");
        assertThat(UploadLink.clean(Platform.YOUTUBE, "https://youtu.be/abc123")).isPresent();
        assertThat(UploadLink.clean(Platform.INSTAGRAM, "https://www.instagram.com/reel/Cabc/")).isPresent();
        assertThat(UploadLink.clean(Platform.TIKTOK, "https://www.tiktok.com/@me/video/123")).isPresent();
        assertThat(UploadLink.clean(Platform.TIKTOK, "https://vm.tiktok.com/ZMabc/")).isPresent();
    }

    @Test
    void 다른_사이트나_http_계정정보_포트_빈_경로는_막는다() {
        assertThat(UploadLink.clean(Platform.YOUTUBE, "https://www.instagram.com/reel/Cabc/")).isEmpty();
        assertThat(UploadLink.clean(Platform.YOUTUBE, "https://youtube.com.evil.example/shorts/a")).isEmpty();
        assertThat(UploadLink.clean(Platform.YOUTUBE, "http://youtube.com/shorts/a")).isEmpty();
        assertThat(UploadLink.clean(Platform.YOUTUBE, "javascript:alert(1)")).isEmpty();
        assertThat(UploadLink.clean(Platform.YOUTUBE, "https://user@youtube.com/shorts/a")).isEmpty();
        assertThat(UploadLink.clean(Platform.YOUTUBE, "https://youtube.com:8443/shorts/a")).isEmpty();
        assertThat(UploadLink.clean(Platform.YOUTUBE, "https://youtube.com/")).isEmpty();
        assertThat(UploadLink.clean(Platform.YOUTUBE, "https://youtube.com/shorts/" + "a".repeat(300))).isEmpty();
        assertThat(UploadLink.clean(Platform.YOUTUBE, null)).isEmpty();
    }

    @Test
    void 플랫폼_이름은_대소문자를_가리지_않고_모르는_이름은_없다() {
        assertThat(Platform.parse("youtube")).contains(Platform.YOUTUBE);
        assertThat(Platform.parse("TikTok")).contains(Platform.TIKTOK);
        assertThat(Platform.parse("facebook")).isEmpty();
    }

    @Test
    void YouTube_영상_주소에서_재생할_영상_ID를_꺼낸다() {
        assertThat(UploadLink.youtubeId("https://www.youtube.com/shorts/AbCdEfGhIj0")).contains("AbCdEfGhIj0");
        assertThat(UploadLink.youtubeId("https://youtube.com/shorts/AbCdEfGhIj0?feature=share")).contains("AbCdEfGhIj0");
        assertThat(UploadLink.youtubeId("https://m.youtube.com/watch?si=x&v=AbCdEfGhIj0&t=3")).contains("AbCdEfGhIj0");
        assertThat(UploadLink.youtubeId("https://youtu.be/AbCdEfGhIj0")).contains("AbCdEfGhIj0");
        assertThat(UploadLink.youtubeId("https://www.youtube.com/live/AbCdEfGhIj0")).contains("AbCdEfGhIj0");
        assertThat(UploadLink.youtubeId("https://www.youtube.com/@me")).isEmpty();
        assertThat(UploadLink.youtubeId("https://www.youtube.com/shorts/short")).isEmpty();
        assertThat(UploadLink.youtubeId("https://evil.example/shorts/AbCdEfGhIj0")).isEmpty();
        assertThat(UploadLink.youtubeId("https://www.youtube.com/shorts/AbCdEf\"><x")).isEmpty();
    }
}
