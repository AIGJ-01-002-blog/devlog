package com.team.blog.shared.config;

import java.time.Duration;
import java.util.List;
import java.util.Set;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;

/**
 * 팀원마다 다를 수 있는 정책 수치 (헌법 VI). 코드에 상수로 박지 않고 application.yml에서 읽는다.
 */
@ConfigurationProperties(prefix = "blog")
public record BlogProperties(
        Site site,
        Web web,
        Agreements agreements,
        Auth auth,
        Handle handle,
        Nickname nickname,
        @DefaultValue Post post,
        @DefaultValue Feed feed,
        @DefaultValue Image image,
        @DefaultValue DevLogin devLogin,
        @DefaultValue View view,
        @DefaultValue Search search,
        @DefaultValue Series series) {

    /**
     * @param contactEmail 로그인하지 않아도 운영자에게 연락할 메일 주소 (spec 104). 비우면 보이지 않는다
     * @param contactUrl   로그인하지 않아도 남길 수 있는 공개 문의 창구 (spec 104, 기본: 공개 저장소의 GitHub 이슈). 비우면 보이지 않는다
     */
    public record Site(String baseUrl, @DefaultValue("devlog") String name, @DefaultValue("/og-default.png") String defaultOgImage,
                       @DefaultValue("") String contactEmail,
                       @DefaultValue("https://github.com/AIGJ-01-002-blog/devlog/issues") String contactUrl) {

        public String contactEmailOrNull() {
            return contactEmail == null || contactEmail.isBlank() ? null : contactEmail.strip();
        }

        public String contactUrlOrNull() {
            return contactUrl == null || contactUrl.isBlank() ? null : contactUrl.strip();
        }
    }

    /**
     * @param trustedProxies 이 대역에서 온 요청만 X-Forwarded-For를 믿는다 (docs/02 §5 H4)
     * @param clientIpHeader X-Forwarded-For가 모두 신뢰 대역뿐일 때 쓸 사용자 IP 머리글 (예: CF-Connecting-IP). 비우면 쓰지 않는다
     * @param maxBody        요청 본문 기본 상한 (보안 점검 2026-10-16). 사진·파일·영상처럼 본문을 그대로 받는 곳은 각자 상한을 쓴다
     * @param maxPostBody    글 저장·미리보기 본문 상한 (본문 100,000자)
     * @param maxMcpBody     MCP 요청 본문 상한 (upload_image가 사진 2MB를 base64로 싣는다)
     */
    public record Web(@DefaultValue List<String> trustedProxies, @DefaultValue("") String clientIpHeader,
                      @DefaultValue("256KB") DataSize maxBody, @DefaultValue("1MB") DataSize maxPostBody,
                      @DefaultValue("4MB") DataSize maxMcpBody) {}

    public record Agreements(String termsVersion, String termsEffectiveDate, String privacyVersion, String privacyEffectiveDate) {}

    /**
     * @param verifyTokenTtl    이메일 인증 링크 유효 시간 (docs/07 §3)
     * @param resetTokenTtl     비밀번호 재설정 링크 유효 시간 (docs/07 §4-1)
     * @param loginLockThreshold 같은 계정 연속 실패 허용 횟수, 넘으면 loginLockDuration 동안 잠금 (docs/07 §6)
     * @param mailFrom          보내는 사람 (메일 머리글 From)
     */
    public record Auth(@DefaultValue("10m") Duration pendingSignupTtl,
                       @DefaultValue("20") int loginRateLimitPerMinute,
                       @DefaultValue("30") int availabilityRateLimitPerMinute,
                       @DefaultValue("24h") Duration verifyTokenTtl,
                       @DefaultValue("30m") Duration resetTokenTtl,
                       @DefaultValue("5") int loginLockThreshold,
                       @DefaultValue("15m") Duration loginLockDuration,
                       @DefaultValue("devlog <no-reply@devlog.local>") String mailFrom,
                       // 가입 화면 이메일 인증번호 (spec 065): auto는 메일을 실제로 보내거나 개발 환경이면 요구, on·off는 강제
                       @DefaultValue("auto") String signupEmailCode,
                       // 로그인 세션은 쓰는 동안 늘어나도 로그인한 때부터 이 기간이 지나면 끝난다 (보안 점검 2026-10-16)
                       @DefaultValue("30d") Duration sessionAbsoluteTtl,
                       // 같은 IP에서 1시간에 만들 수 있는 이메일 가입 계정 수. 학교처럼 여럿이 한 IP를 쓰는 곳을 생각해 넉넉히 둔다
                       @DefaultValue("10") int signupPerIpPerHour) {}

    public record Handle(@DefaultValue("16") int autoBodyMaxLength, @DefaultValue Set<String> reserved) {}

    public record Nickname(@DefaultValue("30d") Duration changeCooldown, @DefaultValue List<String> reserved) {}

    public record Post(@DefaultValue("100000") int maxContentLength,
                       @DefaultValue("100") int maxTitleLength,
                       @DefaultValue("200") int excerptLength,
                       @DefaultValue("20") int maxNestingDepth,
                       @DefaultValue("1s") Duration renderTimeout,
                       @DefaultValue("24h") Duration autosaveTtl,
                       @DefaultValue("5s") Duration autosaveMinInterval,
                       @DefaultValue("10m") Duration idempotencyTtl,
                       @DefaultValue("24h") Duration emptyDraftTtl,
                       @DefaultValue("60") int previewRateLimitPerMinute,
                       @DefaultValue("10") int maxTags) {}

    public record Feed(@DefaultValue("9") int pageSize, @DefaultValue("20") int managePageSize) {}

    /**
     * 본문 사진 (009, docs/23 §3). 공개 주소(public-base-url)는 ImageUrls가 따로 읽는다.
     * @param quota      1인 사진 저장 공간 (원본 + 썸네일, 프로필 사진 포함)
     * @param dailyLimit 하루(한국 0시 기준) 업로드 장수. 실패한 업로드도 센다
     */
    public record Image(@DefaultValue("1GB") DataSize quota,
                        @DefaultValue("200") int dailyLimit,
                        @DefaultValue("20") int perMinute) {}

    public record DevLogin(@DefaultValue("false") boolean enabled) {}

    /**
     * 조회수 중복 기준과 제외 목록 (spec 013, docs/31 §2·§3).
     * @param window    같은 방문자·같은 글을 다시 세기까지의 기간 (처음 본 순간부터)
     * @param maxPerWindow 기간 안 최대 횟수
     * @param botPattern 로봇·링크 미리보기로 보는 User-Agent (대소문자 무시 정규식)
     */
    public record View(@DefaultValue("24h") Duration window,
                       @DefaultValue("1") int maxPerWindow,
                       @DefaultValue("60") int perMinute,
                       @DefaultValue("bot|crawl|spider|slurp|preview|facebookexternalhit|embedly|whatsapp|telegrambot|discordbot|slackbot|slack-imgproxy|kakaotalk-scrap|headless|curl|wget|python-requests|httpclient")
                       String botPattern) {}

    /**
     * 검색 (spec 014, docs/33 §4·§5).
     * @param recentWindow 먼저 찾아볼 최근 공개 글 수. 여기서 모자라면 인덱스로 전체에서 찾는다
     * @param perMinute    같은 방문자 1분 검색 횟수
     */
    public record Search(@DefaultValue("3000") int recentWindow, @DefaultValue("30") int perMinute) {}

    /** 시리즈 (024 FR-003): 회원당 시리즈 수, 시리즈당 글 수 */
    public record Series(@DefaultValue("100") int maxPerMember, @DefaultValue("200") int maxPosts) {}
}
