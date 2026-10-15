package com.team.blog.post.application;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.team.blog.post.infra.PostSql;
import com.team.blog.shared.config.BlogProperties;
import com.team.blog.shared.cursor.CursorCodec;
import com.team.blog.shared.error.ApiException;
import com.team.blog.shared.time.Times;

/**
 * 내 글 관리 목록 (docs/41 §5). 작성자 = 현재 사용자 고정, 20개씩 커서, 본문은 읽지 않는다.
 * 정렬은 updated_at 최신순, 같으면 id 큰 순 (ix_post_manage). 개수는 첫 요청에만 한 번의 GROUP BY로 센다.
 * 휴지통 탭은 007에서 붙인다 (지금은 개수만 0 이상으로 보여 준다).
 */
@Service
public class MyPostsQuery {
    private static final Logger log = LoggerFactory.getLogger(MyPostsQuery.class);

    private final JdbcTemplate jdbc;
    private final CursorCodec cursors;
    private final StringRedisTemplate redis;
    private final int pageSize;

    public MyPostsQuery(JdbcTemplate jdbc, CursorCodec cursors, StringRedisTemplate redis, BlogProperties props) {
        this.jdbc = jdbc;
        this.cursors = cursors;
        this.redis = redis;
        this.pageSize = props.feed().managePageSize();
    }

    public enum Tab { DRAFTS, PUBLISHED, TRASH }

    public record Item(long id, String title, String status, String visibility, boolean editing, boolean hidden,
                       Instant updatedAt, Instant publishedAt, Instant editedAt, Instant deletedAt, Instant purgeAt,
                       long viewCount, int likeCount, int commentCount) {}

    public record Counts(long drafts, long published, long trash) {}

    public record Result(List<Item> items, String nextCursor, Counts counts) {}

    /** 제목 검색어 상한. 쇼츠 스튜디오의 바탕 글 고르기(#308)처럼 내 글 전체에서 찾을 때 쓴다 */
    static final int QUERY_MAX = 100;

    public Result list(long memberId, String tabParam, String visibilityParam, String cursor) {
        return list(memberId, tabParam, visibilityParam, cursor, null);
    }

    /** @param q 제목에 들어간 글자(선택, 대소문자 무시). 비우면 전체 */
    public Result list(long memberId, String tabParam, String visibilityParam, String cursor, String q) {
        Tab tab = parseTab(tabParam);
        String visibility = parseVisibility(visibilityParam);
        String query = q == null ? "" : q.strip();
        if (query.length() > QUERY_MAX) query = query.substring(0, QUERY_MAX);
        // 다른 검색어의 커서를 거부하려고 목록 이름에 검색어 지문을 넣는다(검색어 원문은 커서에 담지 않는다)
        String listName = "me:" + tab.name().toLowerCase() + ":" + (visibility == null ? "all" : visibility)
                + (query.isEmpty() ? "" : ":q" + Integer.toHexString(query.toLowerCase().hashCode()));

        StringBuilder sql = new StringBuilder("""
                SELECT p.id, p.title, p.status, p.visibility, p.hidden_at IS NOT NULL AS hidden,
                       p.updated_at, p.published_at, p.edited_at, p.deleted_at,
                       s.view_count, s.like_count, s.comment_count, p.edit_version,
                       EXISTS (SELECT 1 FROM post_draft d WHERE d.post_id = p.id) AS has_draft
                FROM post p
                """ + PostSql.STAT_JOIN + " WHERE p.author_id = ?");
        List<Object> args = new ArrayList<>(List.of(memberId));
        String sortColumn = tab == Tab.TRASH ? "p.deleted_at" : "p.updated_at";
        switch (tab) {
            case DRAFTS -> sql.append(" AND p.status = 'DRAFT' AND p.deleted_at IS NULL");
            case PUBLISHED -> sql.append(" AND p.status = 'PUBLISHED' AND p.deleted_at IS NULL");
            case TRASH -> sql.append(" AND p.deleted_at IS NOT NULL");
        }
        if (visibility != null && tab == Tab.PUBLISHED) {
            sql.append(" AND p.visibility = ?");
            args.add(visibility);
        }
        if (!query.isEmpty()) {
            sql.append(" AND p.title ILIKE ?");
            args.add(likePattern(query));
        }
        if (cursor != null && !cursor.isBlank()) {
            long[] k = cursors.decode(cursor, listName, 2);
            sql.append(" AND (").append(sortColumn).append(", p.id) < (?, ?)");
            args.add(Timestamp.from(Times.fromEpochMicros(k[0])));
            args.add(k[1]);
        }
        sql.append(" ORDER BY ").append(sortColumn).append(" DESC, p.id DESC LIMIT ").append(pageSize + 1);

        List<Row> rows = jdbc.query(sql.toString(), MyPostsQuery::row, args.toArray());
        Set<Long> autosaving = autosavingPublished(rows);
        List<Item> items = rows.stream().map(r -> r.toItem(autosaving.contains(r.id))).toList();
        CursorCodec.Page<Item> page = CursorCodec.page(items, pageSize, it -> cursors.encode(listName,
                Times.toEpochMicros(tab == Tab.TRASH ? it.deletedAt() : it.updatedAt()), it.id()));
        Counts counts = cursor == null || cursor.isBlank() ? counts(memberId) : null;
        return new Result(page.items(), page.nextCursor(), counts);
    }

