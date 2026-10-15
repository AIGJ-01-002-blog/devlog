package com.team.blog.mcp.application;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.team.blog.shared.time.Times;

/**
 * 전공자용 글감 추천 (075, suggest_topics). 회원 본인의 일기 메모·AI 일기·글 제안·임시글에서 "비전공자는 모르지만 전공자는 따져야 하는 것"
 * (동시성·장애·보안·성능·접근성·운영·인프라·AI 하네스·설계)이 보이는 주제를 골라, 이미 발행한 글과 겹치는지 함께 돌려준다.
 * <p>
 * 서버 AI를 부르지 않는다: 관점은 낱말 사전으로 고르고, 겹침은 제목·태그 낱말로 잰다. 결과가 늘 같고 비용·동의가 필요 없다.
 * 글감을 문장으로 다듬는 일은 연결한 AI가 하고, 저장은 propose_post에 맡긴다. 이 클래스는 아무것도 쓰지 않는다.
 * 근거로 보여 주는 자료 조각은 IP·메일 주소·비밀값처럼 보이는 값을 가려서 돌려준다(글에 그대로 옮겨지지 않게).
 */
@Service
public class TopicSuggester {
    static final int DEFAULT_DAYS = 14;
    static final int MAX_DAYS = 90;
    static final int DEFAULT_LIMIT = 5;
    static final int MAX_LIMIT = 10;
    private static final int SNIPPET = 140;
    private static final String TITLE = "title";
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(KST);
    /** AiJournal.diaryTitle과 같은 꼴. 일기는 자료로 읽고, 겹침 비교(발행한 글)에서는 뺀다 */
    private static final String DIARY_TITLE = "^[0-9]{4}-[0-9]{2}-[0-9]{2} 개발 일기$";

    /**
     * 전공자 관점. 영문 짧은 낱말은 단어 경계로, 한글은 포함 여부로 찾는다.
     * "배포했다"처럼 작업을 알리는 말과 "색 토큰"(디자인 토큰)은 관점으로 치지 않는다 (1.51.1, 실제 메모에서 거의 모든 글감이 운영·보안으로 잡혔다).
     */
    enum Lens {
        CONCURRENCY("동시성", "두 요청이 동시에 오면 무엇이 깨지나요? 무엇(잠금·버전·멱등 키)으로 막았나요?",
                "동시", "경쟁 조건", "잠금", "트랜잭션", "멱등", "중복 요청", "충돌", "버전 관문", "lock", "race", "transaction", "idempot", "lua"),
        FAILURE("장애·복구", "무엇이 어떻게 실패했고, 사용자는 무엇을 봤나요? 되돌리기·재시도는 어떻게 하나요?",
                "장애", "롤백", "재시도", "타임아웃", "복구", "flake", "재현", "503", "500 오류", "rollback", "retry", "timeout", "fallback"),
        SECURITY("보안", "누가 무엇을 볼 수 있으면 안 되나요? 검증은 어디서 하나요?",
                "보안", "(?<!색 |디자인 )토큰", "권한", "인증", "비밀", "암호", "취약", "개인 정보", "token", "oauth", "pkce", "xss", "csrf", "secret", "jwt"),
        PERFORMANCE("성능", "무엇을 재서 얼마나 줄였나요? 숫자(전·후)가 있나요?",
                "성능", "캐시", "인덱스", "번들", "지연", "쿼리", "느려", "최적화", "cache", "index", "bundle", "latency", "n+1", "cls"),
        ACCESSIBILITY("접근성", "키보드·화면 읽기 사용자는 이 화면을 어떻게 쓰나요?",
                "접근성", "키보드", "화면 읽기", "스크린 리더", "초점", "aria", "a11y", "focus"),
        OPERATIONS("운영·배포", "배포·로그·알림에서 무엇을 바꿨고, 다음 사람은 무엇을 보면 되나요?",
                "배포(?!했|해|한|됐)", "(?<!블)로그(?!인|아웃)", "로깅", "모니터링", "릴리스", "무중단", "deploy", "ci", "github actions", "logging", "sonar"),
        INFRA("인프라", "어떤 구성 요소를 왜 골랐고, 설정 파일 어디를 바꿨나요?",
                "도커", "쿠버네티스", "터널", "클러스터", "docker", "kubernetes", "k8s", "k3d", "redis", "postgres", "nginx", "minio", "pvc", "helm"),
        HARNESS("AI 하네스", "AI 에이전트에게 무엇을 맡기고, 사람은 무엇을 정했나요? 규칙은 어디에 적었나요?",
                "에이전트", "하네스", "프롬프트", "스킬", "agent", "mcp", "claude", "harness", "prompt", "llm", "spec kit"),
        DESIGN("설계·데이터", "다른 안은 무엇이었고 왜 이 구조를 골랐나요?",
                "스키마", "정규화", "마이그레이션", "설계", "schema", "erd", "migration", "flyway", "api 설계");

