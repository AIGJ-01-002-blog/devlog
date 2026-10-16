package com.team.blog.trade.application;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import com.team.blog.shared.error.NotFoundException;
import com.team.blog.shared.scheduling.JobLock;
import com.team.blog.shared.time.Times;
import com.team.blog.trade.application.TradeCharts.Candle;
import com.team.blog.trade.application.TradeCharts.Coin;
import com.team.blog.trade.application.TradeCharts.Exchange;

/**
 * 서버가 돌리는 모의 봇 (spec 102). /trade에서 켠 회원마다 5분마다 1시간봉 닫힌 봉으로 이동평균 교차 모의 매매를 하고,
 * 결과를 spec 101 표에 계좌 server-paper-…로 남긴다. 회원이 자기 컴퓨터 봇을 함께 돌려도 계좌 이름이 달라 섞이지 않는다.
 * <p>모의만 한다: 공개 캔들(키 없음)만 쓰고 거래소 키를 받지 않는다. 실거래는 블로그 주인 맥에서만 돈다(2026-10-15 결정).
 * 규칙은 trade-bot(devlog_trade/strategy.py)의 MovingAverageCross와 같다. 처음 현금·한 번 금액·수수료도 봇 기본값과 같다.
 * <p>포인트가 켜진 서버에서는 두 계좌 모두 가상 돈 대신 회원의 기본 크레딧으로 사고판다(#331, #339, 블로그 주인 결정: 1크레딧 = 1원).
 * 바이낸스 계좌도 신호는 USDT 봉에서 받고 체결은 포인트 모의투자의 같은 코인 원화 시세로 해서 환율을 쓰지 않는다.
 * 체결은 포인트 모의투자 보유분과 매매 일지에 함께 남는다. 포인트가 꺼진 서버에서는 거래소 통화의 가상 돈으로 돈다.
 */
@Service
public class PaperBot {
    private static final Logger log = LoggerFactory.getLogger(PaperBot.class);
    public static final String PREFIX = "server-paper-";
    public static final String INTERVAL = "1h";
    private static final Duration STEP = Duration.ofHours(1);
    private static final int WINDOW = 5;
    private static final BigDecimal STOP_LOSS = new BigDecimal("0.03");
    private static final MathContext MC = MathContext.DECIMAL64;
    // 계좌 상태 JSON(trade_paper_bot.state)과 화면 규칙 칸의 키
    private static final String POSITIONS = "positions";
    private static final String QUANTITY = "quantity";
    private static final String AVG_PRICE = "avg_price";
    private static final String CREDIT = "credit";
    /** 신호를 낸 봉의 종가. 크레딧 체결가(원)와 봉(바이낸스는 USDT) 단위가 달라 손절은 이 값으로 본다 */
    private static final String REF_PRICE = "ref_price";
    /** 크레딧 계좌의 한 번 금액. 두 거래소 모두 같다 */
    static final long CREDIT_BUDGET = 100_000;

    /** 거래소마다 처음 현금·한 번 금액·수수료율. trade-bot exchanges.py 기본값과 같다 */
    record Book(Exchange exchange, String currency, BigDecimal cash, BigDecimal budget, BigDecimal feeRate) {
        String account() {
            return PREFIX + exchange.name().toLowerCase(java.util.Locale.ROOT) + "-spot-" + INTERVAL;
        }

        BigDecimal money(BigDecimal v) {
            return currency.equals("KRW") ? v.setScale(0, RoundingMode.HALF_UP) : v.setScale(8, RoundingMode.HALF_UP);
        }
    }

    static final List<Book> BOOKS = List.of(
            new Book(Exchange.BINANCE, "USDT", new BigDecimal("1000"), new BigDecimal("100"), new BigDecimal("0.001")),
            new Book(Exchange.UPBIT, "KRW", new BigDecimal("1000000"), new BigDecimal("100000"), new BigDecimal("0.0005")));

    /** 체결 하나 (spec 101 FILL 사건 모양) */
    record Fill(String id, String symbol, String side, BigDecimal quantity, BigDecimal price, BigDecimal pnl, String text, Instant at) {}

