package com.team.blog.paper.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.team.blog.point.application.PointLedger;
import com.team.blog.point.application.PointReason;
import com.team.blog.point.application.Wallet;
import com.team.blog.shared.error.ApiException;
import com.team.blog.shared.error.FieldErrorItem;
import com.team.blog.shared.error.NotFoundException;
import com.team.blog.shared.time.Times;
import com.team.blog.trade.application.TradeJournal;

/**
 * 회원 포인트 모의투자 (#267, 목표 #253). 업비트 공개 시세로 BTC·ETH·XRP·SOL·DOGE를 포인트로 사고판다(1P = 1원, 현물만).
 * <ul>
 *   <li>살 때는 투자 전용 포인트(가입 첫 지급)를 먼저 쓰고, 회원이 고르면 모자란 만큼 상점 포인트도 쓴다.</li>
 *   <li>보유분마다 어느 지갑에서 얼마를 냈는지(원금)를 들고 있다가, 팔 때 원금을 낸 지갑으로 돌려준다.
 *       수익은 투자 전용으로 들어가 다시 투자에 쓰고, 그와 별도로 수익의 일부(기본 1%, 하루 상한)가 상점 포인트로 적립된다.
 *       손실은 원금을 낸 지갑에서 비율대로 빠진다.</li>
 *   <li>체결은 그 회원의 매매 일지에 PAPER(모의투자)·거래소 UPBIT·통화 KRW로 남는다.</li>
 *   <li>같은 주문 번호(clientOrderId)로 다시 보내면 한 번만 체결된다(두 번 누르기·재전송).</li>
 * </ul>
 */
@Service
public class PaperTrading {
    private static final Pattern ORDER_ID = Pattern.compile("[A-Za-z0-9-]{8,64}");
    private static final int REASON_MAX = 300;
    public static final String STRATEGY = "포인트 모의투자";

    public enum Side { BUY, SELL }

    /**
     * @param amount   살 때 쓸 포인트(수수료 포함)
     * @param quantity 팔 수량. all이면 무시하고 전부 판다
     * @param useShop  투자 전용 포인트가 모자라면 상점 포인트도 쓸지
     */
    public record Order(String clientOrderId, String symbol, String side, Long amount, BigDecimal quantity, Boolean all, Boolean useShop,
                        String reason) {}

    public record CoinQuote(String symbol, String name, BigDecimal price, BigDecimal changeRate) {}

    /** @param cost 남은 원금(두 지갑 합), value 지금 시세로 친 값, pnl 평가 손익 */
    public record Position(String symbol, String name, BigDecimal quantity, long cost, long costInvest, long costShop, BigDecimal avgPrice,
                           long value, long pnl) {}

    /** @param quotesError 시세를 받지 못했으면 이유(화면이 주문을 막는다) */
    public record State(List<CoinQuote> quotes, List<Position> positions, long invest, long shop, long holdings, BigDecimal feeRate, long minOrder,
                        String quotesError) {}

    /** @param tradeId 매매 일지 체결 번호, reward 이번 매도로 적립된 상점 포인트 */
    public record Filled(boolean duplicate, Long tradeId, String side, String symbol, BigDecimal quantity, BigDecimal price, long fee, Long pnl,
                         long reward, State state) {}

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final PointLedger points;
    private final TradeJournal journal;
    private final Quotes quotes;
    private final PaperProperties props;
    private final Clock clock;