        final String label;
        final String question;
        final Pattern pattern;

        Lens(String label, String question, String... words) {
            this.label = label;
            this.question = question;
            List<String> parts = new ArrayList<>();
            for (String w : words) {
                boolean ascii = w.chars().allMatch(c -> c < 128);
                // 정규식 조각(로그 앞 "블" 제외)은 그대로, 영문은 앞뒤가 영문·숫자가 아닐 때만 맞춘다 (ci가 decision에 걸리지 않게)
                String part;
                if (w.contains("(?")) part = w;
                else if (ascii) part = "(?<![a-z0-9])" + Pattern.quote(w) + "(?![a-z0-9])";
                else part = Pattern.quote(w);
                parts.add(part);
            }
            this.pattern = Pattern.compile(String.join("|", parts), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
        }
    }

    /** 근거 자료 하나: 어디서 왔는지(kind·ref)와 가린 조각 */
    record Evidence(String kind, String ref, String snippet, Instant at) {}

    /** 이미 발행한 글 (겹침 비교용) */
    record Published(long id, String title, Set<String> words) {}

    static final class Candidate {
        final String title;
        final StringBuilder text = new StringBuilder();
        final List<Evidence> evidence = new ArrayList<>();
        Instant latest = Instant.EPOCH;
        Set<Lens> lenses = EnumSet.noneOf(Lens.class);
        List<Published> overlaps = List.of();
        boolean secretsSeen;

        Candidate(String title) {
            this.title = title;
        }

        void add(String kind, String ref, String body, Instant at) {
            text.append(' ').append(body);
            Masked m = mask(body);
            secretsSeen |= m.changed();
            evidence.add(new Evidence(kind, ref, snippet(m.text()), at));
            if (at.isAfter(latest)) latest = at;
        }

        int score() {
            return lenses.size() * 3 + Math.min(evidence.size(), 4) - (overlaps.isEmpty() ? 0 : 2);
        }

        private static String snippet(String s) {
            String one = s.replaceAll("\\s+", " ").strip();
            return one.length() <= SNIPPET ? one : one.substring(0, SNIPPET) + "…";
        }
    }

    public record Masked(String text, boolean changed) {}

    private static final Pattern WORD = Pattern.compile("[\\p{IsHangul}]+|[A-Za-z][A-Za-z0-9+#.]*");
    private static final Pattern IPV4 = Pattern.compile("(?<![0-9.])(?:\\d{1,3}\\.){3}\\d{1,3}(?![0-9.])");
    private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");
    /** 비밀값 이름과 구분자(token: , password= ...). 값은 SECRET_VALUE로 바로 뒤에서 읽는다 */
    private static final Pattern SECRET_KEY = Pattern.compile(
            "(?:password|passwd|pwd|secret|token|api[_-]?key|비밀번호|토큰)\\s*+[=:]\\s*+",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE | Pattern.CANON_EQ);
    /** 따옴표로 감싼 값이 먼저, 아니면 공백 전까지 */
    private static final Pattern SECRET_VALUE = Pattern.compile("\"[^\"]*+\"|'[^']*+'|\\S++");
    private static final Pattern BEARER = Pattern.compile("(?i)(bearer\\s+)[a-z0-9._~+/=-]{8,}");

    /** IP·메일 주소·비밀값처럼 보이는 값을 ****로 바꾼다. 무엇이 있었는지는 알려 주되 값은 돌려주지 않는다 */
    public static Masked mask(String raw) {
        String s = IPV4.matcher(raw).replaceAll("****");
        s = EMAIL.matcher(s).replaceAll("****");
        s = maskSecrets(s);
        s = BEARER.matcher(s).replaceAll("$1****");
        return new Masked(s, !s.equals(raw));
    }

