package com.team.blog.friend.application;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.team.blog.friend.application.FriendEvents.FriendAccepted;
import com.team.blog.friend.application.FriendEvents.FriendRequested;
import com.team.blog.shared.error.ApiException;
import com.team.blog.shared.error.NotFoundException;
import com.team.blog.shared.jdbc.Columns;
import com.team.blog.shared.time.Times;

/**
 * 친구 맺기 (008 US1, docs/06 §6-2). 두 회원 사이 관계는 (작은 번호, 큰 번호) 한 행뿐이라 동시에 서로 요청해도 하나만 생긴다.
 * 모든 동작은 로그인한 본인 기준이고, 상대는 블로그 주소로만 고른다. 거절·취소·끊기는 행을 지울 뿐 상대에게 알리지 않는다.
 */
@Service
public class FriendService {
    private static final String ACCEPTED = "ACCEPTED";

    private final JdbcTemplate jdbc;
    private final ApplicationEventPublisher events;
    private final TransactionTemplate tx;
    private final Clock clock;

    public FriendService(JdbcTemplate jdbc, ApplicationEventPublisher events, TransactionTemplate tx, Clock clock) {
        this.jdbc = jdbc;
        this.events = events;
        this.tx = tx;
        this.clock = clock;
    }

    /** 보는 사람 기준 관계. */
    public enum Relation { NONE, SENT, RECEIVED, FRIENDS }

    private record Row(long requestedBy, String status) {}

    /** [친구 요청]. 상대가 먼저 보낸 요청이 있으면 바로 친구가 된다 (FR-002). 이미 요청 중·친구면 그대로 (FR-003). */
    public Relation request(long me, String handle) {
        long target = activeMember(handle).orElseThrow(NotFoundException::new);
        if (target == me) throw ApiException.badRequest("FRIEND_SELF", "자기 자신에게는 친구 요청을 보낼 수 없어요.");
        long a = Math.min(me, target);
        long b = Math.max(me, target);
        return tx.execute(s -> {
            Instant now = Times.now(clock);
            // 맞요청이 동시에 오면 늦은 쪽은 앞 트랜잭션이 끝날 때까지 기다렸다가 충돌로 끝나고, 아래에서 상대 요청을 수락한다
            int inserted = jdbc.update("""
                    INSERT INTO friendship (member_a_id, member_b_id, requested_by, status, created_at)
                    VALUES (?, ?, ?, 'PENDING', ?) ON CONFLICT DO NOTHING
                    """, a, b, me, Timestamp.from(now));
            if (inserted == 1) {
                events.publishEvent(new FriendRequested(me, target, now));
                return Relation.SENT;
            }
            Row row = lock(a, b).orElse(null);
            if (row == null) return Relation.NONE; // 그 사이 상대가 지웠다. 다시 누르면 새로 요청된다
            if (ACCEPTED.equals(row.status())) return Relation.FRIENDS;
            if (row.requestedBy() == me) return Relation.SENT;
            accept(a, b, now);
            events.publishEvent(new FriendAccepted(target, me, now));
            return Relation.FRIENDS;
        });
    }

    /** [수락]: 받은 요청만, 받은 사람만 (FR-006). 이미 친구면 그대로. */
    public Relation accept(long me, String handle) {
        long other = activeMember(handle).orElseThrow(NotFoundException::new);
        long a = Math.min(me, other);
        long b = Math.max(me, other);
        return tx.execute(s -> {
            Row row = lock(a, b).orElseThrow(NotFoundException::new);
            if (ACCEPTED.equals(row.status())) return Relation.FRIENDS;
            if (row.requestedBy() != other) throw new NotFoundException(); // 내가 보낸 요청을 내가 수락할 수는 없다
            Instant now = Times.now(clock);
            accept(a, b, now);
            events.publishEvent(new FriendAccepted(other, me, now));
            return Relation.FRIENDS;
        });
    }

    /** [거절]·[요청 취소]·[친구 끊기]: 관계를 지운다. 없으면 그대로 (FR-004). 상대에게 알리지 않는다. */
    public void remove(long me, String handle) {
        Optional<Long> other = anyMember(handle);
        if (other.isEmpty() || other.get() == me) return;
        jdbc.update("DELETE FROM friendship WHERE member_a_id = ? AND member_b_id = ?",
                Math.min(me, other.get()), Math.max(me, other.get()));
    }

    public Relation relation(long viewer, long other) {
        if (viewer == other) return Relation.NONE;
        List<Row> rows = jdbc.query("SELECT requested_by, status FROM friendship WHERE member_a_id = ? AND member_b_id = ?",
                (rs, i) -> new Row(rs.getLong(1), rs.getString(2)), Math.min(viewer, other), Math.max(viewer, other));
        if (rows.isEmpty()) return Relation.NONE;
        Row r = rows.getFirst();
        if (ACCEPTED.equals(r.status())) return Relation.FRIENDS;
        return r.requestedBy() == viewer ? Relation.SENT : Relation.RECEIVED;
    }

    private void accept(long a, long b, Instant now) {
        jdbc.update("UPDATE friendship SET status = 'ACCEPTED', accepted_at = ? WHERE member_a_id = ? AND member_b_id = ?",
                Timestamp.from(now), a, b);
    }

    private Optional<Row> lock(long a, long b) {
        return jdbc.query("SELECT requested_by, status FROM friendship WHERE member_a_id = ? AND member_b_id = ? FOR UPDATE",
                (rs, i) -> new Row(rs.getLong(1), rs.getString(2)), a, b).stream().findFirst();
    }

    /** 탈퇴 유예·삭제된 회원은 없는 블로그와 같아 대상이 될 수 없다 (FR-006). */
    private Optional<Long> activeMember(String handle) {
        if (handle == null) return Optional.empty();
        return Columns.firstLong(jdbc, "SELECT id FROM member WHERE handle = ? AND withdrawn_at IS NULL AND deleted_at IS NULL",
                handle.toLowerCase(java.util.Locale.ROOT));
    }

    /** 끊기·거절은 상대가 탈퇴 유예 중이어도 할 수 있다. */
    private Optional<Long> anyMember(String handle) {
        if (handle == null) return Optional.empty();
        return Columns.firstLong(jdbc, "SELECT id FROM member WHERE handle = ?", handle.toLowerCase(java.util.Locale.ROOT));
    }
}
