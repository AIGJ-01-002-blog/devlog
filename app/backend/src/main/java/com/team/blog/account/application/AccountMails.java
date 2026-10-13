package com.team.blog.account.application;

import java.time.Duration;
import java.util.List;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import com.team.blog.account.domain.AuthProvider;
import com.team.blog.shared.config.BlogProperties;
import com.team.blog.shared.mail.Mail;
import com.team.blog.shared.text.TextCleaner;

/** 계정 메일 문구 (docs/07 §3·§4-1, docs/11 §6-2). 링크는 사이트 주소(SITE_BASE_URL) 기준 절대 주소다. */
@Component
public class AccountMails {
    private final String site;
    private final String base;

    public AccountMails(BlogProperties props) {
        this.site = props.site().name();
        String b = props.site().baseUrl() == null ? "" : props.site().baseUrl();
        this.base = TextCleaner.trimEnd(b, '/');
    }

    public Mail verify(String to, String token, Duration ttl) {
        return new Mail(to, "[" + site + "] 이메일 인증을 완료해 주세요", """
                안녕하세요. %s 가입을 환영해요.

                아래 링크를 눌러 이메일 인증을 완료해 주세요. 인증을 마치면 글을 쓸 수 있어요.
                %s/verify-email?token=%s

                링크는 %d시간 동안, 한 번만 쓸 수 있어요.
                직접 가입하지 않았다면 이 메일은 무시해 주세요.
                """.formatted(site, base, token, ttl.toHours()));
    }

    /** 가입 화면에서 넣을 인증번호 (spec 065). */
    public Mail signupCode(String to, String code, Duration ttl) {
        return new Mail(to, "[" + site + "] 가입 인증번호 " + code, """
                안녕하세요. %s 가입 화면에 아래 인증번호를 넣어 주세요.

                인증번호: %s

                인증번호는 %d분 동안 쓸 수 있어요.
                직접 가입하지 않았다면 이 메일은 무시해 주세요.
                """.formatted(site, code, ttl.toMinutes()));
    }

    /** @param socials 같은 이메일로 가입한 소셜 계정 (안내만 덧붙인다) */
    public Mail reset(String to, String token, Duration ttl, List<AuthProvider> socials) {
        String extra = socials.isEmpty() ? "" : "\n이 이메일로 " + names(socials) + "로 가입한 계정도 있어요. 그 계정은 "
                + names(socials) + "로 로그인하세요.\n";
        return new Mail(to, "[" + site + "] 비밀번호 재설정 안내", """
                비밀번호 재설정을 요청하셨어요.

                아래 링크에서 새 비밀번호를 정해 주세요.
                %s/reset-password?token=%s

                링크는 %d분 동안, 한 번만 쓸 수 있어요.
                %s
                직접 요청하지 않았다면 이 메일은 무시해 주세요. 비밀번호는 바뀌지 않아요.
                """.formatted(base, token, ttl.toMinutes(), extra));
    }

    /** 이메일 가입 계정 없이 소셜 계정만 있는 이메일. */
    public Mail socialOnly(String to, List<AuthProvider> socials) {
        return new Mail(to, "[" + site + "] 비밀번호 재설정 안내", """
                비밀번호 재설정을 요청하셨어요.

                이 이메일은 %s로 가입되어 비밀번호가 없어요. %s로 계속하기를 눌러 로그인해 주세요.
                %s/login

                직접 요청하지 않았다면 이 메일은 무시해 주세요.
                """.formatted(names(socials), names(socials), base));
    }

    public Mail passwordChanged(String to) {
        return new Mail(to, "[" + site + "] 비밀번호가 변경됐어요", """
                계정의 비밀번호가 변경됐어요. 다른 기기에서는 로그아웃됐어요.

                본인이 아니라면 바로 비밀번호를 재설정해 주세요.
                %s/forgot-password
                """.formatted(base));
    }

    /** 탈퇴 접수 (020 FR-015). 본인이 하지 않은 탈퇴를 알아챌 수 있게 복구 방법을 함께 적는다. */
    public Mail withdrawn(String to, String handle, String restoreBy) {
        return new Mail(to, "[" + site + "] 탈퇴 신청이 접수됐어요", """
                @%s 계정의 탈퇴 신청이 접수됐어요. 모든 기기에서 로그아웃됐고, 블로그와 글은 다른 사람에게 보이지 않아요.

                %s까지 다시 로그인하면 [복구하기]로 모두 되돌릴 수 있어요. 그 뒤에는 글·사진이 완전히 지워져요.
                %s/login

                본인이 신청하지 않았다면 바로 로그인해 복구하고 비밀번호를 바꿔 주세요.
                """.formatted(handle, restoreBy, base));
    }

    /** 복구 완료 (020 FR-019). */
    public Mail restored(String to, String handle) {
        return new Mail(to, "[" + site + "] 계정이 복구됐어요", """
                @%s 계정이 복구됐어요. 블로그와 글이 다시 보여요.

                본인이 복구하지 않았다면 바로 비밀번호를 바꿔 주세요.
                %s/settings
                """.formatted(handle, base));
    }

    private static String names(List<AuthProvider> providers) {
        return providers.stream().distinct().map(p -> switch (p) {
            case GOOGLE -> "Google";
            case GITHUB -> "GitHub";
            case KAKAO -> "카카오";
            case FACEBOOK -> "Facebook";
            case NAVER -> "네이버";
            case LOCAL -> "이메일";
        }).collect(Collectors.joining("·"));
    }
}