    /** 비밀값 이름 뒤의 값만 ****로 바꾼다. 이름 뒤에 값이 없으면 다음 글자부터 다시 찾는다 */
    private static String maskSecrets(String s) {
        Matcher key = SECRET_KEY.matcher(s);
        Matcher value = SECRET_VALUE.matcher(s);
        StringBuilder out = new StringBuilder();
        int last = 0;
        int from = 0;
        while (key.find(from)) {
            value.region(key.end(), s.length());
            if (value.lookingAt()) {
                out.append(s, last, key.end()).append("****");
                last = value.end();
                from = last;
            } else {
                from = key.start() + 1;
            }
        }
        return out.append(s, last, s.length()).toString();
    }

    private final JdbcTemplate jdbc;
    private final AiJournal journal;
    private final Clock clock;

    public TopicSuggester(JdbcTemplate jdbc, AiJournal journal, Clock clock) {
        this.jdbc = jdbc;
        this.journal = journal;
        this.clock = clock;
    }

    /** 도구 결과 문장. days·limit은 범위 밖이면 가장 가까운 값으로 맞춘다 */
    public String suggest(long memberId, int days, String focus, int limit) {
        int d = Math.clamp(days, 1, MAX_DAYS);
        int n = Math.clamp(limit, 1, MAX_LIMIT);
        Instant since = Times.now(clock).minus(Duration.ofDays(d));
        Map<String, Candidate> byKey = new LinkedHashMap<>();
        int notes = collectNotes(byKey, memberId, since);
        int diaries = collectDiaries(byKey, memberId, since);
        int proposals = collectProposals(byKey, memberId);
        int drafts = collectDrafts(byKey, memberId, since);
        List<Published> published = published(memberId);

        List<String> focusWords = focus == null ? List.of() : words(focus).stream().toList();
        Picks picks = pick(byKey.values(), focusWords, published);
        List<Candidate> picked = picks.picked();

        if (notes + diaries + proposals + drafts == 0) {
            return "최근 " + d + "일 동안 읽을 자료(일기 메모·AI 일기·글 제안·임시글)가 없어요. "
                    + "작업하면서 add_note로 메모를 남기거나 propose_post로 제안을 남기면 다음에 글감을 고를 수 있어요. days를 늘려 볼 수도 있어요.";
        }
        String source = "최근 " + d + "일 자료(메모 " + notes + "개, AI 일기 " + diaries + "편, 글 제안 " + proposals + "개, 임시글 "
                + drafts + "편)";
        if (picked.isEmpty()) {
            return source + "에서 전공자용 관점(동시성·장애·보안·성능·접근성·운영·인프라·AI 하네스·설계)이 보이는 주제를 찾지 못했어요"
                    + (focus == null || focusWords.isEmpty() ? "." : " (focus: " + focus.strip() + ").")
                    + "\n지금 자료는 사용 후기나 일정 기록에 가까워요. 개발 일지(write_devlog)가 더 맞을 수 있어요.";
        }
        StringBuilder out = new StringBuilder(source).append("에서 전공자용 글감 ").append(Math.min(n, picked.size()))
                .append("개를 골랐어요. 발행한 글 ").append(published.size()).append("편과 겹치는지 비교했어요")
                .append(picks.plain() > 0 ? " (관점이 안 보이는 기록 " + picks.plain() + "개는 뺐어요)" : "").append(".\n");
        int i = 0;
        for (Candidate c : picked) {
            if (i == n) break;
            appendCandidate(out, ++i, c);
        }
        if (picked.size() > n) out.append("\n(관점이 보이는 주제가 ").append(picked.size() - n).append("개 더 있어요. limit을 늘려 볼 수 있어요.)\n");
        out.append("""

                글감을 글로 옮길 때
                - 비유보다 실제 코드·설정 조각을 넣고, 조각 바로 위에 파일 경로를 적으세요.
                - 비밀번호·토큰·서버 주소·IP·계정·포트는 ****로 가리세요.
                - "제 역할"처럼 사용자가 한 일을 적는 문장은 사용자에게 확인받은 것만 쓰세요.
                - 사용자가 고른 글감은 propose_post로 제목과 범위(다룰 것·뺄 것)를 남기세요. 이 도구는 아무것도 저장하지 않아요.""");
        return out.toString();
    }

