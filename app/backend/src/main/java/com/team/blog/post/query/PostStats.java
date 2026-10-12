package com.team.blog.post.query;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.team.blog.shared.stats.Counts;

/**
 * 관리자 페이지(062)의 글 집계와 글 목록. 관리자·매니저도 남의 비공개 글·임시글의 제목과 본문은 보지 않는다:
 * 목록은 발행한 글만 싣고, 비공개 글은 제목 대신 빈 값을 준다(docs/42 관리자 권한 원칙).
 */
@Service
public class PostStats {
    public static final int PAGE_SIZE = 20;

    /** 열 이름 */
    private static final String HIDDEN = "hidden";
    private static final String AUTHOR_ID = "author_id";

    private final JdbcTemplate jdbc;

    public PostStats(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** @param hidden 신고·관리자가 숨긴 글 (휴지통 제외) */
    public record Summary(long published, long publicPosts, long privatePosts, long drafts, long trash, long hidden) {}

    /** @param title 공개 글만. 비공개면 null */
    public record Row(long id, long authorId, String authorHandle, String title, String visibility, boolean hidden, String hiddenReason,
                      Instant publishedAt) {}

    public record Page(List<Row> items, int page, long total, int pageSize) {}

    public record AuthorCounts(long published, long publicPosts, long drafts, long hidden) {}

    public Summary summary() {
        return jdbc.queryForObject("""
                SELECT count(*) FILTER (WHERE deleted_at IS NULL AND status = 'PUBLISHED') AS published,
                       count(*) FILTER (WHERE deleted_at IS NULL AND status = 'PUBLISHED' AND visibility = 'PUBLIC' AND hidden_at IS NULL) AS public_posts,
                       count(*) FILTER (WHERE deleted_at IS NULL AND status = 'PUBLISHED' AND visibility <> 'PUBLIC') AS private_posts,
                       count(*) FILTER (WHERE deleted_at IS NULL AND status = 'DRAFT') AS drafts,
                       count(*) FILTER (WHERE deleted_at IS NOT NULL) AS trash,
                       count(*) FILTER (WHERE deleted_at IS NULL AND hidden_at IS NOT NULL) AS hidden
                FROM post
                """, (rs, i) -> new Summary(rs.getLong("published"), rs.getLong("public_posts"), rs.getLong("private_posts"),
                rs.getLong("drafts"), rs.getLong("trash"), rs.getLong(HIDDEN)));
    }

    /** 처음 발행한 날 기준 (다시 발행은 세지 않는다) */
    public Map<LocalDate, Long> publishedByDay(Instant from) {
        return Counts.byDay(jdbc, """
                SELECT (published_at AT TIME ZONE 'Asia/Seoul')::date AS d, count(*) AS n
                FROM post WHERE published_at >= ? AND deleted_at IS NULL GROUP BY d
                """, from);
    }

    /** 기간 안에 글을 가장 많이 발행한 회원 번호 → 편 수 (많은 순) */
    public List<Map.Entry<Long, Long>> topAuthors(Instant from, int limit) {
        return jdbc.query("""
                SELECT author_id, count(*) AS n FROM post
                WHERE published_at >= ? AND deleted_at IS NULL AND status = 'PUBLISHED'
                GROUP BY author_id ORDER BY n DESC, author_id LIMIT ?
                """, (rs, i) -> Map.entry(rs.getLong(AUTHOR_ID), rs.getLong("n")), Timestamp.from(from), limit);
    }

    public Map<Long, AuthorCounts> countsByAuthors(List<Long> authorIds) {
        Map<Long, AuthorCounts> out = new HashMap<>();
        if (authorIds.isEmpty()) return out;
        jdbc.query("""
                SELECT author_id,
                       count(*) FILTER (WHERE status = 'PUBLISHED') AS published,
                       count(*) FILTER (WHERE status = 'PUBLISHED' AND visibility = 'PUBLIC' AND hidden_at IS NULL) AS public_posts,
                       count(*) FILTER (WHERE status = 'DRAFT') AS drafts,
                       count(*) FILTER (WHERE hidden_at IS NOT NULL) AS hidden
                FROM post WHERE author_id = ANY (?) AND deleted_at IS NULL GROUP BY author_id
                """, rs -> {
            out.put(rs.getLong(AUTHOR_ID), new AuthorCounts(rs.getLong("published"), rs.getLong("public_posts"), rs.getLong("drafts"),
                    rs.getLong(HIDDEN)));
        }, (Object) authorIds.toArray(Long[]::new));
        return out;
    }

    /** 회원이 발행한 글 번호 (받은 조회수·좋아요를 셀 때) */
    public List<Long> publishedIdsOf(long authorId) {
        return jdbc.queryForList("SELECT id FROM post WHERE author_id = ? AND status = 'PUBLISHED' AND deleted_at IS NULL", Long.class, authorId);
    }

    /** 회원의 달별 첫 발행 편 수 (yyyy-MM → 편 수) */
    public Map<String, Long> monthlyOf(long authorId, Instant from) {
        Map<String, Long> out = new HashMap<>();
        jdbc.query("""
                SELECT to_char(published_at AT TIME ZONE 'Asia/Seoul', 'YYYY-MM') AS m, count(*) AS n
                FROM post WHERE author_id = ? AND published_at >= ? AND deleted_at IS NULL GROUP BY m
                """, rs -> {
            out.put(rs.getString("m"), rs.getLong("n"));
        }, authorId, Timestamp.from(from));
        return out;
    }

    /**
     * 발행한 글 목록 (새로 발행한 순).
     * @param q      제목 일부 (공개 글만 제목으로 찾는다) 또는 작성자 주소
     * @param filter all·public·private·hidden
     */
    public Page page(String q, String filter, Long authorId, int page) {
        List<Object> args = new ArrayList<>();
        StringBuilder where = new StringBuilder("p.status = 'PUBLISHED' AND p.deleted_at IS NULL");
        String f = filter == null ? "" : filter.strip().toLowerCase(Locale.ROOT);
        switch (f) {
            case "public" -> where.append(" AND p.visibility = 'PUBLIC' AND p.hidden_at IS NULL");
            case "private" -> where.append(" AND p.visibility <> 'PUBLIC'");
            case "hidden" -> where.append(" AND p.hidden_at IS NOT NULL");
            default -> {
                // all이나 모르는 값은 거르지 않는다
            }
        }
        if (authorId != null) {
            where.append(" AND p.author_id = ?");
            args.add(authorId);
        }
        String query = q == null ? "" : q.strip().toLowerCase(Locale.ROOT);
        if (!query.isEmpty()) {
            String like = "%" + query.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
            where.append(" AND ((p.visibility = 'PUBLIC' AND lower(p.title) LIKE ?) OR m.handle LIKE ?)");
            args.add(like);
            args.add(like);
        }
        long total = Counts.one(jdbc, "SELECT count(*) FROM post p JOIN member m ON m.id = p.author_id WHERE " + where, args.toArray());
        int p = Math.max(1, page);
        args.add(PAGE_SIZE);
        args.add((long) (p - 1) * PAGE_SIZE);
        List<Row> rows = jdbc.query("""
                SELECT p.id, p.author_id, m.handle, p.title, p.visibility, p.hidden_at IS NOT NULL AS hidden, p.hidden_reason, p.published_at
                FROM post p JOIN member m ON m.id = p.author_id
                WHERE""" + " " + where + " ORDER BY p.published_at DESC, p.id DESC LIMIT ? OFFSET ?", (rs, i) -> row(rs), args.toArray());
        return new Page(rows, p, total, PAGE_SIZE);
    }

    /** 번호 → 글 (대시보드의 많이 본 글). 휴지통·임시글은 빠진다 */
    public Map<Long, Row> byIds(List<Long> ids) {
        Map<Long, Row> out = new HashMap<>();
        if (ids.isEmpty()) return out;
        jdbc.query("""
                SELECT p.id, p.author_id, m.handle, p.title, p.visibility, p.hidden_at IS NOT NULL AS hidden, p.hidden_reason, p.published_at
                FROM post p JOIN member m ON m.id = p.author_id
                WHERE p.id = ANY (?) AND p.status = 'PUBLISHED' AND p.deleted_at IS NULL
                """, rs -> {
            out.put(rs.getLong("id"), row(rs));
        }, (Object) ids.toArray(Long[]::new));
        return out;
    }

    private static Row row(ResultSet rs) throws SQLException {
        boolean open = "PUBLIC".equals(rs.getString("visibility"));
        return new Row(rs.getLong("id"), rs.getLong(AUTHOR_ID), rs.getString("handle"), open ? rs.getString("title") : null,
                rs.getString("visibility"), rs.getBoolean(HIDDEN), rs.getString("hidden_reason"), rs.getTimestamp("published_at").toInstant());
    }
}
