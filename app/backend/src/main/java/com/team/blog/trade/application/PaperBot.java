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
 */
@Service
public class PaperBot {
    private static final Logger log = LoggerFactory.getLogger(PaperBot.class);
    public static final String PREFIX = "server-paper-";
    public static final String INTERVAL = "1h";
    private static final Duration STEP = Duration.ofHours(1);
    private static final int WINDOW = 5;
    private static final BigDecimal STOP_LOSS = new BigDecimal("0.03");
    private static final BigDecimal QTY_STEP = new BigDecimal("0.00000001");
    private static final MathContext MC = MathContext.DECIMAL64;

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
            String cross = above && !prevAbove ? "UP" : !above && prevAbove ? "DOWN" : null;
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
    private final Clock clock;
    private final JsonMapper json = JsonMapper.builder().build();

    public PaperBot(JdbcTemplate jdbc, PlatformTransactionManager txm, TradeCharts charts, TradeProperties props, JobLock lock, Clock clock) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(txm);
        this.charts = charts;
        this.props = props;
        this.lock = lock;
        this.clock = clock;
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
        if (members.isEmpty()) return 0;
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
            for (Book book : BOOKS) {
                ObjectNode acc = state.has(book.exchange().name()) ? (ObjectNode) state.get(book.exchange().name()) : fresh(book);
                List<Fill> fills = new ArrayList<>();
                Map<String, BigDecimal> last = new LinkedHashMap<>();
                for (Coin coin : candles.getOrDefault(book.exchange(), List.of())) {
                    if (coin.candles().isEmpty()) continue;
                    fills.addAll(trade(book, acc, coin));
                    last.put(coin.symbol(), coin.candles().getLast().c());
                }
                state.set(book.exchange().name(), acc);
                writeAccount(memberId, book, acc, last, now);
                for (Fill f : fills) writeFill(memberId, book, f, now);
            }
            jdbc.update("UPDATE trade_paper_bot SET state = ?::jsonb, updated_at = ? WHERE member_id = ?",
                    json.writeValueAsString(state), Timestamp.from(now), memberId);
            jdbc.update("""
                    INSERT INTO trade_bot_status (member_id, reported_at) VALUES (?, ?)
                    ON CONFLICT (member_id) DO UPDATE SET reported_at = GREATEST(trade_bot_status.reported_at, EXCLUDED.reported_at)
                    """, memberId, Timestamp.from(now));
        });
    }

    private ObjectNode fresh(Book book) {
        ObjectNode acc = json.createObjectNode();
        acc.put("cash", book.cash().toPlainString());
        acc.set("positions", json.createObjectNode());
        acc.set("seen", json.createObjectNode());
        return acc;
    }

    /** 한 종목: 받은 봉 전체로 규칙을 데우고, 지난번에 처리한 봉보다 뒤인 봉에서만 체결한다 */
    List<Fill> trade(Book book, ObjectNode acc, Coin coin) {
        ObjectNode positions = (ObjectNode) acc.get("positions");
        ObjectNode seen = (ObjectNode) acc.get("seen");
        long seenT = seen.path(coin.symbol()).asLong(0);
        Rule rule = new Rule(WINDOW, STOP_LOSS);
        List<Fill> out = new ArrayList<>();
        for (Candle c : coin.candles()) {
            JsonNode pos = positions.get(coin.symbol());
            BigDecimal qty = pos == null ? BigDecimal.ZERO : new BigDecimal(pos.path("quantity").asString("0"));
            BigDecimal avg = pos == null ? BigDecimal.ZERO : new BigDecimal(pos.path("avg_price").asString("0"));
            String signal = rule.onBar(c.c(), qty.signum() > 0, avg);
            if (c.t() <= seenT || signal == null) continue;
            Instant at = Instant.ofEpochMilli(c.t()).plus(STEP); // 봉이 닫힌 시각에 체결
            BigDecimal cash = new BigDecimal(acc.path("cash").asString("0"));
            if (signal.equals("BUY")) {
                BigDecimal spend = book.budget().min(cash);
                BigDecimal q = spend.divide(c.c().multiply(BigDecimal.ONE.add(book.feeRate())), MC).setScale(8, RoundingMode.FLOOR);
                BigDecimal cost = q.multiply(c.c());
                BigDecimal fee = book.money(cost.multiply(book.feeRate()));
                if (q.signum() <= 0 || cost.add(fee).compareTo(cash) > 0) continue;
                ObjectNode p = json.createObjectNode();
                p.put("quantity", q.toPlainString());
                p.put("avg_price", c.c().toPlainString());
                positions.set(coin.symbol(), p);
                acc.put("cash", cash.subtract(cost).subtract(fee).toPlainString());
                out.add(new Fill(id(book, coin.symbol(), c.t(), "BUY"), coin.symbol(), "BUY", q, c.c(), null,
                        "종가가 " + WINDOW + "개 1h봉 이동평균선 위로 올라섬", at));
            } else {
                BigDecimal proceeds = qty.multiply(c.c());
                BigDecimal fee = book.money(proceeds.multiply(book.feeRate()));
                BigDecimal pnl = book.money(proceeds.subtract(qty.multiply(avg)).subtract(fee));
                positions.remove(coin.symbol());
                acc.put("cash", cash.add(proceeds).subtract(fee).toPlainString());
                out.add(new Fill(id(book, coin.symbol(), c.t(), "SELL"), coin.symbol(), "SELL", qty, c.c(), pnl, signal.substring(5), at));
            }
        }
        if (!coin.candles().isEmpty()) seen.put(coin.symbol(), Math.max(seenT, coin.candles().getLast().t()));
        return out;
    }

    private static String id(Book book, String symbol, long t, String side) {
        return "fill:" + PREFIX + book.exchange().name().toLowerCase(java.util.Locale.ROOT) + ":" + symbol + ":" + t + ":" + side;
    }

    private void writeAccount(long memberId, Book book, ObjectNode acc, Map<String, BigDecimal> last, Instant now) {
        List<Map<String, Object>> positions = new ArrayList<>();
        BigDecimal unrealized = BigDecimal.ZERO;
        for (Map.Entry<String, JsonNode> e : acc.get("positions").properties()) {
            BigDecimal q = new BigDecimal(e.getValue().path("quantity").asString("0"));
            BigDecimal avg = new BigDecimal(e.getValue().path("avg_price").asString("0"));
            BigDecimal price = last.getOrDefault(e.getKey(), avg);
            BigDecimal pnl = book.money(price.subtract(avg).multiply(q));
            unrealized = unrealized.add(pnl);
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("symbol", e.getKey());
            p.put("side", "");
            p.put("quantity", q);
            p.put("avgPrice", avg);
            p.put("price", price);
            p.put("unrealizedPnl", pnl);
            positions.add(p);
        }
        List<Map<String, String>> rules = List.of(
                Map.of("label", "전략", "value", WINDOW + "봉 이동평균 교차"), Map.of("label", "봉", "value", INTERVAL),
                Map.of("label", "코인", "value", String.join(", ", TradeCharts.COINS)),
                Map.of("label", "한 번 금액", "value", book.budget().toPlainString() + " " + book.currency()),
                Map.of("label", "손절", "value", STOP_LOSS.movePointRight(2).stripTrailingZeros().toPlainString() + "%"),
                Map.of("label", "돌리는 곳", "value", "devlog 서버 (모의)"));
        jdbc.update("DELETE FROM trade_bot_account WHERE member_id = ? AND account = ?", memberId, book.account());
        jdbc.update("""
                INSERT INTO trade_bot_account (member_id, account, exchange, market, currency, cash, unrealized_pnl, positions, rules, reported_at)
                VALUES (?, ?, ?, 'SPOT', ?, ?, ?, ?::jsonb, ?::jsonb, ?)
                """, memberId, book.account(), book.exchange().name(), book.currency(), new BigDecimal(acc.path("cash").asString("0")), unrealized,
                json.writeValueAsString(positions), json.writeValueAsString(rules), Timestamp.from(now));
    }

    private void writeFill(long memberId, Book book, Fill f, Instant now) {
        if (f.at().isBefore(now.minus(TradeBotSim.KEEP))) return;
        jdbc.update("""
                INSERT INTO trade_bot_event (member_id, external_id, kind, account, symbol, side, quantity, price, currency, realized_pnl,
                    verdict, text, occurred_at, created_at)
                VALUES (?, ?, 'FILL', ?, ?, ?, ?, ?, ?, ?, '', ?, ?, ?)
                ON CONFLICT (member_id, external_id) DO NOTHING
                """, memberId, f.id(), book.account(), f.symbol(), f.side(), f.quantity(), f.price(), book.currency(), f.pnl(), f.text(),
                Timestamp.from(f.at()), Timestamp.from(now));
    }
}
