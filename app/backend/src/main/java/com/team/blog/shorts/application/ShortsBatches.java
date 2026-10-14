package com.team.blog.shorts.application;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import com.team.blog.shared.error.ApiException;
import com.team.blog.shared.error.FieldErrorItem;
import com.team.blog.shared.error.NotFoundException;
import com.team.blog.shared.text.TextCleaner;
import com.team.blog.shared.time.Times;

/**
 * 쇼츠 주제 묶음 (#276). 글 하나나 MCP로 들어온 맥락 하나를 작은 주제 2~5개로 나누고, 주제마다 쇼츠 한 편을 만든다.
 * 주제 나누기와 대본 쓰기는 사용자 쪽 AI가 하고(서버는 유료 AI를 부르지 않는다), 서버는 묶음과 근거를 남겨
 * 양산형 검사가 근거·같은 묶음의 다른 편과 비교하게 한다. 영상 만들기는 여전히 사람이 누른다(묶음 전체를 한 번에 누를 수 있다).
 */
@Service
public class ShortsBatches {
    public static final int TOPICS_MIN = 2;
    public static final int TOPICS_MAX = 5;
    public static final int HOOK_MAX = 120;
    public static final int SCOPE_MAX = 300;
    public static final int SOURCE_MAX = 20_000;
    static final int LIST_MAX = 30;

    /** @param hook 첫 장면에서 던질 질문·문제 한 줄, scope 이 편에서 다룰 것(다른 편과 겹치지 않게) */
    public record Topic(String title, String hook, String scope) {
        public Topic {
            title = TextCleaner.cleanLine(title);
            hook = TextCleaner.cleanLine(hook);
            scope = TextCleaner.cleanLine(scope);
        }
    }

    /** @param postId 근거 글(내 글). sourceText MCP로 받은 맥락. 둘 중 하나는 있어야 한다 */
    public record Plan(Long postId, String sourceText, String title, List<Topic> topics) {}

    /** @param shortsId 이 주제로 만든 쇼츠. 아직 대본이 없으면 null */
    public record TopicView(int number, String title, String hook, String scope, Long shortsId, ShortsService.Status status) {}

    public record Batch(long id, Long postId, String postTitle, String title, boolean hasSource, List<TopicView> topics,
                        Instant createdAt, Instant updatedAt) {}

    public record Skipped(long shortsId, String title, String reason) {}

    /** 묶음 영상 만들기 결과: 대기열에 넣은 쇼츠와 못 넣은 쇼츠(이유) */
    public record RenderAll(List<Long> queued, List<Skipped> skipped) {}

    private static final TypeReference<List<Topic>> TOPICS = new TypeReference<>() {};

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final ShortsService shorts;
    private final Clock clock;
    private final JsonMapper json = JsonMapper.builder().build();

