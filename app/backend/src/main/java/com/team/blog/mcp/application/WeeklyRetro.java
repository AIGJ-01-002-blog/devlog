package com.team.blog.mcp.application;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.team.blog.post.application.PostCommandService;
import com.team.blog.post.application.PublishCommand;
import com.team.blog.post.application.PublishExtension;
import com.team.blog.post.domain.Post;
import com.team.blog.shared.error.FieldErrorItem;
import com.team.blog.shared.markdown.RenderedContent;
import com.team.blog.shared.time.Times;

/**
 * 주간 회고 초안 (spec 107). 매일 자동 발행되는 AI 일기는 검색 수집에서 빠진다(spec 088). 대신 매주 일요일에 그 주 일기를 한 편으로 묶어
 * 임시글로 만들고, 사람이 도입·배운 점 문단을 직접 써야 발행된다. 이렇게 해야 수집되는 글에 사람이 쓴 문단이 늘 들어간다.
 * <p>
 * 사람이 쓴 문단 없이 수집 대상으로 나가는 길을 막는 곳은 두 군데다: 발행 검증(이 클래스의 validate, 웹·MCP 모두 지난다)과
 * MCP의 update_draft·publish_post 거절(McpTools). 회고는 AI 일기가 아니라서 본문이 1,000자를 넘으면 수집 대상이 된다.
 */
@Component
public class WeeklyRetro implements PublishExtension {
    private static final Logger log = LoggerFactory.getLogger(WeeklyRetro.class);
    /** 사람이 써야 하는 소제목. 이 아래 글자 수(안내 줄 빼고)가 HUMAN_MIN_CHARS 이상이어야 발행된다 */
    static final String INTRO = "## 도입";
    static final String LEARNED = "## 배운 점";
    static final int HUMAN_MIN_CHARS = 100;
    /** 초안에 넣는 안내 줄. 지우지 않아도 글자 수에는 세지 않는다 */
    static final String GUIDE_MARK = "> ✍️";
    static final String HUMAN_ONLY = "주간 회고는 사람이 도입·배운 점을 직접 써서 웹에서 발행하는 글이라 AI 도구로 고치거나 발행할 수 없어요.";
    private static final Pattern DIARY_TITLE = Pattern.compile("^(\\d{4}-\\d{2}-\\d{2}) 개발 일기$");

    private final JdbcTemplate jdbc;
    private final PostCommandService commands;
    private final AiDraftHints hints;
    private final Clock clock;

    public WeeklyRetro(JdbcTemplate jdbc, @Lazy PostCommandService commands, AiDraftHints hints,
                       Clock clock) {
        this.jdbc = jdbc;
        this.commands = commands;
        this.hints = hints;
        this.clock = clock;
    }

    record Diary(long id, String handle, LocalDate day, String contentMd, String status) {}

    /** 이번 주(월~일, KST) 일기가 있는 회원마다 초안을 하나씩 만든다. 이미 만든 주는 건너뛴다. @return 만든 초안 수 */
    public int createDrafts() {
        LocalDate today = LocalDate.ofInstant(Times.now(clock), AiJournal.KST);
        LocalDate monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        List<Long> members = jdbc.queryForList("""
                SELECT DISTINCT p.author_id FROM post p JOIN member m ON m.id = p.author_id
                WHERE p.ai_diary AND p.deleted_at IS NULL AND m.status = 'ACTIVE' AND m.deleted_at IS NULL
                  AND p.created_at >= ? AND NOT EXISTS (
                      SELECT 1 FROM post r WHERE r.author_id = p.author_id AND r.retro_week = ? AND r.deleted_at IS NULL)
                """, Long.class, java.sql.Timestamp.from(monday.atStartOfDay(AiJournal.KST).toInstant()), monday);
        int made = 0;
        for (long memberId : members) {
            try {
                if (createDraft(memberId, monday)) made++;
            } catch (RuntimeException e) {
                log.warn("회원 {}의 주간 회고 초안을 만들지 못했어요: {}", memberId, e.getMessage());
            }
        }
        if (made > 0) log.info("주간 회고 초안 {}개를 만들었습니다 ({} 주)", made, monday);
        return made;
    }

