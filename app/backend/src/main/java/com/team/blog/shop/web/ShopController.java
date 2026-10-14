package com.team.blog.shop.web;

import java.util.List;
import java.util.Locale;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.team.blog.point.application.PointLedger;
import com.team.blog.shared.error.ApiException;
import com.team.blog.shared.error.NotFoundException;
import com.team.blog.shared.security.CurrentMember;
import com.team.blog.shared.security.MemberPrincipal;
import com.team.blog.shop.application.ShopService;

/**
 * 크레딧 상점과 블로그 꾸미기 API (#268). 포인트가 꺼져 있으면 모두 404다.
 * <ul>
 *   <li>GET /api/shop: 아이템 목록, 내가 산 것·적용한 것, 크레딧 잔액</li>
 *   <li>POST /api/shop/items/{id}/purchase: 크레딧으로 사기 (아래 티어가 없으면 409)</li>
 *   <li>PUT /api/me/decorations/{itemId}: 산 아이템 적용 (같은 종류는 바뀐다), DELETE /api/me/decorations/{kind}: 해제</li>
 *   <li>GET /api/members/{handle}/decorations: 누구나. 그 블로그에 적용된 꾸미기</li>
 *   <li>GET·POST /api/admin/shop/items, PUT /api/admin/shop/items/{id}: 관리자만(매니저 403)</li>
 * </ul>
 */
@RestController
public class ShopController {
    private final ShopService shop;
    private final PointLedger points;

    public ShopController(ShopService shop, PointLedger points) {
        this.shop = shop;
        this.points = points;
    }

    @GetMapping("/api/shop")
    public ResponseEntity<ShopService.Shop> list(@CurrentMember MemberPrincipal me) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(shop.shop(me.id()));
    }

    @PostMapping("/api/shop/items/{id}/purchase")
    public ShopService.Shop purchase(@CurrentMember MemberPrincipal me, @PathVariable long id) {
        return shop.purchase(me.id(), id);
    }

    @PutMapping("/api/me/decorations/{itemId}")
    public ShopService.Shop apply(@CurrentMember MemberPrincipal me, @PathVariable long itemId) {
        return shop.apply(me.id(), itemId);
    }

    @DeleteMapping("/api/me/decorations/{kind}")
    public ShopService.Shop remove(@CurrentMember MemberPrincipal me, @PathVariable String kind) {
        try {
            return shop.remove(me.id(), ShopService.Kind.valueOf(kind.toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("INVALID_KIND", "종류는 THEME·FRAME·BANNER·EFFECT 가운데 하나예요.");
        }
    }

    @GetMapping("/api/members/{handle}/decorations")
    public ResponseEntity<ShopService.Decorations> decorations(@PathVariable String handle) {
        return ResponseEntity.ok().cacheControl(CacheControl.noCache()).body(shop.decorations(handle));
    }

    @GetMapping("/api/admin/shop/items")
    public ResponseEntity<List<ShopService.Item>> adminItems(@CurrentMember MemberPrincipal me) {
        admin(me);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(shop.adminItems());
    }

    @PostMapping("/api/admin/shop/items")
    public ResponseEntity<ShopService.Item> create(@CurrentMember MemberPrincipal me, @RequestBody ShopService.ItemInput body) {
        admin(me);
        return ResponseEntity.status(HttpStatus.CREATED).body(shop.create(body));
    }

    @PutMapping("/api/admin/shop/items/{id}")
    public ShopService.Item update(@CurrentMember MemberPrincipal me, @PathVariable long id, @RequestBody ShopService.ItemInput body) {
        admin(me);
        return shop.update(id, body);
    }

    private void admin(MemberPrincipal me) {
        points.requireEnabled();
        if (!me.isStaff()) throw new NotFoundException();
        if (!me.isAdmin()) throw new ApiException(HttpStatus.FORBIDDEN, "ADMIN_ONLY", "상점 아이템은 관리자만 바꿀 수 있어요.");
    }
}
