package com.team.blog.trade.application;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 매매 일지에서 원화 모의투자 매도 수익을 읽는 공개 조회 (#267). 포인트(봇 모의투자 수익 정산)가 매매 일지 표를 직접 읽지 않게
 * 이 서비스로만 가져간다. 읽기만 한다.
 */
@Service
public class PaperProfits {

    /** @param tradeId 체결 번호, profit 원 단위로 내린 실현 수익(1원 이상) */
    public record Profit(long tradeId, long profit) {}

    private final JdbcTemplate jdbc;

    public PaperProfits(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** since 이후에 남긴 이 회원의 원화(KRW) 모의투자(PAPER) 매도 중 수익이 1원 이상인 체결, 먼저 남긴 순 */
    public List<Profit> krwSellProfits(long memberId, Instant since) {
        return jdbc.query("""
                SELECT id, FLOOR(realized_pnl)::bigint AS pnl FROM trade
                WHERE member_id = ? AND mode = 'PAPER' AND side = 'SELL' AND currency = 'KRW' AND realized_pnl >= 1 AND created_at >= ?
                ORDER BY id
                """, (rs, i) -> new Profit(rs.getLong("id"), rs.getLong("pnl")), memberId, Timestamp.from(since));
    }
}
