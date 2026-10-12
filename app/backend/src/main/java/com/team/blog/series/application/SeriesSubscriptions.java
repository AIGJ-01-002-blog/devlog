package com.team.blog.series.application;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.team.blog.shared.error.ApiException;
import com.team.blog.shared.error.NotFoundException;

/**
 * 시리즈 구독 (072 2단계). 구독한 시리즈에 새 글이 처음 공개되면 알림(새 글)을 받는다. 알림은 공개 글에만 가므로
 * 구독 자체는 시리즈가 있으면 누구나 할 수 있다. 내 시리즈는 구독하지 않는다.
 */
@Service
public class SeriesSubscriptions {
    private final JdbcTemplate jdbc;

    public SeriesSubscriptions(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void subscribe(long memberId, long seriesId) {
        Long owner = jdbc.query("""
                SELECT s.member_id FROM series s JOIN member m ON m.id = s.member_id
                WHERE s.id = ? AND m.withdrawn_at IS NULL""", (rs, i) -> rs.getLong(1), seriesId).stream().findFirst().orElse(null);
        if (owner == null) throw new NotFoundException();
        if (owner == memberId) throw ApiException.badRequest("OWN_SERIES", "내 시리즈는 구독하지 않아도 돼요.");
        jdbc.update("INSERT INTO series_subscription (member_id, series_id) VALUES (?, ?) ON CONFLICT DO NOTHING", memberId, seriesId);
    }

    public void unsubscribe(long memberId, long seriesId) {
        jdbc.update("DELETE FROM series_subscription WHERE member_id = ? AND series_id = ?", memberId, seriesId);
    }

    public boolean subscribed(long memberId, long seriesId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM series_subscription WHERE member_id = ? AND series_id = ?)", Boolean.class, memberId, seriesId));
    }
}
