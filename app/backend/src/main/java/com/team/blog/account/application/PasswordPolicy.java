package com.team.blog.account.application;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import com.team.blog.shared.error.FieldErrorItem;

/**
 * 비밀번호 규칙 (docs/07 §4, 004 FR-015·FR-016). 8~16자, 영문·숫자·특수문자 각 1개 이상, 정해진 문자만,
 * 이메일 앞부분·흔한 비밀번호 금지. 위반한 규칙을 모두 돌려줘 화면이 한 번에 안내할 수 있게 한다.
 */
@Component
public class PasswordPolicy {
    static final String SPECIALS = "!@#$%^&*()-_=+[]{};:'\",.<>/?\\|`~";
    static final int MIN = 8;
    static final int MAX = 16;

    private final Set<String> common;

    public PasswordPolicy() {
        this.common = load("policy/common-passwords.txt");
    }

    public enum Code {
        PASSWORD_LENGTH("8~16자로 입력해 주세요. (최대 16자)"),
        PASSWORD_LETTER("영문을 1자 이상 넣어 주세요."),
        PASSWORD_DIGIT("숫자를 1자 이상 넣어 주세요."),
        PASSWORD_SPECIAL("특수문자를 1자 이상 넣어 주세요."),
        PASSWORD_CHARS("영문·숫자·특수문자만 쓸 수 있어요. 공백·한글은 쓸 수 없어요."),
        PASSWORD_CONTAINS_EMAIL("이메일 앞부분이 들어간 비밀번호는 쓸 수 없어요."),
        PASSWORD_TOO_COMMON("너무 흔한 비밀번호예요. 다른 비밀번호를 써 주세요.");

        private final String message;

        Code(String message) {
            this.message = message;
        }

        public String message() {
            return message;
        }
    }

    /** @param email 이메일 앞부분 검사용. 없으면 null */
    public List<Code> violations(String password, String email) {
        List<Code> out = new ArrayList<>();
        String p = password == null ? "" : password;
        int len = p.codePointCount(0, p.length());
        if (len < MIN || len > MAX) out.add(Code.PASSWORD_LENGTH);
        boolean letter = false;
        boolean digit = false;
        boolean special = false;
        boolean other = false;
        for (int i = 0; i < p.length(); i++) {
            char c = p.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')) letter = true;
            else if (c >= '0' && c <= '9') digit = true;
            else if (SPECIALS.indexOf(c) >= 0) special = true;
            else other = true;
        }
        if (!letter) out.add(Code.PASSWORD_LETTER);
        if (!digit) out.add(Code.PASSWORD_DIGIT);
        if (!special) out.add(Code.PASSWORD_SPECIAL);
        if (other) out.add(Code.PASSWORD_CHARS);
        String lower = p.toLowerCase(Locale.ROOT);
        String local = localPart(email);
        if (local.length() >= 3 && lower.contains(local)) out.add(Code.PASSWORD_CONTAINS_EMAIL);
        if (common.contains(lower)) out.add(Code.PASSWORD_TOO_COMMON);
        return out;
    }

    /** 위반 규칙을 필드 오류로. 비어 있으면 통과. */
    public List<FieldErrorItem> errors(String field, String password, String email) {
        return violations(password, email).stream().map(c -> new FieldErrorItem(field, c.name(), c.message())).toList();
    }

    private static String localPart(String email) {
        if (email == null) return "";
        int at = email.indexOf('@');
        return (at < 0 ? email : email.substring(0, at)).toLowerCase(Locale.ROOT);
    }

    private static Set<String> load(String path) {
        Set<String> set = new HashSet<>();
        ClassPathResource r = new ClassPathResource(path);
        if (!r.exists()) return set;
        try (BufferedReader in = new BufferedReader(new InputStreamReader(r.getInputStream(), StandardCharsets.UTF_8))) {
            for (String line; (line = in.readLine()) != null; ) {
                String s = line.strip();
                if (!s.isEmpty() && !s.startsWith("#")) set.add(s.toLowerCase(Locale.ROOT));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return Set.copyOf(set);
    }
}
