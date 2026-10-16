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
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import com.team.blog.shared.error.ApiException;
import com.team.blog.shared.error.NotFoundException;
import com.team.blog.shared.scheduling.JobLock;
import com.team.blog.shared.time.Times;
import com.team.blog.trade.application.TradeCharts.Candle;
import com.team.blog.trade.application.TradeCharts.Coin;
import com.team.blog.trade.application.TradeCharts.Exchange;

/**
 * 서버가 돌리는 모의 봇 (spec 102). /trade에서 켠 회원마다 5분마다 닫힌 봉으로 이동평균 교차 모의 매매를 하고,
 * 결과를 spec 101 표에 계좌 server-paper-…로 남긴다. 회원이 자기 컴퓨터 봇을 함께 돌려도 계좌 이름이 달라 섞이지 않는다.
 * <p>모의만 한다: 공개 캔들(키 없음)만 쓰고 거래소 키를 받지 않는다. 실거래는 블로그 주인 맥에서만 돈다(2026-10-15 결정).
 * 규칙은 trade-bot(devlog_trade/strategy.py)의 MovingAverageCross와 같다. 처음 현금·한 번 금액·수수료도 봇 기본값과 같다.
 * <p>코인·손절·익절·봉 간격·한 번 금액은 회원마다 고른다(#340, trade_paper_bot.rules). 바퀴는 5분마다 돌지만 고른 봉이 새로 닫혀야 거래한다.
 * <p>포인트가 켜진 서버에서는 모든 계좌가 가상 돈 대신 회원의 기본 크레딧으로 거래한다(#331, #352, 블로그 주인 결정: 1크레딧 = 1원).
 * 업비트(원화) 계좌는 포인트 모의투자 보유분으로 사고판다. 바이낸스 현물·선물(3배, 롱·숏) 계좌는 진입할 때 한 번 금액(크레딧)을
 * 그때 환율로 USDT로 바꿔 바이낸스 USDT 가격으로 거래하고, 정리할 때 USDT를 그때 환율로 크레딧으로 되돌린다.
 * 포인트가 꺼진 서버에서는 현물 두 계좌만 거래소 통화의 가상 돈으로 돈다.
 */
@Service
public class PaperBot {
    private static final Logger log = LoggerFactory.getLogger(PaperBot.class);
    public static final String PREFIX = "server-paper-";
    public static final String INTERVAL = "1h";
    /** 고를 수 있는 봉 간격과 한 봉의 길이. 바퀴가 5분마다라 그보다 짧은 봉은 두지 않는다 */
    static final Map<String, Duration> STEPS = new LinkedHashMap<>();
    static {
        STEPS.put("15m", Duration.ofMinutes(15));
        STEPS.put("1h", Duration.ofHours(1));
        STEPS.put("4h", Duration.ofHours(4));
        STEPS.put("1d", Duration.ofDays(1));
    }
    private static final int WINDOW = 5;
    private static final BigDecimal STOP_LOSS = new BigDecimal("0.03");
    private static final MathContext MC = MathContext.DECIMAL64;
    // 계좌 상태 JSON(trade_paper_bot.state)과 화면 규칙 칸의 키
    private static final String POSITIONS = "positions";
    private static final String QUANTITY = "quantity";
    private static final String AVG_PRICE = "avg_price";
    private static final String CREDIT = "credit";
    private static final String CASH = "cash";
    private static final String SEEN = "seen";
    private static final String LONG = "LONG";
    private static final String SHORT = "SHORT";
    /** 계좌 모드 값 */
    private static final String MODE_VIRTUAL = "virtual";
    private static final String MODE_CREDIT = "credit";
    private static final String MODE_USDT = "usdt";
    /** 계좌가 어떤 돈으로 도는지: 가상 돈, 포인트 모의투자 보유분(원화), 크레딧을 USDT로 바꾼 돈. 바뀌면 새 계좌로 시작한다 */
    private static final String MODE = "mode";
    private static final String SIDE = "side";
    /** USDT 계좌 보유분: 들어갈 때 맡긴 USDT(수수료 포함)와 그만큼 뺀 크레딧 */
    private static final String MARGIN = "margin";
    private static final String COST = "cost";
    /** USDT 계좌가 마지막으로 받은 환율(1 USDT = ?원). 환율을 못 받는 바퀴에도 정리는 이 값으로 한다 */
    private static final String RATE = "usdt_krw";
    /** 신호를 낸 봉의 종가. 크레딧 체결가(원)와 봉(바이낸스는 USDT) 단위가 달라 손절은 이 값으로 본다 */
    private static final String REF_PRICE = "ref_price";
    /** 한 번 금액의 기본값. 운영 값은 application.yml(blog.trades.bot-budget)에서 읽는다 */
    static final long CREDIT_BUDGET = PaperBotBudget.DEFAULT.budget();

    /**
     * 회원이 고른 기준 (#340). 손절·익절은 비율(0.03 = 3%), 익절 0은 쓰지 않음. 한 번 금액은 기본 크레딧(원) 단위이고,
     * 포인트가 꺼진 서버의 가상 돈 계좌는 원화면 그대로, USDT면 거래소 기본값을 같은 비율로 늘리거나 줄여 쓴다.
     */
    public record Settings(List<String> coins, BigDecimal stopLoss, BigDecimal takeProfit, String interval, long budget) {
        Duration step() {
            return STEPS.get(interval);
        }
    }

    public static final Settings DEFAULTS = new Settings(TradeCharts.COINS, STOP_LOSS, BigDecimal.ZERO, INTERVAL, CREDIT_BUDGET);

    /**
     * 거래소마다 처음 현금·한 번 금액·수수료율·시장·레버리지. trade-bot exchanges.py 기본값과 같다.
     * 선물은 바이낸스 현물 봉을 가격으로 쓴다(선물 가격과 거의 같다).
     */
    record Book(Exchange exchange, String currency, BigDecimal cash, BigDecimal budget, BigDecimal feeRate, String market, int leverage) {
        Book(Exchange exchange, String currency, BigDecimal cash, BigDecimal budget, BigDecimal feeRate) {
            this(exchange, currency, cash, budget, feeRate, "spot", 1);
        }