    public ShortsBatches(JdbcTemplate jdbc, PlatformTransactionManager txm, ShortsService shorts, Clock clock) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(txm);
        this.shorts = shorts;
        this.clock = clock;
    }

    public Batch plan(long memberId, Plan plan) {
        shorts.requireEnabled();
        String title = ShortsScript.cleanTitle(plan.title());
        String source = plan.sourceText() == null ? "" : TextCleaner.cleanMultiline(plan.sourceText());
        List<Topic> topics = plan.topics() == null ? List.of() : plan.topics().stream()
                .map(t -> t == null ? new Topic("", "", "") : t).toList();
        List<FieldErrorItem> errors = new ArrayList<>();
        if (title.isEmpty()) errors.add(new FieldErrorItem("title", "REQUIRED", "묶음 제목을 적어 주세요."));
        else if (title.length() > ShortsScript.TITLE_MAX) errors.add(new FieldErrorItem("title", "TOO_LONG", "묶음 제목은 " + ShortsScript.TITLE_MAX + "자까지예요."));
        if (plan.postId() == null && source.isEmpty()) {
            errors.add(new FieldErrorItem("source", "REQUIRED", "근거가 필요해요. 내 글 번호(post_id)나 맥락 본문(source_text)을 주세요."));
        }
        if (source.length() > SOURCE_MAX) errors.add(new FieldErrorItem("sourceText", "TOO_LONG", "맥락 본문은 " + SOURCE_MAX + "자까지예요."));
        if (topics.size() < TOPICS_MIN || topics.size() > TOPICS_MAX) {
            errors.add(new FieldErrorItem("topics", "COUNT", "주제는 " + TOPICS_MIN + "~" + TOPICS_MAX + "개로 나눠 주세요."));
        }
        for (int i = 0; i < topics.size(); i++) {
            Topic t = topics.get(i);
            String at = "topics[" + i + "].";
            if (t.title().isEmpty()) errors.add(new FieldErrorItem(at + "title", "REQUIRED", (i + 1) + "번 주제의 제목이 없어요."));
            else if (t.title().length() > ShortsScript.TITLE_MAX) errors.add(new FieldErrorItem(at + "title", "TOO_LONG", (i + 1) + "번 주제 제목은 " + ShortsScript.TITLE_MAX + "자까지예요."));
            if (t.hook().isEmpty()) errors.add(new FieldErrorItem(at + "hook", "REQUIRED", (i + 1) + "번 주제의 첫 질문(hook)이 없어요."));
            else if (t.hook().length() > HOOK_MAX) errors.add(new FieldErrorItem(at + "hook", "TOO_LONG", (i + 1) + "번 주제의 첫 질문은 " + HOOK_MAX + "자까지예요."));
            if (t.scope().length() > SCOPE_MAX) errors.add(new FieldErrorItem(at + "scope", "TOO_LONG", (i + 1) + "번 주제의 범위는 " + SCOPE_MAX + "자까지예요."));
        }
        if (!errors.isEmpty()) throw ApiException.validation(errors);
        if (plan.postId() != null) ownPost(memberId, plan.postId());
        Instant now = Times.now(clock);
        Long id = jdbc.queryForObject("""
                INSERT INTO shorts_batch (member_id, post_id, title, source_text, topics, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?::jsonb, ?, ?) RETURNING id
                """, Long.class, memberId, plan.postId(), title, source, json.writeValueAsString(topics), Timestamp.from(now), Timestamp.from(now));
        return get(memberId, id);
    }

    /**
     * 묶음의 한 주제로 대본을 만든다. 근거(글·맥락)는 묶음 것을 쓰고, 주제마다 한 편만 둔다.
     * @param topic 1부터
     */
    public ShortsService.Item create(long memberId, long batchId, Integer topic, String title, List<ShortsScript.Scene> scenes) {
        shorts.requireEnabled();
        Row row = row(memberId, batchId);
        if (topic == null || topic < 1 || topic > row.topics().size()) {
            throw ApiException.badRequest("TOPIC_NOT_FOUND", "주제 번호(topic)는 1~" + row.topics().size() + " 사이예요.");
        }
        String name = title == null || title.isBlank() ? row.topics().get(topic - 1).title() : title;
        try {
            ShortsService.Item item = shorts.create(memberId, new ShortsService.Draft(row.postId(), name, scenes, batchId, topic, row.sourceText()));
            touch(batchId);
            return item;
        } catch (DuplicateKeyException e) {
            Long existing = jdbc.query("SELECT id FROM shorts WHERE batch_id = ? AND topic = ?", rs -> rs.next() ? rs.getLong(1) : null, batchId, topic);
            throw ApiException.conflict("TOPIC_TAKEN", topic + "번 주제는 이미 대본이 있어요" + (existing == null ? "" : " (쇼츠 " + existing + "번)")
                    + ". 그 대본을 고쳐 주세요.");
        }
    }

    public Batch get(long memberId, long batchId) {
        shorts.requireEnabled();
        return views(memberId, List.of(row(memberId, batchId))).getFirst();
    }

    public List<Batch> list(long memberId) {
        shorts.requireEnabled();
        List<Row> rows = jdbc.query("""
                SELECT b.id, b.post_id, p.title AS post_title, b.title, b.source_text, b.topics::text, b.created_at, b.updated_at
                FROM shorts_batch b LEFT JOIN post p ON p.id = b.post_id AND p.deleted_at IS NULL
                WHERE b.member_id = ? ORDER BY b.updated_at DESC, b.id DESC LIMIT ?
                """, (rs, i) -> rowOf(rs), memberId, LIST_MAX);
        return views(memberId, rows);
    }

    /**
     * 묶음의 대본을 주제 순서대로 모두 영상 만들기 대기열에 넣는다. 한 편씩 같은 검사(대본 규칙·양산형·하루 한도)를 거치고,
     * 막힌 편은 이유와 함께 건너뛴다. 하루 한도에 닿으면 남은 편은 모두 건너뛴다. 이미 대기·완성인 편은 손대지 않는다.
     */
    public RenderAll renderAll(long memberId, long batchId) {
        shorts.requireEnabled();
        row(memberId, batchId);
        List<Long> ids = jdbc.queryForList("""
                SELECT id FROM shorts WHERE batch_id = ? AND member_id = ? AND status IN ('DRAFT', 'FAILED') ORDER BY topic, id
                """, Long.class, batchId, memberId);
        List<Long> queued = new ArrayList<>();
        List<Skipped> skipped = new ArrayList<>();
        String stop = null;
        for (long id : ids) {
            ShortsService.Item item = shorts.get(memberId, id);
            if (stop != null) {
                skipped.add(new Skipped(id, item.title(), stop));
                continue;
            }
            try {
                shorts.render(memberId, id);
                queued.add(id);
            } catch (ApiException e) {
                String reason = e.errors().isEmpty() ? e.getMessage() : e.errors().getFirst().message();
                skipped.add(new Skipped(id, item.title(), reason));
                if (e.status().value() == HttpStatus.TOO_MANY_REQUESTS.value()) stop = reason;
            }
        }
        return new RenderAll(List.copyOf(queued), List.copyOf(skipped));
    }

    /** 묶음을 푼다. 대본과 영상은 그대로 남고 묶음·주제 표시만 사라진다 */
    public void delete(long memberId, long batchId) {
        shorts.requireEnabled();
        tx.executeWithoutResult(s -> {
            row(memberId, batchId);
            jdbc.update("UPDATE shorts SET batch_id = NULL, topic = NULL WHERE batch_id = ?", batchId);
            jdbc.update("DELETE FROM shorts_batch WHERE id = ?", batchId);
        });
    }

    private record Row(long id, Long postId, String postTitle, String title, String sourceText, List<Topic> topics, Instant createdAt,
                       Instant updatedAt) {}

    private Row row(long memberId, long batchId) {
        List<Row> rows = jdbc.query("""
                SELECT b.id, b.post_id, p.title AS post_title, b.title, b.source_text, b.topics::text, b.created_at, b.updated_at
                FROM shorts_batch b LEFT JOIN post p ON p.id = b.post_id AND p.deleted_at IS NULL
                WHERE b.id = ? AND b.member_id = ?
                """, (rs, i) -> rowOf(rs), batchId, memberId);
        if (rows.isEmpty()) throw new NotFoundException();
        return rows.getFirst();
    }

    private Row rowOf(ResultSet rs) throws SQLException {
        long postId = rs.getLong("post_id");
        Long post = rs.wasNull() ? null : postId;
        return new Row(rs.getLong("id"), post, rs.getString("post_title"), rs.getString("title"), rs.getString("source_text"),
                json.readValue(rs.getString(6), TOPICS), rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant());
    }

    private record Made(long id, ShortsService.Status status) {}

    private List<Batch> views(long memberId, List<Row> rows) {
        if (rows.isEmpty()) return List.of();
        Map<Long, Map<Integer, Made>> made = new HashMap<>();
        List<Long> ids = rows.stream().map(Row::id).toList();
        List<Object> args = new ArrayList<>(ids);
        args.addFirst(memberId);
        jdbc.query("SELECT batch_id, topic, id, status FROM shorts WHERE member_id = ? AND batch_id IN ("
                        + String.join(", ", Collections.nCopies(ids.size(), "?")) + ")",
                rs -> {
                    made.computeIfAbsent(rs.getLong(1), k -> new HashMap<>())
                            .put(rs.getInt(2), new Made(rs.getLong(3), ShortsService.Status.valueOf(rs.getString(4))));
                }, args.toArray());
        return rows.stream().map(r -> {
            Map<Integer, Made> byTopic = made.getOrDefault(r.id(), Map.of());
            List<TopicView> topics = new ArrayList<>();
            for (int i = 0; i < r.topics().size(); i++) {
                Topic t = r.topics().get(i);
                Made m = byTopic.get(i + 1);
                topics.add(new TopicView(i + 1, t.title(), t.hook(), t.scope(), m == null ? null : m.id(), m == null ? null : m.status()));
            }
            return new Batch(r.id(), r.postId(), r.postTitle(), r.title(), !r.sourceText().isEmpty(), List.copyOf(topics), r.createdAt(), r.updatedAt());
        }).toList();
    }

    private void touch(long batchId) {
        jdbc.update("UPDATE shorts_batch SET updated_at = ? WHERE id = ?", Timestamp.from(Times.now(clock)), batchId);
    }

    private void ownPost(long memberId, long postId) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM post WHERE id = ? AND author_id = ? AND deleted_at IS NULL",
                Integer.class, postId, memberId);
        if (n == null || n == 0) throw ApiException.badRequest("POST_NOT_FOUND", "내 글에서만 쇼츠를 만들 수 있어요. 글 번호를 확인해 주세요.");
    }
}