    /** 매매 규칙: 이동평균 교차 + 손절. 봉을 하나씩 넣으면 그 봉에서 낼 신호를 돌려준다 (strategy.py on_bar와 같다) */
    static final class Rule {
        private final List<BigDecimal> closes = new ArrayList<>();
        private final int window;
        private final BigDecimal stopLoss;

        Rule(int window, BigDecimal stopLoss) {
            this.window = window;
            this.stopLoss = stopLoss;
        }

        /** @return "BUY", "SELL:이유", 또는 null */
        String onBar(BigDecimal close, boolean holding, BigDecimal avgPrice) {
            boolean prevAbove = closes.size() >= window && closes.getLast().compareTo(avg()) > 0;
            closes.add(close);
            if (closes.size() <= window) return null;
            boolean above = close.compareTo(avg()) > 0;
            String cross = null;
            if (above && !prevAbove) cross = "UP";
            else if (!above && prevAbove) cross = "DOWN";
            if (holding && avgPrice.signum() > 0 && close.compareTo(avgPrice.multiply(BigDecimal.ONE.subtract(stopLoss))) <= 0) {
                return "SELL:손절선 -" + stopLoss.movePointRight(2).stripTrailingZeros().toPlainString() + "% 도달";
            }
            if (!holding && "UP".equals(cross)) return "BUY";
            if (holding && "DOWN".equals(cross)) return "SELL:종가가 " + window + "개 1h봉 이동평균선 아래로 내려감";
            return null;
        }

        private BigDecimal avg() {
            BigDecimal sum = BigDecimal.ZERO;
            for (BigDecimal c : closes.subList(closes.size() - window, closes.size())) sum = sum.add(c);
            return sum.divide(BigDecimal.valueOf(window), MC);
        }
    }

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final TradeCharts charts;
    private final TradeProperties props;
    private final JobLock lock;
    private final BotCredits credits;
    private final Clock clock;
    private final JsonMapper json = JsonMapper.builder().build();

