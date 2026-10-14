package com.team.blog.shop.application;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.team.blog.discovery.application.FeedQuery;
import com.team.blog.point.application.PointLedger;
import com.team.blog.point.application.PointReason;
import com.team.blog.point.application.Wallet;
import com.team.blog.shared.error.ApiException;
import com.team.blog.shared.error.FieldErrorItem;
import com.team.blog.shared.error.NotFoundException;
import com.team.blog.shared.markdown.ImageUrls;
import com.team.blog.shared.time.Times;

/**
 * 포인트 상점과 블로그 꾸미기 (#268, 목표 #253).
 * <ul>
 *   <li>아이템은 상점 포인트(SHOP 지갑)로만 산다. 가입 첫 지급(INVEST_ONLY)으로는 살 수 없다.</li>
 *   <li>산 아이템은 보관함에 남고 종류(THEME·FRAME·BANNER)마다 하나씩 내 블로그에 적용한다. 다른 사람도 내 블로그에서 그대로 본다.</li>
 *   <li>꾸미기 값은 화면이 style에 넣으므로 #RRGGBB, 정해 둔 모양 이름, 이 서버 사진 저장소의 키만 받는다(임의 CSS·주소를 받지 않는다).</li>
 * </ul>
 * 포인트가 꺼져 있으면(POINTS_ENABLED=false) 상점·꾸미기도 없는 주소(404)다.
 */
@Service
public class ShopService {
    public enum Kind { THEME, FRAME, BANNER }

    public static final int NAME_MAX = 40;
    public static final int DESCRIPTION_MAX = 200;
    public static final long PRICE_MAX = 10_000_000;
    private static final Pattern HEX = Pattern.compile("#[0-9a-fA-F]{6}");
    /** 화면(lib/shop.ts)과 같은 이름. 늘리면 둘 다 고친다 */
    public static final Set<String> FRAME_PRESETS = Set.of("gold", "rainbow", "aurora");
    public static final Set<String> BANNER_PRESETS = Set.of("sunset", "ocean", "night", "forest");

    /** @param imageUrl 사진 표지면 주소, owned 내가 샀는지, applied 내 블로그에 적용했는지 */
    public record Item(long id, String kind, String name, String description, long price, String value, String imageUrl, boolean active,
                       int sort, boolean owned, boolean applied) {}

    /** @param shopBalance 상점에서 쓸 수 있는 포인트, investBalance 상점에서 못 쓰는 투자 전용 포인트(안내용) */
    public record Shop(long shopBalance, long investBalance, List<Item> items) {}

    /** 블로그에 보이는 꾸미기 하나. value나 imageUrl 중 하나가 있다 */
    public record Look(long itemId, String name, String value, String imageUrl) {}

    public record Decorations(Look theme, Look frame, Look banner) {
        static final Decorations NONE = new Decorations(null, null, null);
    }

    /** 관리자가 아이템을 만들거나 고칠 때. 고칠 때 비운 칸(null)은 그대로 둔다 */
    public record ItemInput(String kind, String name, String description, Long price, String value, String imageUrl, Boolean active, Integer sort) {}

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final PointLedger points;
    private final ImageUrls imageUrls;
    private final FeedQuery feed;
    private final Clock clock;

