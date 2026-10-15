package com.team.blog.shop.application;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
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
 * 크레딧 상점과 블로그 꾸미기 (#268, 목표 #253).
 * <ul>
 *   <li>아이템은 활동으로 번 크레딧(SHOP 지갑)으로만 산다. 가입 첫 지급(INVEST_ONLY)으로는 살 수 없다.</li>
 *   <li>아이템마다 티어(1 아이언 ~ 9 챌린저)가 있고 기본 가격은 100 × 10^(티어-1) 크레딧이다. 윗 티어는 바로 아래 티어를 가진 사람만 산다(블로그 주인 결정).
 *       같은 종류에 바로 아래 티어 아이템이 있으면 같은 종류에서, 없으면(효과처럼 몇 티어에만 있는 종류) 어느 종류든 바로 아래 티어를 가지면 된다.</li>
 *   <li>산 아이템은 보관함에 남고 종류(THEME·FRAME·BANNER·EFFECT)마다 하나씩 내 블로그에 적용한다. 다른 사람도 내 블로그에서 그대로 본다.</li>
 *   <li>꾸미기 값은 화면이 style에 넣으므로 #RRGGBB, 정해 둔 모양 이름, 이 서버 사진 저장소의 키만 받는다(임의 CSS·주소를 받지 않는다).</li>
 * </ul>
 * 포인트가 꺼져 있으면(POINTS_ENABLED=false) 상점·꾸미기도 없는 주소(404)다.
 */
@Service
public class ShopService {
    public enum Kind { THEME, FRAME, BANNER, EFFECT }

    public static final int NAME_MAX = 40;
    public static final int DESCRIPTION_MAX = 200;
    public static final int TIER_MAX = 9;
    /** 챌린저 기본 가격(100억)까지 */
    public static final long PRICE_MAX = 10_000_000_000L;
    /** 티어 이름 (1부터). 화면(lib/shop.ts TIERS)과 같은 순서 */
    public static final List<String> TIER_NAMES = List.of("아이언", "브론즈", "실버", "골드", "플래티넘", "다이아", "마스터", "그랜드마스터", "챌린저");
    private static final Pattern HEX = Pattern.compile("#[0-9a-fA-F]{6}");
    /** 화면(lib/shop.ts)과 같은 이름. 늘리면 둘 다 고친다 */
    public static final Set<String> TIER_PRESETS = Set.of("iron", "bronze", "silver", "gold", "platinum", "diamond", "master", "grandmaster", "challenger");
    public static final Set<String> EFFECT_PRESETS = Set.of("glow", "stardust", "radiance");
    private static final String INVALID = "INVALID";
    private static final String VALUE = "value";
    private static final String ORDER = "CASE i.kind WHEN 'THEME' THEN 0 WHEN 'FRAME' THEN 1 WHEN 'BANNER' THEN 2 ELSE 3 END, i.tier, i.sort, i.id";

    /** 티어 기본 가격: 100 × 10^(티어-1) 크레딧 (아이언 100 ~ 챌린저 100억) */
    public static long defaultPrice(int tier) {
        long p = 100;
        for (int i = 1; i < tier; i++) p *= 10;
        return p;
    }

    /**
     * @param imageUrl 사진 표지면 주소, owned 내가 샀는지, applied 내 블로그에 적용했는지
     * @param locked 아래 티어가 없어 아직 못 사는지. needTier·needKind는 사려면 가져야 하는 티어와 종류(needKind가 null이면 어느 종류든)
     */
    public record Item(long id, String kind, int tier, String name, String description, long price, String value, String imageUrl, boolean active,
                       int sort, boolean owned, boolean applied, boolean locked, Integer needTier, String needKind) {}

    /** @param shopBalance 상점에서 쓸 수 있는 크레딧, investBalance 상점에서 못 쓰는 투자 전용 포인트(안내용) */
    public record Shop(long shopBalance, long investBalance, List<Item> items) {}

    /** 블로그에 보이는 꾸미기 하나. value나 imageUrl 중 하나가 있다 */
    public record Look(long itemId, String name, int tier, String value, String imageUrl) {}

    public record Decorations(Look theme, Look frame, Look banner, Look effect) {
        static final Decorations NONE = new Decorations(null, null, null, null);
    }

    /** 관리자가 아이템을 만들거나 고칠 때. 고칠 때 비운 칸(null)은 그대로 둔다. 가격을 비우고 만들면 티어 기본 가격이다 */
    public record ItemInput(String kind, Integer tier, String name, String description, Long price, String value, String imageUrl, Boolean active,
                            Integer sort) {}

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

    /** 상점에 올라간 아이템과 내가 산 아이템(내려간 것 포함). 아직 못 사는 윗 티어는 locked와 필요한 티어를 함께 준다 */
    public Shop shop(long memberId) {
        points.requireEnabled();
        points.ensureWelcome(memberId);
        // 봇 모의투자 수익을 먼저 정산해 잔액에 보이게 한다
        points.settleBotProfits(memberId);
        List<Item> raw = jdbc.query("""
                SELECT i.*, (o.item_id IS NOT NULL) AS owned, (d.item_id IS NOT NULL) AS applied
                FROM shop_item i
                LEFT JOIN member_item o ON o.item_id = i.id AND o.member_id = ?
                LEFT JOIN member_decoration d ON d.item_id = i.id AND d.member_id = ?
                WHERE i.active OR o.item_id IS NOT NULL
                ORDER BY\s""" + ORDER, (rs, n) -> item(rs, rs.getBoolean("owned"), rs.getBoolean("applied")), memberId, memberId);
        Ladder ladder = ladder(memberId);
        List<Item> items = raw.stream().map(it -> {
            Need need = ladder.need(it.kind(), it.tier());
            boolean locked = !it.owned() && need != null && !ladder.has(need);
            return new Item(it.id(), it.kind(), it.tier(), it.name(), it.description(), it.price(), it.value(), it.imageUrl(), it.active(), it.sort(),
                    it.owned(), it.applied(), locked, need == null ? null : need.tier(), need == null ? null : need.kind());
        }).toList();
        PointLedger.Balance b = points.balance(memberId);
        return new Shop(b.shop(), b.invest(), items);
    }

    /** 크레딧으로 산다. 이미 샀으면 409, 아래 티어가 없으면 409 TIER_LOCKED, 크레딧이 모자라면 409 POINTS_SHORT */
    public Shop purchase(long memberId, long itemId) {
        points.requireEnabled();
        points.ensureWelcome(memberId);
        tx.executeWithoutResult(st -> {
            List<Listing> rows = jdbc.query("SELECT name, kind, tier, price, active FROM shop_item WHERE id = ? FOR SHARE",
                    (rs, n) -> new Listing(rs.getString("name"), rs.getString("kind"), rs.getInt("tier"), rs.getLong("price"), rs.getBoolean("active")),
                    itemId);
            if (rows.isEmpty() || !rows.getFirst().active()) throw new NotFoundException();
            Listing row = rows.getFirst();
            Boolean owned = jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM member_item WHERE member_id = ? AND item_id = ?)", Boolean.class, memberId, itemId);
            if (Boolean.TRUE.equals(owned)) throw ApiException.conflict("ALREADY_OWNED", "이미 산 아이템이에요. 보관함에서 적용해 주세요.");
            // 산 아이템은 지우지 않으니(내려도 보관함에 남는다) 한 번 가진 아래 티어는 계속 유효하다. 그래서 잠금 없이 읽어도 된다
            Ladder ladder = ladder(memberId);
            Need need = ladder.need(row.kind(), row.tier());
            if (need != null && !ladder.has(need)) throw ApiException.conflict("TIER_LOCKED", needText(need) + "부터 사야 열려요.");
            long price = row.price();
            // 같은 트랜잭션 안에서 크레딧을 빼고 보관함에 넣는다. 하나라도 실패하면 둘 다 없던 일이 된다
            // spend가 회원별 잠금을 잡으므로, 동시에 누른 두 번째 요청은 여기서 같은 요청 키를 보고 false를 받는다
            if (!points.spend(memberId, Wallet.SHOP, price, PointReason.SHOP, "shop:item:" + itemId, itemId, row.name() + " 구매")) {
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
        List<String> kinds = jdbc.query("""
                SELECT i.kind FROM member_item o JOIN shop_item i ON i.id = o.item_id WHERE o.member_id = ? AND o.item_id = ?
                """, (rs, n) -> rs.getString(1), memberId, itemId);
        if (kinds.isEmpty()) throw ApiException.conflict("NOT_OWNED", "산 아이템만 적용할 수 있어요.");
        String kind = kinds.getFirst();
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
        Look[] looks = new Look[Kind.values().length];
        jdbc.query("""
                SELECT d.kind, i.id, i.name, i.tier, i.value, i.image_key FROM member_decoration d JOIN shop_item i ON i.id = d.item_id
                WHERE d.member_id = ?
                """, (ResultSet rs) -> {
            Look look = new Look(rs.getLong("id"), rs.getString("name"), rs.getInt("tier"), rs.getString(VALUE), imageUrls.urlOf(rs.getString("image_key")));
            looks[Kind.valueOf(rs.getString("kind")).ordinal()] = look;
        }, memberId);
        return Arrays.stream(looks).allMatch(Objects::isNull) ? Decorations.NONE : new Decorations(looks[0], looks[1], looks[2], looks[3]);
    }

    // ---- 관리자 ----

    public List<Item> adminItems() {
        points.requireEnabled();
        return jdbc.query("SELECT i.*, false AS owned, false AS applied FROM shop_item i ORDER BY " + ORDER, (rs, n) -> item(rs, false, false));
    }

    public Item create(ItemInput in) {
        points.requireEnabled();
        Valid v = validate(in, null);
        Long id = jdbc.queryForObject("""
                INSERT INTO shop_item (kind, tier, name, description, price, value, image_key, active, sort, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id
                """, Long.class, v.kind().name(), v.tier(), v.name(), v.description(), v.price(), v.value(), v.imageKey(), v.active(), v.sort(),
                Timestamp.from(Times.now(clock)), Timestamp.from(Times.now(clock)));
        return find(id).orElseThrow();
    }

    /** 티어·이름·설명·가격·모양·올림/내림·순서를 고친다. 종류는 바꾸지 않는다(이미 적용한 블로그가 깨지지 않게) */
    public Item update(long id, ItemInput in) {
        points.requireEnabled();
        Item current = find(id).orElseThrow(NotFoundException::new);
        Valid v = validate(in, current);
        jdbc.update("""
                UPDATE shop_item SET tier = ?, name = ?, description = ?, price = ?, value = ?, image_key = ?, active = ?, sort = ?, updated_at = ?
                WHERE id = ?
                """, v.tier(), v.name(), v.description(), v.price(), v.value(), v.imageKey(), v.active(), v.sort(), Timestamp.from(Times.now(clock)), id);
        return find(id).orElseThrow();
    }

    // ---- 탈퇴 ----

    public void purge(long memberId) {
        jdbc.update("DELETE FROM member_decoration WHERE member_id = ?", memberId);
        jdbc.update("DELETE FROM member_item WHERE member_id = ?", memberId);
    }

    // ---- 내부 ----

    /** 살 때 보는 아이템 정보 */
    private record Listing(String name, String kind, int tier, long price, boolean active) {}

    /** 사려면 가져야 하는 것: 이 티어 이상의 아이템 하나(kind가 null이면 어느 종류든) */
    record Need(int tier, String kind) {}

    /**
     * 티어 사다리: 상점에 올라간 (종류, 티어)와 회원이 가진 아이템의 (종류, 티어).
     * 아래 티어를 가졌는지는 "그 티어 이상"으로 본다. 관리자가 중간 티어를 나중에 추가해도 이미 윗 티어를 가진 사람은 막히지 않는다.
     */
    record Ladder(Set<String> listed, List<Map.Entry<String, Integer>> owned) {
        Need need(String kind, int tier) {
            if (tier <= 1) return null;
            return new Need(tier - 1, listed.contains(kind + ":" + (tier - 1)) ? kind : null);
        }

        boolean has(Need need) {
            return owned.stream().anyMatch(o -> o.getValue() >= need.tier() && (need.kind() == null || need.kind().equals(o.getKey())));
        }
    }

    private Ladder ladder(long memberId) {
        Set<String> listed = new HashSet<>(jdbc.queryForList("SELECT DISTINCT kind || ':' || tier FROM shop_item WHERE active", String.class));
        List<Map.Entry<String, Integer>> owned = jdbc.query("""
                SELECT i.kind, i.tier FROM member_item o JOIN shop_item i ON i.id = o.item_id WHERE o.member_id = ?
                """, (rs, n) -> Map.entry(rs.getString(1), rs.getInt(2)), memberId);
        return new Ladder(listed, owned);
    }

    private static String needText(Need need) {
        String tier = TIER_NAMES.get(need.tier() - 1);
        if (need.kind() == null) return tier + " 티어 아이템";
        return tier + " " + switch (Kind.valueOf(need.kind())) {
            case THEME -> "테마 색";
            case FRAME -> "테두리";
            case BANNER -> "표지";
            case EFFECT -> "효과";
        };
    }

    private Optional<Item> find(long id) {
        return jdbc.query("SELECT i.*, false AS owned, false AS applied FROM shop_item i WHERE id = ?", (rs, n) -> item(rs, false, false), id)
                .stream().findFirst();
    }

    private Item item(ResultSet rs, boolean owned, boolean applied) throws SQLException {
        return new Item(rs.getLong("id"), rs.getString("kind"), rs.getInt("tier"), rs.getString("name"), rs.getString("description"), rs.getLong("price"),
                rs.getString(VALUE), imageUrls.urlOf(rs.getString("image_key")), rs.getBoolean("active"), rs.getInt("sort"), owned, applied, false, null, null);
    }

    private record Valid(Kind kind, int tier, String name, String description, long price, String value, String imageKey, boolean active, int sort) {}

    private Valid validate(ItemInput in, Item current) {
        if (in == null) throw ApiException.badRequest("EMPTY", "아이템 정보를 보내 주세요.");
        List<FieldErrorItem> errors = new ArrayList<>();
        Kind kind = current != null ? Kind.valueOf(current.kind()) : kindOf(in.kind(), errors);
        int tier = pick(in.tier(), current, Item::tier, 1);
        String name = text(in.name(), current, Item::name).strip();
        String description = text(in.description(), current, Item::description).strip();
        long price = priceOf(in, current, tier);
        String value = text(in.value(), current, Item::value).strip();
        String imageKey = imageKeyOf(in, current, errors);
        boolean active = pick(in.active(), current, Item::active, true);
        int sort = pick(in.sort(), current, Item::sort, 0);
        if (tier < 1 || tier > TIER_MAX) errors.add(new FieldErrorItem("tier", INVALID, "티어는 1(아이언)~" + TIER_MAX + "(챌린저)예요."));
        if (name.isEmpty() || name.length() > NAME_MAX) errors.add(new FieldErrorItem("name", INVALID, "이름은 1~" + NAME_MAX + "자예요."));
        if (description.length() > DESCRIPTION_MAX) errors.add(new FieldErrorItem("description", "TOO_LONG", "설명은 " + DESCRIPTION_MAX + "자까지예요."));
        if (price <= 0 || price > PRICE_MAX) errors.add(new FieldErrorItem("price", INVALID, "가격은 1~" + String.format("%,d", PRICE_MAX) + "크레딧이에요."));
        if (kind != null) imageKey = checkLook(kind, value, imageKey, errors);
        if (!errors.isEmpty()) throw ApiException.validation(errors);
        if (kind == Kind.THEME) value = value.toLowerCase(Locale.ROOT);
        return new Valid(kind, tier, name, description, price, value, imageKey, active, sort);
    }

    private static Kind kindOf(String kind, List<FieldErrorItem> errors) {
        try {
            return Kind.valueOf(kind == null ? "" : kind.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            errors.add(new FieldErrorItem("kind", INVALID, "종류는 THEME·FRAME·BANNER·EFFECT 가운데 하나예요."));
            return null;
        }
    }

    /** 가격을 비우면 지금 가격, 새로 만들 때는 티어 기본 가격 */
    private static long priceOf(ItemInput in, Item current, int tier) {
        if (in.price() != null) return in.price();
        if (current != null) return current.price();
        return defaultPrice(Math.clamp(tier, 1, TIER_MAX));
    }

    /** 사진 주소를 저장소 키로 바꾼다. 비운 칸(null)은 지금 사진, 빈 문자열은 사진 없음 */
    private String imageKeyOf(ItemInput in, Item current, List<FieldErrorItem> errors) {
        if (in.imageUrl() == null) return current == null ? null : imageUrls.keyOf(current.imageUrl());
        if (in.imageUrl().isBlank()) return null;
        String key = imageUrls.keyOf(in.imageUrl().strip());
        if (key == null) errors.add(new FieldErrorItem("imageUrl", INVALID, "이 블로그에 올린 사진 주소만 쓸 수 있어요."));
        return key;
    }

    /** 종류마다 꾸미기 값을 확인한다 @return 이 종류에서 쓰는 사진 키 (표지만 사진을 쓴다) */
    private static String checkLook(Kind kind, String value, String imageKey, List<FieldErrorItem> errors) {
        return switch (kind) {
            case THEME -> {
                if (!HEX.matcher(value).matches()) errors.add(new FieldErrorItem(VALUE, INVALID, "테마 색은 #2f9e44처럼 적어 주세요."));
                yield null;
            }
            case FRAME -> {
                if (!HEX.matcher(value).matches() && !TIER_PRESETS.contains(value)) {
                    errors.add(new FieldErrorItem(VALUE, INVALID, "테두리는 #RRGGBB 색이나 iron~challenger 티어 모양 가운데 하나예요."));
                }
                yield null;
            }
            case BANNER -> {
                if (imageKey == null && !TIER_PRESETS.contains(value)) {
                    errors.add(new FieldErrorItem(VALUE, INVALID, "표지는 사진을 올리거나 iron~challenger 티어 표지 가운데 하나를 골라 주세요."));
                }
                if (imageKey != null && !value.isEmpty() && !TIER_PRESETS.contains(value)) {
                    errors.add(new FieldErrorItem(VALUE, INVALID, "사진 표지의 바탕은 비우거나 iron~challenger 티어 표지 가운데 하나예요."));
                }
                yield imageKey;
            }
            case EFFECT -> {
                if (!EFFECT_PRESETS.contains(value)) errors.add(new FieldErrorItem(VALUE, INVALID, "효과는 glow·stardust·radiance 가운데 하나예요."));
                yield null;
            }
        };
    }

    /** 들어온 값이 있으면 그 값, 비웠으면(null) 지금 값, 새로 만들 때는 fallback */
    private static <T> T pick(T in, Item current, Function<Item, T> now, T fallback) {
        if (in != null) return in;
        if (current == null) return fallback;
        return now.apply(current);
    }

    /** 글자 칸은 비었거나 지금 값이 없으면 빈 문자열 */
    private static String text(String in, Item current, Function<Item, String> now) {
        return Objects.requireNonNullElse(pick(in, current, now, ""), "");
    }
}
