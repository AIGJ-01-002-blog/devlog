package com.team.blog.account.application;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import com.team.blog.account.domain.AuthProvider;
import com.team.blog.shared.config.BlogProperties;

/**
 * 블로그 주소 규칙 (docs/08 §2·§5, 2026-10-07 H-6~H-9). DB CHECK(V2)와 같은 규칙을 애플리케이션에서도 검사한다.
 * 형식: [접두어-]본문, 본문은 영문 소문자·숫자·_ 3~20자, 처음·끝은 영문·숫자.
 */
@Component
public class HandlePolicy {
    public static final Pattern FULL = Pattern.compile("^((go|gi|ka|fb|nv)-)?[a-z0-9][a-z0-9_]{1,18}[a-z0-9]$");
    private static final Pattern BODY = Pattern.compile("^[a-z0-9][a-z0-9_]{1,18}[a-z0-9]$");
    private static final Pattern PREFIX_LOOKALIKE = Pattern.compile("^(go|gi|ka|fb|nv)_");

    private final Set<String> reserved;
    private final WordFilter wordFilter;

    public HandlePolicy(BlogProperties props, WordFilter wordFilter) {
        this.reserved = props.handle().reserved().stream().map(s -> s.toLowerCase(Locale.ROOT)).collect(Collectors.toUnmodifiableSet());
        this.wordFilter = wordFilter;
    }

    public enum Reason {
        INVALID_FORMAT("영문 소문자·숫자·밑줄(_)로 3~20자까지 쓸 수 있어요. 처음과 끝은 영문이나 숫자여야 해요."),
        PREFIX_LOOKALIKE("소셜 가입 주소와 헷갈려서 go_·gi_·ka_·fb_·nv_로 시작할 수 없어요."),
        RESERVED("사용할 수 없는 주소예요."),
        BANNED_WORD("사용할 수 없는 단어가 들어 있어요."),
        TAKEN("이미 사용 중인 주소예요.");

        private final String message;

        Reason(String message) {
            this.message = message;
        }

        public String message() {
            return message;
        }
    }

    /** 가입 수단의 접두어를 뺀 본문을 검사한다. 중복은 보지 않는다. @return 통과하면 null */
    public Reason checkBody(AuthProvider provider, String body) {
        if (body == null || !BODY.matcher(body).matches()) return Reason.INVALID_FORMAT;
        if (provider == AuthProvider.LOCAL && PREFIX_LOOKALIKE.matcher(body).find()) return Reason.PREFIX_LOOKALIKE;
        if (reserved.contains(body)) return Reason.RESERVED;
        if (wordFilter.containsBanned(body)) return Reason.BANNED_WORD;
        return null;
    }

    /** 접두어가 붙은 전체 주소를 검사한다 (중복 확인 API용). */
    public Reason checkFull(String handle) {
        if (handle == null || !FULL.matcher(handle).matches()) return Reason.INVALID_FORMAT;
        if (PREFIX_LOOKALIKE.matcher(handle).find()) return Reason.PREFIX_LOOKALIKE;
        return checkBody(providerOf(handle), bodyOf(handle));
    }

    public boolean isReserved(String body) {
        return reserved.contains(body);
    }

    public static AuthProvider providerOf(String handle) {
        if (handle.startsWith("go-")) return AuthProvider.GOOGLE;
        if (handle.startsWith("gi-")) return AuthProvider.GITHUB;
        if (handle.startsWith("ka-")) return AuthProvider.KAKAO;
        if (handle.startsWith("fb-")) return AuthProvider.FACEBOOK;
        if (handle.startsWith("nv-")) return AuthProvider.NAVER;
        return AuthProvider.LOCAL;
    }

    public static String bodyOf(String handle) {
        AuthProvider p = providerOf(handle);
        return handle.substring(p.handlePrefix().length());
    }
}
