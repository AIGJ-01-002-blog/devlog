package com.team.blog.ai.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Set;
import java.util.regex.Pattern;

import com.team.blog.shared.text.TextCleaner;

/**
 * AI로 보낼 글의 정리와 재사용 식별 (018 FR-006·FR-007·FR-022, docs/34 §4). 서식 기호는 빼고 글자만 남겨, 공백·사진 주소만 바뀐 글은
 * 같은 입력이 된다. 대소문자는 바꾸지 않는다(언어 이름·고유 명사를 그대로 보낸다).
 */
public final class AiInput {
    /** 지시문·응답 형식을 바꿀 때만 올린다. 재사용 식별값에 들어가 이전 결과를 무효로 만든다 (FR-023). */
    public static final int PROMPT_VERSION = 1;
    static final int CODE_LINES = 5;

    private static final Pattern FENCE = Pattern.compile("^\\s{0,3}+(`{3,}+|~{3,}+)\\s*+([^\\s`]*+).*$");
    private static final Pattern IMAGE = Pattern.compile("!\\[[^\\]]*]\\([^)]*\\)|<img\\b[^>]*>", Pattern.CASE_INSENSITIVE);
    private static final Pattern LINK = Pattern.compile("\\[([^\\]]*)]\\([^)]*\\)");
    private static final Pattern AUTOLINK = Pattern.compile("<(https?://[^>\\s]+)>");
    private static final Pattern HTML_TAG = Pattern.compile("</?[a-zA-Z][^>]*>");
    private static final Pattern RULE = Pattern.compile("^\\s*([-*_])(?:\\s*+\\1){2,}+\\s*$");
    private static final Pattern HEADING = Pattern.compile("^\\s{0,3}#{1,6}\\s+");
    private static final Pattern QUOTE = Pattern.compile("^\\s*(?:>\\s?)++");
    private static final Pattern LIST = Pattern.compile("^\\s*(?:[-*+]|\\d+[.)])\\s+(?:\\[[ xX]]\\s+)?");
    private static final Pattern EMPHASIS = Pattern.compile("(\\*{1,3}|_{1,3}|~~)(?=\\S)(.+?)(?<=\\S)\\1");
    private static final Pattern INLINE_CODE = Pattern.compile("`+([^`]+)`+");
    private static final Pattern SPACES = Pattern.compile("\\s+");

    private AiInput() {}

    /** 제목과 본문을 정리한 결과. body는 자르기 전 전체다. */
    public record Cleaned(String title, String body) {
        public int length() {
            return body.codePointCount(0, body.length());
        }

        /** 공급자 최대 글자 수로 자른 본문 */
        public String bodyUpTo(int maxChars) {
            return truncate(body, maxChars);
        }

        public boolean truncatedAt(int maxChars) {
            return length() > maxChars;
        }
    }

    public static Cleaned clean(String title, String markdown) {
        return new Cleaned(SPACES.matcher(TextCleaner.cleanLine(title)).replaceAll(" "), cleanBody(markdown));
    }

    static String cleanBody(String markdown) {
        String text = TextCleaner.cleanMultiline(markdown);
        StringBuilder out = new StringBuilder(text.length());
        String fence = null;
        int codeLines = 0;
        for (String line : text.split("\n", -1)) {
            var m = FENCE.matcher(line);
            if (fence != null) {
                // 코드 블록 안: 닫는 울타리까지 앞 5줄만 남긴다
                if (m.matches() && m.group(1).charAt(0) == fence.charAt(0) && m.group(1).length() >= fence.length() && m.group(2).isEmpty()) {
                    fence = null;
                } else if (codeLines++ < CODE_LINES) {
                    out.append(line).append('\n');
                }
            } else if (m.matches()) {
                fence = m.group(1);
                codeLines = 0;
                if (!m.group(2).isEmpty()) out.append(m.group(2)).append('\n');
            } else {
                out.append(cleanLine(line)).append('\n');
            }
        }
        return SPACES.matcher(out).replaceAll(" ").strip();
    }

    private static String cleanLine(String line) {
        if (RULE.matcher(line).matches()) return "";
        String s = IMAGE.matcher(line).replaceAll("");
        s = LINK.matcher(s).replaceAll("$1");
        s = AUTOLINK.matcher(s).replaceAll("$1");
        s = HTML_TAG.matcher(s).replaceAll("");
        s = HEADING.matcher(s).replaceFirst("");
        s = QUOTE.matcher(s).replaceFirst("");
        s = LIST.matcher(s).replaceFirst("");
        s = INLINE_CODE.matcher(s).replaceAll("$1");
        // 굵게 안의 기울임처럼 겹친 강조도 벗긴다
        for (int i = 0; i < 3; i++) {
            String next = EMPHASIS.matcher(s).replaceAll("$2");
            if (next.equals(s)) break;
            s = next;
        }
        return s;
    }

    static String truncate(String s, int maxChars) {
        return s.codePointCount(0, s.length()) <= maxChars ? s : s.substring(0, s.offsetByCodePoints(0, maxChars));
    }

    /**
     * 같은 내용 재사용 식별값: 프롬프트 버전 + 정리한 제목·본문(외부 AI 최대 길이까지). 이미 붙인 태그·인기 태그는 넣지 않는다(FR-023).
     * 내용에서 만든 값이라 보관소에 글 내용이 남지 않는다.
     */
    public static String key(Cleaned input, int maxChars) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            String s = PROMPT_VERSION + "\u0000" + input.title() + "\u0000" + input.bodyUpTo(maxChars);
            return HexFormat.of().formatHex(sha.digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** 3글자 단위 유사도 (자카드, pg_trgm의 similarity와 같은 뜻). 둘 다 비면 1. */
    public static double similarity(String a, String b) {
        Set<String> x = trigrams(a);
        Set<String> y = trigrams(b);
        if (x.isEmpty() && y.isEmpty()) return 1;
        int common = 0;
        for (String t : x) if (y.contains(t)) common++;
        return (double) common / (x.size() + y.size() - common);
    }

    private static Set<String> trigrams(String s) {
        int[] cps = ("  " + s.toLowerCase(java.util.Locale.ROOT) + " ").codePoints().toArray();
        Set<String> out = new HashSet<>();
        for (int i = 0; i + 3 <= cps.length; i++) out.add(new String(cps, i, 3));
        return out;
    }
}
