package com.team.blog.trade.application;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.team.blog.post.application.PostCommandService;
import com.team.blog.shared.error.ApiException;
import com.team.blog.shared.error.FieldErrorItem;
import com.team.blog.shared.error.NotFoundException;
import com.team.blog.shared.time.Times;
import com.team.blog.shorts.application.ShortsService;

/**
 * 매매 일지 (#243). 집 컴퓨터의 매매 봇이 MCP 도구 record_trade로 체결을 보내면 남기고, 하루(한국 시간) 단위로 묶어 보여 준다.
 * 하루 기록으로 요약 임시글과 쇼츠 대본을 만든다. 공개 여부는 회원이 글·쇼츠마다 정한다(여기서는 임시글·대본까지만 만든다).
 * 실제 주문은 devlog가 하지 않는다. 증권사 접속 정보도 받지 않는다.
 */
@Service
public class TradeJournal {
    public static final int SYMBOL_MAX = 20;
    public static final int SYMBOL_NAME_MAX = 60;
    public static final int EXTERNAL_ID_MAX = 80;
    public static final int STRATEGY_MAX = 60;
    public static final int REASON_MAX = 300;
    public static final int MEMO_MAX = 2000;
    /** 한 번에 보는 날 수 */
    public static final int DAYS_MAX = 120;
    /** numeric(18, 4)에 들어가는 값 */
    private static final BigDecimal AMOUNT_MAX = new BigDecimal("99999999999999");
    private static final Pattern SYMBOL = Pattern.compile("[A-Za-z0-9._-]{1," + SYMBOL_MAX + "}");

    public enum Mode { PAPER, LIVE }

    public enum Side { BUY, SELL }

    /** 봇이 보내는 체결 하나 */
    public record Input(String externalId, Mode mode, String symbol, String symbolName, Side side, BigDecimal quantity, BigDecimal price,
                        BigDecimal fee, BigDecimal realizedPnl, String strategy, String reason, Instant executedAt) {}

    /** @param duplicate 같은 externalId로 이미 남긴 체결이면 true (새로 남기지 않았다) */
    public record Recorded(long id, boolean duplicate, LocalDate day) {}

    public record Fill(long id, String externalId, String mode, String symbol, String symbolName, String side, BigDecimal quantity,
                       BigDecimal price, BigDecimal fee, BigDecimal realizedPnl, String strategy, String reason, Instant executedAt) {}

    /** @param wins 손익이 0보다 큰 매도 수, losses 0보다 작은 매도 수 */
    public record Summary(int trades, int buys, int sells, int wins, int losses, BigDecimal realizedPnl, BigDecimal fees) {}

    public record DayRow(LocalDate date, Summary summary, BigDecimal cumulativePnl, boolean memo, Long postId, Long shortsId) {}

    /** @param cumulativePnl 첫 기록부터 이 날까지 실현 손익 합 */
    public record Overview(LocalDate from, LocalDate to, List<DayRow> days, Summary total, BigDecimal cumulativePnl, boolean live) {}

    public record Day(LocalDate date, List<Fill> fills, Summary summary, BigDecimal cumulativePnl, String memo, Long postId, Long shortsId) {}

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final PostCommandService posts;
    private final ShortsService shorts;
    private final TradeProperties props;
    private final Clock clock;

