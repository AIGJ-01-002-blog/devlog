package com.team.blog.series.application;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.team.blog.shared.config.BlogProperties;
import com.team.blog.shared.error.ApiException;
import com.team.blog.shared.error.FieldErrorItem;
import com.team.blog.shared.error.NotFoundException;
import com.team.blog.shared.jdbc.Columns;

/**
 * 시리즈 만들기·이름 바꾸기·지우기와 글 넣기·빼기·순서 (024 US1·US3). 모두 본인 것만, 남의 것이면 404 (FR-007).
 * 잠금 순서는 글 → 시리즈(번호 순)로 같아 서로 기다리다 멈추지 않는다.
 */
@Service
public class SeriesService {
    private static final String TOUCH_SERIES = "UPDATE series SET updated_at = now() WHERE id = ?";
    /** 보이지 않는 글은 남길 글 뒤로 원래 순서대로 */
    private static final String PLACE_HIDDEN_AFTER = """
            UPDATE series_post sp SET position = ? + r.rn - 1
            FROM (SELECT post_id, row_number() OVER (ORDER BY position) AS rn FROM series_post
                  WHERE series_id = ? AND NOT (post_id = ANY (?))) r
            WHERE sp.post_id = r.post_id
            """;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final int maxSeries;
    private final int maxPosts;

