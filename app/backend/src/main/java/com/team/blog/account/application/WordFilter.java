package com.team.blog.account.application;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * 금칙어·예약어 포함 검사 (docs/09 §4-2). 소문자로 바꾼 뒤 변형 4가지(그대로, 숫자 제거, 숫자→영문, 1→l) 중
 * 하나라도 목록의 단어를 포함하면 걸린다. 각 변형에서 예외 단어는 먼저 지운다.
 * 어떤 단어에 걸렸는지는 밖으로 알리지 않는다 (N-5).
 */
@Component
public class WordFilter {
    private final List<String> banned;
    private final List<String> exceptions;

    @Autowired
    public WordFilter() {
        this(load("policy/banned-words.txt"), load("policy/banned-words-exceptions.txt"));
    }

    WordFilter(List<String> banned, List<String> exceptions) {
        this.banned = banned.stream().map(s -> s.toLowerCase(Locale.ROOT)).toList();
        this.exceptions = exceptions.stream().map(s -> s.toLowerCase(Locale.ROOT)).toList();
    }

    public boolean containsBanned(String value) {
        return containsAny(value, banned, exceptions);
    }

    public static boolean containsAny(String value, Collection<String> words, Collection<String> exceptions) {
        for (String v : variants(value)) {
            String cleaned = v;
            for (String e : exceptions) cleaned = cleaned.replace(e, "\0");
            for (String w : words) {
                if (!w.isEmpty() && cleaned.contains(w)) return true;
            }
        }
        return false;
    }

    static Set<String> variants(String value) {
        String low = value.toLowerCase(Locale.ROOT);
        Set<String> v = new LinkedHashSet<>();
        v.add(low);
        v.add(low.replaceAll("\\d", ""));
        v.add(leet(low));
        v.add(low.replace('1', 'l'));
        return v;
    }

    private static String leet(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (char c : s.toCharArray()) {
            sb.append(switch (c) {
                case '0' -> 'o';
                case '1' -> 'i';
                case '3' -> 'e';
                case '4' -> 'a';
                case '5' -> 's';
                case '7' -> 't';
                default -> c;
            });
        }
        return sb.toString();
    }

    private static List<String> load(String path) {
        try (InputStream in = new ClassPathResource(path).getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).lines()
                    .map(String::strip)
                    .filter(l -> !l.isEmpty() && !l.startsWith("#"))
                    .toList();
        } catch (IOException e) {
            throw new IllegalStateException("금칙어 목록을 읽을 수 없어요: " + path, e);
        }
    }
}
