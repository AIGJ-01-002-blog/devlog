package com.team.blog.paper.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import com.team.blog.point.application.PointLedger;
import com.team.blog.point.application.PointReason;
import com.team.blog.point.application.Wallet;
import com.team.blog.shared.error.ApiException;
import com.team.blog.shared.error.FieldErrorItem;
import com.team.blog.shared.error.NotFoundException;
import com.team.blog.shared.time.Times;
import com.team.blog.trade.application.BotCredits;
import com.team.blog.trade.application.TradeJournal;

/**
 * 회원 포인트 모의투자 (#267, 목표 #253). 업비트 공개 시세로 BTC·ETH·XRP·SOL·DOGE를 포인트로 사고판다(1P = 1원, 현물만).
 * <ul>
 *   <li>살 때는 투자 전용 포인트(가입 첫 지급)를 먼저 쓰고, 회원이 고르면 모자란 만큼 크레딧(상점 지갑)도 쓴다.</li>
 *   <li>보유분마다 어느 지갑에서 얼마를 냈는지(원금)를 들고 있다가, 팔 때 원금을 낸 지갑으로 돌려준다.
 *       수익은 전부 크레딧(상점 지갑)으로 들어간다(블로그 주인 결정: 가입 첫 지급만 투자 전용).
 *       손실은 원금을 낸 지갑에서 비율대로 빠진다.</li>
 *   <li>체결은 그 회원의 매매 일지에 PAPER(모의투자)·거래소 UPBIT·통화 KRW로 남는다.</li>
 *   <li>같은 주문 번호(clientOrderId)로 다시 보내면 한 번만 체결된다(두 번 누르기·재전송).</li>
 *   <li>지정가 주문(#332)은 살 때 낼 크레딧(수수료 포함)을, 팔 때 팔 수량을 묶어 두고, 시세가 지정가에 닿으면 체결한다.
 *       사는 쪽은 지정가와 시세 가운데 낮은 값, 파는 쪽은 높은 값으로 체결한다(업비트처럼 더 좋은 값이 있으면 그 값). 취소하면 묶은 것을 푼다.</li>
 * </ul>
 */
@Service
public class PaperTrading implements BotCredits {
    private static final Logger log = LoggerFactory.getLogger(PaperTrading.class);
    private static final Pattern ORDER_ID = Pattern.compile("[A-Za-z0-9-]{8,64}");
    private static final int REASON_MAX = 300;
    private static final BigDecimal MAX_PRICE = new BigDecimal("10000000000000");
    private static final String TOO_SMALL = "TOO_SMALL";
    private static final String INVALID = "INVALID";
    private static final String SYMBOL = "symbol";
    private static final String QUANTITY = "quantity";
    private static final String PRICE = "price";
    public static final String STRATEGY = "포인트 모의투자";
    /** 서버 모의 봇이 기본 크레딧으로 낸 체결의 매매 일지 전략 이름 (#331) */
    public static final String BOT_STRATEGY = "서버 모의 봇";

    public enum Side { BUY, SELL }

    /**
     * @param amount   살 때 쓸 포인트(수수료 포함)
     * @param quantity 팔 수량. all이면 무시하고 전부 판다
     * @param useShop  투자 전용 포인트가 모자라면 크레딧도 쓸지
     */
    public record Order(String clientOrderId, String symbol, String side, Long amount, BigDecimal quantity, Boolean all, Boolean useShop,
                        String reason) {}

    public record CoinQuote(String symbol, String name, BigDecimal price, BigDecimal changeRate) {}

    /** @param cost 남은 원금(두 지갑 합), value 지금 시세로 친 값, pnl 평가 손익, available 지정가 매도에 묶이지 않아 지금 팔 수 있는 수량 */
    public record Position(String symbol, String name, BigDecimal quantity, long cost, long costInvest, long costShop, BigDecimal avgPrice,
                           long value, long pnl, BigDecimal available) {}

    /**
     * 지정가 주문 (#332).
     *
     * @param price  지정가
     * @param locked 살 때 묶어 둔 크레딧(수수료 포함). 팔 때는 0
     * @param status OPEN(미체결)·FILLED(체결)·CANCELED(취소)
     * @param filledPrice 체결 가격. 미체결·취소면 null
     */
    public record LimitView(long id, String symbol, String name, String side, BigDecimal price, BigDecimal quantity, long locked, String status,
                            BigDecimal filledPrice, Instant createdAt, Instant closedAt) {}

    /** 최근 체결 한 건(시장가·지정가·봇). bot이면 서버 모의 봇이 낸 체결 */
    public record FillView(long tradeId, String symbol, String name, String side, BigDecimal quantity, BigDecimal price, BigDecimal fee, BigDecimal pnl,
                           boolean bot, Instant at) {}

    /**
     * @param locked      미체결 지정가 매수에 묶인 크레딧 합
     * @param orders      미체결 지정가 주문 전부와 최근 끝난 지정가 주문
     * @param fills       최근 체결
     * @param quotesError 시세를 받지 못했으면 이유(화면이 주문을 막는다)
     */
    public record State(List<CoinQuote> quotes, List<Position> positions, long invest, long shop, long holdings, long locked, BigDecimal feeRate,
                        long minOrder, List<LimitView> orders, List<FillView> fills, String quotesError) {}

    /** 지정가 주문 (#332). 가격과 수량을 정하고, 살 때는 기본 크레딧이 모자라면 useShop으로 번 크레딧도 쓴다 */
    public record LimitOrder(String clientOrderId, String symbol, String side, BigDecimal price, BigDecimal quantity, Boolean useShop, String reason) {}

