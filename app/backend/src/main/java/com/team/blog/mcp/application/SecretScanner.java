package com.team.blog.mcp.application;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * AI가 쓴 글·메모에 비밀값이 섞였는지 본다 (086). AI는 사용자의 컴퓨터에서 파일·환경 변수를 읽을 수 있어,
 * "비밀값은 넣지 마세요"라는 안내만으로는 실수로 글에 들어가는 것을 막지 못한다.
 * 사람이 읽지 않고 공개되는 길(publish_post, AI 일기 자동 발행, 일기 메모)에서만 막는다. 웹에서 사람이 직접 발행하는 글은 그대로 둔다.
 * 오탐보다 놓침이 덜 나쁘도록 형식이 뚜렷한 값만 잡는다. 찾은 값 자체는 돌려주지 않고 종류만 돌려준다(로그·응답에 다시 새지 않게).
 */
public final class SecretScanner {
    private record Rule(String label, Pattern pattern) {}

    private static final List<Rule> RULES = List.of(
            new Rule("devlog 접근 토큰", Pattern.compile("\\bdv[lr]_[A-Za-z0-9_-]{30,}")),
            new Rule("GitHub 토큰", Pattern.compile("\\b(?:gh[pousr]_[A-Za-z0-9]{30,}|github_pat_\\w{40,})")),
            new Rule("AI API 키", Pattern.compile("\\bsk-(?:ant-|proj-)?[A-Za-z0-9_-]{24,}")),
            new Rule("Google API 키", Pattern.compile("\\bAIza[0-9A-Za-z_-]{35}")),
            new Rule("AWS 접근 키", Pattern.compile("\\b(?:AKIA|ASIA)[0-9A-Z]{16}\\b")),
            new Rule("Slack 토큰", Pattern.compile("\\bxox[abprs]-[A-Za-z0-9-]{10,}")),
            new Rule("개인 키", Pattern.compile("-----BEGIN (?:[A-Z]+ )?PRIVATE KEY-----")),
            new Rule("JWT", Pattern.compile("\\beyJ[A-Za-z0-9_-]{10,}\\.eyJ[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}")),
            new Rule("주민등록번호", Pattern.compile("(?<!\\d)\\d{2}(?:0[1-9]|1[0-2])(?:0[1-9]|[12]\\d|3[01])-[1-4]\\d{6}(?!\\d)")));

    /** 주소 안의 비밀번호 (postgres://user:pass@host). 자리표시자(${...}, <...>, ****, password)는 넘긴다 */
    private static final Pattern URL_PASSWORD = Pattern.compile("\\b[a-zA-Z][a-zA-Z0-9+.-]*://[^\\s:/@]+:([^\\s/@]+)@");
    private static final Pattern PLACEHOLDER = Pattern.compile("(?i)^(?:\\$.*|<.*>|\\*+|x+|pass(?:w(?:or)?d)?|pw|secret|changeme|example)$");

    private static final String URL_PASSWORD_LABEL = "주소 속 비밀번호";

    private SecretScanner() {}

    /** @return 찾은 비밀값 종류 (없으면 빈 목록). 같은 종류는 한 번만 */
    public static List<String> find(String... texts) {
        List<String> found = new ArrayList<>();
        for (String text : texts) {
            if (text == null || text.isEmpty()) continue;
            for (Rule r : RULES) {
                if (!found.contains(r.label()) && r.pattern().matcher(text).find()) found.add(r.label());
            }
            if (!found.contains(URL_PASSWORD_LABEL) && hasUrlPassword(text)) found.add(URL_PASSWORD_LABEL);
        }
        return found;
    }

    /** 주소 속 비밀번호 가운데 자리표시자가 아닌 것이 하나라도 있는가 */
    private static boolean hasUrlPassword(String text) {
        Matcher m = URL_PASSWORD.matcher(text);
        while (m.find()) {
            if (!PLACEHOLDER.matcher(m.group(1)).matches()) return true;
        }
        return false;
    }

    /** AI에게 돌려줄 안내 문장 */
    static String warning(List<String> kinds) {
        return "본문에 비밀값으로 보이는 내용(" + String.join(", ", kinds) + ")이 있어요.";
    }
}