        boolean futures() {
            return market.equals("futures");
        }

        /** state 칸 이름. 현물은 #102 때 이름(BINANCE·UPBIT) 그대로 */
        String key() {
            return futures() ? exchange.name() + "_FUTURES" : exchange.name();
        }

        String account(String interval) {
            return accountPrefix() + interval;
        }

        String accountPrefix() {
            return PREFIX + exchange.name().toLowerCase(java.util.Locale.ROOT) + "-" + market + "-";
        }

        BigDecimal money(BigDecimal v) {
            return currency.equals("KRW") ? v.setScale(0, RoundingMode.HALF_UP) : v.setScale(8, RoundingMode.HALF_UP);
        }
    }

    static final List<Book> BOOKS = List.of(
            new Book(Exchange.BINANCE, "USDT", new BigDecimal("1000"), new BigDecimal("100"), new BigDecimal("0.001")),
            new Book(Exchange.UPBIT, "KRW", new BigDecimal("1000000"), new BigDecimal("100000"), new BigDecimal("0.0005")));
    /** 크레딧으로만 도는 바이낸스 USDT-M 선물 (#352). 가상 돈 서버에는 없다 */
    static final Book FUTURES = new Book(Exchange.BINANCE, "USDT", BigDecimal.ZERO, new BigDecimal("100"), new BigDecimal("0.0005"), "futures", 3);

    /** 체결 하나 (spec 101 FILL 사건 모양) */
    record Fill(String id, String symbol, String side, BigDecimal quantity, BigDecimal price, BigDecimal pnl, String text, Instant at) {}

    /** 매매 규칙: 이동평균 교차 + 손절. 봉을 하나씩 넣으면 그 봉에서 낼 신호를 돌려준다 (strategy.py on_bar와 같다) */
    static final class Rule {
        private final List<BigDecimal> closes = new ArrayList<>();
        private final int window;
        private final BigDecimal stopLoss;
        private final BigDecimal takeProfit;
        private final String interval;

        Rule(int window, BigDecimal stopLoss) {
            this(window, stopLoss, BigDecimal.ZERO, INTERVAL);
        }

        Rule(int window, BigDecimal stopLoss, BigDecimal takeProfit, String interval) {
            this.window = window;
            this.stopLoss = stopLoss;
            this.takeProfit = takeProfit;
            this.interval = interval;
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
                return "SELL:손절선 -" + percent(stopLoss) + "% 도달";
            }
            if (holding && takeProfit.signum() > 0 && avgPrice.signum() > 0
                    && close.compareTo(avgPrice.multiply(BigDecimal.ONE.add(takeProfit))) >= 0) {
                return "SELL:익절선 +" + percent(takeProfit) + "% 도달";
            }
            if (!holding && "UP".equals(cross)) return "BUY";
            if (holding && "DOWN".equals(cross)) return "SELL:종가가 " + window + "개 " + interval + "봉 이동평균선 아래로 내려감";
            return null;
        }

        /**
         * 선물: 위로 뚫으면 롱, 아래로 뚫으면 숏. 반대 신호·손절·익절이면 먼저 정리한다 (strategy.py on_bar_futures와 같다).
         * @param side 들고 있는 방향 LONG·SHORT, 없으면 null
         * @return "CLOSE:이유"·"LONG:이유"·"SHORT:이유" 차례
         */
        List<String> onBarFutures(BigDecimal close, String side, BigDecimal entry) {
            boolean prevAbove = closes.size() >= window && closes.getLast().compareTo(avg()) > 0;
            closes.add(close);
            if (closes.size() <= window) return List.of();
            boolean above = close.compareTo(avg()) > 0;
            boolean isLong = LONG.equals(side);
            boolean isShort = SHORT.equals(side);
            String stop = futuresExit(close, isLong, isShort, entry);
            if (stop != null) return List.of(stop);
            List<String> out = new ArrayList<>();
            String up = "종가가 " + window + "개 " + interval + "봉 이동평균선 위로 올라섬";
            String down = "종가가 " + window + "개 " + interval + "봉 이동평균선 아래로 내려감";
            if (above && !prevAbove) {
                if (isShort) out.add("CLOSE:" + up);
                if (!isLong) out.add("LONG:" + up);
            } else if (!above && prevAbove) {
                if (isLong) out.add("CLOSE:" + down);
                if (!isShort) out.add("SHORT:" + down);
            }
            return out;
        }

