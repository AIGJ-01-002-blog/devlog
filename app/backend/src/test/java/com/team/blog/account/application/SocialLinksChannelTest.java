package com.team.blog.account.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.team.blog.account.application.SocialLinks.Kind;

/** spec 103: 쇼츠 채널(YouTube·Instagram·TikTok)은 붙여 넣은 채널 주소에서 핸들·아이디를 꺼내 저장한다. */
class SocialLinksChannelTest {

    @Test
    void YouTube는_핸들이나_채널_ID를_youtube_com_뒤_경로로_정리한다() {
        assertThat(SocialLinks.normalize(Kind.YOUTUBE, "@devlog_kim")).isEqualTo("@devlog_kim");
        assertThat(SocialLinks.normalize(Kind.YOUTUBE, "devlog_kim")).isEqualTo("@devlog_kim");
        assertThat(SocialLinks.normalize(Kind.YOUTUBE, "https://www.youtube.com/@devlog_kim/shorts")).isEqualTo("@devlog_kim");
        assertThat(SocialLinks.normalize(Kind.YOUTUBE, "m.youtube.com/@devlog.kim?si=abc")).isEqualTo("@devlog.kim");
        assertThat(SocialLinks.normalize(Kind.YOUTUBE, "https://youtube.com/@%EB%A1%9C%EA%B7%B8%EB%83%A5")).isEqualTo("@로그냥");
        assertThat(SocialLinks.normalize(Kind.YOUTUBE, "@로그냥")).isEqualTo("@로그냥");
        assertThat(SocialLinks.normalize(Kind.YOUTUBE, "https://www.youtube.com/channel/UCabcdefghijklmnopqrstuv"))
                .isEqualTo("channel/UCabcdefghijklmnopqrstuv");
        assertThat(SocialLinks.normalize(Kind.YOUTUBE, "UCabcdefghijklmnopqrstuv")).isEqualTo("channel/UCabcdefghijklmnopqrstuv");
    }

    @Test
    void YouTube_영상_주소나_다른_사이트_틀린_핸들은_받지_않는다() {
        assertThat(SocialLinks.normalize(Kind.YOUTUBE, "https://www.youtube.com/watch?v=abc")).isNull();
        assertThat(SocialLinks.normalize(Kind.YOUTUBE, "https://youtu.be/abc")).isNull();
        assertThat(SocialLinks.normalize(Kind.YOUTUBE, "https://evil.example/@kim")).isNull();
        assertThat(SocialLinks.normalize(Kind.YOUTUBE, "@ab")).isNull();
        assertThat(SocialLinks.normalize(Kind.YOUTUBE, "@has space")).isNull();
        assertThat(SocialLinks.normalize(Kind.YOUTUBE, "https://www.youtube.com/channel/UCshort")).isNull();
        assertThat(SocialLinks.normalize(Kind.YOUTUBE, "https://www.youtube.com/@%ZZ")).isNull();
    }

    @Test
    void Instagram과_TikTok은_아이디만_남긴다() {
        assertThat(SocialLinks.normalize(Kind.INSTAGRAM, "https://www.instagram.com/dev.kim_/")).isEqualTo("dev.kim_");
        assertThat(SocialLinks.normalize(Kind.INSTAGRAM, "@dev.kim")).isEqualTo("dev.kim");
        assertThat(SocialLinks.normalize(Kind.INSTAGRAM, "https://www.instagram.com/reel/Cxyz/")).isNull();
        assertThat(SocialLinks.normalize(Kind.INSTAGRAM, "한글아이디")).isNull();

        assertThat(SocialLinks.normalize(Kind.TIKTOK, "https://www.tiktok.com/@dev.kim?lang=ko")).isEqualTo("dev.kim");
        assertThat(SocialLinks.normalize(Kind.TIKTOK, "@dev_kim")).isEqualTo("dev_kim");
        assertThat(SocialLinks.normalize(Kind.TIKTOK, "https://www.tiktok.com/@dev.kim/video/123")).isNull();
        assertThat(SocialLinks.normalize(Kind.TIKTOK, "a")).isNull();
    }
}
