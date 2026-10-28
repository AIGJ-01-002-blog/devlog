package com.team.blog.shorts.autoupload;

import java.time.Duration;
import java.time.LocalTime;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 쇼츠 자동 올리기 (spec 108). 토큰 열쇠와 플랫폼 앱 값이 모두 있어야 그 플랫폼을 이을 수 있다.
 * 이어도 주인이 시험 올리기를 통과한 뒤 직접 켜기 전에는 아무것도 올리지 않는다.
 *
 * @param tokenKey     SHORTS_TOKEN_KEY. 플랫폼 토큰을 암호화하는 AES-256 열쇠(base64 32바이트). 비우면 기능 전체가 꺼진다
 * @param audience     누가 쓸 수 있는지. 기본은 관리자(블로그 주인)만. API 하루 한도·앱 검수가 앱 하나에 걸려 있어서다
 * @param publishTime  공개 시각(한국 시간). 화면 SHORTS_PUBLISH_TIME과 같다
 * @param minLead      공개까지 이보다 덜 남았으면 다음 날로 미룬다
 * @param maxAttempts  이만큼 실패하면 FAILED로 끝낸다
 * @param retryBackoff 첫 실패 뒤 기다리는 시간. 실패할 때마다 두 배
 * @param poll         예약 작업을 이 서버에서 돌릴지. 시험은 끄고 직접 부른다
 */
@ConfigurationProperties("blog.shorts.auto-upload")
public record AutoUploadProperties(@DefaultValue("") String tokenKey,
                                   @DefaultValue("ADMINS") Audience audience,
                                   @DefaultValue("20:00") LocalTime publishTime,
                                   @DefaultValue("30m") Duration minLead,
                                   @DefaultValue("3") int maxAttempts,
                                   @DefaultValue("5m") Duration retryBackoff,
                                   @DefaultValue("true") boolean poll,
                                   @DefaultValue YouTube youtube,
                                   @DefaultValue Instagram instagram) {

    /** 잘못 적은 값은 시작할 때 막는다: 0번 시도는 바로 FAILED, 음수 기다림·여유는 지난 시각을 고른다 */
    public AutoUploadProperties {
        if (maxAttempts < 1) throw new IllegalArgumentException("blog.shorts.auto-upload.max-attempts는 1 이상이어야 합니다");
        if (retryBackoff == null || retryBackoff.isNegative()) throw new IllegalArgumentException("blog.shorts.auto-upload.retry-backoff는 음수일 수 없습니다");
        if (minLead == null || minLead.isNegative()) throw new IllegalArgumentException("blog.shorts.auto-upload.min-lead는 음수일 수 없습니다");
    }

    public enum Audience { ADMINS, MEMBERS }

    /**
     * @param clientId     YOUTUBE_CLIENT_ID. Google Cloud 콘솔의 OAuth 클라이언트(웹 애플리케이션)
     * @param clientSecret YOUTUBE_CLIENT_SECRET
     */
    public record YouTube(@DefaultValue("") String clientId,
                          @DefaultValue("") String clientSecret,
                          @DefaultValue("https://accounts.google.com/o/oauth2/v2/auth") String authUrl,
                          @DefaultValue("https://oauth2.googleapis.com/token") String tokenUrl,
                          @DefaultValue("https://www.googleapis.com") String apiUrl) {
        boolean configured() {
            return !clientId.isBlank() && !clientSecret.isBlank();
        }

        @Override
        public String toString() {
            return "YouTube[configured=" + configured() + "]";
        }
    }

    /**
     * @param appId      INSTAGRAM_APP_ID. Meta 앱 대시보드 → Instagram → API setup with Instagram login의 Instagram 앱 ID
     * @param appSecret  INSTAGRAM_APP_SECRET
     * @param apiVersion Graph API 버전. Meta가 버전을 내리면 바꾼다
     */
    public record Instagram(@DefaultValue("") String appId,
                            @DefaultValue("") String appSecret,
                            @DefaultValue("https://www.instagram.com/oauth/authorize") String authUrl,
                            @DefaultValue("https://api.instagram.com") String tokenUrl,
                            @DefaultValue("https://graph.instagram.com") String graphUrl,
                            @DefaultValue("v24.0") String apiVersion,
                            @DefaultValue("3s") Duration containerPoll,
                            @DefaultValue("20") int containerPollMax) {
        boolean configured() {
            return !appId.isBlank() && !appSecret.isBlank();
        }

        @Override
        public String toString() {
            return "Instagram[configured=" + configured() + ", apiVersion=" + apiVersion + "]";
        }
    }

    public boolean hasKey() {
        return tokenKey != null && !tokenKey.isBlank();
    }

    /** 이 플랫폼을 이을 수 있게 설정됐는지 */
    public boolean configured(AutoPlatform platform) {
        if (!hasKey()) return false;
        return switch (platform) {
            case YOUTUBE -> youtube.configured();
            case INSTAGRAM -> instagram.configured();
        };
    }

    public boolean anyConfigured() {
        return configured(AutoPlatform.YOUTUBE) || configured(AutoPlatform.INSTAGRAM);
    }

    /** 열쇠·비밀값이 로그에 찍히지 않게 한다 */
    @Override
    public String toString() {
        return "AutoUploadProperties[key=" + hasKey() + ", audience=" + audience + ", " + youtube + ", " + instagram + "]";
    }
}