    /** @param order 낸 주문. 시세가 이미 닿았으면 FILLED로 온다 */
    public record LimitPlaced(boolean duplicate, LimitView order, State state) {}

    /** @param tradeId 매매 일지 체결 번호, reward 이번 매도 수익으로 들어간 크레딧 */
    public record Filled(boolean duplicate, Long tradeId, String side, String symbol, BigDecimal quantity, BigDecimal price, long fee, Long pnl,
                         long reward, State state) {}

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    // 봇 주문은 봇 바퀴 트랜잭션과 따로 끝낸다: 크레딧이 모자라 거절돼도 봇 바퀴가 함께 되돌려지지 않는다
    private final TransactionTemplate botTx;
    private final PointLedger points;
    private final TradeJournal journal;
    private final Quotes quotes;
    private final PaperProperties props;
    private final Clock clock;

    public PaperTrading(JdbcTemplate jdbc, PlatformTransactionManager txm, PointLedger points, TradeJournal journal, Quotes quotes,
                        PaperProperties props, Clock clock) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(txm);
        this.botTx = new TransactionTemplate(txm);
        this.botTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
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
        // 화면을 열 때도 닿은 지정가를 체결해 둔다. 체결기를 기다리지 않고 바로 보인다
        matchMember(memberId, q);
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
            String symbol = (String) row.get(SYMBOL);
            PaperCoins.Coin coin = PaperCoins.of(symbol).orElse(new PaperCoins.Coin(symbol, symbol, 8));
            BigDecimal qty = (BigDecimal) row.get(QUANTITY);
            long ci = ((Number) row.get("cost_invest")).longValue();
            long cs = ((Number) row.get("cost_shop")).longValue();
            long cost = ci + cs;
            Quotes.Quote quote = q.get(symbol);
            long value = quote == null ? cost : qty.multiply(quote.price()).setScale(0, RoundingMode.DOWN).longValue();
            BigDecimal avg = qty.signum() == 0 ? BigDecimal.ZERO : BigDecimal.valueOf(cost).divide(qty, 2, RoundingMode.HALF_UP);
            BigDecimal free = qty.subtract(reserved(memberId, symbol, null)).max(BigDecimal.ZERO);
            positions.add(new Position(symbol, coin.name(), qty.stripTrailingZeros(), cost, ci, cs, avg, value, value - cost, free.stripTrailingZeros()));
            holdings += value;
        }
        PointLedger.Balance b = points.balance(memberId);
        Long locked = jdbc.queryForObject("""
                SELECT COALESCE(SUM(locked_invest + locked_shop), 0) FROM paper_limit_order WHERE member_id = ? AND status = 'OPEN' AND side = 'BUY'
                """, Long.class, memberId);
        return new State(list, positions, b.invest(), b.shop(), holdings, locked == null ? 0 : locked, props.feeRate(), props.minOrder(),
                limitViews(memberId), fills(memberId), error);
    }

    /** 미체결 지정가 전부와 최근 끝난 지정가 20건. 새 주문이 위로 */
    private List<LimitView> limitViews(long memberId) {
        return jdbc.query("""
                SELECT o.id, o.symbol, o.side, o.price, o.quantity, o.locked_invest + o.locked_shop AS locked, o.status, t.price AS filled_price,
                       o.created_at, o.closed_at
                FROM paper_limit_order o LEFT JOIN trade t ON t.id = o.trade_id
                WHERE o.member_id = ? AND (o.status = 'OPEN' OR o.id IN (
                    SELECT id FROM paper_limit_order WHERE member_id = ? AND status <> 'OPEN' ORDER BY id DESC LIMIT 20))
                ORDER BY o.id DESC
                """, (rs, i) -> {
            String symbol = rs.getString(SYMBOL);
            BigDecimal filledPrice = rs.getBigDecimal("filled_price");
            Timestamp closed = rs.getTimestamp("closed_at");
            return new LimitView(rs.getLong("id"), symbol, nameOf(symbol), rs.getString("side"), rs.getBigDecimal(PRICE).stripTrailingZeros(),
                    rs.getBigDecimal(QUANTITY).stripTrailingZeros(), rs.getLong("locked"), rs.getString("status"),
                    filledPrice == null ? null : filledPrice.stripTrailingZeros(), rs.getTimestamp("created_at").toInstant(),
                    closed == null ? null : closed.toInstant());
        }, memberId, memberId);
    }

    /** 최근 체결 30건: 내가 낸 것(시장가·지정가)과 서버 모의 봇이 낸 것 */
    private List<FillView> fills(long memberId) {
        return jdbc.query("""
                SELECT id, symbol, side, quantity, price, fee, realized_pnl, strategy, executed_at FROM trade
                WHERE member_id = ? AND mode = 'PAPER' AND exchange = 'UPBIT' AND strategy IN (?, ?)
                ORDER BY executed_at DESC, id DESC LIMIT 30
                """, (rs, i) -> {
            String symbol = rs.getString(SYMBOL);
            BigDecimal pnl = rs.getBigDecimal("realized_pnl");
            return new FillView(rs.getLong("id"), symbol, nameOf(symbol), rs.getString("side"), rs.getBigDecimal(QUANTITY).stripTrailingZeros(),
                    rs.getBigDecimal(PRICE).stripTrailingZeros(), rs.getBigDecimal("fee").stripTrailingZeros(), pnl == null ? null : pnl.stripTrailingZeros(),
                    BOT_STRATEGY.equals(rs.getString("strategy")), rs.getTimestamp("executed_at").toInstant());
        }, memberId, STRATEGY, BOT_STRATEGY);
    }

    private static String nameOf(String symbol) {
        return PaperCoins.of(symbol).map(PaperCoins.Coin::name).orElse(symbol);
    }

    // ---- 주문 ----

    public Filled order(long memberId, Order in) {
        requireEnabled();
        points.ensureWelcome(memberId);
        Valid v = validate(in);
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
        Optional<Filled> done = tx.execute(st -> {
            points.lock(memberId);
            if (ordered(memberId, v.orderId())) return Optional.empty();
            Filled f = v.side() == Side.BUY ? buy(memberId, v, price) : sell(memberId, v, price, reserved(memberId, v.coin().symbol(), null), true);
            // 이 주문을 끝냈다는 표시. 같은 주문 번호를 다시 받으면 이 줄을 보고 체결하지 않는다
            jdbc.update("INSERT INTO paper_order (member_id, client_order_id, trade_id, created_at) VALUES (?, ?, ?, ?)", memberId, v.orderId(),
                    f.tradeId(), Timestamp.from(Times.now(clock)));
            return Optional.of(f);
        });
        if (done.isEmpty()) return duplicate(memberId, v);
        Filled filled = done.get();
        Map<String, Quotes.Quote> q;
        try {
            q = quotes.latest();
        } catch (Quotes.QuotesUnavailable e) {
            q = Map.of();
        }
        return new Filled(false, filled.tradeId(), filled.side(), filled.symbol(), filled.quantity(), filled.price(), filled.fee(), filled.pnl(),
                filled.reward(), stateWith(memberId, q, null));
    }

    private Filled buy(long memberId, Valid v, BigDecimal price) {
        long amount = v.amount();
        long fee = BigDecimal.valueOf(amount).multiply(props.feeRate()).setScale(0, RoundingMode.CEILING).longValue();
        BigDecimal qty = BigDecimal.valueOf(amount - fee).divide(price, v.coin().scale(), RoundingMode.DOWN);
        if (qty.signum() <= 0) throw ApiException.badRequest(TOO_SMALL, "이 금액으로는 " + v.coin().name() + "을(를) 살 수 없어요. 금액을 늘려 주세요.");
        PointLedger.Balance b = points.balance(memberId);
        long fromInvest = Math.min(amount, b.invest());
        long fromShop = amount - fromInvest;
        if (fromShop > 0 && (!v.useShop() || b.shop() < fromShop)) {
            throw ApiException.conflict("POINTS_SHORT", v.useShop()
                    ? "크레딧이 모자라요. 기본 " + won(b.invest()) + " + 번 크레딧 " + won(b.shop()) + "까지 살 수 있어요."
                    : "기본 크레딧이 모자라요. 지금 " + won(b.invest()) + "이에요. 번 크레딧도 쓰려면 [크레딧도 쓰기]를 켜 주세요.",
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
        long tradeId = recordTrade(memberId, v, TradeJournal.Side.BUY, qty, price, fee, null);
        return new Filled(false, tradeId, Side.BUY.name(), v.coin().symbol(), qty, price, fee, null, 0, null);
    }

    /**
     * @param reserved 다른 지정가 매도에 묶여 있어 이번에 팔 수 없는 수량
     * @param checkMin 최소 주문 금액을 볼지. 지정가 체결은 낼 때 이미 봤으니 보지 않는다
     */
    private Filled sell(long memberId, Valid v, BigDecimal price, BigDecimal reserved, boolean checkMin) {
        Holding pos = holding(memberId, v);
        BigDecimal have = pos.quantity();
        BigDecimal free = have.subtract(reserved).max(BigDecimal.ZERO);
        BigDecimal qty = v.all() ? free : v.quantity().setScale(v.coin().scale(), RoundingMode.DOWN);
        requireSellable(v, qty, free, reserved);
        boolean whole = qty.compareTo(have) == 0;
        long ci = pos.costInvest();
        long cs = pos.costShop();
        BigDecimal ratio = whole ? BigDecimal.ONE : qty.divide(have, 12, RoundingMode.HALF_UP);
        long partInvest = whole ? ci : BigDecimal.valueOf(ci).multiply(ratio).setScale(0, RoundingMode.HALF_UP).longValue();
        long partShop = whole ? cs : BigDecimal.valueOf(cs).multiply(ratio).setScale(0, RoundingMode.HALF_UP).longValue();
        long gross = qty.multiply(price).setScale(0, RoundingMode.DOWN).longValue();
        if (checkMin && gross < props.minOrder() && qty.compareTo(free) != 0) {
            throw ApiException.badRequest(TOO_SMALL, minOrderText("팔") + " 남은 것을 모두 팔 때는 금액 제한이 없어요.");
        }
        long fee = BigDecimal.valueOf(gross).multiply(props.feeRate()).setScale(0, RoundingMode.CEILING).longValue();
        long proceeds = Math.max(0, gross - fee);
        long cost = partInvest + partShop;
        long pnl = proceeds - cost;
        long backShop = shopBack(partShop, proceeds, cost, pnl);
        long backInvest = pnl >= 0 ? partInvest : proceeds - backShop;
        String memo = v.coin().name() + " 매도";
        points.credit(memberId, Wallet.INVEST_ONLY, backInvest, PointReason.INVEST, key(v, "sell-invest"), null, memo);
        points.credit(memberId, Wallet.SHOP, backShop, PointReason.INVEST, key(v, "sell-shop"), null, memo);
        reducePosition(memberId, v, whole, qty, partInvest, partShop);
        long tradeId = recordTrade(memberId, v, TradeJournal.Side.SELL, qty, price, fee, BigDecimal.valueOf(pnl));
        long reward = points.creditTradeProfit(memberId, tradeId, pnl);
        return new Filled(false, tradeId, Side.SELL.name(), v.coin().symbol(), qty, price, fee, pnl, reward, null);
    }

    /** 팔 수량이 0이거나 팔 수 있는 것보다 많으면 거절한다 */
    private static void requireSellable(Valid v, BigDecimal qty, BigDecimal free, BigDecimal reserved) {
        if (qty.signum() <= 0) {
            throw ApiException.badRequest(TOO_SMALL, v.all() && reserved.signum() > 0 ? "지정가 매도에 묶이지 않은 수량이 없어요. 주문을 취소하면 풀려요." : "팔 수량을 적어 주세요.");
        }
        if (qty.compareTo(free) > 0) throw notEnough(v, free, reserved);
    }

    private static ApiException notEnough(Valid v, BigDecimal free, BigDecimal reserved) {
        return ApiException.conflict("NOT_ENOUGH", "팔 수 있는 " + v.coin().name() + "은(는) " + free.stripTrailingZeros().toPlainString() + "개예요."
                + (reserved.signum() > 0 ? " 지정가 매도에 " + reserved.stripTrailingZeros().toPlainString() + "개가 묶여 있어요." : ""));
    }

    /**
     * 판 돈 가운데 번 크레딧 지갑으로 돌려줄 몫. 이익이면 원금은 낸 지갑으로 돌려주고 수익은 따로 크레딧으로 넣는다(블로그 주인 결정).
     * 손실이면 원금을 낸 비율대로 나눠 뺀다
     */
    private static long shopBack(long partShop, long proceeds, long cost, long pnl) {
        if (pnl >= 0) return partShop;
        if (cost == 0) return 0;
        return BigDecimal.valueOf(partShop).multiply(BigDecimal.valueOf(proceeds)).divide(BigDecimal.valueOf(cost), 0, RoundingMode.DOWN).longValue();
    }

    /** 보유분 하나: 수량과 지갑별 남은 원금 */
    private record Holding(BigDecimal quantity, long costInvest, long costShop) {}

    /** 이 코인 보유분. 없으면 409 NO_POSITION */
    private Holding holding(long memberId, Valid v) {
        List<Holding> rows = jdbc.query("SELECT quantity, cost_invest, cost_shop FROM paper_position WHERE member_id = ? AND symbol = ?",
                (rs, i) -> new Holding(rs.getBigDecimal(1), rs.getLong(2), rs.getLong(3)), memberId, v.coin().symbol());
        if (rows.isEmpty()) throw ApiException.conflict("NO_POSITION", v.coin().name() + "을(를) 갖고 있지 않아요.");
        return rows.getFirst();
    }

    /** 판 만큼 보유분을 줄인다. 전부 팔았으면 줄을 지운다 */
    private void reducePosition(long memberId, Valid v, boolean whole, BigDecimal qty, long partInvest, long partShop) {
        if (whole) {
            jdbc.update("DELETE FROM paper_position WHERE member_id = ? AND symbol = ?", memberId, v.coin().symbol());
        } else {
            jdbc.update("""
                    UPDATE paper_position SET quantity = quantity - ?, cost_invest = cost_invest - ?, cost_shop = cost_shop - ?, updated_at = ?
                    WHERE member_id = ? AND symbol = ?
                    """, qty, partInvest, partShop, Timestamp.from(Times.now(clock)), memberId, v.coin().symbol());
        }
    }

    private long recordTrade(long memberId, Valid v, TradeJournal.Side side, BigDecimal qty, BigDecimal price, long fee, BigDecimal pnl) {
        String externalId = "paper-" + v.orderId();
        TradeJournal.Recorded r = journal.recordFill(memberId, new TradeJournal.Input(externalId, TradeJournal.Mode.PAPER, v.coin().symbol(), v.coin().name(),
                side, qty, price, BigDecimal.valueOf(fee), pnl, v.strategy(), v.reason(), Times.now(clock), "UPBIT", "KRW"));
        return r.id();
    }

    private Filled duplicate(long memberId, Valid v) {
        Long tradeId = jdbc.query("SELECT trade_id FROM paper_order WHERE member_id = ? AND client_order_id = ?",
                rs -> rs.next() ? (Long) rs.getObject(1) : null, memberId, v.orderId());
        return new Filled(true, tradeId, v.side().name(), v.coin().symbol(), null, null, 0, null, 0, state(memberId));
    }

    // ---- 지정가 (#332) ----

    /** 지정가 주문 한 줄 */
    private record LimitRow(long id, String clientOrderId, String symbol, Side side, BigDecimal price, BigDecimal quantity, long lockedInvest,
                            long lockedShop, String reason) {}

    private static final String LIMIT_COLUMNS = "id, client_order_id, symbol, side, price, quantity, locked_invest, locked_shop, reason";
    /** 지정가 주문 줄을 읽는 SELECT. 뒤에 조건을 붙여 쓴다 */
    private static final String SELECT_LIMIT = "SELECT " + LIMIT_COLUMNS + " FROM paper_limit_order WHERE ";

    private static LimitRow limitRow(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        return new LimitRow(rs.getLong(1), rs.getString(2), rs.getString(3), Side.valueOf(rs.getString(4)), rs.getBigDecimal(5), rs.getBigDecimal(6),
                rs.getLong(7), rs.getLong(8), rs.getString(9));
    }

    /**
     * 지정가로 낸다. 살 때는 지정가 × 수량 + 수수료만큼 크레딧을 묶고, 팔 때는 그 수량을 묶는다.
     * 시세가 이미 지정가에 닿아 있으면 바로 체결한다. 같은 주문 번호는 한 번만 받는다.
     */
    public LimitPlaced placeLimit(long memberId, LimitOrder in) {
        requireEnabled();
        points.ensureWelcome(memberId);
        Valid v = validateLimit(in);
        // 같은 주문 번호를 이미 받았으면 빈 값. 그때는 먼저 받은 주문을 보여 준다
        Optional<Long> placed = tx.execute(st -> {
            points.lock(memberId);
            if (!limitIds(memberId, v.orderId()).isEmpty()) return Optional.<Long>empty();
            return Optional.of(v.side() == Side.BUY ? lockBuy(memberId, v) : lockSell(memberId, v));
        });
        boolean duplicate = placed.isEmpty();
        long orderId = placed.orElseGet(() -> limitIds(memberId, v.orderId()).getFirst());
        Map<String, Quotes.Quote> q;
        try {
            q = quotes.latest();
        } catch (Quotes.QuotesUnavailable e) {
            q = Map.of();
        }
        matchMember(memberId, q);
        State state = stateWith(memberId, q, null);
        LimitView view = state.orders().stream().filter(o -> o.id() == orderId).findFirst().orElse(null);
        return new LimitPlaced(duplicate, view, state);
    }

    private List<Long> limitIds(long memberId, String clientOrderId) {
        return Objects.requireNonNullElse(
                jdbc.queryForList("SELECT id FROM paper_limit_order WHERE member_id = ? AND client_order_id = ?", Long.class, memberId, clientOrderId), List.of());
    }

    private long lockBuy(long memberId, Valid v) {
        long gross = v.quantity().multiply(v.price()).setScale(0, RoundingMode.CEILING).longValue();
        long fee = BigDecimal.valueOf(gross).multiply(props.feeRate()).setScale(0, RoundingMode.CEILING).longValue();
        long need = gross + fee;
        PointLedger.Balance b = points.balance(memberId);
        long fromInvest = Math.min(need, b.invest());
        long fromShop = need - fromInvest;
        if (fromShop > 0 && (!v.useShop() || b.shop() < fromShop)) {
            throw ApiException.conflict("POINTS_SHORT", v.useShop()
                    ? "크레딧이 모자라요. 기본 " + won(b.invest()) + " + 번 크레딧 " + won(b.shop()) + "까지 묶을 수 있어요."
                    : "기본 크레딧이 모자라요. 지금 " + won(b.invest()) + "이에요. 번 크레딧도 쓰려면 [크레딧도 쓰기]를 켜 주세요.",
                    Map.of("invest", b.invest(), "shop", b.shop(), "need", need));
        }
        long id = insertLimit(memberId, v, fromInvest, fromShop);
        String memo = v.coin().name() + " 지정가 매수 주문";
        if (fromInvest > 0) points.spend(memberId, Wallet.INVEST_ONLY, fromInvest, PointReason.INVEST, limitKey(v.orderId(), "lock-invest"), id, memo);
        if (fromShop > 0) points.spend(memberId, Wallet.SHOP, fromShop, PointReason.INVEST, limitKey(v.orderId(), "lock-shop"), id, memo);
        return id;
    }

    private long lockSell(long memberId, Valid v) {
        Holding pos = holding(memberId, v);
        BigDecimal reserved = reserved(memberId, v.coin().symbol(), null);
        BigDecimal free = pos.quantity().subtract(reserved).max(BigDecimal.ZERO);
        if (v.quantity().compareTo(free) > 0) throw notEnough(v, free, reserved);
        long gross = v.quantity().multiply(v.price()).setScale(0, RoundingMode.DOWN).longValue();
        if (gross < props.minOrder() && v.quantity().compareTo(free) != 0) {
            throw ApiException.badRequest(TOO_SMALL, minOrderText("팔") + " 남은 것을 모두 팔 때는 금액 제한이 없어요.");
        }
        return insertLimit(memberId, v, 0, 0);
    }

    private long insertLimit(long memberId, Valid v, long lockedInvest, long lockedShop) {
        Long id = jdbc.queryForObject("""
                INSERT INTO paper_limit_order (member_id, client_order_id, symbol, side, price, quantity, locked_invest, locked_shop, reason, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id
                """, Long.class, memberId, v.orderId(), v.coin().symbol(), v.side().name(), v.price(), v.quantity(), lockedInvest, lockedShop,
                v.reason(), Timestamp.from(Times.now(clock)));
        return Objects.requireNonNull(id, "RETURNING id가 비었습니다");
    }

    /** 미체결 지정가 매도에 묶인 수량. except 주문은 빼고 센다(그 주문을 체결할 때) */
    private BigDecimal reserved(long memberId, String symbol, Long except) {
        BigDecimal r = jdbc.queryForObject("""
                SELECT COALESCE(SUM(quantity), 0) FROM paper_limit_order
                WHERE member_id = ? AND symbol = ? AND status = 'OPEN' AND side = 'SELL' AND id <> ?
                """, BigDecimal.class, memberId, symbol, except == null ? -1L : except);
        return r == null ? BigDecimal.ZERO : r;
    }

    /** 미체결 지정가를 취소하고 묶은 크레딧을 낸 지갑으로 돌려준다 */
    public State cancelLimit(long memberId, long id) {
        requireEnabled();
        tx.executeWithoutResult(st -> {
            points.lock(memberId);
            List<String> status = jdbc.queryForList("SELECT status FROM paper_limit_order WHERE id = ? AND member_id = ? FOR UPDATE", String.class, id,
                    memberId);
            if (status.isEmpty()) throw new NotFoundException();
            if (!"OPEN".equals(status.getFirst())) throw ApiException.conflict("CLOSED", "이미 체결됐거나 취소된 주문이에요.");
            LimitRow o = jdbc.query(SELECT_LIMIT + "id = ?", PaperTrading::limitRow, id).getFirst();
            close(memberId, o, "취소", "cancel");
        });
        return state(memberId);
    }

    /** 묶은 크레딧을 돌려주고 주문을 취소로 닫는다 */
    private void close(long memberId, LimitRow o, String why, String part) {
        String memo = nameOf(o.symbol()) + " 지정가 주문 " + why;
        points.credit(memberId, Wallet.INVEST_ONLY, o.lockedInvest(), PointReason.INVEST, limitKey(o.clientOrderId(), part + "-invest"), o.id(), memo);
        points.credit(memberId, Wallet.SHOP, o.lockedShop(), PointReason.INVEST, limitKey(o.clientOrderId(), part + "-shop"), o.id(), memo);
        jdbc.update("UPDATE paper_limit_order SET status = 'CANCELED', closed_at = ? WHERE id = ?", Timestamp.from(Times.now(clock)), o.id());
    }

    /** 모든 회원의 미체결 지정가를 지금 시세에 맞춰 본다(체결기가 부른다). 미체결이 없으면 거래소도 부르지 않는다 */
    public int matchAll() {
        if (!enabled()) return 0;
        List<Long> members = Objects.requireNonNullElse(
                jdbc.queryForList("SELECT DISTINCT member_id FROM paper_limit_order WHERE status = 'OPEN'", Long.class), List.of());
        if (members.isEmpty()) return 0;
        Map<String, Quotes.Quote> q;
        try {
            q = quotes.latest();
        } catch (Quotes.QuotesUnavailable e) {
            return 0;
        }
        int n = 0;
        for (long m : members) n += matchMember(m, q);
        return n;
    }

    /** 한 회원의 미체결 지정가 가운데 시세가 닿은 것을 체결한다. 사는 쪽은 시세 ≤ 지정가, 파는 쪽은 시세 ≥ 지정가 */
    int matchMember(long memberId, Map<String, Quotes.Quote> q) {
        if (q.isEmpty()) return 0;
        int n = 0;
        for (LimitRow o : jdbc.query(SELECT_LIMIT + "member_id = ? AND status = 'OPEN' ORDER BY id", PaperTrading::limitRow, memberId)) {
            BigDecimal at = reachedPrice(o, q.get(o.symbol()));
            if (at != null && fillOrVoid(memberId, o, at)) n++;
        }
        return n;
    }

    /** 시세가 지정가에 닿았으면 체결할 값(사는 쪽은 둘 가운데 낮은 값, 파는 쪽은 높은 값), 아니면 null */
    private static BigDecimal reachedPrice(LimitRow o, Quotes.Quote quote) {
        if (quote == null) return null;
        BigDecimal market = quote.price();
        if (o.side() == Side.BUY) return market.compareTo(o.price()) <= 0 ? market.min(o.price()) : null;
        return market.compareTo(o.price()) >= 0 ? market.max(o.price()) : null;
    }

    /** 체결해 보고 체결했으면 true. 체결할 수 없게 된 주문(보유분이 사라짐 등)은 묶은 것을 풀고 닫는다. 다음 바퀴에 같은 실패를 되풀이하지 않는다 */
    private boolean fillOrVoid(long memberId, LimitRow o, BigDecimal at) {
        try {
            return Boolean.TRUE.equals(tx.execute(st -> fillLimit(memberId, o.id(), at)));
        } catch (ApiException e) {
            log.warn("지정가 주문 {} 체결 실패, 취소합니다: {}", o.id(), e.getMessage());
            tx.executeWithoutResult(st -> {
                points.lock(memberId);
                List<LimitRow> cur = jdbc.query(SELECT_LIMIT + "id = ? AND status = 'OPEN' FOR UPDATE", PaperTrading::limitRow, o.id());
                if (!cur.isEmpty()) close(memberId, cur.getFirst(), "자동 취소", "void");
            });
            return false;
        }
    }

    /** 체결 한 건. 다른 쪽에서 먼저 체결·취소했으면 false */
    private boolean fillLimit(long memberId, long id, BigDecimal at) {
        points.lock(memberId);
        List<LimitRow> rows = jdbc.query(SELECT_LIMIT + "id = ? AND status = 'OPEN' FOR UPDATE",
                PaperTrading::limitRow, id);
        if (rows.isEmpty()) return false;
        LimitRow o = rows.getFirst();
        PaperCoins.Coin coin = PaperCoins.of(o.symbol()).orElseThrow(() -> ApiException.conflict("NO_COIN", o.symbol() + "은(는) 더 이상 사고팔 수 없어요."));
        Valid v = new Valid(o.clientOrderId(), coin, o.side(), 0, o.quantity(), false, o.lockedShop() > 0, o.reason(), STRATEGY, o.price());
        long tradeId;
        if (o.side() == Side.BUY) {
            BigDecimal qty = o.quantity();
            long gross = qty.multiply(at).setScale(0, RoundingMode.CEILING).longValue();
            long fee = BigDecimal.valueOf(gross).multiply(props.feeRate()).setScale(0, RoundingMode.CEILING).longValue();
            // 지정가보다 싸게 샀으면 남은 몫을 돌려준다. 번 크레딧을 나중에 썼으니 그쪽부터 돌려준다
            long refund = Math.max(0, o.lockedInvest() + o.lockedShop() - gross - fee);
            long refundShop = Math.min(refund, o.lockedShop());
            long refundInvest = refund - refundShop;
            String memo = coin.name() + " 지정가 매수 남은 몫";
            points.credit(memberId, Wallet.SHOP, refundShop, PointReason.INVEST, limitKey(o.clientOrderId(), "refund-shop"), o.id(), memo);
            points.credit(memberId, Wallet.INVEST_ONLY, refundInvest, PointReason.INVEST, limitKey(o.clientOrderId(), "refund-invest"), o.id(), memo);
            jdbc.update("""
                    INSERT INTO paper_position (member_id, symbol, quantity, cost_invest, cost_shop, updated_at) VALUES (?, ?, ?, ?, ?, ?)
                    ON CONFLICT (member_id, symbol) DO UPDATE SET quantity = paper_position.quantity + EXCLUDED.quantity,
                        cost_invest = paper_position.cost_invest + EXCLUDED.cost_invest, cost_shop = paper_position.cost_shop + EXCLUDED.cost_shop,
                        updated_at = EXCLUDED.updated_at
                    """, memberId, o.symbol(), qty, o.lockedInvest() - refundInvest, o.lockedShop() - refundShop, Timestamp.from(Times.now(clock)));
            tradeId = recordTrade(memberId, v, TradeJournal.Side.BUY, qty, at, fee, null);
        } else {
            tradeId = sell(memberId, v, at, reserved(memberId, o.symbol(), o.id()), false).tradeId();
        }
        jdbc.update("UPDATE paper_limit_order SET status = 'FILLED', trade_id = ?, closed_at = ? WHERE id = ?", tradeId, Timestamp.from(Times.now(clock)),
                o.id());
        return true;
    }

    private static String limitKey(String clientOrderId, String part) {
        return "paper-limit:" + clientOrderId + ":" + part;
    }

    private Valid validateLimit(LimitOrder in) {
        if (in == null) throw ApiException.badRequest("EMPTY", "주문 내용을 보내 주세요.");
        List<FieldErrorItem> errors = new ArrayList<>();
        String orderId = orderId(in.clientOrderId(), errors);
        PaperCoins.Coin coin = coin(in.symbol(), errors);
        Side side = side(in.side(), errors);
        BigDecimal price = limitPrice(in.price(), errors);
        BigDecimal qty = limitQuantity(in.quantity(), coin, errors);
        if (side == Side.BUY && price != null && qty != null && qty.multiply(price).compareTo(BigDecimal.valueOf(props.minOrder())) < 0) {
            errors.add(new FieldErrorItem(QUANTITY, TOO_SMALL, minOrderText("살")));
        }
        String reason = reason(in.reason(), errors);
        if (!errors.isEmpty()) throw ApiException.validation(errors);
        return new Valid(orderId, coin, side, 0, qty, false, Boolean.TRUE.equals(in.useShop()), reason, STRATEGY, price);
    }

    // ---- 서버 모의 봇 (#331) ----

    @Override
    public long invest(long memberId) {
        // 가입 지급을 봇 바퀴 트랜잭션 밖에서 먼저 끝낸다. 그래야 따로 끝내는 봇 주문(botTx)이 그 지급을 읽는다
        botTx.executeWithoutResult(st -> points.ensureWelcome(memberId));
        return points.balance(memberId).invest();
    }

    @Override
    public Optional<BotCredits.Filled> buy(long memberId, String coin, long amount, String orderId, String reason) {
        return botOrder(memberId, new Order(orderId, coin, Side.BUY.name(), amount, null, false, false, reason));
    }

    @Override
    public BigDecimal held(long memberId, String coin) {
        List<BigDecimal> rows = Objects.requireNonNullElse(
                jdbc.queryForList("SELECT quantity FROM paper_position WHERE member_id = ? AND symbol = ?", BigDecimal.class, memberId, coin), List.of());
        BigDecimal have = rows.isEmpty() ? BigDecimal.ZERO : rows.getFirst();
        return have.subtract(reserved(memberId, coin, null)).max(BigDecimal.ZERO);
    }

    @Override
    public Optional<BotCredits.Filled> sell(long memberId, String coin, BigDecimal quantity, String orderId, String reason) {
        BigDecimal have = held(memberId, coin);
        if (have.signum() <= 0) return Optional.empty();
        boolean all = quantity.compareTo(have) >= 0;
        return botOrder(memberId, new Order(orderId, coin, Side.SELL.name(), null, all ? null : quantity, all, false, reason));
    }

    /** 봇 주문 하나. 기본 크레딧만 쓰고(상점 크레딧은 쓰지 않는다), 거절되면 빈 값. 이미 낸 주문이면 그때 체결을 돌려준다 */
    private Optional<BotCredits.Filled> botOrder(long memberId, Order in) {
        if (!enabled()) return Optional.empty();
        try {
            botTx.executeWithoutResult(st -> points.ensureWelcome(memberId));
            Valid base = validate(in);
            Valid v = new Valid(base.orderId(), base.coin(), base.side(), base.amount(), base.quantity(), base.all(), false, base.reason(), BOT_STRATEGY, null);
            if (ordered(memberId, v.orderId())) return done(memberId, v.orderId());
            Quotes.Quote quote = quotes.latest().get(v.coin().symbol());
            if (quote == null) return Optional.empty();
            Optional<Filled> placed = botTx.execute(st -> {
                points.lock(memberId);
                if (ordered(memberId, v.orderId())) return Optional.<Filled>empty();
                Filled r = v.side() == Side.BUY ? buy(memberId, v, quote.price())
                        : sell(memberId, v, quote.price(), reserved(memberId, v.coin().symbol(), null), true);
                jdbc.update("INSERT INTO paper_order (member_id, client_order_id, trade_id, created_at) VALUES (?, ?, ?, ?)", memberId, v.orderId(),
                        r.tradeId(), Timestamp.from(Times.now(clock)));
                return Optional.of(r);
            });
            if (placed.isEmpty()) return done(memberId, v.orderId());
            Filled f = placed.get();
            return Optional.of(new BotCredits.Filled(f.quantity(), f.price(), f.pnl() == null ? null : BigDecimal.valueOf(f.pnl())));
        } catch (ApiException | Quotes.QuotesUnavailable e) {
            return Optional.empty();
        }
    }

    /** 이미 체결한 봇 주문: 매매 일지에 남긴 수량·가격·손익 */
    private Optional<BotCredits.Filled> done(long memberId, String orderId) {
        return jdbc.query("""
                SELECT t.quantity, t.price, t.realized_pnl FROM paper_order o JOIN trade t ON t.id = o.trade_id
                WHERE o.member_id = ? AND o.client_order_id = ?
                """, rs -> rs.next() ? Optional.of(new BotCredits.Filled(rs.getBigDecimal(1), rs.getBigDecimal(2), rs.getBigDecimal(3)))
                : Optional.<BotCredits.Filled>empty(), memberId, orderId);
    }

    // ---- 탈퇴 ----

    public void purge(long memberId) {
        jdbc.update("DELETE FROM paper_position WHERE member_id = ?", memberId);
        jdbc.update("DELETE FROM paper_order WHERE member_id = ?", memberId);
        jdbc.update("DELETE FROM paper_limit_order WHERE member_id = ?", memberId);
    }

    // ---- 내부 ----

    /** @param price 지정가(#332). 시장가면 null */
    private record Valid(String orderId, PaperCoins.Coin coin, Side side, long amount, BigDecimal quantity, boolean all, boolean useShop, String reason,
                         String strategy, BigDecimal price) {}

    private Valid validate(Order in) {
        if (in == null) throw ApiException.badRequest("EMPTY", "주문 내용을 보내 주세요.");
        List<FieldErrorItem> errors = new ArrayList<>();
        String orderId = orderId(in.clientOrderId(), errors);
        PaperCoins.Coin coin = coin(in.symbol(), errors);
        Side side = side(in.side(), errors);
        long amount = in.amount() == null ? 0 : in.amount();
        boolean all = Boolean.TRUE.equals(in.all());
        if (side == Side.BUY && (amount < props.minOrder() || amount > 1_000_000_000_000L)) {
            errors.add(new FieldErrorItem("amount", INVALID, minOrderText("살")));
        }
        if (side == Side.SELL && !all && (in.quantity() == null || in.quantity().signum() <= 0)) {
            errors.add(new FieldErrorItem(QUANTITY, INVALID, "팔 수량을 적어 주세요."));
        }
        String reason = reason(in.reason(), errors);
        if (!errors.isEmpty()) throw ApiException.validation(errors);
        return new Valid(orderId, coin, side, amount, in.quantity(), all, Boolean.TRUE.equals(in.useShop()), reason, STRATEGY, null);
    }

    /** 지정가 가격. 올바르지 않으면 오류를 더하고 null */
    private static BigDecimal limitPrice(BigDecimal raw, List<FieldErrorItem> errors) {
        BigDecimal price = raw == null ? null : raw.stripTrailingZeros();
        if (price != null && price.signum() > 0 && price.scale() <= 8 && price.compareTo(MAX_PRICE) <= 0) return price;
        errors.add(new FieldErrorItem(PRICE, INVALID, "주문 가격을 적어 주세요."));
        return null;
    }

    /** 지정가 수량. 코인 소수 자리로 자르고, 0 이하이면 오류를 더하고 null */
    private static BigDecimal limitQuantity(BigDecimal raw, PaperCoins.Coin coin, List<FieldErrorItem> errors) {
        BigDecimal qty = raw == null || coin == null ? null : raw.setScale(coin.scale(), RoundingMode.DOWN);
        if (qty != null && qty.signum() > 0) return qty;
        errors.add(new FieldErrorItem(QUANTITY, INVALID, "주문 수량을 적어 주세요."));
        return null;
    }

    private static String orderId(String raw, List<FieldErrorItem> errors) {
        String orderId = raw == null ? "" : raw.strip();
        if (!ORDER_ID.matcher(orderId).matches()) errors.add(new FieldErrorItem("clientOrderId", INVALID, "주문 번호가 올바르지 않아요. 화면을 새로 고쳐 주세요."));
        return orderId;
    }

    private static PaperCoins.Coin coin(String symbol, List<FieldErrorItem> errors) {
        PaperCoins.Coin coin = PaperCoins.of(symbol).orElse(null);
        if (coin == null) errors.add(new FieldErrorItem(SYMBOL, INVALID, "BTC·ETH·XRP·SOL·DOGE 가운데 하나를 골라 주세요."));
        return coin;
    }

    private static String reason(String raw, List<FieldErrorItem> errors) {
        String reason = raw == null ? "" : raw.strip().replaceAll("\\s+", " ");
        if (reason.length() > REASON_MAX) errors.add(new FieldErrorItem("reason", "TOO_LONG", "이유는 " + REASON_MAX + "자까지예요."));
        return reason;
    }

    private static Side side(String side, List<FieldErrorItem> errors) {
        try {
            return Side.valueOf(side == null ? "" : side.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            errors.add(new FieldErrorItem("side", INVALID, "사기(BUY)·팔기(SELL) 가운데 하나예요."));
            return null;
        }
    }

    private static String key(Valid v, String part) {
        return "paper:" + v.orderId() + ":" + part;
    }

    /** "한 번에 5,000크레딧 이상 살 수 있어요." 같은 최소 주문 안내. verb는 "살"·"팔" */
    private String minOrderText(String verb) {
        return "한 번에 " + won(props.minOrder()) + " 이상 " + verb + " 수 있어요.";
    }

    private static String won(long n) {
        return String.format("%,d크레딧", n);
    }

    private boolean ordered(long memberId, String orderId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM paper_order WHERE member_id = ? AND client_order_id = ?)",
                Boolean.class, memberId, orderId));
    }
}