    /** @return 만들었으면 true. 그 주 일기가 없거나 이미 있으면 false */
    boolean createDraft(long memberId, LocalDate monday) {
        LocalDate sunday = monday.plusDays(6);
        List<Diary> diaries = jdbc.query("""
                SELECT p.id, m.handle, p.title, COALESCE(p.content_md, '') AS content_md, p.status FROM post p JOIN member m ON m.id = p.author_id
                WHERE p.author_id = ? AND p.ai_diary AND p.deleted_at IS NULL ORDER BY p.title
                """, (rs, i) -> {
            Matcher t = DIARY_TITLE.matcher(rs.getString("title"));
            if (!t.matches()) return null;
            return new Diary(rs.getLong("id"), rs.getString("handle"), LocalDate.parse(t.group(1)), rs.getString("content_md"), rs.getString("status"));
        }, memberId).stream().filter(d -> d != null && !d.day().isBefore(monday) && !d.day().isAfter(sunday)).limit(7).toList();
        if (diaries.isEmpty()) return false;
        long postId = commands.create(memberId, title(monday), body(diaries)).id();
        try {
            jdbc.update("UPDATE post SET retro_week = ? WHERE id = ?", monday, postId);
        } catch (DuplicateKeyException e) {
            // 다른 서버가 먼저 만들었다. 방금 만든 초안은 지운다
            jdbc.update("DELETE FROM post WHERE id = ? AND status = 'DRAFT'", postId);
            return false;
        }
        hints.markAssisted(postId);
        return true;
    }

    static String title(LocalDate monday) {
        return monday + " ~ " + monday.plusDays(6) + " 주간 회고";
    }

    /** 도입 → 그 주 일기(소제목 두 단계 내림) → 배운 점. 도입·배운 점에는 안내 줄만 둔다 */
    static String body(List<Diary> diaries) {
        StringBuilder out = new StringBuilder(INTRO).append("\n\n")
                .append(GUIDE_MARK).append(" 이번 주에 무엇을 하려 했고 어디까지 했는지 직접 써 주세요. ")
                .append(HUMAN_MIN_CHARS).append("자 이상 써야 발행할 수 있어요. 이 줄은 지워도 되고 남겨도 글자 수에 들어가지 않아요.\n\n")
                .append("## 이번 주 일기\n\n");
        for (Diary d : diaries) {
            out.append("### ").append(d.day()).append(" 개발 일기");
            if ("PUBLISHED".equals(d.status())) out.append(" ([원문](/@").append(d.handle()).append("/posts/").append(d.id()).append("))");
            out.append("\n\n").append(demote(d.contentMd().strip())).append("\n\n");
        }
        return out.append(LEARNED).append("\n\n")
                .append(GUIDE_MARK).append(" 이번 주에 새로 알게 된 것, 다음에 다르게 할 것을 직접 써 주세요. ")
                .append(HUMAN_MIN_CHARS).append("자 이상 써야 발행할 수 있어요.\n").toString();
    }

    /** 일기 안 소제목(## 주제)을 회고의 일기 소제목(###) 아래로 두 단계 내린다. 코드 블록 안은 그대로 */
    static String demote(String md) {
        StringBuilder out = new StringBuilder();
        boolean code = false;
        for (String line : md.split("\n", -1)) {
            if (line.startsWith("```") || line.startsWith("~~~")) code = !code;
            if (!code && line.matches("#{1,4} .*")) line = "##" + line;
            out.append(line).append('\n');
        }
        return out.toString().stripTrailing();
    }

    public boolean isRetro(long postId) {
        return Boolean.TRUE.equals(jdbc.query("SELECT retro_week IS NOT NULL FROM post WHERE id = ?",
                rs -> rs.next() ? rs.getBoolean(1) : false, postId));
    }

    /** 주간 회고는 도입·배운 점에 사람이 쓴 글이 있어야 발행된다. 웹 발행 창·MCP 발행 모두 여기를 지난다 */
    @Override
    public void validate(PublishCommand command, List<FieldErrorItem> errors) {
        if (!isRetro(command.postId())) return;
        String md = command.contentMd() == null ? "" : command.contentMd();
        for (String heading : List.of(INTRO, LEARNED)) {
            if (humanChars(md, heading) < HUMAN_MIN_CHARS) {
                errors.add(new FieldErrorItem("contentMd", "RETRO_NEEDS_HUMAN",
                        "주간 회고의 '" + heading.substring(3) + "'에 직접 쓴 글이 " + HUMAN_MIN_CHARS + "자 이상 있어야 발행할 수 있어요."));
            }
        }
    }

    /** 소제목 아래부터 다음 같은 단계 소제목 전까지, 안내 줄을 뺀 글자 수(공백 제외). 소제목이 없으면 0 */
    static int humanChars(String md, String heading) {
        int count = 0;
        boolean in = false;
        for (String line : md.split("\n", -1)) {
            if (line.startsWith("## ")) {
                in = line.strip().equals(heading);
                continue;
            }
            if (!in || line.startsWith(GUIDE_MARK)) continue;
            String text = line.replaceAll("\\s", "");
            count += text.codePointCount(0, text.length());
        }
        return count;
    }

    @Override
    public void onPublish(Post post, RenderedContent rendered, PublishCommand command) {
        // 검증만 한다
    }
}
