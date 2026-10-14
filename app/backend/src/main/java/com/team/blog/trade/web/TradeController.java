package com.team.blog.trade.web;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.team.blog.shared.error.ApiException;
import com.team.blog.shared.security.CurrentMember;
import com.team.blog.shared.security.MemberPrincipal;
import com.team.blog.trade.application.TradeJournal;

/**
 * 매매 일지 화면 API (#243). 로그인한 회원 본인 것만. TRADES_ENABLED가 꺼져 있으면 모두 404다.
 * 체결 기록은 매매 봇이 MCP 도구 record_trade로 보낸다(여기에는 기록을 받는 주소가 없다).
 * <ul>
 *   <li>GET /api/me/trades?days=30: 날별 요약과 누적 실현 손익</li>
 *   <li>GET /api/me/trades/{date}: 그날 체결·메모·만든 글과 쇼츠</li>
 *   <li>PUT /api/me/trades/{date}/memo: 그날 메모</li>
 *   <li>POST /api/me/trades/{date}/draft: 그날 요약 임시글</li>
 *   <li>POST /api/me/trades/{date}/shorts: 그날 쇼츠 대본 (메모가 있어야 한다)</li>
 *   <li>DELETE /api/me/trades/fills/{id}: 잘못 보낸 체결 지우기</li>
 * </ul>
 */
@RestController
public class TradeController {
    private final TradeJournal trades;

    public TradeController(TradeJournal trades) {
        this.trades = trades;
    }

    public record MemoBody(String memo) {}

    @GetMapping("/api/me/trades")
    public ResponseEntity<TradeJournal.Overview> overview(@CurrentMember MemberPrincipal me, @RequestParam(defaultValue = "30") int days) {
        LocalDate to = trades.today();
        LocalDate from = to.minusDays(Math.clamp(days, 1, TradeJournal.DAYS_MAX) - 1L);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(trades.overview(me.id(), from, to));
    }

    @GetMapping("/api/me/trades/{date}")
    public ResponseEntity<TradeJournal.Day> day(@CurrentMember MemberPrincipal me, @PathVariable String date) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(trades.day(me.id(), date(date)));
    }

    @PutMapping("/api/me/trades/{date}/memo")
    public TradeJournal.Day memo(@CurrentMember MemberPrincipal me, @PathVariable String date, @RequestBody MemoBody body) {
        return trades.saveMemo(me.id(), date(date), body.memo());
    }

    @PostMapping("/api/me/trades/{date}/draft")
    public TradeJournal.Day draft(@CurrentMember MemberPrincipal me, @PathVariable String date) {
        return trades.createDraft(me.id(), date(date));
    }

    @PostMapping("/api/me/trades/{date}/shorts")
    public TradeJournal.Day shorts(@CurrentMember MemberPrincipal me, @PathVariable String date) {
        return trades.createShorts(me.id(), date(date));
    }

    @DeleteMapping("/api/me/trades/fills/{id}")
    public ResponseEntity<Void> delete(@CurrentMember MemberPrincipal me, @PathVariable long id) {
        trades.delete(me.id(), id);
        return ResponseEntity.noContent().build();
    }

    private LocalDate date(String raw) {
        trades.requireEnabled();
        try {
            return LocalDate.parse(raw);
        } catch (DateTimeParseException e) {
            throw ApiException.badRequest("INVALID_DATE", "날짜는 2026-10-14처럼 적어 주세요.");
        }
    }
}