    public ShopService(JdbcTemplate jdbc, PlatformTransactionManager txm, PointLedger points, ImageUrls imageUrls, FeedQuery feed, Clock clock) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(txm);
        this.points = points;
        this.imageUrls = imageUrls;
        this.feed = feed;
        this.clock = clock;
    }

    // ---- 상점 ----

    /** 상점에 올라간 아이템과 내가 산 아이템(내려간 것 포함) */
    public Shop shop(long memberId) {
        points.requireEnabled();
        points.ensureWelcome(memberId);
        List<Item> items = jdbc.query("""
                SELECT i.*, (o.item_id IS NOT NULL) AS owned, (d.item_id IS NOT NULL) AS applied
                FROM shop_item i
                LEFT JOIN member_item o ON o.item_id = i.id AND o.member_id = ?
                LEFT JOIN member_decoration d ON d.item_id = i.id AND d.member_id = ?
                WHERE i.active OR o.item_id IS NOT NULL
                ORDER BY CASE i.kind WHEN 'THEME' THEN 0 WHEN 'FRAME' THEN 1 ELSE 2 END, i.sort, i.id
                """, (rs, n) -> item(rs, rs.getBoolean("owned"), rs.getBoolean("applied")), memberId, memberId);
        PointLedger.Balance b = points.balance(memberId);
        return new Shop(b.shop(), b.invest(), items);
    }

    /** 상점 포인트로 산다. 이미 샀으면 409. 포인트가 모자라면 409 POINTS_SHORT */
    public Shop purchase(long memberId, long itemId) {
        points.requireEnabled();
        points.ensureWelcome(memberId);
        tx.executeWithoutResult(st -> {
            Map<String, Object> row = jdbc.query("SELECT name, price, active FROM shop_item WHERE id = ? FOR SHARE",
                    rs -> rs.next() ? Map.<String, Object>of("name", rs.getString("name"), "price", rs.getLong("price"), "active", rs.getBoolean("active")) : null,
                    itemId);
            if (row == null || !(Boolean) row.get("active")) throw new NotFoundException();
            Boolean owned = jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM member_item WHERE member_id = ? AND item_id = ?)", Boolean.class, memberId, itemId);
            if (Boolean.TRUE.equals(owned)) throw ApiException.conflict("ALREADY_OWNED", "이미 산 아이템이에요. 보관함에서 적용해 주세요.");
            long price = (Long) row.get("price");
            // 같은 트랜잭션 안에서 포인트를 빼고 보관함에 넣는다. 하나라도 실패하면 둘 다 없던 일이 된다
            // spend가 회원별 잠금을 잡으므로, 동시에 누른 두 번째 요청은 여기서 같은 요청 키를 보고 false를 받는다
            if (!points.spend(memberId, Wallet.SHOP, price, PointReason.SHOP, "shop:item:" + itemId, itemId, (String) row.get("name") + " 구매")) {
                throw ApiException.conflict("ALREADY_OWNED", "이미 산 아이템이에요. 보관함에서 적용해 주세요.");
            }
            jdbc.update("INSERT INTO member_item (member_id, item_id, price, purchased_at) VALUES (?, ?, ?, ?)", memberId, itemId, price,
                    Timestamp.from(Times.now(clock)));
        });
        return shop(memberId);
    }

    /** 산 아이템을 내 블로그에 적용한다. 같은 종류의 이전 아이템은 해제된다 */
    public Shop apply(long memberId, long itemId) {
        points.requireEnabled();
        String kind = jdbc.query("""
                SELECT i.kind FROM member_item o JOIN shop_item i ON i.id = o.item_id WHERE o.member_id = ? AND o.item_id = ?
                """, rs -> rs.next() ? rs.getString(1) : null, memberId, itemId);
        if (kind == null) throw ApiException.conflict("NOT_OWNED", "산 아이템만 적용할 수 있어요.");
        jdbc.update("""
                INSERT INTO member_decoration (member_id, kind, item_id, updated_at) VALUES (?, ?, ?, ?)
                ON CONFLICT (member_id, kind) DO UPDATE SET item_id = EXCLUDED.item_id, updated_at = EXCLUDED.updated_at
                """, memberId, kind, itemId, Timestamp.from(Times.now(clock)));
        return shop(memberId);
    }

    /** 종류 하나의 꾸미기를 해제한다 (기본 모습으로) */
    public Shop remove(long memberId, Kind kind) {
        points.requireEnabled();
        jdbc.update("DELETE FROM member_decoration WHERE member_id = ? AND kind = ?", memberId, kind.name());
        return shop(memberId);
    }

    /** 블로그에 보이는 꾸미기. 없는 블로그·탈퇴한 회원은 404 */
    public Decorations decorations(String handle) {
        points.requireEnabled();
        long memberId = feed.ownerId(handle).orElseThrow(NotFoundException::new);
        return decorationsOf(memberId);
    }

    public Decorations decorationsOf(long memberId) {
        Look[] looks = new Look[3];
        jdbc.query("""
                SELECT d.kind, i.id, i.name, i.value, i.image_key FROM member_decoration d JOIN shop_item i ON i.id = d.item_id
                WHERE d.member_id = ?
                """, (ResultSet rs) -> {
            Look look = new Look(rs.getLong("id"), rs.getString("name"), rs.getString("value"), imageUrls.urlOf(rs.getString("image_key")));
            looks[Kind.valueOf(rs.getString("kind")).ordinal()] = look;
        }, memberId);
        return looks[0] == null && looks[1] == null && looks[2] == null ? Decorations.NONE : new Decorations(looks[0], looks[1], looks[2]);
    }

    // ---- 관리자 ----

    public List<Item> adminItems() {
        points.requireEnabled();
        return jdbc.query("""
                SELECT i.*, false AS owned, false AS applied FROM shop_item i
                ORDER BY CASE i.kind WHEN 'THEME' THEN 0 WHEN 'FRAME' THEN 1 ELSE 2 END, i.sort, i.id
                """, (rs, n) -> item(rs, false, false));
    }

    public Item create(ItemInput in) {
        points.requireEnabled();
        Valid v = validate(in, null);
        Long id = jdbc.queryForObject("""
                INSERT INTO shop_item (kind, name, description, price, value, image_key, active, sort, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id
                """, Long.class, v.kind().name(), v.name(), v.description(), v.price(), v.value(), v.imageKey(), v.active(), v.sort(),
                Timestamp.from(Times.now(clock)), Timestamp.from(Times.now(clock)));
        return find(id).orElseThrow();
    }

    /** 이름·설명·가격·모양·올림/내림·순서를 고친다. 종류는 바꾸지 않는다(이미 적용한 블로그가 깨지지 않게) */
    public Item update(long id, ItemInput in) {
        points.requireEnabled();
        Item current = find(id).orElseThrow(NotFoundException::new);
        Valid v = validate(in, current);
        jdbc.update("""
                UPDATE shop_item SET name = ?, description = ?, price = ?, value = ?, image_key = ?, active = ?, sort = ?, updated_at = ?
                WHERE id = ?
                """, v.name(), v.description(), v.price(), v.value(), v.imageKey(), v.active(), v.sort(), Timestamp.from(Times.now(clock)), id);
        return find(id).orElseThrow();
    }

    // ---- 탈퇴 ----

    public void purge(long memberId) {
        jdbc.update("DELETE FROM member_decoration WHERE member_id = ?", memberId);
        jdbc.update("DELETE FROM member_item WHERE member_id = ?", memberId);
    }

    // ---- 내부 ----

    private Optional<Item> find(long id) {
        return jdbc.query("SELECT i.*, false AS owned, false AS applied FROM shop_item i WHERE id = ?", (rs, n) -> item(rs, false, false), id)
                .stream().findFirst();
    }

    private Item item(ResultSet rs, boolean owned, boolean applied) throws SQLException {
        return new Item(rs.getLong("id"), rs.getString("kind"), rs.getString("name"), rs.getString("description"), rs.getLong("price"),
                rs.getString("value"), imageUrls.urlOf(rs.getString("image_key")), rs.getBoolean("active"), rs.getInt("sort"), owned, applied);
    }

    private record Valid(Kind kind, String name, String description, long price, String value, String imageKey, boolean active, int sort) {}

    private Valid validate(ItemInput in, Item current) {
        if (in == null) throw ApiException.badRequest("EMPTY", "아이템 정보를 보내 주세요.");
        List<FieldErrorItem> errors = new ArrayList<>();
        Kind kind = null;
        if (current != null) {
            kind = Kind.valueOf(current.kind());
        } else {
            try {
                kind = Kind.valueOf(in.kind() == null ? "" : in.kind().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                errors.add(new FieldErrorItem("kind", "INVALID", "종류는 THEME·FRAME·BANNER 가운데 하나예요."));
            }
        }
        String name = pick(in.name(), current == null ? null : current.name()).strip();
        String description = pick(in.description(), current == null ? "" : current.description()).strip();
        long price = in.price() != null ? in.price() : current == null ? 0 : current.price();
        String value = pick(in.value(), current == null ? "" : current.value()).strip();
        String imageKey;
        if (in.imageUrl() != null) {
            imageKey = in.imageUrl().isBlank() ? null : imageUrls.keyOf(in.imageUrl().strip());
            if (!in.imageUrl().isBlank() && imageKey == null) errors.add(new FieldErrorItem("imageUrl", "INVALID", "이 블로그에 올린 사진 주소만 쓸 수 있어요."));
        } else {
            imageKey = current == null ? null : imageUrls.keyOf(current.imageUrl());
        }
        boolean active = in.active() != null ? in.active() : current == null || current.active();
        int sort = in.sort() != null ? in.sort() : current == null ? 0 : current.sort();
        if (name.isEmpty() || name.length() > NAME_MAX) errors.add(new FieldErrorItem("name", "INVALID", "이름은 1~" + NAME_MAX + "자예요."));
        if (description.length() > DESCRIPTION_MAX) errors.add(new FieldErrorItem("description", "TOO_LONG", "설명은 " + DESCRIPTION_MAX + "자까지예요."));
        if (price <= 0 || price > PRICE_MAX) errors.add(new FieldErrorItem("price", "INVALID", "가격은 1~" + String.format("%,d", PRICE_MAX) + "P예요."));
        if (kind != null) {
            switch (kind) {
                case THEME -> {
                    if (!HEX.matcher(value).matches()) errors.add(new FieldErrorItem("value", "INVALID", "테마 색은 #2f9e44처럼 적어 주세요."));
                    imageKey = null;
                }
                case FRAME -> {
                    if (!HEX.matcher(value).matches() && !FRAME_PRESETS.contains(value)) {
                        errors.add(new FieldErrorItem("value", "INVALID", "테두리는 #RRGGBB 색이나 gold·rainbow·aurora 가운데 하나예요."));
                    }
                    imageKey = null;
                }
                case BANNER -> {
                    if (imageKey == null && !BANNER_PRESETS.contains(value)) {
                        errors.add(new FieldErrorItem("value", "INVALID", "표지는 사진을 올리거나 sunset·ocean·night·forest 가운데 하나를 골라 주세요."));
                    }
                    if (imageKey != null && !value.isEmpty() && !BANNER_PRESETS.contains(value)) {
                        errors.add(new FieldErrorItem("value", "INVALID", "사진 표지의 바탕은 비우거나 sunset·ocean·night·forest 가운데 하나예요."));
                    }
                }
            }
        }
        if (!errors.isEmpty()) throw ApiException.validation(errors);
        if (kind == Kind.THEME) value = value.toLowerCase(Locale.ROOT);
        return new Valid(kind, name, description, price, value, imageKey, active, sort);
    }

    private static String pick(String v, String fallback) {
        return v != null ? v : fallback == null ? "" : fallback;
    }
}