    /** 1) 아직 일기로 묶이지 않은 메모: 주제별로. 읽은 메모 수를 돌려준다 */
    private int collectNotes(Map<String, Candidate> byKey, long memberId, Instant since) {
        int[] count = new int[1];
        jdbc.query("SELECT topic, content, created_at FROM ai_note WHERE member_id = ? AND created_at >= ? ORDER BY created_at",
                rs -> {
                    String topic = rs.getString("topic");
                    candidate(byKey, topic == null || topic.isBlank() ? "그 밖의 메모" : topic)
                            .add("메모", "", rs.getString("content"), rs.getTimestamp("created_at").toInstant());
                    count[0]++;
                }, memberId, Timestamp.from(since));
        return count[0];
    }

    /** 2) AI 일기: 소제목(## 주제)마다 하나. 읽은 일기 수를 돌려준다 */
    private int collectDiaries(Map<String, Candidate> byKey, long memberId, Instant since) {
        int[] count = new int[1];
        jdbc.query("""
                SELECT id, content_md, created_at FROM post
                WHERE author_id = ? AND deleted_at IS NULL AND title ~ ? AND created_at >= ? ORDER BY created_at DESC LIMIT 60
                """, rs -> {
            count[0]++;
            long id = rs.getLong("id");
            Instant at = rs.getTimestamp("created_at").toInstant();
            for (Map.Entry<String, String> sec : sections(rs.getString("content_md")).entrySet()) {
                candidate(byKey, sec.getKey()).add("일기", "글 " + id + "번", sec.getValue(), at);
            }
        }, memberId, DIARY_TITLE, Timestamp.from(since));
        return count[0];
    }

    /** 3) 정하지 않은 글 제안 (기간과 상관없이: 아직 정하지 않았으므로). 읽은 제안 수를 돌려준다 */
    private int collectProposals(Map<String, Candidate> byKey, long memberId) {
        int count = 0;
        for (AiJournal.Proposal p : journal.proposals(memberId, false)) {
            candidate(byKey, p.title()).add("글 제안", "제안 " + p.id() + "번", p.scope() + " " + String.join(" ", p.tags()), p.createdAt());
            count++;
        }
        return count;
    }

    /** 4) 최근에 고친 임시글 (일기 제외). 읽은 임시글 수를 돌려준다 */
    private int collectDrafts(Map<String, Candidate> byKey, long memberId, Instant since) {
        int[] count = new int[1];
        jdbc.query("""
                SELECT id, title, left(content_md, 2000) AS body, updated_at FROM post
                WHERE author_id = ? AND deleted_at IS NULL AND status = 'DRAFT' AND title !~ ? AND updated_at >= ?
                  AND (title <> '' OR content_md <> '') ORDER BY updated_at DESC LIMIT 30
                """, rs -> {
            String title = rs.getString(TITLE);
            candidate(byKey, title.isBlank() ? "(제목 없는 임시글 " + rs.getLong("id") + "번)" : title)
                    .add("임시글", "글 " + rs.getLong("id") + "번", rs.getString("body"), rs.getTimestamp("updated_at").toInstant());
            count[0]++;
        }, memberId, DIARY_TITLE, Timestamp.from(since));
        return count[0];
    }

    /** 겹침 비교에 쓸 발행한 글 (일기 제외, 최근 300편) */
    private List<Published> published(long memberId) {
        return jdbc.query("""
                SELECT p.id, p.title, COALESCE(string_agg(t.name, ' '), '') AS tags FROM post p
                LEFT JOIN post_tag pt ON pt.post_id = p.id LEFT JOIN tag t ON t.id = pt.tag_id
                WHERE p.author_id = ? AND p.deleted_at IS NULL AND p.status <> 'DRAFT' AND p.title !~ ?
                GROUP BY p.id, p.title ORDER BY p.id DESC LIMIT 300
                """, (rs, i) -> new Published(rs.getLong("id"), rs.getString(TITLE),
                words(rs.getString(TITLE) + " " + rs.getString("tags"))), memberId, DIARY_TITLE);
    }

    /** 고른 글감(점수 순)과 관점이 안 보여 뺀 기록 수 */
    private record Picks(List<Candidate> picked, int plain) {}

    /** 관점이 보이고 focus 낱말이 들어간 주제만 고른다 */
    private static Picks pick(Iterable<Candidate> candidates, List<String> focusWords, List<Published> published) {
        List<Candidate> picked = new ArrayList<>();
        int plain = 0;
        for (Candidate c : candidates) {
            String all = c.title + " " + c.text;
            for (Lens l : Lens.values()) if (l.pattern.matcher(all).find()) c.lenses.add(l);
            if (c.lenses.isEmpty()) {
                plain++;
            } else if (focusWords.isEmpty() || focusWords.stream().anyMatch(w -> all.toLowerCase(Locale.ROOT).contains(w))) {
                c.overlaps = overlaps(c, published);
                picked.add(c);
            }
        }
        picked.sort(Comparator.comparingInt(Candidate::score).reversed().thenComparing(c -> c.latest, Comparator.reverseOrder()));
        return new Picks(picked, plain);
    }