    public PaperBot(JdbcTemplate jdbc, PlatformTransactionManager txm, TradeCharts charts, TradeProperties props, JobLock lock, Clock clock,
                    BotCredits credits) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(txm);
        this.charts = charts;
        this.props = props;
        this.lock = lock;
        this.clock = clock;
        this.credits = credits;
    }

    public record Switch(boolean enabled) {}

    public Switch get(long memberId) {
        if (!props.enabled()) throw new NotFoundException();
        Boolean on = jdbc.query("SELECT enabled FROM trade_paper_bot WHERE member_id = ?", rs -> rs.next() ? rs.getBoolean(1) : null, memberId);
        return new Switch(Boolean.TRUE.equals(on));
    }

    /** 켜면 바로 한 바퀴 돌려 화면이 곧장 채워진다. 끄면 다음 바퀴부터 돌지 않고 기록은 남는다 */
    public Switch set(long memberId, boolean enabled) {
        if (!props.enabled()) throw new NotFoundException();
        jdbc.update("""
                INSERT INTO trade_paper_bot (member_id, enabled, created_at, updated_at) VALUES (?, ?, ?, ?)
                ON CONFLICT (member_id) DO UPDATE SET enabled = EXCLUDED.enabled, updated_at = EXCLUDED.updated_at
                """, memberId, enabled, Timestamp.from(Times.now(clock)), Timestamp.from(Times.now(clock)));
        if (enabled) {
            try {
                run(memberId, candles());
            } catch (RuntimeException e) {
                log.warn("모의 봇 첫 바퀴를 돌리지 못함 (회원 {}). 다음 바퀴에 다시 해요: {}", memberId, e.getMessage());
            }
        }
        return new Switch(enabled);
    }

    @Scheduled(fixedDelayString = "${blog.trades.paper-bot-interval:5m}", initialDelayString = "${blog.trades.paper-bot-interval:5m}")
    public void scheduled() {
        if (!props.enabled()) return;
        lock.runExclusively("trade-paper-bot", Duration.ofMinutes(4), this::runAll);
    }

    /** @return 돌린 회원 수 */
    public int runAll() {
        List<Long> members = jdbc.queryForList("SELECT member_id FROM trade_paper_bot WHERE enabled ORDER BY member_id", Long.class);
        if (members == null || members.isEmpty()) return 0;
        Map<Exchange, List<Coin>> candles = candles(); // 모든 회원이 같은 시세를 나눠 쓴다
        int done = 0;
        for (long memberId : members) {
            try {
                run(memberId, candles);
                done++;
            } catch (RuntimeException e) {
                log.warn("모의 봇을 돌리지 못함 (회원 {}). 다음 바퀴에 다시 해요: {}", memberId, e.getMessage());
            }
        }
        return done;
    }

    private Map<Exchange, List<Coin>> candles() {
        Map<Exchange, List<Coin>> out = new LinkedHashMap<>();
        for (Book b : BOOKS) out.put(b.exchange(), charts.closed(b.exchange(), INTERVAL, STEP));
        return out;
    }

    void run(long memberId, Map<Exchange, List<Coin>> candles) {
        Instant now = Times.now(clock);
        tx.executeWithoutResult(st -> {
            // 예약 작업과 [켜기]가 겹쳐도 같은 봉을 두 번 거래하지 않게 회원마다 한 줄로 세운다
            jdbc.queryForObject("SELECT pg_advisory_xact_lock(?, ?)", Object.class, 102, (int) memberId);
            String raw = jdbc.query("SELECT state::text FROM trade_paper_bot WHERE member_id = ? AND enabled",
                    rs -> rs.next() ? rs.getString(1) : null, memberId);
            if (raw == null) return;
            ObjectNode state = (ObjectNode) json.readTree(raw);
            Map<String, BigDecimal> krw = new LinkedHashMap<>(); // 크레딧 계좌 평가용 원화 종가 (코인 → 원)
            for (Coin c : candles.getOrDefault(Exchange.UPBIT, List.of())) {
                if (!c.candles().isEmpty()) krw.put(c.coin(), c.candles().getLast().c());
            }
            for (Book book : BOOKS) runBook(memberId, book, state, candles.getOrDefault(book.exchange(), List.of()), krw, now);
            jdbc.update("UPDATE trade_paper_bot SET state = ?::jsonb, updated_at = ? WHERE member_id = ?",
                    json.writeValueAsString(state), Timestamp.from(now), memberId);
            jdbc.update("""
                    INSERT INTO trade_bot_status (member_id, reported_at) VALUES (?, ?)
                    ON CONFLICT (member_id) DO UPDATE SET reported_at = GREATEST(trade_bot_status.reported_at, EXCLUDED.reported_at)
                    """, memberId, Timestamp.from(now));
        });
    }

    /** 거래소 계좌 하나: 종목마다 규칙을 돌리고 계좌·체결을 남긴다. state의 그 거래소 칸을 고친다 */
    private void runBook(long memberId, Book book, ObjectNode state, List<Coin> coins, Map<String, BigDecimal> krw, Instant now) {
        boolean credit = usesCredits(book);
        JsonNode had = state.get(book.exchange().name());
        // 가상 돈으로 산 보유분을 크레딧 계좌로 가져가지 않는다: 모드가 바뀌면 새 계좌로 시작한다
        ObjectNode acc = had instanceof ObjectNode o && o.path(CREDIT).asBoolean(false) == credit ? o : fresh(book, credit);
        List<Fill> fills = new ArrayList<>();
        Map<String, BigDecimal> last = new LinkedHashMap<>();
        for (Coin coin : coins) {
            if (coin.candles().isEmpty()) continue;
            fills.addAll(credit ? creditTrade(memberId, book, acc, coin) : trade(book, acc, coin));
            BigDecimal price = credit ? krw.get(coin.coin()) : coin.candles().getLast().c();
            if (price != null) last.put(coin.symbol(), price);
        }
        state.set(book.exchange().name(), acc);
        if (credit) acc.put("cash", String.valueOf(credits.invest(memberId)));
        writeAccount(memberId, book, acc, last, now);
        for (Fill f : fills) writeFill(memberId, book, f, now);
    }

    private boolean usesCredits(Book book) {
        return credits != null && credits.enabled();
    }

    private ObjectNode fresh(Book book, boolean credit) {
        ObjectNode acc = json.createObjectNode();
        acc.put(CREDIT, credit);
        acc.put("cash", book.cash().toPlainString());
        acc.set(POSITIONS, json.createObjectNode());
        acc.set("seen", json.createObjectNode());
        return acc;
    }

    /** 한 종목: 받은 봉 전체로 규칙을 데우고, 지난번에 처리한 봉보다 뒤인 봉에서만 체결한다 */
    List<Fill> trade(Book book, ObjectNode acc, Coin coin) {
        ObjectNode positions = (ObjectNode) acc.get(POSITIONS);
        ObjectNode seen = (ObjectNode) acc.get("seen");
        long seenT = seen.path(coin.symbol()).asLong(0);
        Rule rule = new Rule(WINDOW, STOP_LOSS);
        List<Fill> out = new ArrayList<>();
        for (Candle c : coin.candles()) {
            JsonNode pos = positions.get(coin.symbol());
            BigDecimal qty = pos == null ? BigDecimal.ZERO : new BigDecimal(pos.path(QUANTITY).asString("0"));
            BigDecimal avg = pos == null ? BigDecimal.ZERO : new BigDecimal(pos.path(AVG_PRICE).asString("0"));
            String signal = rule.onBar(c.c(), qty.signum() > 0, avg);
            if (c.t() > seenT && signal != null) {
                Fill f = signal.equals("BUY") ? buy(book, acc, positions, coin, c) : sell(book, acc, positions, coin, c, signal.substring(5));
                if (f != null) out.add(f);
            }
        }
        if (!coin.candles().isEmpty()) seen.put(coin.symbol(), Math.max(seenT, coin.candles().getLast().t()));
        return out;
    }

    /**
     * 기본 크레딧으로 도는 원화 계좌 (#331). 켠 뒤 새로 닫힌 마지막 봉에서만 사고팔아 지난 봉을 되짚어 크레딧을 쓰지 않는다.
     * 봇은 자기가 산 수량만 판다. 내가 손으로 먼저 팔았으면 남은 만큼만 팔고, 하나도 없으면 봇 보유분을 지운다.
     */
    List<Fill> creditTrade(long memberId, Book book, ObjectNode acc, Coin coin) {
        ObjectNode positions = (ObjectNode) acc.get(POSITIONS);
        ObjectNode seen = (ObjectNode) acc.get("seen");
        long seenT = seen.path(coin.symbol()).asLong(0);
        Rule rule = new Rule(WINDOW, STOP_LOSS);
        Candle last = coin.candles().getLast();
        String signal = null;
        for (Candle c : coin.candles()) {
            JsonNode pos = positions.get(coin.symbol());
            BigDecimal ref = pos == null ? BigDecimal.ZERO : new BigDecimal(pos.path(REF_PRICE).asString(pos.path(AVG_PRICE).asString("0")));
            signal = rule.onBar(c.c(), pos != null, ref);
        }
        seen.put(coin.symbol(), Math.max(seenT, last.t()));
        if (last.t() <= seenT || signal == null) return List.of();
        Fill f = signal.equals("BUY") ? creditBuy(memberId, book, positions, coin, last) : creditSell(memberId, book, positions, coin, last, signal.substring(5));
        return f == null ? List.of() : List.of(f);
    }

    private Fill creditBuy(long memberId, Book book, ObjectNode positions, Coin coin, Candle c) {
        long amount = Math.min(CREDIT_BUDGET, credits.invest(memberId));
        if (amount <= 0) return null;
        String text = "종가가 " + WINDOW + "개 1h봉 이동평균선 위로 올라섬";
        return credits.buy(memberId, coin.coin(), amount, orderId(book, coin, c, "B"), text).map(f -> {
            ObjectNode p = json.createObjectNode();
            p.put(QUANTITY, f.quantity().toPlainString());
            p.put(AVG_PRICE, f.price().toPlainString());
            p.put(REF_PRICE, c.c().toPlainString());
            positions.set(coin.symbol(), p);
            return new Fill(id(book, coin.symbol(), c.t(), "BUY"), coin.symbol(), "BUY", f.quantity(), f.price(), null, text, closedAt(c));
        }).orElse(null);
    }

    private Fill creditSell(long memberId, Book book, ObjectNode positions, Coin coin, Candle c, String reason) {
        BigDecimal mine = new BigDecimal(positions.get(coin.symbol()).path(QUANTITY).asString("0"));
        BigDecimal held = credits.held(memberId, coin.coin());
        if (held.signum() <= 0) {
            positions.remove(coin.symbol()); // 내가 이미 다 팔았다
            return null;
        }
        return credits.sell(memberId, coin.coin(), mine.min(held), orderId(book, coin, c, "S"), reason).map(f -> {
            positions.remove(coin.symbol());
            return new Fill(id(book, coin.symbol(), c.t(), "SELL"), coin.symbol(), "SELL", f.quantity(), f.price(), f.pnl(), reason, closedAt(c));
        }).orElse(null);
    }

    /** 봇 주문 번호: 같은 봉의 같은 신호는 한 번만 체결된다. 업비트는 #331 때 번호 그대로, 다른 거래소는 이름을 붙여 겹치지 않게 한다 */
    private static String orderId(Book book, Coin coin, Candle c, String side) {
        String ex = book.exchange() == Exchange.UPBIT ? "" : book.exchange().name().toLowerCase(java.util.Locale.ROOT) + "-";
        return "bot-" + ex + coin.coin() + "-" + c.t() + "-" + side;
    }

    /** 한 번 금액만큼 산다. 현금이 모자라면 null */
    private Fill buy(Book book, ObjectNode acc, ObjectNode positions, Coin coin, Candle c) {
        BigDecimal cash = new BigDecimal(acc.path("cash").asString("0"));
        BigDecimal spend = book.budget().min(cash);
        BigDecimal q = spend.divide(c.c().multiply(BigDecimal.ONE.add(book.feeRate())), MC).setScale(8, RoundingMode.FLOOR);
        BigDecimal cost = q.multiply(c.c());
        BigDecimal fee = book.money(cost.multiply(book.feeRate()));
        if (q.signum() <= 0 || cost.add(fee).compareTo(cash) > 0) return null;
        ObjectNode p = json.createObjectNode();
        p.put(QUANTITY, q.toPlainString());
        p.put(AVG_PRICE, c.c().toPlainString());
        positions.set(coin.symbol(), p);
        acc.put("cash", cash.subtract(cost).subtract(fee).toPlainString());
        return new Fill(id(book, coin.symbol(), c.t(), "BUY"), coin.symbol(), "BUY", q, c.c(), null,
                "종가가 " + WINDOW + "개 1h봉 이동평균선 위로 올라섬", closedAt(c));
    }

    /** 가진 수량을 모두 판다 */
    private Fill sell(Book book, ObjectNode acc, ObjectNode positions, Coin coin, Candle c, String reason) {
        JsonNode pos = positions.get(coin.symbol());
        BigDecimal qty = new BigDecimal(pos.path(QUANTITY).asString("0"));
        BigDecimal avg = new BigDecimal(pos.path(AVG_PRICE).asString("0"));
        BigDecimal cash = new BigDecimal(acc.path("cash").asString("0"));
        BigDecimal proceeds = qty.multiply(c.c());
        BigDecimal fee = book.money(proceeds.multiply(book.feeRate()));
        BigDecimal pnl = book.money(proceeds.subtract(qty.multiply(avg)).subtract(fee));
        positions.remove(coin.symbol());
        acc.put("cash", cash.add(proceeds).subtract(fee).toPlainString());
        return new Fill(id(book, coin.symbol(), c.t(), "SELL"), coin.symbol(), "SELL", qty, c.c(), pnl, reason, closedAt(c));
    }

    /** 봉이 닫힌 시각에 체결한다 */
    private static Instant closedAt(Candle c) {
        return Instant.ofEpochMilli(c.t()).plus(STEP);
    }

    private static String id(Book book, String symbol, long t, String side) {
        return "fill:" + PREFIX + book.exchange().name().toLowerCase(java.util.Locale.ROOT) + ":" + symbol + ":" + t + ":" + side;
    }

    private void writeAccount(long memberId, Book book, ObjectNode acc, Map<String, BigDecimal> last, Instant now) {
        List<Map<String, Object>> positions = new ArrayList<>();
        BigDecimal unrealized = BigDecimal.ZERO;
        for (Map.Entry<String, JsonNode> e : acc.get(POSITIONS).properties()) {
            BigDecimal q = new BigDecimal(e.getValue().path(QUANTITY).asString("0"));
            BigDecimal avg = new BigDecimal(e.getValue().path(AVG_PRICE).asString("0"));
            BigDecimal price = last.getOrDefault(e.getKey(), avg);
            BigDecimal diff = price.subtract(avg).multiply(q);
            BigDecimal pnl = usesCredits(book) ? diff.setScale(0, RoundingMode.HALF_UP) : book.money(diff);
            unrealized = unrealized.add(pnl);
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("symbol", e.getKey());
            p.put("side", "");
            p.put(QUANTITY, q);
            p.put("avgPrice", avg);
            p.put("price", price);
            p.put("unrealizedPnl", pnl);
            positions.add(p);
        }
        List<Map<String, String>> rules = List.of(
                rule("전략", WINDOW + "봉 이동평균 교차"), rule("봉", INTERVAL),
                rule("코인", String.join(", ", TradeCharts.COINS)),
                rule("한 번 금액", usesCredits(book) ? CREDIT_BUDGET + " 기본 크레딧" : book.budget().toPlainString() + " " + book.currency()),
                rule("손절", STOP_LOSS.movePointRight(2).stripTrailingZeros().toPlainString() + "%"),
                rule("돌리는 곳", usesCredits(book) ? "devlog 서버 (모의 · 기본 크레딧)" : "devlog 서버 (모의)"));
        jdbc.update("DELETE FROM trade_bot_account WHERE member_id = ? AND account = ?", memberId, book.account());
        jdbc.update("""
                INSERT INTO trade_bot_account (member_id, account, exchange, market, currency, cash, unrealized_pnl, positions, rules, reported_at)
                VALUES (?, ?, ?, 'SPOT', ?, ?, ?, ?::jsonb, ?::jsonb, ?)
                """, memberId, book.account(), book.exchange().name(), currency(book), new BigDecimal(acc.path("cash").asString("0")), unrealized,
                json.writeValueAsString(positions), json.writeValueAsString(rules), Timestamp.from(now));
    }

    /** 크레딧 계좌는 거래소와 상관없이 원(1크레딧 = 1원)으로 남긴다 */
    private String currency(Book book) {
        return usesCredits(book) ? "KRW" : book.currency();
    }

    private static Map<String, String> rule(String label, String value) {
        return Map.of("label", label, "value", value);
    }

    private void writeFill(long memberId, Book book, Fill f, Instant now) {
        if (f.at().isBefore(now.minus(TradeBotSim.KEEP))) return;
        jdbc.update("""
                INSERT INTO trade_bot_event (member_id, external_id, kind, account, symbol, side, quantity, price, currency, realized_pnl,
                    verdict, text, occurred_at, created_at)
                VALUES (?, ?, 'FILL', ?, ?, ?, ?, ?, ?, ?, '', ?, ?, ?)
                ON CONFLICT (member_id, external_id) DO NOTHING
                """, memberId, f.id(), book.account(), f.symbol(), f.side(), f.quantity(), f.price(), currency(book), f.pnl(), f.text(),
                Timestamp.from(f.at()), Timestamp.from(now));
    }
}