    public SeriesService(JdbcTemplate jdbc, TransactionTemplate tx, BlogProperties props) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.maxSeries = props.series().maxPerMember();
        this.maxPosts = props.series().maxPosts();
    }

    public record Mine(long id, String name, String slug, int postCount) {}

    public List<Mine> mine(long memberId) {
        return jdbc.query("""
                SELECT s.id, s.name, s.slug,
                       (SELECT count(*) FROM series_post sp JOIN post p ON p.id = sp.post_id
                        WHERE sp.series_id = s.id AND p.deleted_at IS NULL) AS post_count
                FROM series s WHERE s.member_id = ? ORDER BY s.updated_at DESC, s.id DESC
                """, (rs, i) -> new Mine(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getInt(4)), memberId);
    }

    public Mine create(long memberId, String rawName) {
        String name = validName(rawName);
        return tx.execute(st -> {
            // 회원 행을 잠가 동시에 만들어도 개수 제한을 넘지 않게 한다
            Columns.longs(jdbc, "SELECT id FROM member WHERE id = ? FOR UPDATE", memberId);
            Long count = jdbc.queryForObject("SELECT count(*) FROM series WHERE member_id = ?", Long.class, memberId);
            if (count != null && count >= maxSeries) {
                throw ApiException.badRequest("TOO_MANY_SERIES", "시리즈는 " + maxSeries + "개까지 만들 수 있어요.");
            }
            long id = insertOrConflict(() -> jdbc.queryForObject(
                    "INSERT INTO series (member_id, name, slug) VALUES (?, ?, ?) RETURNING id", Long.class,
                    memberId, name, SeriesNames.slug(name)));
            return new Mine(id, name, SeriesNames.slug(name), 0);
        });
    }

    public Mine rename(long memberId, long seriesId, String rawName) {
        String name = validName(rawName);
        return tx.execute(st -> {
            lockSeries(memberId, seriesId);
            insertOrConflict(() -> jdbc.update("UPDATE series SET name = ?, slug = ?, updated_at = now() WHERE id = ?",
                    name, SeriesNames.slug(name), seriesId));
            return mine(memberId).stream().filter(m -> m.id() == seriesId).findFirst().orElseThrow(NotFoundException::new);
        });
    }

    /** 시리즈만 지운다. 글은 남는다 (US3-3). */
    public void delete(long memberId, long seriesId) {
        if (jdbc.update("DELETE FROM series WHERE id = ? AND member_id = ?", seriesId, memberId) == 0) throw new NotFoundException();
    }

    /**
     * 글을 시리즈에 넣거나(맨 뒤) 뺀다 (US1). seriesId가 null이면 뺀다. 이미 그 시리즈에 있으면 그대로 둔다.
     * 임시글도 넣을 수 있다(독자에게는 발행된 뒤에 보인다).
     */
    public void assign(long memberId, long postId, Long seriesId) {
        tx.executeWithoutResult(st -> {
            List<Long> post = Columns.longs(jdbc, "SELECT id FROM post WHERE id = ? AND author_id = ? AND deleted_at IS NULL FOR UPDATE",
                    postId, memberId);
            if (post.isEmpty()) throw new NotFoundException();
            List<Long> current = Columns.longs(jdbc, "SELECT series_id FROM series_post WHERE post_id = ?", postId);
            if (seriesId != null && current.contains(seriesId)) return;
            // 원래 시리즈와 옮길 시리즈를 번호 순으로 함께 잠근다. 서로 반대로 옮기는 두 요청이 엇갈려 기다리지 않게
            List<Long> touched = new java.util.ArrayList<>(current);
            if (seriesId != null) touched.add(seriesId);
            List<Long> locked = Columns.longs(jdbc, "SELECT id FROM series WHERE id = ANY (?) AND member_id = ? ORDER BY id FOR UPDATE",
                    touched.toArray(Long[]::new), memberId);
            if (seriesId != null && !locked.contains(seriesId)) throw new NotFoundException();
            if (!current.isEmpty()) {
                jdbc.update("DELETE FROM series_post WHERE post_id = ?", postId);
                jdbc.update(TOUCH_SERIES, current.getFirst());
            }
            if (seriesId == null) return;
            Long size = jdbc.queryForObject("SELECT count(*) FROM series_post WHERE series_id = ?", Long.class, seriesId);
            if (size != null && size >= maxPosts) {
                throw ApiException.badRequest("SERIES_FULL", "시리즈 하나에는 글을 " + maxPosts + "개까지 넣을 수 있어요.");
            }
            jdbc.update("""
                    INSERT INTO series_post (post_id, series_id, position)
                    SELECT ?, ?, COALESCE(max(position) + 1, 0) FROM series_post WHERE series_id = ?
                    """, postId, seriesId, seriesId);
            jdbc.update(TOUCH_SERIES, seriesId);
        });
    }

    /**
     * 순서 바꾸기·빼기 (US3-1). postIds는 지금 든 글 가운데 남길 글을 새 순서대로 담는다. 목록에 없는 글은 빠진다.
     * 시리즈 페이지에 보이지 않는 글(휴지통 글·임시글)은 목록에 없어도 빼지 않고 맨 뒤에 남긴다(복구·발행하면 보이게, FR-005).
     */
    public void reorder(long memberId, long seriesId, List<Long> postIds) {
        if (postIds == null || postIds.stream().anyMatch(Objects::isNull) || new HashSet<>(postIds).size() != postIds.size()) {
            throw ApiException.validation(List.of(new FieldErrorItem("postIds", "INVALID_POSTS", "글 목록을 확인해 주세요.")));
        }
        tx.executeWithoutResult(st -> {
            lockSeries(memberId, seriesId);
            List<Long> live = Columns.longs(jdbc, """
                    SELECT sp.post_id FROM series_post sp JOIN post p ON p.id = sp.post_id
                    WHERE sp.series_id = ? AND """ + " " + SeriesQuery.OWNER_CONDITION, seriesId);
            if (!new HashSet<>(live).containsAll(postIds)) {
                throw ApiException.validation(List.of(new FieldErrorItem("postIds", "INVALID_POSTS", "시리즈에 없는 글이 있어요.")));
            }
            Long[] keep = postIds.toArray(Long[]::new);
            jdbc.update("DELETE FROM series_post WHERE series_id = ? AND post_id = ANY (?) AND NOT (post_id = ANY (?))",
                    seriesId, live.toArray(Long[]::new), keep);
            // 남길 글은 0부터, 보이지 않는 글은 그 뒤로 원래 순서대로 (위치 고유 제약은 커밋 때 확인)
            jdbc.update("""
                    UPDATE series_post sp SET position = k.ord - 1
                    FROM unnest(?::bigint[]) WITH ORDINALITY AS k(id, ord)
                    WHERE sp.series_id = ? AND sp.post_id = k.id
                    """, keep, seriesId);
            jdbc.update(PLACE_HIDDEN_AFTER, keep.length, seriesId, keep);
            jdbc.update(TOUCH_SERIES, seriesId);
        });
    }

    /**
     * 글을 시리즈의 position번째(1부터)에 넣는다 (073, MCP add_to_series). 넣기는 assign, 자리 옮기기는 reorder를 그대로 쓴다.
     * 순서는 독자에게 보이는 발행 글끼리 매긴다. 임시글은 assign대로 맨 뒤에 들어가고 발행한 뒤 보인다.
     * @return 글이 놓인 번호(1부터). 임시글이라 순서를 매기지 않았으면 null
     */
    public Integer placeAt(long memberId, long postId, long seriesId, Integer position) {
        assign(memberId, postId, seriesId);
        List<Long> live = Columns.longs(jdbc, """
                SELECT sp.post_id FROM series_post sp JOIN post p ON p.id = sp.post_id
                WHERE sp.series_id = ? AND\s""" + SeriesQuery.OWNER_CONDITION + " ORDER BY sp.position", seriesId);
        int at = live.indexOf(postId);
        if (at < 0) return null;
        if (position == null) return at + 1;
        List<Long> order = new java.util.ArrayList<>(live);
        order.remove(postId);
        at = Math.clamp(position - 1L, 0, order.size());
        order.add(at, postId);
        reorder(memberId, seriesId, order);
        return at + 1;
    }

    private void lockSeries(long memberId, long seriesId) {
        if (Columns.longs(jdbc, "SELECT id FROM series WHERE id = ? AND member_id = ? FOR UPDATE", seriesId, memberId).isEmpty()) {
            throw new NotFoundException();
        }
    }

    private static String validName(String raw) {
        String name = SeriesNames.clean(raw);
        if (name.isEmpty() || name.codePointCount(0, name.length()) > SeriesNames.MAX_NAME || SeriesNames.slug(name).isEmpty()) {
            throw ApiException.validation(List.of(new FieldErrorItem("name", "INVALID_SERIES_NAME",
                    "시리즈 이름은 글자나 숫자를 넣어 " + SeriesNames.MAX_NAME + "자 안으로 써 주세요.")));
        }
        return name;
    }

    private static long insertOrConflict(java.util.function.LongSupplier op) {
        try {
            return op.getAsLong();
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("SERIES_EXISTS", "같은 이름(주소)의 시리즈가 이미 있어요.");
        }
    }
}
