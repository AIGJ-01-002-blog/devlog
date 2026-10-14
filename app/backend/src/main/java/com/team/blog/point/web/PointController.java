package com.team.blog.point.web;

import java.util.List;
import java.util.Map;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.team.blog.discovery.application.FeedQuery;
import com.team.blog.point.application.PointLedger;
import com.team.blog.point.application.Wallet;
import com.team.blog.shared.error.ApiException;
import com.team.blog.shared.error.NotFoundException;
import com.team.blog.shared.security.CurrentMember;
import com.team.blog.shared.security.MemberPrincipal;

/**
 * 블로그 포인트 API (#266). POINTS_ENABLED가 꺼져 있으면 모두 404다.
 * <ul>
 *   <li>GET /api/me/points: 잔액(투자 전용·상점), 출석, 적립 규칙, 오늘 받은 글·수익 포인트, 최근 내역</li>
 *   <li>GET /api/me/points/history?wallet=SHOP&amp;before=123: 내역 다음 쪽</li>
 *   <li>POST /api/me/points/attendance: 오늘 출석 (하루 한 번, 한국 시간). GET /api/me/points/today: 화면 위 출석 알림용</li>
 *   <li>GET /api/members/{handle}/badges: 누구나. 연속 출석 휘장</li>
 *   <li>GET·PUT /api/admin/points/rules: 적립 규칙 값 (관리자만, 매니저는 403)</li>
 * </ul>
 */
@RestController
public class PointController {
    private final PointLedger points;
    private final FeedQuery feed;

    public PointController(PointLedger points, FeedQuery feed) {
        this.points = points;
        this.feed = feed;
    }

    /** 화면 위 출석 알림: 오늘 출석했는지 */
    @GetMapping("/api/me/points/today")
    public ResponseEntity<PointLedger.Today> today(@CurrentMember MemberPrincipal me) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(points.todayState(me.id()));
    }

    /** 누구나: 그 블로그 주인이 받은 연속 출석 휘장. 없는 블로그·탈퇴한 회원은 404 */
    @GetMapping("/api/members/{handle}/badges")
    public List<PointLedger.Badge> badges(@PathVariable String handle) {
        points.requireEnabled();
        return points.badges(feed.ownerId(handle).orElseThrow(NotFoundException::new));
    }

    @GetMapping("/api/me/points")
    public ResponseEntity<PointLedger.Summary> summary(@CurrentMember MemberPrincipal me) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(points.summary(me.id()));
    }

    @GetMapping("/api/me/points/history")
    public ResponseEntity<PointLedger.History> history(@CurrentMember MemberPrincipal me, @RequestParam(required = false) String wallet,
                                                       @RequestParam(required = false) Long before) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(points.history(me.id(), wallet(wallet), before));
    }

    @PostMapping("/api/me/points/attendance")
    public PointLedger.AttendResult attend(@CurrentMember MemberPrincipal me) {
        return points.attend(me.id());
    }

    @GetMapping("/api/admin/points/rules")
    public ResponseEntity<List<PointLedger.RuleView>> rules(@CurrentMember MemberPrincipal me) {
        admin(me);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(points.ruleViews());
    }

    @PutMapping("/api/admin/points/rules")
    public List<PointLedger.RuleView> updateRules(@CurrentMember MemberPrincipal me, @RequestBody Map<String, Long> body) {
        admin(me);
        return points.updateRules(me.id(), body);
    }

    private void admin(MemberPrincipal me) {
        points.requireEnabled();
        if (!me.isStaff()) throw new NotFoundException();
        if (!me.isAdmin()) throw new ApiException(HttpStatus.FORBIDDEN, "ADMIN_ONLY", "포인트 규칙은 관리자만 바꿀 수 있어요.");
    }

    private Wallet wallet(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return Wallet.valueOf(raw);
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("INVALID_WALLET", "지갑은 INVEST_ONLY나 SHOP이에요.");
        }
    }
}