    private Counts counts(long memberId) {
        return jdbc.queryForObject("""
                SELECT count(*) FILTER (WHERE status = 'DRAFT' AND deleted_at IS NULL),
                       count(*) FILTER (WHERE status = 'PUBLISHED' AND deleted_at IS NULL),
                       count(*) FILTER (WHERE deleted_at IS NOT NULL)
                FROM post WHERE author_id = ?""",
                (rs, i) -> new Counts(rs.getLong(1), rs.getLong(2), rs.getLong(3)), memberId);
    }

    /** 작업본은 아직 없지만 DB 반영 전 자동 저장이 있는 발행 글도 "수정 중"으로 본다. */
    private Set<Long> autosavingPublished(List<Row> rows) {
        List<Long> ids = rows.stream().filter(r -> "PUBLISHED".equals(r.status) && !r.hasDraft).map(r -> r.id).toList();
        if (ids.isEmpty()) return Set.of();
        try {
            List<Object> versions = redis.executePipelined((org.springframework.data.redis.core.RedisCallback<Object>) c -> {
                for (long id : ids) c.hashCommands().hGet(("autosave:post:" + id).getBytes(), "version".getBytes());
                return null;
            });
            Map<Long, Long> editVersions = rows.stream().collect(Collectors.toMap(r -> r.id, r -> r.editVersion, (a, b) -> a));
            Set<Long> result = new java.util.HashSet<>();
            for (int i = 0; i < ids.size(); i++) {
                Object v = versions.get(i);
                if (v != null && Long.parseLong(v.toString()) > editVersions.getOrDefault(ids.get(i), 0L)) result.add(ids.get(i));
            }
            return result;
        } catch (RuntimeException e) {
            log.debug("자동 저장 상태를 읽지 못했습니다: {}", e.getMessage());
            return Set.of();
        }
    }

    /** ILIKE 패턴: %, _, \ 를 글자 그대로 찾는다(PostgreSQL 기본 ESCAPE는 \) */
    static String likePattern(String word) {
        StringBuilder sb = new StringBuilder("%");
        for (int i = 0; i < word.length(); i++) {
            char c = word.charAt(i);
            if (c == '\\' || c == '%' || c == '_') sb.append('\\');
            sb.append(c);
        }
        return sb.append('%').toString();
    }

    private static Tab parseTab(String raw) {
        if (raw == null || raw.isBlank()) return Tab.DRAFTS;
        try {
            return Tab.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("INVALID_TAB", "목록 종류가 올바르지 않아요.");
        }
    }

    private static String parseVisibility(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String v = raw.trim().toUpperCase();
        if (!v.equals("PUBLIC") && !v.equals("FRIENDS") && !v.equals("PRIVATE")) {
            throw ApiException.badRequest("INVALID_VISIBILITY", "공개 범위가 올바르지 않아요.");
        }
        return v;
    }

    private record Row(long id, String title, String status, String visibility, boolean hidden, Instant updatedAt,
                       Instant publishedAt, Instant editedAt, Instant deletedAt, long viewCount, int likeCount,
                       int commentCount, boolean hasDraft, long editVersion) {
        Item toItem(boolean autosaving) {
            Instant purgeAt = deletedAt == null ? null : deletedAt.plus(java.time.Duration.ofDays(30));
            return new Item(id, title, status, "DRAFT".equals(status) ? null : visibility,
                    "PUBLISHED".equals(status) && (hasDraft || autosaving), hidden, updatedAt, publishedAt, editedAt,
                    deletedAt, purgeAt, viewCount, likeCount, commentCount);
        }
    }

    private static Row row(ResultSet rs, int i) throws SQLException {
        return new Row(rs.getLong("id"), rs.getString("title"), rs.getString("status"), rs.getString("visibility"),
                rs.getBoolean("hidden"), instant(rs, "updated_at"), instant(rs, "published_at"), instant(rs, "edited_at"),
                instant(rs, "deleted_at"), rs.getLong("view_count"), rs.getInt("like_count"), rs.getInt("comment_count"),
                rs.getBoolean("has_draft"), rs.getLong("edit_version"));
    }

    private static Instant instant(ResultSet rs, String col) throws SQLException {
        Timestamp t = rs.getTimestamp(col);
        return t == null ? null : t.toInstant();
    }
}