    /** 글감 하나: 관점·근거(최근 4개)·겹치는 글·가릴 것·물어볼 것 */
    private static void appendCandidate(StringBuilder out, int no, Candidate c) {
        out.append('\n').append(no).append(". ").append(c.title).append('\n');
        out.append("   - 관점: ").append(String.join(", ", c.lenses.stream().map(l -> l.label).toList())).append('\n');
        out.append("   - 근거:\n");
        c.evidence.stream().sorted(Comparator.comparing(Evidence::at).reversed()).limit(4).forEach(e -> out.append("     · ")
                .append(e.kind()).append(e.ref().isEmpty() ? "" : " " + e.ref()).append(" (").append(DAY.format(e.at())).append(") ")
                .append(e.snippet()).append('\n'));
        if (!c.overlaps.isEmpty()) {
            out.append("   - 겹치는 글: ");
            out.append(String.join(", ", c.overlaps.stream().map(p -> "[" + p.id() + "] " + p.title()).toList()));
            out.append(" → 같은 내용 대신 코드·설정·장애 원인처럼 새 각도만\n");
        }
        if (c.secretsSeen) out.append("   - 가릴 것: 자료에 IP·메일 주소·비밀값처럼 보이는 값이 있어요. 글에는 ****로 가려 쓰세요\n");
        out.append("   - 물어볼 것: ");
        out.append(String.join(" / ", c.lenses.stream().limit(2).map(l -> l.question).toList())).append('\n');
    }

    private static Candidate candidate(Map<String, Candidate> byKey, String title) {
        String t = title.strip();
        return byKey.computeIfAbsent(t.toLowerCase(Locale.ROOT).replaceAll("\\s+", ""), k -> new Candidate(t));
    }

    /** "## 주제" 소제목마다 본문을 나눈다. 소제목이 없으면 통째로 "오늘 한 일" */
    static Map<String, String> sections(String md) {
        Map<String, String> out = new LinkedHashMap<>();
        String head = "오늘 한 일";
        StringBuilder body = new StringBuilder();
        for (String line : md.split("\n")) {
            if (line.startsWith("## ")) {
                if (!body.toString().isBlank()) out.merge(head, body.toString(), (a, b) -> a + "\n" + b);
                head = line.substring(3).strip();
                body.setLength(0);
            } else {
                body.append(line).append('\n');
            }
        }
        if (!body.toString().isBlank()) out.merge(head, body.toString(), (a, b) -> a + "\n" + b);
        return out;
    }

    private static final Set<String> STOP = Set.of("글", "것", "수", "더", "한", "및", "the", "and", "for", "with", "만들기", "이야기", "정리",
            "설계", "기록", "개발", "devlog", "블로그", "spec", "새");

    /** 낱말: 한글·영문·숫자 덩어리, 두 글자 이상, 흔한 낱말 제외 */
    public static Set<String> words(String s) {
        Set<String> out = new HashSet<>();
        Matcher m = WORD.matcher(s.toLowerCase(Locale.ROOT));
        while (m.find()) {
            String w = m.group();
            if (w.length() >= 2 && !STOP.contains(w)) out.add(w);
        }
        return out;
    }

    /** 제목·태그 낱말이 두 개 이상 같으면 겹친다(제목이 한두 낱말이면 하나만 같아도). 많이 겹치는 순으로 둘까지 */
    static List<Published> overlaps(Candidate c, List<Published> published) {
        Set<String> mine = words(c.title);
        if (mine.isEmpty()) return List.of();
        record Hit(Published p, int shared) {}
        List<Hit> hits = new ArrayList<>();
        for (Published p : published) {
            int shared = 0;
            for (String w : mine) if (p.words().contains(w)) shared++;
            if (shared >= 2 || shared == 1 && mine.size() <= 2) hits.add(new Hit(p, shared));
        }
        hits.sort(Comparator.comparingInt(Hit::shared).reversed());
        return hits.stream().limit(2).map(Hit::p).toList();
    }
}