        /** 선물 손절·익절: 닿으면 "CLOSE:이유", 아니면 null */
        private String futuresExit(BigDecimal close, boolean isLong, boolean isShort, BigDecimal entry) {
            BigDecimal down = entry.multiply(BigDecimal.ONE.subtract(stopLoss));
            BigDecimal up = entry.multiply(BigDecimal.ONE.add(stopLoss));
            if (isLong && close.compareTo(down) <= 0 || isShort && close.compareTo(up) >= 0) {
                return "CLOSE:손절선 -" + percent(stopLoss) + "% 도달";
            }
            if (takeProfit.signum() <= 0) return null;
            BigDecimal longTarget = entry.multiply(BigDecimal.ONE.add(takeProfit));
            BigDecimal shortTarget = entry.multiply(BigDecimal.ONE.subtract(takeProfit));
            if (isLong && close.compareTo(longTarget) >= 0 || isShort && close.compareTo(shortTarget) <= 0) {
                return "CLOSE:익절선 +" + percent(takeProfit) + "% 도달";
            }
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
    private final PaperBotBudget budgets;
    private final JsonMapper json = JsonMapper.builder().build();

    public PaperBot(JdbcTemplate jdbc, PlatformTransactionManager txm, TradeCharts charts, TradeProperties props, JobLock lock, Clock clock,
                    BotCredits credits) {
        this(jdbc, txm, charts, props, lock, clock, credits, PaperBotBudget.DEFAULT);
    }

    @Autowired
    public PaperBot(JdbcTemplate jdbc, PlatformTransactionManager txm, TradeCharts charts, TradeProperties props, JobLock lock, Clock clock,
                    BotCredits credits, PaperBotBudget budgets) {
        this.budgets = budgets;
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(txm);
        this.charts = charts;
        this.props = props;
        this.lock = lock;
        this.clock = clock;
        this.credits = credits;
    }

    /** 화면에 주는 켜짐과 기준. 고를 수 있는 코인·봉 간격과 이 서버의 한 번 금액 범위도 함께 준다 */
    public record Switch(boolean enabled, Settings settings, List<String> coins, List<String> intervals, long budgetMin, long budgetMax) {
        Switch(boolean enabled, Settings settings, PaperBotBudget budgets) {
            this(enabled, settings, TradeCharts.COINS, List.copyOf(STEPS.keySet()), budgets.min(), budgets.max());
        }
    }

    /** 바꿀 것만 보낸다. enabled가 없으면 켜짐은 그대로, settings가 없으면 기준은 그대로다 */
    public record Change(Boolean enabled, Settings settings) {}

    public Switch get(long memberId) {
        if (!props.enabled()) throw new NotFoundException();
        List<Switch> rows = jdbc.query("SELECT enabled, rules::text FROM trade_paper_bot WHERE member_id = ?",
                (rs, i) -> new Switch(rs.getBoolean(1), settings(rs.getString(2)), budgets), memberId);
        return rows.isEmpty() ? new Switch(false, defaults(), budgets) : rows.getFirst();
    }

    /** 켜면 바로 한 바퀴 돌려 화면이 곧장 채워진다. 끄면 다음 바퀴부터 돌지 않고 기록은 남는다. 기준은 다음 바퀴부터 쓴다 */
    public Switch set(long memberId, Change change) {
        if (!props.enabled()) throw new NotFoundException();
        Settings next = change.settings() == null ? null : checked(change.settings(), budgets);
        Timestamp now = Timestamp.from(Times.now(clock));
        jdbc.update("""
                INSERT INTO trade_paper_bot (member_id, enabled, rules, created_at, updated_at) VALUES (?, ?, COALESCE(?::jsonb, '{}'), ?, ?)
                ON CONFLICT (member_id) DO UPDATE SET enabled = COALESCE(?::boolean, trade_paper_bot.enabled),
                    rules = COALESCE(?::jsonb, trade_paper_bot.rules), updated_at = EXCLUDED.updated_at
                """, memberId, Boolean.TRUE.equals(change.enabled()), next == null ? null : rulesJson(next), now, now,
                change.enabled(), next == null ? null : rulesJson(next));
        Switch out = get(memberId);
        if (out.enabled() && (Boolean.TRUE.equals(change.enabled()) || next != null)) {
            try {
                run(memberId, this::candles);
            } catch (RuntimeException e) {
                log.warn("모의 봇 첫 바퀴를 돌리지 못함 (회원 {}). 다음 바퀴에 다시 해요: {}", memberId, e.getMessage());
            }
        }
        return out;
    }

    /** 회원이 아직 고르지 않았을 때의 기준. 한 번 금액은 설정값 */
    Settings defaults() {
        return new Settings(DEFAULTS.coins(), DEFAULTS.stopLoss(), DEFAULTS.takeProfit(), DEFAULTS.interval(), budgets.budget());
    }

    static Settings checked(Settings in) {
        return checked(in, PaperBotBudget.DEFAULT);
    }

    /** 고른 기준을 확인한다. 코인은 COINS 순서로 맞추고 겹친 것은 하나로 */
    static Settings checked(Settings in, PaperBotBudget budgets) {
        if (in.coins() == null || in.coins().isEmpty()) throw ApiException.badRequest("INVALID_COINS", "코인을 하나 이상 골라 주세요.");
        for (String c : in.coins()) {
            if (!TradeCharts.COINS.contains(c)) throw ApiException.badRequest("INVALID_COINS", "코인은 " + String.join(", ", TradeCharts.COINS) + " 중에서 골라요.");
        }
        List<String> coins = TradeCharts.COINS.stream().filter(in.coins()::contains).toList();
        BigDecimal sl = in.stopLoss();
        if (sl == null || sl.compareTo(new BigDecimal("0.005")) < 0 || sl.compareTo(new BigDecimal("0.5")) > 0) {
            throw ApiException.badRequest("INVALID_STOP_LOSS", "손절은 0.5%에서 50% 사이로 정해 주세요.");
        }
        BigDecimal tp = in.takeProfit() == null ? BigDecimal.ZERO : in.takeProfit();
        if (tp.signum() < 0 || (tp.signum() > 0 && tp.compareTo(new BigDecimal("0.005")) < 0) || tp.compareTo(BigDecimal.ONE) > 0) {
            throw ApiException.badRequest("INVALID_TAKE_PROFIT", "익절은 0(쓰지 않음)이나 0.5%에서 100% 사이로 정해 주세요.");
        }
        if (!STEPS.containsKey(in.interval())) throw ApiException.badRequest("INVALID_INTERVAL", "봉은 " + String.join(", ", STEPS.keySet()) + " 중 하나예요.");
        if (in.budget() < budgets.min() || in.budget() > budgets.max()) {
            throw ApiException.badRequest("INVALID_BUDGET", "한 번 금액은 " + budgets.min() + "에서 " + budgets.max() + " 크레딧 사이로 정해 주세요.");
        }
        return new Settings(coins, sl.stripTrailingZeros(), tp.stripTrailingZeros(), in.interval(), in.budget());
    }

    private String rulesJson(Settings s) {
        ObjectNode n = json.createObjectNode();
        n.set("coins", json.valueToTree(s.coins()));
        n.put("stop_loss", s.stopLoss().toPlainString());
        n.put("take_profit", s.takeProfit().toPlainString());
        n.put("interval", s.interval());
        n.put("budget", s.budget());
        return json.writeValueAsString(n);
    }

    /** 저장된 기준. 비었거나 잘못된 칸은 기본값으로. 운영 범위가 바뀌어 벗어난 한 번 금액은 새 범위 끝으로 맞추고 나머지 기준은 그대로 둔다 */
    Settings settings(String raw) {
        if (raw == null || raw.isBlank()) return defaults();
        JsonNode n = json.readTree(raw);
        List<String> coins = new ArrayList<>();
        for (JsonNode c : n.path("coins")) {
            if (TradeCharts.COINS.contains(c.asString())) coins.add(c.asString());
        }
        String interval = n.path("interval").asString(INTERVAL);
        try {
            return checked(new Settings(coins.isEmpty() ? DEFAULTS.coins() : coins,
                    new BigDecimal(n.path("stop_loss").asString(STOP_LOSS.toPlainString())),
                    new BigDecimal(n.path("take_profit").asString("0")),
                    STEPS.containsKey(interval) ? interval : INTERVAL, Math.clamp(n.path("budget").asLong(budgets.budget()), budgets.min(), budgets.max())), budgets);
        } catch (RuntimeException e) {
            return defaults();
        }
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
        Map<String, Map<Exchange, List<Coin>>> shared = new java.util.HashMap<>(); // 같은 봉 간격을 고른 회원은 같은 시세를 나눠 쓴다
        Function<String, Map<Exchange, List<Coin>>> candles = interval -> shared.computeIfAbsent(interval, this::candles);
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

    private Map<Exchange, List<Coin>> candles(String interval) {
        Map<Exchange, List<Coin>> out = new LinkedHashMap<>();
        for (Book b : BOOKS) out.put(b.exchange(), charts.closed(b.exchange(), interval, STEPS.get(interval)));
        return out;
    }

    void run(long memberId, Function<String, Map<Exchange, List<Coin>>> candlesOf) {
        Instant now = Times.now(clock);
        tx.executeWithoutResult(st -> {
            // 예약 작업과 [켜기]가 겹쳐도 같은 봉을 두 번 거래하지 않게 회원마다 한 줄로 세운다
            jdbc.queryForObject("SELECT pg_advisory_xact_lock(?, ?)", Object.class, 102, (int) memberId);
            String[] row = jdbc.query("SELECT state::text, rules::text FROM trade_paper_bot WHERE member_id = ? AND enabled",
                    rs -> rs.next() ? new String[] {rs.getString(1), rs.getString(2)} : null, memberId);
            if (row == null) return;
            ObjectNode state = (ObjectNode) json.readTree(row[0]);
            Settings s = settings(row[1]);
            Map<Exchange, List<Coin>> candles = candlesOf.apply(s.interval());
            for (Book book : books()) runBook(memberId, book, s, state, candles.getOrDefault(book.exchange(), List.of()), now);
            jdbc.update("UPDATE trade_paper_bot SET state = ?::jsonb, updated_at = ? WHERE member_id = ?",
                    json.writeValueAsString(state), Timestamp.from(now), memberId);
            jdbc.update("""
                    INSERT INTO trade_bot_status (member_id, reported_at) VALUES (?, ?)
                    ON CONFLICT (member_id) DO UPDATE SET reported_at = GREATEST(trade_bot_status.reported_at, EXCLUDED.reported_at)
                    """, memberId, Timestamp.from(now));
        });
    }

    /** 포인트가 켜진 서버에는 선물 계좌가 더 있다 */
    private List<Book> books() {
        return usesCredits() ? List.of(BOOKS.get(0), FUTURES, BOOKS.get(1)) : BOOKS;
    }

    /** 거래소 계좌 하나: 종목마다 규칙을 돌리고 계좌·체결을 남긴다. state의 그 계좌 칸을 고친다 */
    private void runBook(long memberId, Book book, Settings s, ObjectNode state, List<Coin> coins, Instant now) {
        String mode = mode(book);
        JsonNode had = state.get(book.key());
        // 가상 돈으로 산 보유분을 크레딧 계좌로 가져가지 않는다: 모드가 바뀌면 새 계좌로 시작한다
        ObjectNode acc = had instanceof ObjectNode o && mode.equals(modeOf(o)) ? o : fresh(book, mode);
        BigDecimal rate = mode.equals(MODE_USDT) ? usdtRate(acc) : null;
        List<Fill> fills = new ArrayList<>();
        Map<String, BigDecimal> last = new LinkedHashMap<>();
        for (Coin coin : coins) {
            // 고른 코인과, 고르지 않았어도 아직 들고 있는 코인(팔기만 한다)
            if (coin.candles().isEmpty() || !(s.coins().contains(coin.coin()) || acc.path(POSITIONS).has(coin.symbol()))) continue;
            fills.addAll(switch (mode) {
                case MODE_CREDIT -> creditTrade(memberId, book, s, acc, coin);
                case MODE_USDT -> usdtTrade(memberId, book, s, acc, coin, rate);
                default -> trade(book, s, acc, coin);
            });
            last.put(coin.symbol(), coin.candles().getLast().c());
        }
        state.set(book.key(), acc);
        if (mode.equals(MODE_CREDIT)) acc.put(CASH, String.valueOf(credits.invest(memberId)));
        // USDT 계좌의 현금: 지금 기본 크레딧을 지금 환율로 바꾸면 되는 USDT
        if (mode.equals(MODE_USDT)) acc.put(CASH, rate == null ? "0" : usdt(BigDecimal.valueOf(credits.invest(memberId)), rate).toPlainString());
        writeAccount(memberId, book, s, acc, last, rate, now);
        for (Fill f : fills) writeFill(memberId, book, s, f, now);
    }

    /** 지금 환율. 못 받으면 계좌에 남은 마지막 환율, 그것도 없으면 null */
    private BigDecimal usdtRate(ObjectNode acc) {
        BigDecimal rate = credits.usdtKrw().orElse(null);
        if (rate != null) acc.put(RATE, rate.toPlainString());
        else if (acc.hasNonNull(RATE)) rate = new BigDecimal(acc.path(RATE).asString());
        return rate;
    }

    private boolean usesCredits() {
        return credits != null && credits.enabled();
    }

    /** virtual: 가상 돈, credit: 포인트 모의투자 보유분(업비트), usdt: 크레딧을 USDT로 바꾼 돈(바이낸스) */
    private String mode(Book book) {
        if (!usesCredits()) return MODE_VIRTUAL;
        return book.exchange() == Exchange.UPBIT ? MODE_CREDIT : MODE_USDT;
    }

    /** #352 전 상태는 mode 없이 credit 참·거짓만 있다 */
    private static String modeOf(JsonNode acc) {
        if (acc.hasNonNull(MODE)) return acc.path(MODE).asString();
        return acc.path(CREDIT).asBoolean(false) ? MODE_CREDIT : MODE_VIRTUAL;
    }

    private ObjectNode fresh(Book book, String mode) {
        ObjectNode acc = json.createObjectNode();
        acc.put(MODE, mode);
        acc.put(CREDIT, !mode.equals(MODE_VIRTUAL));
        acc.put(CASH, book.cash().toPlainString());
        acc.set(POSITIONS, json.createObjectNode());
        acc.set(SEEN, json.createObjectNode());
        return acc;
    }

    /** 한 종목: 받은 봉 전체로 규칙을 데우고, 지난번에 처리한 봉보다 뒤인 봉에서만 체결한다 */
    List<Fill> trade(Book book, ObjectNode acc, Coin coin) {
        return trade(book, DEFAULTS, acc, coin);
    }

    List<Fill> trade(Book book, Settings s, ObjectNode acc, Coin coin) {
        ObjectNode positions = (ObjectNode) acc.get(POSITIONS);
        ObjectNode seen = (ObjectNode) acc.get(SEEN);
        long seenT = seen.path(coin.symbol()).asLong(0);
        boolean canBuy = s.coins().contains(coin.coin());
        Rule rule = rule(s);
        List<Fill> out = new ArrayList<>();
        for (Candle c : coin.candles()) {
            JsonNode pos = positions.get(coin.symbol());
            String signal = rule.onBar(c.c(), decimal(pos, QUANTITY).signum() > 0, decimal(pos, AVG_PRICE));
            if (c.t() > seenT && allowed(signal, canBuy)) {
                Fill f = signal.equals("BUY") ? buy(book, s, acc, positions, coin, c) : sell(book, s, acc, positions, coin, c, signal.substring(5));
                if (f != null) out.add(f);
            }
        }
        if (!coin.candles().isEmpty()) seen.put(coin.symbol(), Math.max(seenT, coin.candles().getLast().t()));
        return out;
    }

    /** 보유분 칸의 숫자. 보유분이 없으면 0 */
    private static BigDecimal decimal(JsonNode pos, String field) {
        return pos == null ? BigDecimal.ZERO : new BigDecimal(pos.path(field).asString("0"));
    }

    /** 신호가 있고, 사는 신호면 고른 코인일 때만 따른다(고르지 않은 코인은 팔기만 한다) */
    private static boolean allowed(String signal, boolean canBuy) {
        return signal != null && (canBuy || !signal.equals("BUY"));
    }

    /**
     * 기본 크레딧으로 도는 원화 계좌 (#331). 켠 뒤 새로 닫힌 마지막 봉에서만 사고팔아 지난 봉을 되짚어 크레딧을 쓰지 않는다.
     * 봇은 자기가 산 수량만 판다. 내가 손으로 먼저 팔았으면 남은 만큼만 팔고, 하나도 없으면 봇 보유분을 지운다.
     */
    List<Fill> creditTrade(long memberId, Book book, ObjectNode acc, Coin coin) {
        return creditTrade(memberId, book, DEFAULTS, acc, coin);
    }

    List<Fill> creditTrade(long memberId, Book book, Settings s, ObjectNode acc, Coin coin) {
        ObjectNode positions = (ObjectNode) acc.get(POSITIONS);
        ObjectNode seen = (ObjectNode) acc.get(SEEN);
        long seenT = seen.path(coin.symbol()).asLong(0);
        Candle last = coin.candles().getLast();
        String signal = lastSignal(rule(s), positions, coin);
        if (last.t() <= seenT) return List.of();
        if (signal == null || (signal.equals("BUY") && !s.coins().contains(coin.coin()))) {
            seen.put(coin.symbol(), last.t());
            return List.of();
        }
        boolean held = positions.has(coin.symbol());
        Fill f = signal.equals("BUY") ? creditBuy(memberId, book, s, positions, coin, last) : creditSell(memberId, book, s, positions, coin, last, signal.substring(5));
        // 주문이 실패했으면(시세를 못 받음 등) 이 봉을 끝냈다고 적지 않아 다음 바퀴에 같은 신호를 다시 낸다.
        // 주문 번호가 봉마다 같아 두 번 체결되지는 않는다
        if (f != null || held != positions.has(coin.symbol())) seen.put(coin.symbol(), last.t());
        return f == null ? List.of() : List.of(f);
    }

    /** 받은 봉 전체로 규칙을 데우고 마지막 봉의 신호를 돌려준다. 기준값은 봇이 산 봉의 종가(없으면 평균 단가) */
    private static String lastSignal(Rule rule, ObjectNode positions, Coin coin) {
        String signal = null;
        for (Candle c : coin.candles()) {
            JsonNode pos = positions.get(coin.symbol());
            BigDecimal ref = pos == null ? BigDecimal.ZERO : new BigDecimal(pos.path(REF_PRICE).asString(pos.path(AVG_PRICE).asString("0")));
            signal = rule.onBar(c.c(), pos != null, ref);
        }
        return signal;
    }

    private Fill creditBuy(long memberId, Book book, Settings s, ObjectNode positions, Coin coin, Candle c) {
        long amount = Math.min(s.budget(), credits.invest(memberId));
        if (amount <= 0) return null;
        String text = buyText(s);
        return credits.buy(memberId, coin.coin(), amount, orderId(book, coin, c, "B"), text).map(f -> {
            ObjectNode p = json.createObjectNode();
            p.put(QUANTITY, f.quantity().toPlainString());
            p.put(AVG_PRICE, f.price().toPlainString());
            p.put(REF_PRICE, c.c().toPlainString());
            positions.set(coin.symbol(), p);
            return new Fill(id(book, coin.symbol(), c.t(), "BUY"), coin.symbol(), "BUY", f.quantity(), f.price(), null, text, closedAt(c, s));
        }).orElse(null);
    }

    private Fill creditSell(long memberId, Book book, Settings s, ObjectNode positions, Coin coin, Candle c, String reason) {
        BigDecimal mine = new BigDecimal(positions.get(coin.symbol()).path(QUANTITY).asString("0"));
        BigDecimal held = credits.held(memberId, coin.coin());
        if (held.signum() <= 0) {
            positions.remove(coin.symbol()); // 내가 이미 다 팔았다
            return null;
        }
        return credits.sell(memberId, coin.coin(), mine.min(held), orderId(book, coin, c, "S"), reason).map(f -> {
            positions.remove(coin.symbol());
            return new Fill(id(book, coin.symbol(), c.t(), "SELL"), coin.symbol(), "SELL", f.quantity(), f.price(), f.pnl(), reason, closedAt(c, s));
        }).orElse(null);
    }

    /**
     * 크레딧을 USDT로 바꿔 도는 바이낸스 현물·선물 계좌 (#352). 크레딧 계좌처럼 새로 닫힌 마지막 봉에서만 거래한다.
     * 진입: 한 번 금액(크레딧)을 그때 환율로 USDT로 바꿔 맡기고(margin), 레버리지만큼 산다. 수수료는 맡긴 돈에서 낸다.
     * 정리: 맡긴 돈 + 손익 - 수수료를 그때 환율로 크레딧으로 되돌린다(잃어도 맡긴 돈까지만).
     * @param rate 1 USDT가 몇 크레딧인지. 모르면 진입하지 않고, 정리도 다음 바퀴로 미룬다
     */
    List<Fill> usdtTrade(long memberId, Book book, Settings s, ObjectNode acc, Coin coin, BigDecimal rate) {
        ObjectNode positions = (ObjectNode) acc.get(POSITIONS);
        ObjectNode seen = (ObjectNode) acc.get(SEEN);
        long seenT = seen.path(coin.symbol()).asLong(0);
        Candle last = coin.candles().getLast();
        if (rate == null) return List.of(); // 환율을 한 번도 못 받았다: 봉을 넘기지 않고 다음 바퀴에 다시 본다
        List<String> signals = usdtSignals(book, s, positions, coin);
        if (last.t() <= seenT) return List.of();
        UsdtBar bar = new UsdtBar(memberId, book, s, positions, coin, last, rate);
        boolean tried = false;
        boolean failed = false;
        List<Fill> out = new ArrayList<>();
        for (String signal : signals) {
            String kind = signal.substring(0, signal.indexOf(':'));
            String reason = signal.substring(signal.indexOf(':') + 1);
            boolean exit = kind.equals("SELL") || kind.equals("CLOSE");
            if (!exit && !s.coins().contains(coin.coin())) continue;
            tried = true;
            JsonNode was = positions.deepCopy();
            Fill f = exit ? usdtExit(bar, kind, reason) : usdtEntry(bar, kind, reason);
            if (f != null) out.add(f);
            else if (was.equals(positions)) failed = true;
        }
        // 낸 주문 가운데 하나라도 되지 않았으면(환전 실패 등) 이 봉을 끝냈다고 적지 않고 다음 바퀴에 다시 본다.
        // 선물 반전에서 정리만 되고 반대 진입이 실패하면, 다음 바퀴는 포지션이 없으니 진입만 다시 낸다
        if (!tried || !failed) seen.put(coin.symbol(), last.t());
        return out;
    }

    /** USDT 계좌가 주문을 내는 봉 하나 */
    private record UsdtBar(long memberId, Book book, Settings s, ObjectNode positions, Coin coin, Candle c, BigDecimal rate) {}

    /** 받은 봉 전체로 규칙을 데우고 마지막 봉의 신호들을 "종류:이유"로 돌려준다 */
    private static List<String> usdtSignals(Book book, Settings s, ObjectNode positions, Coin coin) {
        Rule rule = rule(s);
        List<String> signals = List.of();
        for (Candle c : coin.candles()) {
            JsonNode pos = positions.get(coin.symbol());
            BigDecimal entry = decimal(pos, AVG_PRICE);
            if (book.futures()) {
                signals = rule.onBarFutures(c.c(), pos == null ? null : pos.path(SIDE).asString(), entry);
                continue;
            }
            String one = rule.onBar(c.c(), pos != null, entry);
            if (one == null) signals = List.of();
            else if (one.equals("BUY")) signals = List.of("BUY:" + buyText(s));
            else signals = List.of(one);
        }
        return signals;
    }

    /** 크레딧 → USDT (8자리에서 버림) */
    static BigDecimal usdt(BigDecimal credits, BigDecimal rate) {
        return credits.divide(rate, 8, RoundingMode.DOWN);
    }

    private Fill usdtEntry(UsdtBar bar, String kind, String reason) {
        long memberId = bar.memberId();
        Book book = bar.book();
        Coin coin = bar.coin();
        Candle c = bar.c();
        Settings s = bar.s();
        BigDecimal rate = bar.rate();
        long amount = Math.min(s.budget(), credits.invest(memberId));
        BigDecimal margin = usdt(BigDecimal.valueOf(amount), rate);
        BigDecimal lev = BigDecimal.valueOf(book.leverage());
        // 맡긴 돈 = 수량×가격/레버리지 + 수수료(수량×가격×수수료율)
        BigDecimal q = margin.multiply(lev).divide(c.c().multiply(BigDecimal.ONE.add(book.feeRate().multiply(lev))), MC).setScale(8, RoundingMode.FLOOR);
        if (amount <= 0 || q.signum() <= 0) return null;
        String memo = "모의 봇 " + (book.futures() ? "선물 " : "") + coin.coin() + " USDT 환전";
        if (!credits.exchangeOut(memberId, amount, usdtKey(book, coin, c, kind), memo)) return null;
        ObjectNode p = json.createObjectNode();
        p.put(QUANTITY, q.toPlainString());
        p.put(AVG_PRICE, c.c().toPlainString());
        p.put(SIDE, book.futures() ? kind : "");
        p.put(MARGIN, margin.toPlainString());
        p.put(COST, amount);
        bar.positions().set(coin.symbol(), p);
        return new Fill(id(book, coin.symbol(), c.t(), kind), coin.symbol(), kind, q, c.c(), null, reason, closedAt(c, s));
    }

    private Fill usdtExit(UsdtBar bar, String kind, String reason) {
        Book book = bar.book();
        Coin coin = bar.coin();
        Candle c = bar.c();
        ObjectNode positions = bar.positions();
        JsonNode pos = positions.get(coin.symbol());
        if (pos == null) return null;
        BigDecimal q = decimal(pos, QUANTITY);
        BigDecimal entry = decimal(pos, AVG_PRICE);
        BigDecimal margin = decimal(pos, MARGIN);
        long cost = pos.path(COST).asLong(0);
        BigDecimal dir = SHORT.equals(pos.path(SIDE).asString()) ? BigDecimal.ONE.negate() : BigDecimal.ONE;
        BigDecimal back = settle(q, entry, c.c(), dir, margin, book.feeRate());
        BigDecimal pnl = back.subtract(margin).setScale(8, RoundingMode.HALF_UP);
        long credited = back.multiply(bar.rate()).setScale(0, RoundingMode.DOWN).longValue();
        credits.exchangeIn(bar.memberId(), cost, credited, usdtKey(book, coin, c, kind), "모의 봇 " + (book.futures() ? "선물 " : "") + coin.coin() + " USDT 되돌림");
        positions.remove(coin.symbol());
        return new Fill(id(book, coin.symbol(), c.t(), kind), coin.symbol(), kind, q, c.c(), pnl, reason, closedAt(c, bar.s()));
    }

    /** 정리하면 돌려받는 USDT: 맡긴 돈 - 들어갈 때 수수료 + 방향×수량×(지금가 - 진입가) - 나갈 때 수수료. 0보다 적어지지 않는다 */
    static BigDecimal settle(BigDecimal q, BigDecimal entry, BigDecimal price, BigDecimal dir, BigDecimal margin, BigDecimal feeRate) {
        BigDecimal in = q.multiply(entry).multiply(feeRate);
        BigDecimal outFee = q.multiply(price).multiply(feeRate);
        return margin.subtract(in).add(dir.multiply(q).multiply(price.subtract(entry))).subtract(outFee).max(BigDecimal.ZERO).setScale(8, RoundingMode.DOWN);
    }

    /** 크레딧 원장 키: 같은 봉의 같은 신호는 한 번만 바꾼다 */
    private static String usdtKey(Book book, Coin coin, Candle c, String kind) {
        return "bot-usdt-" + book.key().toLowerCase(java.util.Locale.ROOT) + "-" + coin.coin() + "-" + c.t() + "-" + kind;
    }

    /** 봇 주문 번호: 같은 봉의 같은 신호는 한 번만 체결된다. 업비트는 #331 때 번호 그대로, 다른 거래소는 이름을 붙여 겹치지 않게 한다 */
    private static String orderId(Book book, Coin coin, Candle c, String side) {
        String ex = book.exchange() == Exchange.UPBIT ? "" : book.exchange().name().toLowerCase(java.util.Locale.ROOT) + "-";
        return "bot-" + ex + coin.coin() + "-" + c.t() + "-" + side;
    }

    /**
     * 가상 돈 계좌의 한 번 금액: 원화 계좌는 회원이 고른 금액 그대로, USDT 계좌는 거래소 기본값(100 USDT)을
     * 고른 금액 ÷ 기본 금액 비율로 늘리거나 줄인다. 기본값을 고르면 예전과 같다
     */
    BigDecimal virtualBudget(Book book, Settings s) {
        if (Money.KRW.equals(book.currency())) return BigDecimal.valueOf(s.budget());
        return book.budget().multiply(BigDecimal.valueOf(s.budget())).divide(BigDecimal.valueOf(budgets.budget()), 2, RoundingMode.DOWN);
    }

    /** 한 번 금액만큼 산다. 현금이 모자라면 null */
    private Fill buy(Book book, Settings s, ObjectNode acc, ObjectNode positions, Coin coin, Candle c) {
        BigDecimal cash = new BigDecimal(acc.path(CASH).asString("0"));
        BigDecimal spend = virtualBudget(book, s).min(cash);
        BigDecimal q = spend.divide(c.c().multiply(BigDecimal.ONE.add(book.feeRate())), MC).setScale(8, RoundingMode.FLOOR);
        BigDecimal cost = q.multiply(c.c());
        BigDecimal fee = book.money(cost.multiply(book.feeRate()));
        if (q.signum() <= 0 || cost.add(fee).compareTo(cash) > 0) return null;
        ObjectNode p = json.createObjectNode();
        p.put(QUANTITY, q.toPlainString());
        p.put(AVG_PRICE, c.c().toPlainString());
        positions.set(coin.symbol(), p);
        acc.put(CASH, cash.subtract(cost).subtract(fee).toPlainString());
        return new Fill(id(book, coin.symbol(), c.t(), "BUY"), coin.symbol(), "BUY", q, c.c(), null, buyText(s), closedAt(c, s));
    }

    /** 가진 수량을 모두 판다 */
    private Fill sell(Book book, Settings s, ObjectNode acc, ObjectNode positions, Coin coin, Candle c, String reason) {
        JsonNode pos = positions.get(coin.symbol());
        BigDecimal qty = new BigDecimal(pos.path(QUANTITY).asString("0"));
        BigDecimal avg = new BigDecimal(pos.path(AVG_PRICE).asString("0"));
        BigDecimal cash = new BigDecimal(acc.path(CASH).asString("0"));
        BigDecimal proceeds = qty.multiply(c.c());
        BigDecimal fee = book.money(proceeds.multiply(book.feeRate()));
        BigDecimal pnl = book.money(proceeds.subtract(qty.multiply(avg)).subtract(fee));
        positions.remove(coin.symbol());
        acc.put(CASH, cash.add(proceeds).subtract(fee).toPlainString());
        return new Fill(id(book, coin.symbol(), c.t(), "SELL"), coin.symbol(), "SELL", qty, c.c(), pnl, reason, closedAt(c, s));
    }

    /** 봉이 닫힌 시각에 체결한다 */
    private static Instant closedAt(Candle c, Settings s) {
        return Instant.ofEpochMilli(c.t()).plus(s.step());
    }

    private static Rule rule(Settings s) {
        return new Rule(WINDOW, s.stopLoss(), s.takeProfit(), s.interval());
    }

    private static String buyText(Settings s) {
        return "종가가 " + WINDOW + "개 " + s.interval() + "봉 이동평균선 위로 올라섬";
    }

    static String percent(BigDecimal ratio) {
        return ratio.movePointRight(2).stripTrailingZeros().toPlainString();
    }

    private static String id(Book book, String symbol, long t, String side) {
        String name = book.futures() ? book.key().toLowerCase(java.util.Locale.ROOT) : book.exchange().name().toLowerCase(java.util.Locale.ROOT);
        return "fill:" + PREFIX + name + ":" + symbol + ":" + t + ":" + side;
    }

    private void writeAccount(long memberId, Book book, Settings s, ObjectNode acc, Map<String, BigDecimal> last, BigDecimal rate, Instant now) {
        List<Map<String, Object>> positions = new ArrayList<>();
        BigDecimal unrealized = BigDecimal.ZERO;
        for (Map.Entry<String, JsonNode> e : acc.get(POSITIONS).properties()) {
            BigDecimal q = new BigDecimal(e.getValue().path(QUANTITY).asString("0"));
            BigDecimal avg = new BigDecimal(e.getValue().path(AVG_PRICE).asString("0"));
            BigDecimal price = last.getOrDefault(e.getKey(), avg);
            String side = e.getValue().path(SIDE).asString("");
            BigDecimal diff = price.subtract(avg).multiply(q);
            if (SHORT.equals(side)) diff = diff.negate();
            BigDecimal pnl = currency(book).equals("KRW") ? diff.setScale(0, RoundingMode.HALF_UP) : diff.setScale(8, RoundingMode.HALF_UP);
            unrealized = unrealized.add(pnl);
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("symbol", e.getKey());
            p.put("side", side);
            p.put(QUANTITY, q);
            p.put("avgPrice", avg);
            p.put("price", price);
            p.put("unrealizedPnl", pnl);
            positions.add(p);
        }
        List<Map<String, String>> rules = new ArrayList<>(List.of(
                rule("전략", WINDOW + "봉 이동평균 교차"), rule("봉", s.interval()),
                rule("코인", String.join(", ", s.coins())),
                rule("한 번 금액", usesCredits() ? s.budget() + " 기본 크레딧" : virtualBudget(book, s).stripTrailingZeros().toPlainString() + " " + book.currency()),
                rule("손절", percent(s.stopLoss()) + "%"),
                rule("익절", s.takeProfit().signum() > 0 ? percent(s.takeProfit()) + "%" : "없음"),
                rule("돌리는 곳", usesCredits() ? "devlog 서버 (모의 · 기본 크레딧)" : "devlog 서버 (모의)")));
        if (book.futures()) rules.add(rule("레버리지", book.leverage() + "배"));
        if (mode(book).equals(MODE_USDT)) {
            rules.add(rule("환전", rate == null ? "환율을 받는 중" : "1 USDT = " + rate.stripTrailingZeros().toPlainString() + " 크레딧 (진입·정리 때 그때 환율, 환전 수수료 없음)"));
        }
        // 봉 간격을 바꾸면 계좌 이름이 바뀐다: 이 거래소의 예전 서버 계좌 줄도 함께 지운다
        jdbc.update("DELETE FROM trade_bot_account WHERE member_id = ? AND account LIKE ?", memberId, book.accountPrefix() + "%");
        jdbc.update("""
                INSERT INTO trade_bot_account (member_id, account, exchange, market, currency, cash, unrealized_pnl, positions, rules, reported_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?)
                """, memberId, book.account(s.interval()), book.exchange().name(), book.market().toUpperCase(java.util.Locale.ROOT), currency(book), new BigDecimal(acc.path(CASH).asString("0")), unrealized,
                json.writeValueAsString(positions), json.writeValueAsString(rules), Timestamp.from(now));
    }

    /** 업비트 크레딧 계좌는 원(1크레딧 = 1원), USDT로 바꿔 도는 바이낸스 계좌와 가상 돈 계좌는 거래소 통화로 남긴다 */
    private String currency(Book book) {
        return mode(book).equals(MODE_CREDIT) ? "KRW" : book.currency();
    }

    private static Map<String, String> rule(String label, String value) {
        return Map.of("label", label, "value", value);
    }

    private void writeFill(long memberId, Book book, Settings s, Fill f, Instant now) {
        if (f.at().isBefore(now.minus(TradeBotSim.KEEP))) return;
        jdbc.update("""
                INSERT INTO trade_bot_event (member_id, external_id, kind, account, symbol, side, quantity, price, currency, realized_pnl,
                    verdict, text, occurred_at, created_at)
                VALUES (?, ?, 'FILL', ?, ?, ?, ?, ?, ?, ?, '', ?, ?, ?)
                ON CONFLICT (member_id, external_id) DO NOTHING
                """, memberId, f.id(), book.account(s.interval()), f.symbol(), f.side(), f.quantity(), f.price(), currency(book), f.pnl(), f.text(),
                Timestamp.from(f.at()), Timestamp.from(now));
    }
}
