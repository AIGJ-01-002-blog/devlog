package com.team.blog.mcp.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.team.blog.shared.time.Times;
import com.team.blog.view.application.ViewStats;

/**
 * 쇼츠로 만들 만한 글 고르기 (#240). AI가 글을 쓰거나 발행할 때 결과 문구로 알려 주고, list_shorts_candidates로 목록을 본다.
 * 쇼츠 검사(OriginalityCheck)가 요구하는 "직접 겪은 문제 · 숫자로 된 결과 · 코드 한 조각" 가운데 둘 이상이 글에 있으면 후보로 본다.
 * 글을 고르는 근거일 뿐 대본을 대신 만들지 않는다: 사용자가 원할 때만 AI가 create_shorts_script로 대본을 쓴다.
 */
@Component
class ShortsCandidates {
    static final int MIN_TEXT = 300;
    static final int SCAN = 50;
    static final Duration VIEW_WINDOW = Duration.ofDays(30);

    private static final Pattern CODE_BLOCK = Pattern.compile("(?s)```.*?```");
    /** 숫자 덩어리의 맨 앞에서만 시작하고 되돌아가지 않게(*+, ++) 해서 긴 숫자열에서도 한 번만 훑는다 */
    private static final Pattern NUMBER = Pattern.compile(
            "(?<!\\d)\\d\\d*+(?:[.,]\\d++)?+\\s*+(?:[%배초분일명개건번줄원]|ms|시간|[mgkt]b|[fqr]ps)",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE | Pattern.CANON_EQ);
    private static final Pattern PROBLEM = Pattern.compile("문제|오류|에러|버그|실패|원인|막혔|느려|느린|안 ?되|안 ?돼|해결|고쳤", Pattern.CANON_EQ);

    /** @param reasons 쇼츠로 좋은 점 (글에 있는 것만) */
    record Fit(boolean fit, List<String> reasons) {}

    record Candidate(long postId, String title, Instant publishedAt, long views, List<String> reasons) {}

    private final JdbcTemplate jdbc;
    private final ViewStats views;
    private final Clock clock;

    ShortsCandidates(JdbcTemplate jdbc, ViewStats views, Clock clock) {
        this.jdbc = jdbc;
        this.views = views;
        this.clock = clock;
    }

    /** 글 본문만 보고 판단한다. 코드 블록 밖의 글이 MIN_TEXT자보다 짧으면 60초를 채울 이야기가 없다고 본다 */
    static Fit fit(String content) {
        String md = content == null ? "" : content;
        boolean code = CODE_BLOCK.matcher(md).find();
        String prose = CODE_BLOCK.matcher(md).replaceAll(" ");
        List<String> reasons = new ArrayList<>();
        if (PROBLEM.matcher(prose).find()) reasons.add("직접 겪은 문제와 해결");
        if (NUMBER.matcher(prose).find()) reasons.add("숫자로 된 결과");
        if (code) reasons.add("화면에 보일 코드");
        boolean longEnough = prose.replaceAll("\\s+", " ").strip().length() >= MIN_TEXT;
        return new Fit(longEnough && reasons.size() >= 2, reasons);
    }

    /** 이 글로 이미 쇼츠를 만들었는가 (상태와 상관없이) */
    boolean hasShorts(long postId) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM shorts WHERE post_id = ?", Integer.class, postId);
        return n != null && n > 0;
    }

    /**
     * 아직 쇼츠가 없는 내 공개 글 가운데 후보 (좋은 점이 많은 순, 같으면 최근 30일 조회가 많은 순).
     * 비공개·친구 공개 글과 숨겨진 글, AI 일기는 빼고, 최근 발행한 SCAN편만 본다.
     */
    List<Candidate> of(long memberId, int limit) {
        record Row(long id, String title, String content, Instant publishedAt) {}
        List<Row> rows = jdbc.query("""
                SELECT p.id, p.title, p.content_md, p.published_at FROM post p
                WHERE p.author_id = ? AND p.status = 'PUBLISHED' AND p.deleted_at IS NULL AND p.visibility = 'PUBLIC'
                  AND p.hidden_at IS NULL AND NOT p.ai_diary AND NOT EXISTS (SELECT 1 FROM shorts s WHERE s.post_id = p.id)
                ORDER BY p.published_at DESC, p.id DESC LIMIT ?
                """, (rs, i) -> new Row(rs.getLong("id"), rs.getString("title"), rs.getString("content_md"),
                rs.getTimestamp("published_at").toInstant()), memberId, SCAN);
        Map<Long, Long> recent = views.byPostsSince(rows.stream().map(Row::id).toList(), Times.now(clock).minus(VIEW_WINDOW));
        List<Candidate> out = new ArrayList<>();
        for (Row r : rows) {
            Fit f = fit(r.content());
            if (f.fit()) out.add(new Candidate(r.id(), r.title(), r.publishedAt(), recent.getOrDefault(r.id(), 0L), f.reasons()));
        }
        out.sort(Comparator.comparingInt((Candidate c) -> c.reasons().size()).reversed()
                .thenComparing(Comparator.comparingLong(Candidate::views).reversed())
                .thenComparing(Comparator.comparing(Candidate::publishedAt).reversed()));
        return out.size() > limit ? out.subList(0, limit) : out;
    }
}
