package com.team.blog.mcp.application;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.team.blog.notification.application.NotificationService;
import com.team.blog.post.application.PostCommandService;
import com.team.blog.post.application.PostEditorQuery;
import com.team.blog.post.application.PublishCommand;
import com.team.blog.shared.config.BlogProperties;
import com.team.blog.shared.error.ApiException;
import com.team.blog.shared.error.NotFoundException;
import com.team.blog.shared.jdbc.Columns;
import com.team.blog.shared.time.Times;

/**
 * AI 글 제안과 AI 일기 (061·071).
 * <p>
 * 글 제안: 서버는 AI의 대화를 읽지 못하므로 "한 주제가 끝났다"는 판단은 연결한 AI가 한다. AI는 propose_post로 제목·쓸 범위·태그를 남기고,
 * 사용자는 대화에서 바로 쓰라고 하거나 devlog 내 글 관리에서 [임시글로 만들기]·[넘기기]를 누른다.
 * <p>
 * 새 제안이 생기면 회원에게 알림을 보낸다(071).
 * <p>
 * AI 일기: 회원이 설정에서 "AI 일기 쓰기"를 켜면 AI가 작업 단위마다 add_note로 한두 문장 메모를 남긴다. 매일 회원이 고른 시각(KST, 기본 0시)에
 * 그때까지의 메모를 날짜별 일기 하나로 묶고 메모는 지운다. 메모가 없는 날은 아무것도 만들지 않는다. 일기는 서버 AI를 거치지 않고
 * 주제별 소제목과 시각이 붙은 목록으로 묶는다(동의·비용이 필요 없고 결과가 늘 같다). 늘 임시글로 만들고, "AI가 발행·삭제하도록 허용"을
 * 켠 회원이면 글에 정해진 공개 범위로 바로 발행한다(071).
 */
@Service
public class AiJournal {
    private static final Logger log = LoggerFactory.getLogger(AiJournal.class);
    static final ZoneId KST = ZoneId.of("Asia/Seoul");
    static final int MAX_OPEN_PROPOSALS = 20;
    /** 하루 메모 상한. 1,000자 메모가 다 차도 일기 본문이 글 길이 상한(10만 자) 안에 들어가게 잡았다 */
    public static final int NOTES_PER_DAY = 60;
    static final int TITLE_MAX = 100;
    static final int SCOPE_MAX = 2000;
    static final int NOTE_MAX = 1000;
    static final int TOPIC_MAX = 50;
    /** 일기로 묶지 못한 메모(정지된 계정 등)는 이만큼 지나면 지운다 */
    private static final String CHARS_PLEASE = "자로 적어 주세요.";
    private static final String TITLE = "title";
    private static final String SCOPE = "scope";
    private static final String CREATED_AT = "created_at";
    static final Duration NOTE_KEEP = Duration.ofDays(7);

    public enum Status { OPEN, DRAFTED, DISMISSED }

    public record Proposal(long id, String title, String scope, List<String> tags, Status status, Long postId, Instant createdAt) {}

    /** @param todayCount 이번 일기 구간(고른 시각부터 24시간)에 남긴 메모 수 (이번 메모 포함) */
    public record NoteSaved(long id, int todayCount) {}

    /** @param hour 일기를 묶는 시각 (KST 0~23시) */
    public record DiarySetting(boolean enabled, int hour) {}

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final PostCommandService commands;
    private final PostEditorQuery editor;
    private final AiDraftHints hints;
    private final NotificationService notifications;
    private final Clock clock;
    private final int maxTags;

