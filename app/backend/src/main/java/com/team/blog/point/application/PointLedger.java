package com.team.blog.point.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.team.blog.shared.error.ApiException;
import com.team.blog.shared.error.FieldErrorItem;
import com.team.blog.shared.error.NotFoundException;
import com.team.blog.shared.time.Times;

/**
 * 블로그 포인트 원장 (#266, 목표 #253).
 * <ul>
 *   <li>잔액은 원장(point_ledger) 지갑별 합계다. 따로 들고 있는 잔액 칸이 없어서 원장과 어긋날 수 없다.</li>
 *   <li>모든 적립·사용은 요청 키(request_key)로 한 번만 반영한다. 같은 출석·같은 글·같은 체결이 두 번 와도 한 줄이다.</li>
 *   <li>한 회원의 쓰기는 advisory lock으로 한 줄로 세운다. 잔액 확인과 사용, 하루 상한 확인과 적립 사이에 다른 요청이 끼지 않는다.</li>
 * </ul>
 * 가입 첫 지급(INVEST_ONLY)은 처음 포인트를 볼 때 준다. 이미 가입한 회원도 같은 길로 한 번 받는다.
 */
@Service
public class PointLedger {
    public static final ZoneId KST = ZoneId.of("Asia/Seoul");
    public static final int PAGE_SIZE = 30;
    private static final int MEMO_MAX = 200;
    private static final int KEY_MAX = 120;

    /** @param invest 투자 전용(가입 첫 지급) 잔액, shop 상점에서도 쓰는 잔액 */
    public record Balance(long invest, long shop) {
        public long total() {
            return invest + shop;
        }

        public long of(Wallet w) {
            return w == Wallet.INVEST_ONLY ? invest : shop;
        }
    }

    public record Entry(long id, String wallet, long amount, String reason, Long refId, String memo, Instant createdAt) {}

    /** @param nextBefore 다음 쪽을 받을 때 넘기는 번호 (없으면 끝) */
    public record History(List<Entry> items, Long nextBefore) {}

    /**
     * @param attendedToday 오늘(한국 시간) 출석했는지, streak 이어진 연속 출석 일수(오늘 안 했으면 어제까지)
     * @param todayReward   오늘 받은 보상(출석했으면) 또는 지금 출석하면 받을 보상
     * @param nextReward    내일도 이어서 출석하면 받을 보상
     * @param maxReward     하루 상한, maxStreak 상한에 닿는 연속 출석 일수
     */
    public record Attendance(LocalDate today, boolean attendedToday, int streak, long todayReward, long nextReward, long maxReward, int maxStreak,
                             List<LocalDate> recent) {}

    public record RuleView(String code, String label, long value, long defaultValue, long max) {}

    /** 연속 출석 휘장을 주는 날들. 365일은 1년을 채운 회원에게 주는 특별 휘장 (블로그 주인 결정) */
    public static final List<Integer> BADGE_DAYS = List.of(20, 50, 100, 150, 200, 300, 365);

    /** @param special 1년(365일) 휘장 */
    public record Badge(int days, boolean special, Instant earnedAt) {}

    /** 내 포인트 화면 한 번에 */
    /** @param badges 받은 휘장, nextBadge 다음 휘장까지의 연속 일수 기준 (다 받았으면 null) */
    public record Summary(Balance balance, Attendance attendance, List<RuleView> rules, int postsRewardedToday, long tradeProfitToday,
                          History history, List<Badge> badges, Integer nextBadge) {}

    /** @param earned 이번 출석으로 받은 크레딧. 이미 출석했으면 0, streak 오늘까지 이어진 연속 출석 일수, newBadges 이번에 받은 휘장 */
    public record AttendResult(boolean already, long earned, int streak, List<Integer> newBadges, Summary summary) {}

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final PointProperties props;
    private final Clock clock;

