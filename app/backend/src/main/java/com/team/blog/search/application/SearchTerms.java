package com.team.blog.search.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 검색어 처리 (spec 014 FR-005~FR-009, docs/33 §2). NFC 정규화 → 앞뒤 공백 제거 → 50자 → 띄어쓰기로 나눔 →
 * 1글자 무시 → 최대 5단어. 2글자 단어는 제목·태그에서만, 3글자 이상은 본문까지 찾는다.
 */
public record SearchTerms(String normalized, List<String> words) {
    public static final int MAX_LENGTH = 50;
    public static final int MAX_WORDS = 5;
    /** 이 길이부터 본문에서도 찾는다. trigram 인덱스가 3글자 단위라 2글자는 본문 인덱스를 쓰지 못한다 (docs/33 Q-2). */
    public static final int BODY_MIN_LENGTH = 3;
    private static final Pattern SPACES = Pattern.compile("\\s+");

    public static SearchTerms parse(String raw) {
        if (raw == null) return new SearchTerms("", List.of());
        String s = SPACES.matcher(Normalizer.normalize(raw, Normalizer.Form.NFC)).replaceAll(" ").strip();
        if (s.codePointCount(0, s.length()) > MAX_LENGTH) s = s.substring(0, s.offsetByCodePoints(0, MAX_LENGTH)).strip();
        LinkedHashSet<String> words = new LinkedHashSet<>();
        for (String w : s.split(" ")) {
            if (length(w) >= 2) {
                words.add(w);
                if (words.size() == MAX_WORDS) break;
            }
        }
        return new SearchTerms(s, List.copyOf(words));
    }

    public boolean isEmpty() {
        return words.isEmpty();
    }

    /** 2글자 단어가 있으면 "두 글자 단어는 제목·태그에서만 찾았어요" (FR-011). */
    public boolean hasShortWord() {
        return words.stream().anyMatch(w -> !searchesBody(w));
    }

    public boolean hasBodyWord() {
        return words.stream().anyMatch(SearchTerms::searchesBody);
    }

    public static boolean searchesBody(String word) {
        return length(word) >= BODY_MIN_LENGTH;
    }

    /** 인덱스 후보를 고를 단어: 가장 긴 단어가 가장 드물다. 같으면 본문까지 찾는 단어 → 앞에 쓴 단어. */
    public String longest() {
        return words.stream().max(Comparator.comparingInt(SearchTerms::length)).orElseThrow();
    }

    /** 다른 검색의 커서를 거부하려고 목록 이름에 넣는 값. 검색어 원문은 커서에 담지 않는다. */
    public String fingerprint() {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(String.join("\u0001", words).toLowerCase().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(d, 0, 8);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** ILIKE 패턴: {@code %}, {@code _}, {@code \}를 글자 그대로 찾도록 이스케이프한다 (FR-009). PostgreSQL 기본 ESCAPE는 \이다. */
    public static String likePattern(String word) {
        StringBuilder sb = new StringBuilder("%");
        for (int i = 0; i < word.length(); i++) {
            char c = word.charAt(i);
            if (c == '\\' || c == '%' || c == '_') sb.append('\\');
            sb.append(c);
        }
        return sb.append('%').toString();
    }

    /** 결과 문장에서 단어를 찾는 정규식 (대소문자 무시). 긴 단어를 먼저 맞춘다. */
    public Pattern highlightPattern() {
        List<String> sorted = new ArrayList<>(words);
        sorted.sort(Comparator.comparingInt(SearchTerms::length).reversed());
        StringBuilder sb = new StringBuilder();
        for (String w : sorted) {
            if (!sb.isEmpty()) sb.append('|');
            sb.append(Pattern.quote(w));
        }
        return Pattern.compile(sb.toString(), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    }

    static int length(String s) {
        return s.codePointCount(0, s.length());
    }
}
