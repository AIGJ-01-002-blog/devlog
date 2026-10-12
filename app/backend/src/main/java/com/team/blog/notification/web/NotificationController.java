package com.team.blog.notification.web;

import java.util.Map;
import java.util.Set;

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

import com.team.blog.notification.application.NotificationQuery;
import com.team.blog.notification.application.NotificationType;
import com.team.blog.shared.error.NotFoundException;
import com.team.blog.shared.security.CurrentMember;
import com.team.blog.shared.security.MemberPrincipal;

/**
 * 내 알림 API (015, docs/25 §5). 모두 본인 것만이고 응답은 어디에도 저장하지 않는다(FR-026).
 * 이메일 인증 전 회원도 쓸 수 있다(FR-034). 비회원은 401, 탈퇴 유예 회원은 계정 상태 안내(AccountStateFilter).
 */
@RestController
public class NotificationController {
    /** 펼침 목록 10개, 전체 페이지 20개 (FR-027). */
    private static final Set<Integer> SIZES = Set.of(10, 20);

    private final NotificationQuery notifications;

    public NotificationController(NotificationQuery notifications) {
        this.notifications = notifications;
    }

    public record SettingsRequest(Set<NotificationType> muted) {}

    @GetMapping("/api/me/notifications")
    public ResponseEntity<NotificationQuery.Page> list(@RequestParam(required = false) String cursor,
                                                       @RequestParam(defaultValue = "20") int size,
                                                       @CurrentMember MemberPrincipal me) {
        return noStore(notifications.page(me.id(), cursor, SIZES.contains(size) ? size : 20));
    }

    @GetMapping("/api/me/notifications/unread-count")
    public ResponseEntity<Map<String, Long>> unreadCount(@CurrentMember MemberPrincipal me) {
        return noStore(Map.of("count", notifications.unreadCount(me.id())));
    }

    @PostMapping("/api/me/notifications/{id}/read")
    public ResponseEntity<Void> read(@PathVariable String id, @CurrentMember MemberPrincipal me) {
        notifications.markRead(me.id(), id(id));
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/api/me/notifications/read-all")
    public ResponseEntity<Map<String, Integer>> readAll(@CurrentMember MemberPrincipal me) {
        return noStore(Map.of("updated", notifications.markAllRead(me.id())));
    }

    @DeleteMapping("/api/me/notifications/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id, @CurrentMember MemberPrincipal me) {
        notifications.delete(me.id(), id(id));
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/api/me/notification-settings")
    public ResponseEntity<NotificationQuery.Settings> settings(@CurrentMember MemberPrincipal me) {
        return noStore(notifications.settings(me.id()));
    }

    @PutMapping("/api/me/notification-settings")
    public ResponseEntity<NotificationQuery.Settings> updateSettings(@RequestBody SettingsRequest body, @CurrentMember MemberPrincipal me) {
        return noStore(notifications.updateSettings(me.id(), body.muted() == null ? Set.of() : body.muted()));
    }

    private static <T> ResponseEntity<T> noStore(T body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }

    private static long id(String raw) {
        if (raw == null || !raw.matches("[1-9]\\d{0,17}")) throw new NotFoundException();
        return Long.parseLong(raw);
    }
}