    public PointLedger(JdbcTemplate jdbc, PlatformTransactionManager txm, PointProperties props, Clock clock) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(txm);
        this.props = props;
        this.clock = clock;
    }

    public boolean enabled() {
        return props.enabled();
    }

    /** 꺼져 있으면 없는 주소처럼 404 */
    public void requireEnabled() {
        if (!props.enabled()) throw new NotFoundException();
    }

    public LocalDate today() {
        return LocalDate.ofInstant(Times.now(clock), KST);
    }

    private Instant startOf(LocalDate day) {
        return day.atStartOfDay(KST).toInstant();
    }

    // ---- 규칙 ----

    public long rule(PointRule r) {
        List<Long> v = jdbc.queryForList("SELECT value FROM point_rule WHERE code = ?", Long.class, r.name());
        return v.isEmpty() ? r.defaultValue() : v.getFirst();
    }

    public Map<PointRule, Long> rules() {
        Map<PointRule, Long> out = new EnumMap<>(PointRule.class);
        for (PointRule r : PointRule.values()) out.put(r, r.defaultValue());
        jdbc.query("SELECT code, value FROM point_rule", rs -> {
            try {
                out.put(PointRule.valueOf(rs.getString("code")), rs.getLong("value"));
            } catch (IllegalArgumentException ignored) {
                // 코드에서 빠진 옛 규칙은 무시한다
            }
        });
        return out;
    }

    public List<RuleView> ruleViews() {
        List<RuleView> out = new ArrayList<>();
        rules().forEach((r, v) -> out.add(new RuleView(r.name(), r.label(), v, r.defaultValue(), r.max())));
        return out;
    }

    /** 관리자가 규칙 값을 바꾼다. 들어온 것만 바꾸고, 기본값과 같으면 기본값으로 되돌린다(행을 지운다) */
    public List<RuleView> updateRules(long adminId, Map<String, Long> values) {
        requireEnabled();
        if (values == null || values.isEmpty()) throw ApiException.badRequest("EMPTY", "바꿀 규칙을 보내 주세요.");
        List<FieldErrorItem> errors = new ArrayList<>();
        Map<PointRule, Long> parsed = new EnumMap<>(PointRule.class);
        values.forEach((code, value) -> {
            PointRule r;
            try {
                r = PointRule.valueOf(code);
            } catch (IllegalArgumentException e) {
                errors.add(new FieldErrorItem(code, "UNKNOWN", "없는 규칙이에요."));
                return;
            }
            long min = r == PointRule.POST_DAILY_MAX ? 1 : 0;
            if (value == null || value < min || value > r.max()) {
                errors.add(new FieldErrorItem(code, "OUT_OF_RANGE", r.label() + "은(는) " + min + "~" + String.format("%,d", r.max()) + " 사이로 적어 주세요."));
                return;
            }
            parsed.put(r, value);
        });
        if (!errors.isEmpty()) throw ApiException.validation(errors);
        tx.executeWithoutResult(st -> parsed.forEach((r, v) -> {
            if (v == r.defaultValue()) {
                jdbc.update("DELETE FROM point_rule WHERE code = ?", r.name());
            } else {
                jdbc.update("""
                        INSERT INTO point_rule (code, value, updated_by, updated_at) VALUES (?, ?, ?, ?)
                        ON CONFLICT (code) DO UPDATE SET value = EXCLUDED.value, updated_by = EXCLUDED.updated_by, updated_at = EXCLUDED.updated_at
                        """, r.name(), v, adminId, Timestamp.from(Times.now(clock)));
            }
        }));
        return ruleViews();
    }

    // ---- 잔액·내역 ----

    public Balance balance(long memberId) {
        long[] b = new long[2];
        jdbc.query("SELECT wallet, COALESCE(SUM(amount), 0) AS s FROM point_ledger WHERE member_id = ? GROUP BY wallet", rs -> {
            if (Wallet.INVEST_ONLY.name().equals(rs.getString("wallet"))) b[0] = rs.getLong("s");
            else b[1] = rs.getLong("s");
        }, memberId);
        return new Balance(b[0], b[1]);
    }

    public History history(long memberId, Wallet wallet, Long before) {
        requireEnabled();
        StringBuilder sql = new StringBuilder("SELECT id, wallet, amount, reason, ref_id, memo, created_at FROM point_ledger WHERE member_id = ?");
        List<Object> args = new ArrayList<>(List.of(memberId));
        if (wallet != null) {
            sql.append(" AND wallet = ?");
            args.add(wallet.name());
        }
        if (before != null) {
            sql.append(" AND id < ?");
            args.add(before);
        }
        sql.append(" ORDER BY id DESC LIMIT ?");
        args.add(PAGE_SIZE + 1);
        List<Entry> rows = jdbc.query(sql.toString(), (rs, i) -> new Entry(rs.getLong("id"), rs.getString("wallet"), rs.getLong("amount"),
                rs.getString("reason"), (Long) rs.getObject("ref_id"), rs.getString("memo"), rs.getTimestamp("created_at").toInstant()),
                args.toArray());
        boolean more = rows.size() > PAGE_SIZE;
        List<Entry> page = more ? rows.subList(0, PAGE_SIZE) : rows;
        return new History(List.copyOf(page), more ? page.getLast().id() : null);
    }

    /** 내 포인트 화면. 처음 보는 회원이면 가입 첫 지급을 먼저 넣는다 */
    public Summary summary(long memberId) {
        requireEnabled();
        ensureWelcome(memberId);
        return summaryWithoutWelcome(memberId);
    }

    private Summary summaryWithoutWelcome(long memberId) {
        Map<PointRule, Long> rules = rules();
        LocalDate today = today();
        Instant dayStart = startOf(today);
        Integer posts = jdbc.queryForObject("SELECT count(*) FROM point_ledger WHERE member_id = ? AND reason = 'POST' AND created_at >= ?",
                Integer.class, memberId, Timestamp.from(dayStart));
        Long trade = jdbc.queryForObject("SELECT COALESCE(SUM(amount), 0) FROM point_ledger WHERE member_id = ? AND reason = 'TRADE_PROFIT' AND created_at >= ?",
                Long.class, memberId, Timestamp.from(dayStart));
        List<RuleView> views = new ArrayList<>();
        rules.forEach((r, v) -> views.add(new RuleView(r.name(), r.label(), v, r.defaultValue(), r.max())));
        Attendance attendance = attendance(memberId, today, rules);
        List<Badge> badges = badges(memberId);
        int streak = attendance.streak();
        Integer nextBadge = BADGE_DAYS.stream().filter(d -> d > streak).findFirst().orElse(null);
        return new Summary(balance(memberId), attendance, views, posts == null ? 0 : posts, trade == null ? 0 : trade,
                history(memberId, null, null), badges, nextBadge);
    }

    private Attendance attendance(long memberId, LocalDate today, Map<PointRule, Long> rules) {
        List<LocalDate> recent = jdbc.queryForList("SELECT day FROM point_attendance WHERE member_id = ? AND day > ? ORDER BY day",
                LocalDate.class, memberId, today.minusDays(14));
        Optional<int[]> last = jdbc.query("SELECT day, streak FROM point_attendance WHERE member_id = ? AND day >= ? ORDER BY day DESC LIMIT 1",
                (rs, i) -> new int[] {rs.getObject("day", LocalDate.class).equals(today) ? 1 : 0, rs.getInt("streak")},
                memberId, today.minusDays(1)).stream().findFirst();
        boolean attended = last.map(a -> a[0] == 1).orElse(false);
        int streak = last.map(a -> a[1]).orElse(0);
        // 오늘 했으면 오늘 몫은 이미 받은 값, 안 했으면 지금 출석해 streak+1이 될 때의 값
        int todayStreak = attended ? streak : streak + 1;
        long base = rules.get(PointRule.ATTENDANCE);
        long max = rules.get(PointRule.ATTENDANCE_MAX);
        int maxStreak = base <= 0 ? 0 : (int) Math.ceil(Math.sqrt((double) max / base));
        return new Attendance(today, attended, streak, attendanceReward(rules, todayStreak), attendanceReward(rules, todayStreak + 1), max, maxStreak,
                recent);
    }

    /** 연속 출석 streak일째의 보상: 기준 값 × streak², 하루 상한까지 (1일 100, 2일 400, 10일 1만, 365일 1,332만 2,500) */
    static long attendanceReward(Map<PointRule, Long> rules, int streak) {
        long base = rules.get(PointRule.ATTENDANCE);
        long max = rules.get(PointRule.ATTENDANCE_MAX);
        if (base <= 0 || streak <= 0) return 0;
        long sq = (long) streak * streak;
        return sq > max / base ? max : Math.min(max, base * sq);
    }

    // ---- 적립 ----

    /** 가입 첫 지급(투자 전용). 회원마다 한 번 */
    public void ensureWelcome(long memberId) {
        if (!props.enabled()) return;
        Boolean has = jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM point_ledger WHERE member_id = ? AND request_key = 'welcome')", Boolean.class, memberId);
        if (Boolean.TRUE.equals(has)) return;
        long amount = rule(PointRule.WELCOME);
        if (amount <= 0) return;
        tx.executeWithoutResult(st -> insert(memberId, Wallet.INVEST_ONLY, amount, PointReason.WELCOME, "welcome", null, "가입 축하 모의투자 포인트"));
    }

    /** 오늘 출석. 이미 했으면 그대로 돌려준다. 보상은 기준 값(기본 100) × 연속 출석 일수², 하루 상한(기본 13,322,500 = 365일 연속)까지 */
    public AttendResult attend(long memberId) {
        requireEnabled();
        ensureWelcome(memberId);
        LocalDate today = today();
        List<Integer> newBadges = new ArrayList<>();
        long[] got = tx.execute(st -> {
            lock(memberId);
            Boolean done = jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM point_attendance WHERE member_id = ? AND day = ?)", Boolean.class, memberId, today);
            if (Boolean.TRUE.equals(done)) return null;
            List<Integer> prev = jdbc.queryForList("SELECT streak FROM point_attendance WHERE member_id = ? AND day = ?", Integer.class, memberId,
                    today.minusDays(1));
            int streak = prev.isEmpty() ? 1 : prev.getFirst() + 1;
            jdbc.update("INSERT INTO point_attendance (member_id, day, streak, created_at) VALUES (?, ?, ?, ?)", memberId, today, streak,
                    Timestamp.from(Times.now(clock)));
            long reward = attendanceReward(rules(), streak);
            if (reward > 0) {
                insert(memberId, Wallet.SHOP, reward, PointReason.ATTENDANCE, "attend:" + today, null,
                        streak == 1 ? today + " 출석" : today + " 출석 (" + streak + "일 연속)");
            }
            List<Integer> fresh = new ArrayList<>();
            for (int d : BADGE_DAYS) {
                // 하루씩 늘어나므로 보통은 그날 하나지만, 빠진 휘장이 있으면 함께 채운다
                if (d <= streak && jdbc.update("INSERT INTO point_badge (member_id, days, earned_at) VALUES (?, ?, ?) ON CONFLICT DO NOTHING",
                        memberId, d, Timestamp.from(Times.now(clock))) == 1) fresh.add(d);
            }
            newBadges.addAll(fresh);
            return new long[] {reward, streak};
        });
        Summary s = summaryWithoutWelcome(memberId);
        if (got == null) return new AttendResult(true, 0, s.attendance().streak(), List.of(), s);
        return new AttendResult(false, got[0], (int) got[1], List.copyOf(newBadges), s);
    }

    /**
     * 처음으로 전체 공개된 글 한 편에 적립한다. AI 일기·숨긴 글·짧은 글(기본 300자 미만)은 빼고, 하루(한국 시간) 상한(기본 3편)까지.
     * 양산형 글로 포인트를 쌓지 못하게 하는 장치다.
     *
     * @return 적립한 포인트 (안 했으면 0)
     */
    public long rewardPost(long memberId, long postId) {
        if (!props.enabled()) return 0;
        Map<PointRule, Long> rules = rules();
        long amount = rules.get(PointRule.POST);
        if (amount <= 0) return 0;
        List<Integer> chars = jdbc.queryForList("""
                SELECT char_length(regexp_replace(content_md, '\\s', '', 'g')) FROM post
                WHERE id = ? AND author_id = ? AND status = 'PUBLISHED' AND visibility = 'PUBLIC'
                  AND deleted_at IS NULL AND hidden_at IS NULL AND NOT ai_diary
                """, Integer.class, postId, memberId);
        if (chars.isEmpty() || chars.getFirst() < rules.get(PointRule.POST_MIN_CHARS)) return 0;
        Long done = tx.execute(st -> {
            lock(memberId);
            Integer today = jdbc.queryForObject("SELECT count(*) FROM point_ledger WHERE member_id = ? AND reason = 'POST' AND created_at >= ?",
                    Integer.class, memberId, Timestamp.from(startOf(today())));
            if (today != null && today >= rules.get(PointRule.POST_DAILY_MAX)) return 0L;
            return insert(memberId, Wallet.SHOP, amount, PointReason.POST, "post:" + postId, postId, "글 발행") ? amount : 0L;
        });
        return done == null ? 0 : done;
    }

    /**
     * 모의투자 실현 수익의 일부(기본 1%)를 크레딧(상점 지갑)으로 적립한다. 모의투자(#267)가 매도 체결마다 부른다.
     * 손실·0은 적립하지 않고, 하루 상한(기본 3,000)을 넘는 만큼은 버린다. 같은 체결은 한 번만.
     *
     * @param profit 그 매도의 실현 손익(포인트 = 원 단위)
     * @return 적립한 포인트
     */
    public long rewardTradeProfit(long memberId, long tradeId, BigDecimal profit) {
        if (!props.enabled() || profit == null || profit.signum() <= 0) return 0;
        Map<PointRule, Long> rules = rules();
        long raw = profit.multiply(BigDecimal.valueOf(rules.get(PointRule.TRADE_PROFIT_PERCENT)))
                .divide(BigDecimal.valueOf(100), 0, RoundingMode.DOWN).min(BigDecimal.valueOf(Long.MAX_VALUE / 2)).longValue();
        if (raw <= 0) return 0;
        Long done = tx.execute(st -> {
            lock(memberId);
            Long today = jdbc.queryForObject("SELECT COALESCE(SUM(amount), 0) FROM point_ledger WHERE member_id = ? AND reason = 'TRADE_PROFIT' AND created_at >= ?",
                    Long.class, memberId, Timestamp.from(startOf(today())));
            long room = rules.get(PointRule.TRADE_PROFIT_DAILY_MAX) - (today == null ? 0 : today);
            long amount = Math.min(raw, room);
            if (amount <= 0) return 0L;
            return insert(memberId, Wallet.SHOP, amount, PointReason.TRADE_PROFIT, "trade:" + tradeId, tradeId, "모의투자 수익") ? amount : 0L;
        });
        return done == null ? 0 : done;
    }

    // ---- 사용 ----

    /**
     * 지갑에서 포인트를 쓴다. 상점(#268)은 SHOP, 모의투자(#267)는 INVEST_ONLY·SHOP을 쓴다. 같은 요청 키는 한 번만 빠진다.
     *
     * @return 이번에 새로 뺐으면 true, 같은 키로 이미 뺐으면 false
     * @throws ApiException 잔액이 모자라면 409 POINTS_SHORT
     */
    public boolean spend(long memberId, Wallet wallet, long amount, PointReason reason, String requestKey, Long refId, String memo) {
        requireEnabled();
        if (amount <= 0) throw ApiException.badRequest("INVALID_AMOUNT", "쓸 포인트는 0보다 커야 해요.");
        Boolean done = tx.execute(st -> {
            lock(memberId);
            Boolean dup = jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM point_ledger WHERE member_id = ? AND request_key = ?)", Boolean.class,
                    memberId, requestKey);
            if (Boolean.TRUE.equals(dup)) return false;
            long have = balance(memberId).of(wallet);
            if (have < amount) {
                throw ApiException.conflict("POINTS_SHORT", wallet == Wallet.SHOP
                        ? "크레딧이 모자라요. 지금 " + String.format("%,d", have) + "크레딧이에요."
                        : "투자 전용 포인트가 모자라요. 지금 " + String.format("%,d", have) + "P예요.", Map.of("wallet", wallet.name(), "balance", have, "need", amount));
            }
            return insert(memberId, wallet, -amount, reason, requestKey, refId, memo);
        });
        return Boolean.TRUE.equals(done);
    }

    // ---- 출석 알림 ----

    /** 화면 위 출석 알림용: 오늘 출석했는지와 지금 출석하면 받을 크레딧 (#266, 블로그 주인 결정 "버튼 + 알림") */
    public record Today(boolean attendedToday, int streak, long reward) {}

    public Today todayState(long memberId) {
        requireEnabled();
        Attendance a = attendance(memberId, today(), rules());
        return new Today(a.attendedToday(), a.streak(), a.todayReward());
    }

    // ---- 휘장 ----

    /** 받은 연속 출석 휘장, 적은 일수부터 */
    public List<Badge> badges(long memberId) {
        return jdbc.query("SELECT days, earned_at FROM point_badge WHERE member_id = ? ORDER BY days", (rs, i) -> {
            int d = rs.getInt("days");
            return new Badge(d, d == 365, rs.getTimestamp("earned_at").toInstant());
        }, memberId);
    }

    /**
     * 지갑에 포인트를 넣는다. 모의투자(#267)가 판 돈(원금·수익)을 돌려줄 때 쓴다. 같은 요청 키는 한 번만 들어간다.
     *
     * @return 이번에 새로 넣었으면 true
     */
    public boolean credit(long memberId, Wallet wallet, long amount, PointReason reason, String requestKey, Long refId, String memo) {
        requireEnabled();
        if (amount <= 0) return false;
        Boolean done = tx.execute(st -> {
            lock(memberId);
            return insert(memberId, wallet, amount, reason, requestKey, refId, memo);
        });
        return Boolean.TRUE.equals(done);
    }

    // ---- 탈퇴 ----

    public void purge(long memberId) {
        jdbc.update("DELETE FROM point_ledger WHERE member_id = ?", memberId);
        jdbc.update("DELETE FROM point_attendance WHERE member_id = ?", memberId);
        jdbc.update("DELETE FROM point_badge WHERE member_id = ?", memberId);
        jdbc.update("UPDATE point_rule SET updated_by = NULL WHERE updated_by = ?", memberId);
    }

    // ---- 내부 ----

    /** 이 회원의 포인트 쓰기를 트랜잭션이 끝날 때까지 한 줄로 세운다. 바깥 트랜잭션에서 미리 잡아도 된다(같은 트랜잭션은 다시 잡아도 막히지 않는다) */
    public void lock(long memberId) {
        jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))", rs -> null, "point:" + memberId);
    }

    /** @return 새로 남겼으면 true, 같은 요청 키가 이미 있으면 false */
    private boolean insert(long memberId, Wallet wallet, long amount, PointReason reason, String key, Long refId, String memo) {
        String k = key.length() > KEY_MAX ? key.substring(0, KEY_MAX) : key;
        String m = memo == null ? "" : memo.length() > MEMO_MAX ? memo.substring(0, MEMO_MAX) : memo;
        int n = jdbc.update("""
                INSERT INTO point_ledger (member_id, wallet, amount, reason, request_key, ref_id, memo, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT (member_id, request_key) DO NOTHING
                """, memberId, wallet.name(), amount, reason.name(), k, refId, m, Timestamp.from(Times.now(clock)));
        return n > 0;
    }
}