    public PaperTrading(JdbcTemplate jdbc, PlatformTransactionManager txm, PointLedger points, TradeJournal journal, Quotes quotes,
                        PaperProperties props, Clock clock) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(txm);
        this.points = points;
        this.journal = journal;
        this.quotes = quotes;
        this.props = props;
        this.clock = clock;
    }

    public boolean enabled() {
        return points.enabled() && journal.enabled();
    }

    public void requireEnabled() {
        if (!enabled()) throw new NotFoundException();
    }

    // ---- 보기 ----

    public State state(long memberId) {
        requireEnabled();
        points.ensureWelcome(memberId);
        Map<String, Quotes.Quote> q;
        String error = null;
        try {
            q = quotes.latest();
        } catch (Quotes.QuotesUnavailable e) {
            q = Map.of();
            error = "시세를 받지 못했어요. 잠시 뒤 다시 열어 주세요.";
        }
        return stateWith(memberId, q, error);
    }

    private State stateWith(long memberId, Map<String, Quotes.Quote> q, String error) {
        List<CoinQuote> list = new ArrayList<>();
        for (PaperCoins.Coin c : PaperCoins.ALL) {
            Quotes.Quote quote = q.get(c.symbol());
            list.add(new CoinQuote(c.symbol(), c.name(), quote == null ? null : quote.price(), quote == null ? null : quote.changeRate()));
        }
        List<Position> positions = new ArrayList<>();
        long holdings = 0;
        for (Map<String, Object> row : jdbc.queryForList(
                "SELECT symbol, quantity, cost_invest, cost_shop FROM paper_position WHERE member_id = ? ORDER BY symbol", memberId)) {
            String symbol = (String) row.get("symbol");
            PaperCoins.Coin coin = PaperCoins.of(symbol).orElse(new PaperCoins.Coin(symbol, symbol, 8));
            BigDecimal qty = (BigDecimal) row.get("quantity");
            long ci = ((Number) row.get("cost_invest")).longValue();
            long cs = ((Number) row.get("cost_shop")).longValue();
            long cost = ci + cs;
            Quotes.Quote quote = q.get(symbol);
            long value = quote == null ? cost : qty.multiply(quote.price()).setScale(0, RoundingMode.DOWN).longValue();
            BigDecimal avg = qty.signum() == 0 ? BigDecimal.ZERO : BigDecimal.valueOf(cost).divide(qty, 2, RoundingMode.HALF_UP);
            positions.add(new Position(symbol, coin.name(), qty.stripTrailingZeros(), cost, ci, cs, avg, value, value - cost));
            holdings += value;
        }
        PointLedger.Balance b = points.balance(memberId);
        return new State(list, positions, b.invest(), b.shop(), holdings, props.feeRate(), props.minOrder(), error);
    }

    // ---- 주문 ----

    public Filled order(long memberId, Order in) {
        requireEnabled();
        points.ensureWelcome(memberId);
        Valid v = validate(in);
        String externalId = "paper-" + v.orderId();
        // 같은 주문 번호로 이미 체결했으면 그대로 돌려준다
        if (ordered(memberId, v.orderId())) return duplicate(memberId, v);
        Quotes.Quote quote;
        try {
            quote = quotes.latest().get(v.coin().symbol());
        } catch (Quotes.QuotesUnavailable e) {
            quote = null;
        }
        if (quote == null) throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "QUOTES_UNAVAILABLE", "시세를 받지 못해 주문하지 못했어요. 잠시 뒤 다시 시도해 주세요.");
        BigDecimal price = quote.price();
        Filled filled = tx.execute(st -> {
            points.lock(memberId);
            if (ordered(memberId, v.orderId())) return null;
            Filled f = v.side() == Side.BUY ? buy(memberId, v, price, externalId) : sell(memberId, v, price, externalId);
            // 이 주문을 끝냈다는 표시. 같은 주문 번호를 다시 받으면 이 줄을 보고 체결하지 않는다
            jdbc.update("INSERT INTO paper_order (member_id, client_order_id, trade_id, created_at) VALUES (?, ?, ?, ?)", memberId, v.orderId(),
                    f.tradeId(), Timestamp.from(Times.now(clock)));
            return f;
        });
        if (filled == null) return duplicate(memberId, v);
        Map<String, Quotes.Quote> q;
        try {
            q = quotes.latest();
        } catch (Quotes.QuotesUnavailable e) {
            q = Map.of();
        }
        return new Filled(false, filled.tradeId(), filled.side(), filled.symbol(), filled.quantity(), filled.price(), filled.fee(), filled.pnl(),
                filled.reward(), stateWith(memberId, q, null));
    }

    private Filled buy(long memberId, Valid v, BigDecimal price, String externalId) {
        long amount = v.amount();
        long fee = BigDecimal.valueOf(amount).multiply(props.feeRate()).setScale(0, RoundingMode.CEILING).longValue();
        BigDecimal qty = BigDecimal.valueOf(amount - fee).divide(price, v.coin().scale(), RoundingMode.DOWN);
        if (qty.signum() <= 0) throw ApiException.badRequest("TOO_SMALL", "이 금액으로는 " + v.coin().name() + "을(를) 살 수 없어요. 금액을 늘려 주세요.");
        PointLedger.Balance b = points.balance(memberId);
        long fromInvest = Math.min(amount, b.invest());
        long fromShop = amount - fromInvest;
        if (fromShop > 0 && (!v.useShop() || b.shop() < fromShop)) {
            throw ApiException.conflict("POINTS_SHORT", v.useShop()
                    ? "포인트가 모자라요. 투자 전용 " + won(b.invest()) + " + 상점 " + won(b.shop()) + "까지 살 수 있어요."
                    : "투자 전용 포인트가 모자라요. 지금 " + won(b.invest()) + "예요. 상점 포인트도 쓰려면 [상점 포인트도 쓰기]를 켜 주세요.",
                    Map.of("invest", b.invest(), "shop", b.shop(), "need", amount));
        }
        String memo = v.coin().name() + " 매수";
        if (fromInvest > 0) points.spend(memberId, Wallet.INVEST_ONLY, fromInvest, PointReason.INVEST, key(v, "buy-invest"), null, memo);
        if (fromShop > 0) points.spend(memberId, Wallet.SHOP, fromShop, PointReason.INVEST, key(v, "buy-shop"), null, memo);
        jdbc.update("""
                INSERT INTO paper_position (member_id, symbol, quantity, cost_invest, cost_shop, updated_at) VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT (member_id, symbol) DO UPDATE SET quantity = paper_position.quantity + EXCLUDED.quantity,
                    cost_invest = paper_position.cost_invest + EXCLUDED.cost_invest, cost_shop = paper_position.cost_shop + EXCLUDED.cost_shop,
                    updated_at = EXCLUDED.updated_at
                """, memberId, v.coin().symbol(), qty, fromInvest, fromShop, Timestamp.from(Times.now(clock)));
        long tradeId = record(memberId, v, externalId, TradeJournal.Side.BUY, qty, price, fee, null);
        return new Filled(false, tradeId, Side.BUY.name(), v.coin().symbol(), qty, price, fee, null, 0, null);
    }

    private Filled sell(long memberId, Valid v, BigDecimal price, String externalId) {
        Map<String, Object> pos = jdbc.query("SELECT quantity, cost_invest, cost_shop FROM paper_position WHERE member_id = ? AND symbol = ?",
                rs -> rs.next() ? Map.<String, Object>of("q", rs.getBigDecimal(1), "ci", rs.getLong(2), "cs", rs.getLong(3)) : null,
                memberId, v.coin().symbol());
        if (pos == null) throw ApiException.conflict("NO_POSITION", v.coin().name() + "을(를) 갖고 있지 않아요.");
        BigDecimal have = (BigDecimal) pos.get("q");
        BigDecimal qty = v.all() ? have : v.quantity().setScale(v.coin().scale(), RoundingMode.DOWN);
        if (qty.signum() <= 0) throw ApiException.badRequest("TOO_SMALL", "팔 수량을 적어 주세요.");
        if (qty.compareTo(have) > 0) throw ApiException.conflict("NOT_ENOUGH", "갖고 있는 " + v.coin().name() + "은(는) " + have.stripTrailingZeros().toPlainString() + "개예요.");
        boolean whole = qty.compareTo(have) == 0;
        long ci = (Long) pos.get("ci");
        long cs = (Long) pos.get("cs");
        BigDecimal ratio = whole ? BigDecimal.ONE : qty.divide(have, 12, RoundingMode.HALF_UP);
        long partInvest = whole ? ci : BigDecimal.valueOf(ci).multiply(ratio).setScale(0, RoundingMode.HALF_UP).longValue();
        long partShop = whole ? cs : BigDecimal.valueOf(cs).multiply(ratio).setScale(0, RoundingMode.HALF_UP).longValue();
        long gross = qty.multiply(price).setScale(0, RoundingMode.DOWN).longValue();
        if (gross < props.minOrder() && !whole) {
            throw ApiException.badRequest("TOO_SMALL", "한 번에 " + won(props.minOrder()) + " 이상 팔 수 있어요. 남은 것을 모두 팔 때는 금액 제한이 없어요.");
        }
        long fee = BigDecimal.valueOf(gross).multiply(props.feeRate()).setScale(0, RoundingMode.CEILING).longValue();
        long proceeds = Math.max(0, gross - fee);
        long cost = partInvest + partShop;
        long pnl = proceeds - cost;
        long backInvest;
        long backShop;
        if (pnl >= 0) {
            // 원금은 낸 지갑으로, 수익은 투자 전용으로 (다시 투자에 쓴다)
            backInvest = partInvest + pnl;
            backShop = partShop;
        } else {
            // 손실은 원금을 낸 비율대로 나눠 뺀다
            backShop = cost == 0 ? 0 : BigDecimal.valueOf(partShop).multiply(BigDecimal.valueOf(proceeds))
                    .divide(BigDecimal.valueOf(cost), 0, RoundingMode.DOWN).longValue();
            backInvest = proceeds - backShop;
        }
        String memo = v.coin().name() + " 매도";
        points.credit(memberId, Wallet.INVEST_ONLY, backInvest, PointReason.INVEST, key(v, "sell-invest"), null, memo);
        points.credit(memberId, Wallet.SHOP, backShop, PointReason.INVEST, key(v, "sell-shop"), null, memo);
        if (whole) {
            jdbc.update("DELETE FROM paper_position WHERE member_id = ? AND symbol = ?", memberId, v.coin().symbol());
        } else {
            jdbc.update("""
                    UPDATE paper_position SET quantity = quantity - ?, cost_invest = cost_invest - ?, cost_shop = cost_shop - ?, updated_at = ?
                    WHERE member_id = ? AND symbol = ?
                    """, qty, partInvest, partShop, Timestamp.from(Times.now(clock)), memberId, v.coin().symbol());
        }
        long tradeId = record(memberId, v, externalId, TradeJournal.Side.SELL, qty, price, fee, BigDecimal.valueOf(pnl));
        long reward = points.rewardTradeProfit(memberId, tradeId, BigDecimal.valueOf(pnl));
        return new Filled(false, tradeId, Side.SELL.name(), v.coin().symbol(), qty, price, fee, pnl, reward, null);
    }

    private long record(long memberId, Valid v, String externalId, TradeJournal.Side side, BigDecimal qty, BigDecimal price, long fee, BigDecimal pnl) {
        TradeJournal.Recorded r = journal.record(memberId, new TradeJournal.Input(externalId, TradeJournal.Mode.PAPER, v.coin().symbol(), v.coin().name(),
                side, qty, price, BigDecimal.valueOf(fee), pnl, STRATEGY, v.reason(), Times.now(clock), "UPBIT", "KRW"));
        return r.id();
    }

    private Filled duplicate(long memberId, Valid v) {
        Long tradeId = jdbc.query("SELECT trade_id FROM paper_order WHERE member_id = ? AND client_order_id = ?",
                rs -> rs.next() ? (Long) rs.getObject(1) : null, memberId, v.orderId());
        return new Filled(true, tradeId, v.side().name(), v.coin().symbol(), null, null, 0, null, 0, state(memberId));
    }

    // ---- 탈퇴 ----

    public void purge(long memberId) {
        jdbc.update("DELETE FROM paper_position WHERE member_id = ?", memberId);
        jdbc.update("DELETE FROM paper_order WHERE member_id = ?", memberId);
    }

    // ---- 내부 ----

    private record Valid(String orderId, PaperCoins.Coin coin, Side side, long amount, BigDecimal quantity, boolean all, boolean useShop, String reason) {}

    private Valid validate(Order in) {
        if (in == null) throw ApiException.badRequest("EMPTY", "주문 내용을 보내 주세요.");
        List<FieldErrorItem> errors = new ArrayList<>();
        String orderId = in.clientOrderId() == null ? "" : in.clientOrderId().strip();
        if (!ORDER_ID.matcher(orderId).matches()) errors.add(new FieldErrorItem("clientOrderId", "INVALID", "주문 번호가 올바르지 않아요. 화면을 새로 고쳐 주세요."));
        PaperCoins.Coin coin = PaperCoins.of(in.symbol()).orElse(null);
        if (coin == null) errors.add(new FieldErrorItem("symbol", "INVALID", "BTC·ETH·XRP·SOL·DOGE 가운데 하나를 골라 주세요."));
        Side side = null;
        try {
            side = Side.valueOf(in.side() == null ? "" : in.side().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            errors.add(new FieldErrorItem("side", "INVALID", "사기(BUY)·팔기(SELL) 가운데 하나예요."));
        }
        long amount = in.amount() == null ? 0 : in.amount();
        boolean all = Boolean.TRUE.equals(in.all());
        if (side == Side.BUY && (amount < props.minOrder() || amount > 1_000_000_000_000L)) {
            errors.add(new FieldErrorItem("amount", "INVALID", "한 번에 " + won(props.minOrder()) + " 이상 살 수 있어요."));
        }
        if (side == Side.SELL && !all && (in.quantity() == null || in.quantity().signum() <= 0)) {
            errors.add(new FieldErrorItem("quantity", "INVALID", "팔 수량을 적어 주세요."));
        }
        String reason = in.reason() == null ? "" : in.reason().strip().replaceAll("\\s+", " ");
        if (reason.length() > REASON_MAX) errors.add(new FieldErrorItem("reason", "TOO_LONG", "이유는 " + REASON_MAX + "자까지예요."));
        if (!errors.isEmpty()) throw ApiException.validation(errors);
        return new Valid(orderId, coin, side, amount, in.quantity(), all, Boolean.TRUE.equals(in.useShop()), reason);
    }

    private static String key(Valid v, String part) {
        return "paper:" + v.orderId() + ":" + part;
    }

    private static String won(long n) {
        return String.format("%,dP", n);
    }

    private boolean ordered(long memberId, String orderId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM paper_order WHERE member_id = ? AND client_order_id = ?)",
                Boolean.class, memberId, orderId));
    }
}
