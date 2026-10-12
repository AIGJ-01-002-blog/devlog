package com.team.blog.friend.application;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.team.blog.post.infra.PostSql;
import com.team.blog.shared.markdown.ImageUrls;
import com.team.blog.shared.time.Times;

/**
 * 친구 목록·받은 요청·보낸 요청과 최근 활동 (008 US1·US2). 목록은 본인 것만 (FR-005).
 * 최근 활동은 "친구 + 양쪽 모두 공개 설정 켬 + 기록 있음"일 때만 0~7일 구간으로 넣는다 (FR-009·FR-010).
 */
@Service
@Transactional(readOnly = true)
public class FriendQuery {
    /** 한 사람이 맺을 수 있는 친구 수에 제한은 없지만, 한 번에 보여주는 수는 넉넉히 자른다. */
    static final int MAX_ROWS = 1000;

    private final JdbcTemplate jdbc;
    private final ImageUrls imageUrls;
    private final Clock clock;

    public FriendQuery(JdbcTemplate jdbc, ImageUrls imageUrls, Clock clock) {
        this.jdbc = jdbc;
        this.imageUrls = imageUrls;
        this.clock = clock;
    }

    /** @param lastActiveDaysAgo 친구이고 볼 수 있을 때만 0~7, 그 밖에는 null */
    public record Person(String handle, String nickname, String profileImageUrl, Instant since, Integer lastActiveDaysAgo) {}

    public record Overview(List<Person> friends, List<Person> received, List<Person> sent, boolean lastActiveVisible) {}

    private record Row(String handle, String nickname, String imageKey, String status, long requestedBy, Instant createdAt,
                       Instant acceptedAt, Instant lastActiveAt, boolean lastActiveVisible) {}

    public Overview overview(long me) {
        boolean mine = viewerShares(me);
        Instant now = Times.now(clock);
        List<Person> friends = new ArrayList<>();
        List<Person> received = new ArrayList<>();
        List<Person> sent = new ArrayList<>();
        jdbc.query("SELECT m.handle, m.nickname," + PostSql.PROFILE_IMAGE_KEY + """
                     , f.status, f.requested_by, f.created_at, f.accepted_at, m.last_active_at, m.last_active_visible
                FROM friendship f
                JOIN member m ON m.id = CASE WHEN f.member_a_id = ? THEN f.member_b_id ELSE f.member_a_id END
                """ + PostSql.PROFILE_IMAGE_JOIN + """

                WHERE (f.member_a_id = ? OR f.member_b_id = ?) AND m.withdrawn_at IS NULL AND m.deleted_at IS NULL
                ORDER BY COALESCE(f.accepted_at, f.created_at) DESC, m.id DESC
                LIMIT """ + " " + MAX_ROWS, (rs, i) -> row(rs), me, me, me).forEach(r -> {
            if ("ACCEPTED".equals(r.status())) {
                Integer days = mine && r.lastActiveVisible() && r.lastActiveAt() != null ? LastActive.daysAgo(r.lastActiveAt(), now) : null;
                friends.add(person(r, r.acceptedAt(), days));
            } else if (r.requestedBy() == me) {
                sent.add(person(r, r.createdAt(), null));
            } else {
                received.add(person(r, r.createdAt(), null));
            }
        });
        return new Overview(friends, received, sent, mine);
    }

    /** 두 회원이 수락된 친구인지. 같은 사람이면 친구가 아니다. */
    public boolean areFriends(long a, long b) {
        return FriendsVisibilityRule.areFriends(jdbc, a, b);
    }

    /** 블로그 프로필에 넣을 최근 활동. 조건을 하나라도 못 채우면 비어 있다. */
    public Optional<Integer> lastActiveDaysAgo(long viewer, long target) {
        if (viewer == target) return Optional.empty();
        List<Instant> at = jdbc.query("""
                SELECT t.last_active_at FROM member t
                JOIN member v ON v.id = ?
                JOIN friendship f ON f.member_a_id = least(t.id, v.id) AND f.member_b_id = greatest(t.id, v.id) AND f.status = 'ACCEPTED'
                WHERE t.id = ? AND t.last_active_visible AND v.last_active_visible AND t.last_active_at IS NOT NULL
                """, (rs, i) -> rs.getTimestamp(1).toInstant(), viewer, target);
        return at.stream().findFirst().map(a -> LastActive.daysAgo(a, Times.now(clock)));
    }

    private boolean viewerShares(long me) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT last_active_visible FROM member WHERE id = ?", Boolean.class, me));
    }

    private Person person(Row r, Instant since, Integer days) {
        return new Person(r.handle(), r.nickname(), imageUrls.urlOf(r.imageKey()), since, days);
    }

    private static Row row(ResultSet rs) throws SQLException {
        return new Row(rs.getString("handle"), rs.getString("nickname"), rs.getString("profile_image_key"), rs.getString("status"),
                rs.getLong("requested_by"), instant(rs.getTimestamp("created_at")), instant(rs.getTimestamp("accepted_at")),
                instant(rs.getTimestamp("last_active_at")), rs.getBoolean("last_active_visible"));
    }

    private static Instant instant(Timestamp t) {
        return t == null ? null : t.toInstant();
    }
}
