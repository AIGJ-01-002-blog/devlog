package com.team.blog.shorts.application;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.text.Normalizer;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 양산형 방지 검사 (099 FR-003·FR-004, #242). YouTube는 2025-07-15부터 틀에 찍어낸 대량 생산 영상을 수익 창출에서 뺀다.
 * 영상 만들기 전에 대본이 ① 내 다른 쇼츠의 복사본인지 ② 원본 글을 낭독만 하는지 ③ 장면끼리 같은 틀을 되풀이하는지
 * ④ 내용 없는 장면이 있는지 보고 막는다. 내 경험·결과가 없거나 첫 장면이 약하거나 AI 티 나는 상투어가 있으면 경고만 한다.
 * <p>
 * 비교는 글자 3개씩 묶은 조각(3-gram)으로 한다. 띄어쓰기·문장 부호·대소문자를 지운 뒤 자르므로 단어를 조금 바꾸거나 띄어 써도 같다고 본다.
 * 외부 서비스를 부르지 않아 비용이 들지 않고, 같은 입력이면 늘 같은 결과다(테스트로 확인한다).
 */
public final class OriginalityCheck {
    static final double DUPLICATE_SIMILARITY = 0.6;
    static final double COPIED_CONTAINMENT = 0.8;
    static final double SCENE_SIMILARITY = 0.6;
    /** 같은 묶음의 다른 편과 이만큼 겹치면 경고: 주제를 나눈 뜻이 없어진다 */
    static final double SIBLING_SIMILARITY = 0.3;
    static final int SAME_OPENING_CHARS = 6;
    static final int MIN_NARRATION_CHARS = 8;
    /** 이보다 짧은 글은 비교할 조각이 너무 적어 우연히 겹친다. 비교하지 않는다 */
    static final int MIN_GRAMS = 10;
    static final int BLOCK_PENALTY = 40;
    static final int WARN_PENALTY = 10;

    private static final Pattern NOT_WORD = Pattern.compile("[^\\p{L}\\p{N}]+");
    /** 내 경험·결과: 낱말은 그대로 찾고, 띄어 쓸 수 있는 말과 숫자만 정규식으로 본다 */
    private static final List<String> EXPERIENCE_WORDS = List.of("제가", "저는", "저도", "저희", "내가", "나는", "우리", "직접", "겪", "실제로", "덕분에",
            "줄었", "늘었", "빨라", "느려", "바꿨", "고쳤");
    private static final Pattern EXPERIENCE = Pattern.compile("해\\s?(?:보니|봤)|\\d", Pattern.CANON_EQ);
    /** 첫 장면: 질문·문제(충격)·결론 숫자 중 하나로 시작해야 한다 (#277 대본 구조) */
    private static final List<String> HOOK_WORDS = List.of("왜", "어떻게", "문제", "오류", "에러", "실패", "막혔", "버그", "느려", "터졌", "날아갔", "사라졌",
            "증발", "모르면", "손해", "비밀");
    /** 띄어 쓸 수 있는 "안 되"만 정규식으로 본다. 물음표 끝맺음과 결론 숫자는 글자를 한 번만 훑는 검사로 본다(긴 입력에도 선형) */
    private static final Pattern HOOK = Pattern.compile("안\\s?되", Pattern.CANON_EQ);
    /** 결론 숫자 뒤에 오는 단위 */
    private static final String HOOK_UNITS = "초배분%편개명번";
    /** 사람이 말할 때는 잘 안 쓰는, AI 대본에 흔한 상투어 (#277). 찾으면 경고만 한다 */
    private static final Pattern CLICHE = Pattern.compile("여러분|오늘은[^.?!]{0,20}(?:알아|살펴)[보볼봐봅]|정말\\s?중요한|꿀팁\\s?대방출|구독과\\s?좋아요|좋아요와\\s?구독",
            Pattern.CANON_EQ);

    public enum Level { BLOCK, WARN }

    /** 하나의 판정. code는 화면·테스트가 쓰는 고정 이름, message는 무엇이 문제인지, hint는 어떻게 고치는지 */
    public record Issue(String code, Level level, String message, String hint) {}

    public record Report(int score, boolean passed, List<Issue> issues) {}

    /**
     * 같은 회원의 다른 쇼츠 대본 (제목은 보지 않는다).
     * @param sameBatch 같은 주제 묶음(#276)의 다른 편. 같은 근거에서 나와 겹치기 쉬워 더 낮은 기준으로 경고한다
     */
    public record OtherScript(long id, String title, List<ShortsScript.Scene> scenes, boolean sameBatch) {
        public OtherScript(long id, String title, List<ShortsScript.Scene> scenes) {
            this(id, title, scenes, false);
        }
    }

    private OriginalityCheck() {}

    /**
     * @param scenes     검사할 대본
     * @param sourceBody 원본 글 본문 Markdown. 원본이 없으면 null
     * @param others     내 다른 쇼츠 대본
     */
    public static Report check(List<ShortsScript.Scene> scenes, String sourceBody, List<OtherScript> others) {
        List<Issue> issues = new ArrayList<>();
        Set<String> grams = grams(joinNarration(scenes));

        // ① 내 다른 쇼츠와 거의 같은 대본. 같은 묶음의 편과는 덜 겹쳐도 경고한다
        addDuplicate(grams, others, issues);
        // ② 원본 글을 그대로 읽기만 함
        addIfPresent(issues, copied(grams, sourceBody));
        // ③ 장면끼리 같은 틀 되풀이
        addIfPresent(issues, template(scenes));
        // ④ 내용 없는 장면
        addIfPresent(issues, emptyScene(scenes));
        addWarnings(scenes, sourceBody, issues);

        long blocks = issues.stream().filter(i -> i.level() == Level.BLOCK).count();
        long warns = issues.size() - blocks;
        int score = (int) Math.max(0, 100 - blocks * BLOCK_PENALTY - warns * WARN_PENALTY);
        return new Report(score, blocks == 0, List.copyOf(issues));
    }

    private static void addIfPresent(List<Issue> issues, Issue issue) {
        if (issue != null) issues.add(issue);
    }

    private static void addDuplicate(Set<String> grams, List<OtherScript> others, List<Issue> issues) {
        Issue sibling = null;
        for (OtherScript other : others) {
            double sim = jaccard(grams, grams(joinNarration(other.scenes())));
            if (sim >= DUPLICATE_SIMILARITY) {
                // 막힘이 있으면 같은 묶음 경고는 덧붙이지 않는다
                issues.add(new Issue("DUPLICATE", Level.BLOCK,
                        "내 다른 쇼츠 '" + other.title() + "'와 내레이션이 " + percent(sim) + " 같아요.",
                        "같은 주제라도 이번 영상만의 장면(다른 문제, 다른 결과, 다른 예시)으로 바꿔 주세요."));
                return;
            }
            if (sibling == null && other.sameBatch() && sim >= SIBLING_SIMILARITY) {
                sibling = new Issue("SIBLING_OVERLAP", Level.WARN,
                        "같은 묶음의 '" + other.title() + "'와 내레이션이 " + percent(sim) + " 겹쳐요.",
                        "편마다 맡은 주제 하나만 다뤄 주세요. 겹치는 설명은 한쪽에만 두면 두 편 모두 짧고 또렷해져요.");
            }
        }
        addIfPresent(issues, sibling);
    }

    private static Issue copied(Set<String> grams, String sourceBody) {
        if (sourceBody == null || grams.size() < MIN_GRAMS) return null;
        double copied = containment(grams, grams(sourceBody));
        if (copied < COPIED_CONTAINMENT) return null;
        return new Issue("COPIED", Level.BLOCK,
                "내레이션의 " + percent(copied) + "가 원본 글 문장을 그대로 읽어요.",
                "글을 요약하지 말고 말로 설명해 주세요. 왜 막혔는지, 무엇을 해 봤는지, 결과가 어땠는지를 내 말투로 바꿔 쓰면 돼요.");
    }

    private static Issue emptyScene(List<ShortsScript.Scene> scenes) {
        for (int i = 0; i < scenes.size(); i++) {
            String n = normalize(scenes.get(i).narration());
            if (n.codePointCount(0, n.length()) < MIN_NARRATION_CHARS) {
                return new Issue("EMPTY_SCENE", Level.BLOCK,
                        (i + 1) + "번 장면의 내레이션이 너무 짧아요.",
                        "장면마다 한 문장 이상 설명을 넣거나, 필요 없는 장면은 지워 주세요.");
            }
        }
        return null;
    }

    private static void addWarnings(List<ShortsScript.Scene> scenes, String sourceBody, List<Issue> issues) {
        // 경고: 내 경험·결과가 없음
        boolean personal = scenes.stream().anyMatch(s -> hasExperience(s.narration()) || hasExperience(s.caption()));
        if (!personal) {
            issues.add(new Issue("NO_EXPERIENCE", Level.WARN,
                    "내가 직접 겪은 일이나 결과(숫자)가 한 장면도 없어요.",
                    "\"제가 해 보니\", \"직접 고쳐 보니 3초에서 0.4초로 줄었어요\"처럼 내 경험과 결과를 한 장면 넣으면 다른 영상과 달라져요."));
        }
        // 경고: 첫 장면이 약함
        if (!scenes.isEmpty() && !hasHook(scenes.get(0).narration())) {
            issues.add(new Issue("WEAK_HOOK", Level.WARN,
                    "첫 장면이 질문·문제·결론 숫자로 시작하지 않아요.",
                    "첫 3초에 \"왜 배포만 하면 서버가 죽을까요?\"처럼 문제를 던지거나 \"한 줄로 4초가 0.1초가 됐어요\"처럼 결론부터 말해 주세요."));
        }
        // 경고: AI 티 나는 상투어
        addIfPresent(issues, cliche(scenes));
        // 경고: 원본 글 없음
        if (sourceBody == null) {
            issues.add(new Issue("NO_SOURCE", Level.WARN,
                    "원본 글이나 맥락 근거가 없어요.",
                    "devlog 글에서 만들면 영상 설명에 글 링크를 넣어 근거와 자세한 내용을 보여 줄 수 있어요. AI라면 받은 맥락을 source_text로 함께 주세요."));
        }
    }

    private static Issue cliche(List<ShortsScript.Scene> scenes) {
        Set<String> cliches = new LinkedHashSet<>();
        for (ShortsScript.Scene s : scenes) {
            var m = CLICHE.matcher(s.narration() + " " + s.caption());
            while (m.find()) cliches.add(m.group());
        }
        if (cliches.isEmpty()) return null;
        return new Issue("CLICHE", Level.WARN,
                "AI 대본에 흔한 말이 있어요: " + String.join(", ", cliches.stream().map(c -> "\"" + c + "\"").toList()) + ".",
                "그 말을 빼고 바로 내용으로 들어가 주세요. 친구에게 말하듯 쓰면 사람 냄새가 나요.");
    }

    static boolean hasExperience(String text) {
        return containsAny(text, EXPERIENCE_WORDS) || EXPERIENCE.matcher(text).find();
    }

    static boolean hasHook(String text) {
        return containsAny(text, HOOK_WORDS) || HOOK.matcher(text).find() || endsWithQuestion(text) || hasCountedNumber(text);
    }

    /** 끝의 공백을 빼고 물음표(?, ？)로 끝나는가 */
    static boolean endsWithQuestion(String text) {
        int end = text.length();
        while (end > 0 && isSpace(text.charAt(end - 1))) end--;
        return end > 0 && (text.charAt(end - 1) == '?' || text.charAt(end - 1) == '？');
    }

    /**
     * "3초", "10 배", "1.5배"처럼 숫자 바로 뒤(공백 한 칸까지)에 단위가 오는가.
     * 숫자는 앞이 숫자가 아닌 곳에서만 시작하고, 한 번 읽은 숫자로 되돌아가지 않는다.
     */
    static boolean hasCountedNumber(String text) {
        String t = Normalizer.normalize(text, Normalizer.Form.NFC);
        int n = t.length();
        for (int i = 0; i < n; i++) {
            if (!isDigit(t.charAt(i)) || (i > 0 && isDigit(t.charAt(i - 1)))) continue;
            int j = skipDigits(t, i);
            if (j + 1 < n && t.charAt(j) == '.' && isDigit(t.charAt(j + 1))) j = skipDigits(t, j + 1);
            if (j < n && isSpace(t.charAt(j))) j++;
            if (j < n && HOOK_UNITS.indexOf(t.charAt(j)) >= 0) return true;
        }
        return false;
    }

    private static int skipDigits(String t, int from) {
        int j = from;
        while (j < t.length() && isDigit(t.charAt(j))) j++;
        return j;
    }

    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }

    /** 정규식 \\s와 같은 공백 글자 */
    private static boolean isSpace(char c) {
        return c == ' ' || c == '\t' || c == '\n' || c == '\u000B' || c == '\f' || c == '\r';
    }

    private static boolean containsAny(String text, List<String> words) {
        for (String w : words) if (text.contains(w)) return true;
        return false;
    }

    private static Issue template(List<ShortsScript.Scene> scenes) {
        List<String> narr = scenes.stream().map(s -> normalize(s.narration())).toList();
        for (int i = 0; i < narr.size(); i++) {
            Set<String> a = grams(narr.get(i));
            if (a.size() < MIN_GRAMS) continue;
            for (int j = i + 1; j < narr.size(); j++) {
                double sim = jaccard(a, grams(narr.get(j)));
                if (sim >= SCENE_SIMILARITY) {
                    return new Issue("REPEATED_SCENE", Level.BLOCK,
                            (i + 1) + "번과 " + (j + 1) + "번 장면이 " + percent(sim) + " 같아요.",
                            "같은 말을 되풀이하지 말고 장면마다 새 정보(원인, 시도, 결과, 배운 점)를 하나씩 넣어 주세요.");
                }
            }
        }
        Map<String, Integer> openings = new HashMap<>();
        for (String n : narr) {
            if (n.codePointCount(0, n.length()) < SAME_OPENING_CHARS) continue;
            openings.merge(n.substring(0, n.offsetByCodePoints(0, SAME_OPENING_CHARS)), 1, Integer::sum);
        }
        int most = openings.values().stream().mapToInt(Integer::intValue).max().orElse(0);
        if (most >= 3 && most * 2 >= narr.size()) {
            return new Issue("TEMPLATE_OPENING", Level.BLOCK,
                    "장면 " + most + "개가 같은 말로 시작해요.",
                    "\"다음으로…\", \"첫 번째…\" 같은 틀을 빼고 장면마다 내용으로 바로 시작해 주세요.");
        }
        return null;
    }

    static String joinNarration(List<ShortsScript.Scene> scenes) {
        StringBuilder sb = new StringBuilder();
        for (ShortsScript.Scene s : scenes) sb.append(s.narration()).append(' ');
        return sb.toString();
    }

    /** 띄어쓰기·문장 부호를 지우고 소문자로 */
    static String normalize(String text) {
        return text == null ? "" : NOT_WORD.matcher(text.toLowerCase(Locale.ROOT)).replaceAll("");
    }

    static Set<String> grams(String text) {
        String n = normalize(text);
        int[] cps = n.codePoints().toArray();
        Set<String> out = new HashSet<>();
        for (int i = 0; i + 3 <= cps.length; i++) out.add(new String(cps, i, 3));
        return out;
    }

    static double jaccard(Set<String> a, Set<String> b) {
        if (a.size() < MIN_GRAMS || b.size() < MIN_GRAMS) return 0;
        int common = 0;
        for (String g : a) if (b.contains(g)) common++;
        return (double) common / (a.size() + b.size() - common);
    }

    /** a의 조각 중 b에도 있는 비율 */
    static double containment(Set<String> a, Set<String> b) {
        if (a.isEmpty()) return 0;
        int common = 0;
        for (String g : a) if (b.contains(g)) common++;
        return (double) common / a.size();
    }

    private static String percent(double ratio) {
        return Math.round(ratio * 100) + "%";
    }
}