    public AiJournal(JdbcTemplate jdbc, TransactionTemplate tx, PostCommandService commands, PostEditorQuery editor, AiDraftHints hints,
                     NotificationService notifications, Clock clock, BlogProperties props) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.commands = commands;
        this.editor = editor;
        this.hints = hints;
        this.notifications = notifications;
        this.clock = clock;
        this.maxTags = props.post().maxTags();
    }

    // ── AI 일기 설정 ──────────────────────────────

    public DiarySetting diary(long memberId) {
        List<DiarySetting> rows = jdbc.query("SELECT ai_diary_enabled, ai_diary_hour FROM member WHERE id = ?",
                (rs, i) -> new DiarySetting(rs.getBoolean(1), rs.getInt(2)), memberId);
        if (rows.isEmpty()) throw new NotFoundException();
        return rows.getFirst();
    }

    public boolean diaryEnabled(long memberId) {
        return diary(memberId).enabled();
    }

    /** 끄면 아직 묶지 않은 메모도 지운다. 일기를 원하지 않는 사람의 작업 메모를 남겨 두지 않는다. */
    public boolean setDiaryEnabled(long memberId, boolean on) {
        return setDiary(memberId, on, null).enabled();
    }

    /** @param hour null이면 시각은 그대로 둔다 */
    public DiarySetting setDiary(long memberId, boolean on, Integer hour) {
        if (hour != null && (hour < 0 || hour > 23)) throw ApiException.badRequest("AI_DIARY_HOUR", "일기 시각(hour)은 0~23 사이로 골라 주세요.");
        tx.executeWithoutResult(s -> {
            int changed = hour == null
                    ? jdbc.update("UPDATE member SET ai_diary_enabled = ? WHERE id = ?", on, memberId)
                    : jdbc.update("UPDATE member SET ai_diary_enabled = ?, ai_diary_hour = ? WHERE id = ?", on, hour, memberId);
            if (changed == 0) throw new NotFoundException();
            if (!on) jdbc.update("DELETE FROM ai_note WHERE member_id = ?", memberId);
        });
        return diary(memberId);
    }

    /** "자정" 또는 "오후 10시"처럼 사람에게 보여 줄 시각 */
    public static String hourLabel(int hour) {
        if (hour == 0) return "자정";
        if (hour == 12) return "정오";
        return (hour < 12 ? "오전 " + hour : "오후 " + (hour - 12)) + "시";
    }

    // ── 글 제안 ──────────────────────────────

    /** 제안을 남긴다. 아직 정하지 않은 같은 제목의 제안이 있으면 범위·태그를 새로 바꾼다. @return 제안 번호 */
    public long propose(long memberId, String rawTitle, String rawScope, List<String> tags) {
        String title = oneLine(rawTitle);
        String scope = rawScope == null ? "" : rawScope.strip();
        if (title.isEmpty() || title.length() > TITLE_MAX) throw ApiException.badRequest("PROPOSAL_TITLE", "제목(title)은 1~" + TITLE_MAX + CHARS_PLEASE);
        if (scope.isEmpty() || scope.length() > SCOPE_MAX) throw ApiException.badRequest("PROPOSAL_SCOPE", "쓸 범위(scope)는 1~" + SCOPE_MAX + CHARS_PLEASE);
        String tagText = String.join(",", tags);
        Instant now = Times.now(clock);
        long[] made = tx.execute(s -> {
            // 같은 회원의 제안은 한 줄씩 차례로 (상한 검사와 같은 제목 찾기가 겹치지 않게)
            jdbc.queryForList("SELECT id FROM member WHERE id = ? FOR UPDATE", Long.class, memberId);
            List<Long> same = Columns.longs(jdbc, "SELECT id FROM ai_post_proposal WHERE member_id = ? AND status = 'OPEN' AND title = ?",
                    memberId, title);
            if (!same.isEmpty()) {
                jdbc.update("UPDATE ai_post_proposal SET scope = ?, tags = ?, created_at = ? WHERE id = ?",
                        scope, tagText, Timestamp.from(now), same.getFirst());
                return new long[] {same.getFirst(), 0};
            }
            Integer open = jdbc.queryForObject("SELECT count(*) FROM ai_post_proposal WHERE member_id = ? AND status = 'OPEN'",
                    Integer.class, memberId);
            if (open != null && open >= MAX_OPEN_PROPOSALS) {
                throw ApiException.conflict("PROPOSAL_LIMIT", "아직 정하지 않은 제안이 " + MAX_OPEN_PROPOSALS
                        + "개라 더 남길 수 없어요. 사용자에게 devlog 내 글 관리에서 지난 제안을 정리해 달라고 알려 주세요.");
            }
            Long id = jdbc.queryForObject("""
                    INSERT INTO ai_post_proposal (member_id, title, scope, tags, created_at) VALUES (?, ?, ?, ?, ?) RETURNING id
                    """, Long.class, memberId, title, scope, tagText, Timestamp.from(now));
            return new long[] {id, 1};
        });
        // 새 제안만 알린다. 커밋 뒤라 알림이 실패해도 제안은 남는다
        if (made[1] == 1) {
            try {
                notifications.aiProposed(made[0], memberId, now);
            } catch (RuntimeException e) {
                log.warn("AI 글 제안 {} 알림을 만들지 못했습니다: {}", made[0], e.getMessage());
            }
        }
        return made[0];
    }

    /** 정하지 않은 제안과 최근 30일 안에 임시글로 만든 제안. 최근 순 */
    public List<Proposal> proposals(long memberId, boolean includeDrafted) {
        return jdbc.query("""
                SELECT id, title, scope, tags, status, post_id, created_at FROM ai_post_proposal
                WHERE member_id = ? AND (status = 'OPEN' OR (? AND status = 'DRAFTED' AND decided_at > ?))
                ORDER BY status = 'OPEN' DESC, created_at DESC, id DESC LIMIT 40
                """, (rs, i) -> new Proposal(rs.getLong("id"), rs.getString(TITLE), rs.getString(SCOPE), split(rs.getString("tags")),
                Status.valueOf(rs.getString("status")), (Long) rs.getObject("post_id"), rs.getTimestamp(CREATED_AT).toInstant()),
                memberId, includeDrafted, Timestamp.from(Times.now(clock).minus(Duration.ofDays(30))));
    }

    public Optional<Proposal> find(long memberId, long id) {
        return jdbc.query("SELECT id, title, scope, tags, status, post_id, created_at FROM ai_post_proposal WHERE id = ? AND member_id = ?",
                (rs, i) -> new Proposal(rs.getLong("id"), rs.getString(TITLE), rs.getString(SCOPE), split(rs.getString("tags")),
                        Status.valueOf(rs.getString("status")), (Long) rs.getObject("post_id"), rs.getTimestamp(CREATED_AT).toInstant()),
                id, memberId).stream().findFirst();
    }

    /**
     * AI가 제안대로 쓴 글 (create_draft의 proposal_id). 먼저 제안을 잡고(OPEN → DRAFTED) 같은 트랜잭션에서 글을 만든다.
     * 그 사이 사용자가 [임시글로 만들기]나 [넘기기]를 눌렀으면 글을 만들지 않고 거절한다. @return 만든 글 번호
     */
    public long draftWith(long memberId, long id, String title, String content, List<String> tags) {
        return tx.execute(s -> {
            int claimed = jdbc.update("UPDATE ai_post_proposal SET status = 'DRAFTED', decided_at = ? WHERE id = ? AND member_id = ? AND status = 'OPEN'",
                    Timestamp.from(Times.now(clock)), id, memberId);
            if (claimed == 0) {
                Proposal p = find(memberId, id).orElseThrow(() -> ApiException.badRequest("PROPOSAL_NOT_FOUND",
                        "제안을 찾을 수 없어요 (proposal_id " + id + "). list_post_proposals로 번호를 확인해 주세요."));
                if (p.status() == Status.DISMISSED) throw ApiException.conflict("PROPOSAL_DISMISSED", "사용자가 넘긴 제안이에요. 다시 쓰려면 사용자에게 먼저 물어봐 주세요.");
                throw ApiException.conflict("PROPOSAL_DRAFTED", "이 제안은 이미 임시글" + (p.postId() == null ? "로" : " " + p.postId() + "번으로")
                        + " 만들었어요. get_post로 읽고 update_draft로 채워 주세요.");
            }
            long postId = commands.create(memberId, title, content).id();
            if (!tags.isEmpty()) hints.suggestTags(postId, tags);
            jdbc.update("UPDATE ai_post_proposal SET post_id = ? WHERE id = ?", postId, id);
            return postId;
        });
    }

    /**
     * 내 글 관리의 [임시글로 만들기]. 제목과 쓸 범위를 뼈대로 한 임시글을 만들고 제안한 태그를 발행 창에 미리 채운다.
     * 먼저 제안을 잡고(OPEN → DRAFTED) 글을 만들어, 두 번 눌러도 임시글은 하나다. 이미 만든 제안이면 그 글 번호를 돌려준다.
     */
    public long draft(long memberId, long id) {
        return tx.execute(s -> {
            Proposal p = jdbc.query("""
                    UPDATE ai_post_proposal SET status = 'DRAFTED', decided_at = ? WHERE id = ? AND member_id = ? AND status = 'OPEN'
                    RETURNING id, title, scope, tags, status, post_id, created_at
                    """, (rs, i) -> new Proposal(rs.getLong("id"), rs.getString(TITLE), rs.getString(SCOPE), split(rs.getString("tags")),
                    Status.OPEN, null, rs.getTimestamp(CREATED_AT).toInstant()), Timestamp.from(Times.now(clock)), id, memberId)
                    .stream().findFirst().orElse(null);
            if (p == null) {
                Proposal done = find(memberId, id).orElseThrow(NotFoundException::new);
                if (done.status() == Status.DRAFTED && done.postId() != null) return done.postId();
                throw ApiException.conflict("PROPOSAL_CLOSED", "이미 넘긴 제안이에요.");
            }
            long postId = commands.create(memberId, p.title(), outline(p)).id();
            if (!p.tags().isEmpty()) hints.suggestTags(postId, p.tags());
            jdbc.update("UPDATE ai_post_proposal SET post_id = ? WHERE id = ?", postId, id);
            return postId;
        });
    }

    public void dismiss(long memberId, long id) {
        int changed = jdbc.update("UPDATE ai_post_proposal SET status = 'DISMISSED', decided_at = ? WHERE id = ? AND member_id = ? AND status = 'OPEN'",
                Timestamp.from(Times.now(clock)), id, memberId);
        if (changed == 0 && find(memberId, id).isEmpty()) throw new NotFoundException();
    }

    /** 제안으로 만든 임시글의 첫 본문. 사용자가 직접 쓰거나 AI에게 이어 쓰게 한다 */
    static String outline(Proposal p) {
        return "## 쓸 범위\n\n" + p.scope() + "\n";
    }

    // ── 하루 메모와 AI 일기 ──────────────────────────────

    public NoteSaved addNote(long memberId, String rawTopic, String rawContent, List<String> tags) {
        return addNote(memberId, rawTopic, rawContent, tags, List.of());
    }

    /** @param links 근거 링크 (#240). 일기로 묶을 때 주제 문단 아래 "근거:" 줄로 붙는다 */
    NoteSaved addNote(long memberId, String rawTopic, String rawContent, List<String> tags, List<EvidenceLinks.Link> links) {
        String content = rawContent == null ? "" : rawContent.strip();
        String topic = oneLine(rawTopic);
        if (content.isEmpty() || content.length() > NOTE_MAX) throw ApiException.badRequest("NOTE_CONTENT", "메모(content)는 1~" + NOTE_MAX + CHARS_PLEASE);
        if (topic.length() > TOPIC_MAX) throw ApiException.badRequest("NOTE_TOPIC", "주제(topic)는 " + TOPIC_MAX + "자까지 적어 주세요.");
        Instant now = Times.now(clock);
        return tx.execute(s -> {
            DiarySetting setting = jdbc.queryForObject("SELECT ai_diary_enabled, ai_diary_hour FROM member WHERE id = ? FOR UPDATE",
                    (rs, i) -> new DiarySetting(rs.getBoolean(1), rs.getInt(2)), memberId);
            if (setting == null || !setting.enabled()) throw ApiException.conflict("AI_DIARY_OFF", AI_DIARY_OFF);
            // 하루 한도는 일기 하나가 묶는 24시간(고른 시각 기준)에 센다. 달력 날짜로 세면 일기 하나에 두 날치가 들어갈 수 있다
            Timestamp since = Timestamp.from(diaryWindowStart(now, setting.hour()));
            Integer count = jdbc.queryForObject("SELECT count(*) FROM ai_note WHERE member_id = ? AND created_at >= ?", Integer.class, memberId, since);
            int n = count == null ? 0 : count;
            if (n >= NOTES_PER_DAY) {
                throw ApiException.conflict("NOTE_LIMIT", "메모는 하루 " + NOTES_PER_DAY + "개까지 남길 수 있어요. 오늘은 여기까지 모아 둘게요.");
            }
            Long id = jdbc.queryForObject("INSERT INTO ai_note (member_id, topic, content, tags, links, created_at) VALUES (?, ?, ?, ?, ?, ?) RETURNING id",
                    Long.class, memberId, topic.isEmpty() ? null : topic, content, String.join(",", tags), EvidenceLinks.toStored(links), Timestamp.from(now));
            return new NoteSaved(id, n + 1);
        });
    }

    /** 일기 하나에 붙이는 근거 링크 상한. 메모 한도(하루 60개 × 1,000자)와 더해도 글 본문 상한(100,000자) 안에 들게 한다 */
    static final int DIARY_LINKS_MAX = 30;

    static final String AI_DIARY_OFF = "AI 일기 쓰기가 꺼져 있어요. 사용자가 devlog 설정 › AI 연결에서 'AI 일기 쓰기'를 켜야 메모를 남길 수 있어요.";

    /**
     * 매시 정각 작업 본체. 지금 시(KST)를 일기 시각으로 고른 회원마다, 이 시각 전에 남긴 메모를 날짜별 일기로 묶는다.
     * 쉬는 동안 놓친 날도 날짜마다 따로 만든다. 메모는 꺼내면서 지우므로(DELETE … RETURNING) 두 번 돌아도 일기는 한 번만 생긴다.
     * 회원 한 명이 실패해도 나머지는 계속한다. 일기를 만든 뒤 "AI가 발행·삭제하도록 허용"을 켠 회원이면 바로 발행한다.
     * @return 만든 일기 수
     */
    public int compileDiaries() {
        return compileDiariesAt(Times.now(clock));
    }

    /** compileDiaries를 정해 둔 시각으로 (테스트용) */
    public int compileDiariesAt(Instant now) {
        LocalDateTime local = LocalDateTime.ofInstant(now, KST).truncatedTo(ChronoUnit.HOURS);
        Instant cutoff = local.atZone(KST).toInstant();
        int hour = local.getHour();
        List<Long> members = Columns.longs(jdbc, """
                SELECT DISTINCT n.member_id FROM ai_note n JOIN member m ON m.id = n.member_id
                WHERE n.created_at < ? AND m.ai_diary_enabled AND m.ai_diary_hour = ? AND m.status = 'ACTIVE' AND m.deleted_at IS NULL
                """, Timestamp.from(cutoff), hour);
        int made = 0;
        for (long memberId : members) {
            List<Long> posts;
            try {
                posts = tx.execute(s -> compile(memberId, cutoff, hour, now));
            } catch (RuntimeException e) {
                log.warn("회원 {}의 AI 일기를 만들지 못했습니다. 메모는 남겨 두고 내일 같은 시각에 다시 묶습니다: {}", memberId, e.getMessage());
                continue;
            }
            made += posts.size();
            for (long postId : posts) autoPublish(memberId, postId);
        }
        // 묶지 못한 채 오래된 메모(정지된 계정 등)는 지운다
        jdbc.update("DELETE FROM ai_note WHERE created_at < ?", Timestamp.from(now.minus(NOTE_KEEP)));
        if (made > 0) log.info("AI 일기 {}개를 만들었습니다 ({}시)", made, hour);
        return made;
    }

    /**
     * "AI가 발행·삭제하도록 허용"을 켠 회원의 일기는 웹 발행과 같은 규칙으로 발행한다(071). 공개 범위는 글에 정해진 값이다.
     * 이메일 인증 전이거나 발행이 거절되면(검증 실패 등) 임시글로 둔다.
     */
    private void autoPublish(long memberId, long postId) {
        List<String> handle = jdbc.query("""
                SELECT m.handle FROM member m WHERE m.id = ? AND m.ai_publish_allowed AND m.status = 'ACTIVE' AND m.deleted_at IS NULL
                  AND EXISTS (SELECT 1 FROM auth_identity a WHERE a.member_id = m.id AND a.email_verified_at IS NOT NULL)
                """, (rs, i) -> rs.getString(1), memberId);
        if (handle.isEmpty()) return;
        try {
            PostEditorQuery.EditorView view = editor.open(memberId, handle.getFirst(), postId);
            // 비밀값이 보이면 사람이 확인하도록 임시글로 둔다 (086). 값은 로그에도 남기지 않는다
            List<String> secrets = SecretScanner.find(view.title(), view.contentMd());
            if (!secrets.isEmpty()) {
                log.warn("회원 {}의 AI 일기(글 {})에 비밀값으로 보이는 내용({})이 있어 임시글로 둡니다", memberId, postId, secrets);
                return;
            }
            List<String> tags = hints.find(postId).map(AiDraftHints.Hint::tags).orElse(List.of());
            commands.publish(new PublishCommand(postId, memberId, view.title(), view.contentMd(), view.summary(), view.visibility(), tags,
                    view.version(), view.thumbnailUrl(), view.thumbnailHidden()), handle.getFirst(), null);
        } catch (RuntimeException e) {
            log.warn("회원 {}의 AI 일기(글 {})를 발행하지 못해 임시글로 둡니다: {}", memberId, postId, e.getMessage());
        }
    }

    record Note(String topic, String content, List<String> tags, List<EvidenceLinks.Link> links, Instant at) {}

    /** @return 만든 일기 글 번호 (날짜 순) */
    private List<Long> compile(long memberId, Instant before, int hour, Instant now) {
        List<Note> notes = jdbc.query("DELETE FROM ai_note WHERE member_id = ? AND created_at < ? RETURNING topic, content, tags, links, created_at",
                (rs, i) -> new Note(rs.getString("topic"), rs.getString("content"), split(rs.getString("tags")),
                        EvidenceLinks.fromStored(rs.getString("links")), rs.getTimestamp(CREATED_AT).toInstant()), memberId, Timestamp.from(before));
        Map<LocalDate, List<Note>> byDay = new TreeMap<>();
        Instant oldest = now.minus(NOTE_KEEP);
        for (Note n : notes) {
            if (n.at().isBefore(oldest)) continue;
            byDay.computeIfAbsent(diaryDay(n.at(), hour), d -> new ArrayList<>()).add(n);
        }
        List<Long> out = new ArrayList<>();
        for (Map.Entry<LocalDate, List<Note>> day : byDay.entrySet()) {
            List<Note> list = day.getValue();
            list.sort((a, b) -> a.at().compareTo(b.at()));
            long postId = commands.createAiDiary(memberId, diaryTitle(day.getKey()), diaryBody(list)).id();
            Set<String> tags = new LinkedHashSet<>();
            for (Note n : list) for (String t : n.tags()) if (tags.size() < maxTags) tags.add(t);
            if (!tags.isEmpty()) hints.suggestTags(postId, List.copyOf(tags));
            out.add(postId);
        }
        return out;
    }

    /**
     * 메모가 들어갈 일기의 날짜. 일기 하나는 "고른 시각부터 다음 날 같은 시각 전까지" 24시간을 묶고, 그 24시간의 한가운데가 속한 날짜를 제목에 쓴다.
     * 0시(자정)면 그날 0~24시가 그날 일기이고, 22시면 전날 22시~오늘 22시가 오늘 일기, 6시면 어제 6시~오늘 6시(새벽 작업 포함)가 어제 일기다.
     */
    static LocalDate diaryDay(Instant at, int hour) {
        return diaryWindowEnd(at, hour).minusHours(12).toLocalDate();
    }

    /** at이 들어가는 일기 구간의 끝: at 뒤에 처음 오는 hour시 정각 (KST) */
    static LocalDateTime diaryWindowEnd(Instant at, int hour) {
        LocalDateTime t = LocalDateTime.ofInstant(at, KST);
        LocalDateTime end = t.toLocalDate().atTime(hour, 0);
        return end.isAfter(t) ? end : end.plusDays(1);
    }

    /** at이 들어가는 일기 구간의 시작 (끝에서 24시간 전) */
    public static Instant diaryWindowStart(Instant at, int hour) {
        return diaryWindowEnd(at, hour).minusDays(1).atZone(KST).toInstant();
    }

    static String diaryTitle(LocalDate day) {
        return day + " 개발 일기";
    }

    /**
     * 주제가 처음 나온 차례대로 소제목을 달고, 그 아래에 그 주제의 메모를 시간 순으로 한 문단으로 잇는다 (071 다듬기, v1.51.3).
     * 시각 목록("- 14:05 …")은 작업 기록처럼 딱딱해서 뺐다. 메모가 일기 문장 그대로 되도록 AI에는 1인칭 일기 말투로 쓰라고 안내한다.
     * 주제 없는 메모는 맨 끝 "그 밖에"로, 주제 없는 메모만 있으면 소제목 없이 문단만 둔다.
     * 메모에 근거 링크가 있으면 그 주제 문단 아래에 "근거: [PR #12](…) · [커밋 abc1234](…)" 한 줄을 둔다 (#240). 같은 주소는 한 번만, 일기 하나에 DIARY_LINKS_MAX개까지.
     */
    static String diaryBody(List<Note> notes) {
        Map<String, List<Note>> byTopic = new LinkedHashMap<>();
        for (Note n : notes) byTopic.computeIfAbsent(n.topic() == null ? "" : n.topic(), t -> new ArrayList<>()).add(n);
        List<Note> rest = byTopic.remove("");
        if (rest != null) byTopic.put("", rest);
        StringBuilder out = new StringBuilder();
        int linkBudget = DIARY_LINKS_MAX;
        for (Map.Entry<String, List<Note>> e : byTopic.entrySet()) {
            if (!out.isEmpty()) out.append('\n');
            if (!e.getKey().isEmpty()) out.append("## ").append(e.getKey()).append("\n\n");
            else if (byTopic.size() > 1) out.append("## 그 밖에\n\n");
            StringBuilder para = new StringBuilder();
            for (Note n : e.getValue()) {
                if (!para.isEmpty()) para.append(' ');
                para.append(sentence(n.content()));
            }
            out.append(para).append('\n');
            Map<String, EvidenceLinks.Link> links = new LinkedHashMap<>();
            for (Note n : e.getValue()) {
                for (EvidenceLinks.Link l : n.links()) if (links.size() < linkBudget) links.putIfAbsent(l.url(), l);
            }
            linkBudget -= links.size();
            if (!links.isEmpty()) {
                out.append("\n근거: ").append(String.join(" · ", links.values().stream().map(EvidenceLinks.Link::markdown).toList())).append('\n');
            }
        }
        return out.toString();
    }

    /** 메모를 문장으로 이을 수 있게 끝에 마침표가 없으면 붙인다. 닫는 괄호·따옴표는 건너뛰고 그 앞을 본다 ("Redis (색인)" → 마침표). */
    public static String sentence(String memo) {
        String m = memo.strip();
        int i = m.length() - 1;
        while (i >= 0 && ")]\"'”’」』".indexOf(m.charAt(i)) >= 0) i--;
        if (i < 0) return m;
        return ".!?~…。".indexOf(m.charAt(i)) >= 0 ? m : m + ".";
    }

    /** 탈퇴 정리 (020) */
    public void deleteAll(long memberId) {
        jdbc.update("DELETE FROM ai_note WHERE member_id = ?", memberId);
        jdbc.update("""
                DELETE FROM notification WHERE id IN (SELECT nap.notification_id FROM notification_ai_proposal nap
                                                      JOIN ai_post_proposal p ON p.id = nap.proposal_id WHERE p.member_id = ?)
                """, memberId);
        jdbc.update("DELETE FROM ai_post_proposal WHERE member_id = ?", memberId);
    }

    private static String oneLine(String raw) {
        return raw == null ? "" : raw.replaceAll("\\s+", " ").strip();
    }

    private static List<String> split(String tags) {
        return tags == null || tags.isEmpty() ? List.of() : Arrays.asList(tags.split(","));
    }
}
