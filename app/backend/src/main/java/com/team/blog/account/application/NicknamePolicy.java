package com.team.blog.account.application;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import com.team.blog.account.infra.MemberRepository;
import com.team.blog.shared.config.BlogProperties;

/**
 * 닉네임 규칙 (docs/09). 가입(이메일·소셜)과 프로필 수정이 같은 정책을 쓴다.
 * 순서: 정리(공백·NFC) → 형식 → 글자 포함 → 예약어 → 금칙어 → 중복.
 */
@Component
public class NicknamePolicy {
    private static final Pattern CHARS = Pattern.compile("^[가-힣a-zA-Z0-9]{2,10}$");
    private static final Pattern HAS_LETTER = Pattern.compile("[가-힣a-zA-Z]");
    private static final Pattern NOT_ALLOWED = Pattern.compile("[^가-힣a-zA-Z0-9]");
    /** 겹칠 때 붙여 볼 숫자의 끝(2~19). 그래도 겹치면 빈칸으로 두고 직접 입력받는다. */
    private static final int MAX_SUFFIX = 20;

    private final WordFilter wordFilter;
    private final List<String> reserved;
    private final MemberRepository members;

    public NicknamePolicy(WordFilter wordFilter, BlogProperties props, MemberRepository members) {
        this.wordFilter = wordFilter;
        this.reserved = props.nickname().reserved().stream().map(s -> s.toLowerCase(Locale.ROOT)).toList();
        this.members = members;
    }

    public enum Code {
        NICKNAME_INVALID_FORMAT("한글·영문·숫자로 2~10자까지 쓸 수 있어요 (공백·특수문자 불가)"),
        NICKNAME_LETTER_REQUIRED("한글이나 영문을 1자 이상 넣어 주세요"),
        NICKNAME_RESERVED("사용할 수 없는 닉네임이에요"),
        NICKNAME_BANNED_WORD("사용할 수 없는 단어가 들어 있어요"),
        NICKNAME_DUPLICATE("이미 사용 중인 닉네임이에요");

        private final String message;

        Code(String message) {
            this.message = message;
        }

        public String message() {
            return message;
        }
    }

    public static String normalize(String raw) {
        return raw == null ? "" : Normalizer.normalize(raw.strip(), Normalizer.Form.NFC);
    }

    /** 중복을 제외한 규칙 검사. @return 통과하면 null */
    public Code checkRules(String normalized) {
        if (!CHARS.matcher(normalized).matches()) return Code.NICKNAME_INVALID_FORMAT;
        if (!HAS_LETTER.matcher(normalized).find()) return Code.NICKNAME_LETTER_REQUIRED;
        if (WordFilter.containsAny(normalized, reserved, List.of())) return Code.NICKNAME_RESERVED;
        if (wordFilter.containsBanned(normalized)) return Code.NICKNAME_BANNED_WORD;
        return null;
    }

    /** 중복까지 포함한 검사. exceptMemberId는 자기 자신(대소문자만 바꾸기)을 제외할 때 쓴다. */
    public Code check(String normalized, long exceptMemberId) {
        Code c = checkRules(normalized);
        if (c != null) return c;
        if (members.existsNicknameIgnoreCase(normalized, exceptMemberId)) return Code.NICKNAME_DUPLICATE;
        return null;
    }

    /**
     * 소셜 이름 미리 채우기 (§7): 허용되지 않는 문자를 지우고 10자로 자른다. 규칙에 안 맞으면 빈칸.
     * 이미 쓰는 닉네임이면 블로그 주소 추천(_2, _3)처럼 뒤에 숫자를 붙여 비어 있는 값을 채운다.
     */
    public String prefill(String socialName) {
        String s = NOT_ALLOWED.matcher(normalize(socialName)).replaceAll("");
        if (s.length() > 10) s = s.substring(0, 10);
        if (checkRules(s) != null) return "";
        if (!members.existsNicknameIgnoreCase(s, -1)) return s;
        for (int n = 2; n < MAX_SUFFIX; n++) {
            String suffix = Integer.toString(n);
            String candidate = s.substring(0, Math.min(s.length(), 10 - suffix.length())) + suffix;
            if (check(candidate, -1) == null) return candidate;
        }
        return "";
    }
}