    public TradeJournal(JdbcTemplate jdbc, PlatformTransactionManager txm, PostCommandService posts, ShortsService shorts,
                        TradeProperties props, Clock clock) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(txm);
        this.posts = posts;
        this.shorts = shorts;
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
        return LocalDate.ofInstant(Times.now(clock), TradeTexts.KST);
    }

    /** 체결 하나를 남긴다. 같은 externalId면 처음 것을 그대로 두고 duplicate로 알린다 */
    public Recorded record(long memberId, Input in) {
        requireEnabled();
        Input v = validate(in);
        Optional<Recorded> existing = findByExternal(memberId, v.externalId());
        if (existing.isPresent()) return existing.get();
        Instant dayStart = today().atStartOfDay(TradeTexts.KST).toInstant();
        Integer today = jdbc.queryForObject("SELECT count(*) FROM trade WHERE member_id = ? AND created_at >= ?", Integer.class,
                memberId, Timestamp.from(dayStart));
        if (today != null && today >= props.dailyRecords()) {
            throw ApiException.conflict("TRADE_DAILY_LIMIT", "체결 기록은 하루 " + props.dailyRecords() + "건까지예요. 봇이 같은 주문을 되풀이하고 있지 않은지 확인해 주세요.");
        }
        try {
            Long id = jdbc.queryForObject("""
                    INSERT INTO trade (member_id, external_id, mode, symbol, symbol_name, side, quantity, price, fee, realized_pnl,
                                       strategy, reason, executed_at, created_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id
                    """, Long.class, memberId, v.externalId(), v.mode().name(), v.symbol(), v.symbolName(), v.side().name(), v.quantity(),
                    v.price(), v.fee(), v.realizedPnl(), v.strategy(), v.reason(), Timestamp.from(v.executedAt()),
                    Timestamp.from(Times.now(clock)));
            return new Recorded(id, false, LocalDate.ofInstant(v.executedAt(), TradeTexts.KST));
        } catch (DuplicateKeyException e) {
            // 같은 체결을 동시에 두 번 보냈다
            return findByExternal(memberId, v.externalId()).orElseThrow(() -> e);
        }
    }

    private Optional<Recorded> findByExternal(long memberId, String externalId) {
        return jdbc.query("SELECT id, executed_at FROM trade WHERE member_id = ? AND external_id = ?",
                (rs, i) -> new Recorded(rs.getLong("id"), true, LocalDate.ofInstant(rs.getTimestamp("executed_at").toInstant(), TradeTexts.KST)),
                memberId, externalId).stream().findFirst();
    }

    private Input validate(Input in) {
        List<FieldErrorItem> errors = new ArrayList<>();
        String externalId = trim(in.externalId());
        String symbol = trim(in.symbol()).toUpperCase(Locale.ROOT);
        String symbolName = trim(in.symbolName());
        String strategy = trim(in.strategy());
        String reason = trim(in.reason()).replaceAll("\\s+", " ");
        if (externalId.isEmpty()) errors.add(new FieldErrorItem("external_id", "REQUIRED", "봇의 주문·체결 번호(external_id)가 필요해요."));
        else if (externalId.length() > EXTERNAL_ID_MAX) errors.add(new FieldErrorItem("external_id", "TOO_LONG", "external_id는 " + EXTERNAL_ID_MAX + "자까지예요."));
        if (!SYMBOL.matcher(symbol).matches()) errors.add(new FieldErrorItem("symbol", "INVALID", "종목 코드(symbol)는 영문·숫자 " + SYMBOL_MAX + "자까지예요. 예: 005930"));
        if (symbolName.length() > SYMBOL_NAME_MAX) errors.add(new FieldErrorItem("symbol_name", "TOO_LONG", "종목 이름은 " + SYMBOL_NAME_MAX + "자까지예요."));
        if (in.side() == null) errors.add(new FieldErrorItem("side", "REQUIRED", "매수(BUY)·매도(SELL)를 알려 주세요."));
        if (in.quantity() == null || in.quantity().signum() <= 0 || in.quantity().compareTo(AMOUNT_MAX) > 0) {
            errors.add(new FieldErrorItem("quantity", "INVALID", "수량(quantity)은 0보다 커야 해요."));
        }
        if (in.price() == null || in.price().signum() < 0 || in.price().compareTo(AMOUNT_MAX) > 0) errors.add(new FieldErrorItem("price", "INVALID", "가격(price)은 0 이상이어야 해요."));
        if (in.fee() != null && (in.fee().signum() < 0 || in.fee().compareTo(AMOUNT_MAX) > 0)) errors.add(new FieldErrorItem("fee", "INVALID", "수수료(fee)는 0 이상이어야 해요."));
        if (in.realizedPnl() != null && in.realizedPnl().abs().compareTo(AMOUNT_MAX) > 0) errors.add(new FieldErrorItem("realized_pnl", "INVALID", "실현 손익이 너무 커요."));
        if (in.realizedPnl() != null && in.side() == Side.BUY) errors.add(new FieldErrorItem("realized_pnl", "INVALID", "실현 손익은 매도(SELL)에만 적어요."));
        if (strategy.length() > STRATEGY_MAX) errors.add(new FieldErrorItem("strategy", "TOO_LONG", "전략 이름은 " + STRATEGY_MAX + "자까지예요."));
        if (reason.length() > REASON_MAX) errors.add(new FieldErrorItem("reason", "TOO_LONG", "이유는 " + REASON_MAX + "자까지예요."));
        Instant now = Times.now(clock);
        Instant at = in.executedAt() == null ? now : in.executedAt();
        // 시계가 조금 어긋난 것은 받되, 내일 이후로 적힌 기록은 받지 않는다
        if (at.isAfter(now.plusSeconds(600))) errors.add(new FieldErrorItem("executed_at", "FUTURE", "체결 시각(executed_at)이 지금보다 뒤예요."));
        if (!errors.isEmpty()) throw ApiException.validation(errors);
        return new Input(externalId, in.mode() == null ? Mode.PAPER : in.mode(), symbol, symbolName, in.side(), in.quantity(), in.price(),
                in.fee() == null ? BigDecimal.ZERO : in.fee(), in.realizedPnl(), strategy, reason, at);
    }

    private static String trim(String s) {
        return s == null ? "" : s.strip();
    }

    /** 기간 안의 날별 요약(체결이 있거나 메모를 남긴 날만, 최근 날 먼저). 누적 손익은 첫 기록부터 센다 */
    public Overview overview(long memberId, LocalDate from, LocalDate to) {
        requireEnabled();
        if (to.isBefore(from)) throw ApiException.badRequest("INVALID_RANGE", "시작 날짜가 끝 날짜보다 뒤예요.");
        if (from.plusDays(DAYS_MAX).isBefore(to)) from = to.minusDays(DAYS_MAX);
        Map<LocalDate, Summary> byDay = new LinkedHashMap<>();
        Map<LocalDate, List<Fill>> fills = groupByDay(fills(memberId, from, to));
        for (var e : fills.entrySet()) byDay.put(e.getKey(), summarize(e.getValue()));
        BigDecimal before = pnlBefore(memberId, from);
        Map<LocalDate, DayMeta> meta = meta(memberId, from, to);
        List<LocalDate> dates = new ArrayList<>(byDay.keySet());
        for (LocalDate d : meta.keySet()) if (!byDay.containsKey(d) && meta.get(d).hasContent()) dates.add(d);
        dates.sort(null);
        List<DayRow> rows = new ArrayList<>();
        BigDecimal running = before;
        for (LocalDate d : dates) {
            Summary s = byDay.getOrDefault(d, summarize(List.of()));
            running = running.add(s.realizedPnl());
            DayMeta m = meta.getOrDefault(d, DayMeta.EMPTY);
            rows.add(new DayRow(d, s, running, !m.memo().isBlank(), m.postId(), m.shortsId()));
        }
        List<Fill> all = fills.values().stream().flatMap(List::stream).toList();
        boolean live = Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM trade WHERE member_id = ? AND mode = 'LIVE')",
                Boolean.class, memberId));
        return new Overview(from, to, rows.reversed(), summarize(all), running, live);
    }

    public Day day(long memberId, LocalDate date) {
        requireEnabled();
        List<Fill> list = fills(memberId, date, date);
        Summary s = summarize(list);
        DayMeta m = meta(memberId, date, date).getOrDefault(date, DayMeta.EMPTY);
        BigDecimal cumulative = pnlBefore(memberId, date).add(s.realizedPnl());
        return new Day(date, list, s, cumulative, m.memo(), m.postId(), m.shortsId());
    }

    public Day saveMemo(long memberId, LocalDate date, String memo) {
        requireEnabled();
        String m = memo == null ? "" : memo.strip();
        if (m.length() > MEMO_MAX) throw ApiException.validation(List.of(new FieldErrorItem("memo", "TOO_LONG", "메모는 " + MEMO_MAX + "자까지예요.")));
        jdbc.update("""
                INSERT INTO trade_day (member_id, day, memo, updated_at) VALUES (?, ?, ?, ?)
                ON CONFLICT (member_id, day) DO UPDATE SET memo = EXCLUDED.memo, updated_at = EXCLUDED.updated_at
                """, memberId, date, m, Timestamp.from(Times.now(clock)));
        return day(memberId, date);
    }

    /** 체결을 잘못 보냈을 때 지운다 */
    public void delete(long memberId, long tradeId) {
        requireEnabled();
        if (jdbc.update("DELETE FROM trade WHERE id = ? AND member_id = ?", tradeId, memberId) == 0) throw new NotFoundException();
    }

    /** 하루 요약 임시글. 그날 만든 글이 남아 있으면 새로 만들지 않고 그 글을 돌려준다 */
    public Day createDraft(long memberId, LocalDate date) {
        requireEnabled();
        Day day = requireTrades(memberId, date);
        if (day.postId() != null && livePost(day.postId())) return day;
        return tx.execute(st -> {
            long postId = posts.create(memberId, TradeTexts.draftTitle(day), TradeTexts.draftBody(day)).id();
            upsertLink(memberId, date, "post_id", postId);
            return day(memberId, date);
        });
    }

    /**
     * 하루 쇼츠 대본. 숫자만 읽는 영상은 다른 날 영상과 거의 같아져 양산형으로 보이므로 그날 메모가 있어야 만든다.
     * 그날 요약 글이 있으면 원본 글로 잇는다. 그날 만든 대본이 남아 있으면 그 대본을 돌려준다.
     */
    public Day createShorts(long memberId, LocalDate date) {
        requireEnabled();
        shorts.requireEnabled();
        Day day = requireTrades(memberId, date);
        if (day.memo().isBlank()) {
            throw ApiException.conflict("TRADE_MEMO_REQUIRED", "쇼츠를 만들기 전에 이날 메모를 남겨 주세요. 숫자만 읽는 영상은 날마다 거의 같아져 양산형으로 보여요.");
        }
        if (day.shortsId() != null && liveShorts(day.shortsId())) return day;
        Long postId = day.postId() != null && livePost(day.postId()) ? day.postId() : null;
        long shortsId = shorts.create(memberId, new ShortsService.Draft(postId, TradeTexts.shortsTitle(day), TradeTexts.shortsScenes(day))).id();
        upsertLink(memberId, date, "shorts_id", shortsId);
        return day(memberId, date);
    }

    public void purge(long memberId) {
        jdbc.update("DELETE FROM trade_day WHERE member_id = ?", memberId);
        jdbc.update("DELETE FROM trade WHERE member_id = ?", memberId);
    }

    private Day requireTrades(long memberId, LocalDate date) {
        Day day = day(memberId, date);
        if (day.fills().isEmpty()) throw ApiException.conflict("TRADE_DAY_EMPTY", "이날은 체결 기록이 없어요.");
        return day;
    }

    private boolean livePost(long postId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM post WHERE id = ? AND deleted_at IS NULL)", Boolean.class, postId));
    }

    private boolean liveShorts(long shortsId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM shorts WHERE id = ?)", Boolean.class, shortsId));
    }

    private void upsertLink(long memberId, LocalDate date, String column, long id) {
        // column은 이 클래스 안의 고정 값(post_id·shorts_id)만 온다
        jdbc.update("INSERT INTO trade_day (member_id, day, " + column + ", updated_at) VALUES (?, ?, ?, ?) "
                + "ON CONFLICT (member_id, day) DO UPDATE SET " + column + " = EXCLUDED." + column + ", updated_at = EXCLUDED.updated_at",
                memberId, date, id, Timestamp.from(Times.now(clock)));
    }

    private List<Fill> fills(long memberId, LocalDate from, LocalDate to) {
        return jdbc.query("""
                SELECT id, external_id, mode, symbol, symbol_name, side, quantity, price, fee, realized_pnl, strategy, reason, executed_at
                FROM trade WHERE member_id = ? AND executed_at >= ? AND executed_at < ?
                ORDER BY executed_at, id
                """, TradeJournal::fill, memberId, Timestamp.from(start(from)), Timestamp.from(start(to.plusDays(1))));
    }

    private static Fill fill(ResultSet rs, int i) throws SQLException {
        return new Fill(rs.getLong("id"), rs.getString("external_id"), rs.getString("mode"), rs.getString("symbol"), rs.getString("symbol_name"),
                rs.getString("side"), rs.getBigDecimal("quantity"), rs.getBigDecimal("price"), rs.getBigDecimal("fee"),
                rs.getBigDecimal("realized_pnl"), rs.getString("strategy"), rs.getString("reason"), rs.getTimestamp("executed_at").toInstant());
    }

    private static Map<LocalDate, List<Fill>> groupByDay(List<Fill> fills) {
        Map<LocalDate, List<Fill>> out = new LinkedHashMap<>();
        for (Fill f : fills) out.computeIfAbsent(LocalDate.ofInstant(f.executedAt(), TradeTexts.KST), k -> new ArrayList<>()).add(f);
        return out;
    }

    static Summary summarize(List<Fill> fills) {
        int buys = 0;
        int sells = 0;
        int wins = 0;
        int losses = 0;
        BigDecimal pnl = BigDecimal.ZERO;
        BigDecimal fees = BigDecimal.ZERO;
        for (Fill f : fills) {
            if (f.side().equals("BUY")) buys++;
            else sells++;
            fees = fees.add(f.fee());
            if (f.realizedPnl() != null) {
                pnl = pnl.add(f.realizedPnl());
                if (f.realizedPnl().signum() > 0) wins++;
                else if (f.realizedPnl().signum() < 0) losses++;
            }
        }
        return new Summary(fills.size(), buys, sells, wins, losses, pnl, fees);
    }

    private BigDecimal pnlBefore(long memberId, LocalDate date) {
        BigDecimal v = jdbc.queryForObject("SELECT coalesce(sum(realized_pnl), 0) FROM trade WHERE member_id = ? AND executed_at < ?",
                BigDecimal.class, memberId, Timestamp.from(start(date)));
        return v == null ? BigDecimal.ZERO : v;
    }

    private record DayMeta(String memo, Long postId, Long shortsId) {
        static final DayMeta EMPTY = new DayMeta("", null, null);

        boolean hasContent() {
            return !memo.isBlank() || postId != null || shortsId != null;
        }
    }

    private Map<LocalDate, DayMeta> meta(long memberId, LocalDate from, LocalDate to) {
        Map<LocalDate, DayMeta> out = new LinkedHashMap<>();
        jdbc.query("SELECT day, memo, post_id, shorts_id FROM trade_day WHERE member_id = ? AND day BETWEEN ? AND ?",
                (RowCallbackHandler) rs -> out.put(rs.getObject("day", LocalDate.class), new DayMeta(rs.getString("memo"),
                        rs.getObject("post_id", Long.class), rs.getObject("shorts_id", Long.class))), memberId, from, to);
        return out;
    }

    private static Instant start(LocalDate d) {
        return d.atStartOfDay(TradeTexts.KST).toInstant();
    }
}
