package com.team.blog.trade.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import com.team.blog.shared.error.ApiException;
import com.team.blog.shared.error.NotFoundException;
import com.team.blog.shared.time.Times;

/**
 * 자동매매 모의 화면 /trade (spec 101). 매매 봇이 MCP 도구 report_trade_bot으로 모의 계좌 상태와 사건을 알리면 남기고 보여 준다.
 * <p>구조(블로그 주인 결정 "규칙형", 2026-10-15): 사용자 AI가 MCP로 기준을 정하고, 봇 코드가 5분마다 그 기준으로 주문하며,
 * 무료 Gemini는 진입 직전 필터와 하루 요약만 맡는다. 여기서는 주문을 내지 않고 보여 주기만 한다.
 * <p>매매 일지(trade)와 표를 나눈다. 모의 기록이 공개 글·일지·포인트 정산에 섞이지 않게 하려는 것이고, 실계좌 계좌는 받지 않는다.
 */
@Service
public class TradeBotSim {
    public static final int ACCOUNTS_MAX = 20;
    public static final int POSITIONS_MAX = 50;
    public static final int RULES_MAX = 20;
    public static final int EVENTS_PER_CALL = 200;
    /** 회원별 하루(한국 0시 기준) 사건 상한. 봇이 고장 나 같은 사건을 끝없이 보내도 여기서 멈춘다 */
    public static final int EVENTS_PER_DAY = 3000;
    /** 사건은 30일만 둔다 */
    public static final Duration KEEP = Duration.ofDays(30);
    public static final int TEXT_MAX = 1000;
    private static final Pattern ACCOUNT = Pattern.compile("[A-Za-z0-9_.-]{1,80}");
    private static final Pattern SYMBOL = Pattern.compile("[A-Za-z0-9._-]{1,20}");
    private static final Pattern CURRENCY = Pattern.compile("[A-Z]{2,10}");
    private static final Pattern EXCHANGE = Pattern.compile("[A-Z0-9_]{0,20}");
    private static final Pattern EXTERNAL = Pattern.compile("[!-~]{1,120}");
    private static final BigDecimal AMOUNT_MAX = new BigDecimal("99999999999999");
    private static final Set<String> SIDES = Set.of("BUY", "SELL", "LONG", "SHORT", "CLOSE");

    public enum Kind { FILL, AI, SUMMARY }

    public record Position(String symbol, String side, BigDecimal quantity, BigDecimal avgPrice, BigDecimal price, BigDecimal unrealizedPnl) {}

    public record Rule(String label, String value) {}

    public record Account(String account, String exchange, String market, String currency, BigDecimal cash, BigDecimal unrealizedPnl,
                          List<Position> positions, List<Rule> rules, Instant reportedAt) {}

    public record Event(long id, String kind, String account, String symbol, String side, BigDecimal quantity, BigDecimal price,
                        String currency, BigDecimal realizedPnl, String verdict, String text, Instant occurredAt) {}

    /** @param reportedAt 봇이 마지막으로 알린 시각. 한 번도 알리지 않았으면 null */
    public record Status(boolean aiEnabled, String aiModel, int aiCallsToday, String botVersion, Instant reportedAt) {}

    public record View(Status status, List<Account> accounts, List<Event> fills, List<Event> decisions, List<Event> summaries) {}

    /** @param added 새로 남긴 사건 수, duplicates 같은 번호로 이미 있던 사건 수 */
    public record Reported(int accounts, int added, int duplicates) {}

    private record EventIn(String externalId, Kind kind, String account, String symbol, String side, BigDecimal quantity, BigDecimal price,
                           String currency, BigDecimal realizedPnl, String verdict, String text, Instant at) {}

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final TradeProperties props;
    private final Clock clock;
    private final JsonMapper json = JsonMapper.builder().build();

    public TradeBotSim(JdbcTemplate jdbc, PlatformTransactionManager txm, TradeProperties props, Clock clock) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(txm);
        this.props = props;
        this.clock = clock;
    }

    public boolean enabled() {
        return props.enabled();
    }

    /**
     * 봇이 보낸 알림 하나를 남긴다. accounts를 주면 그 목록으로 계좌를 통째로 바꾸고(없는 계좌는 지운다), 빼면 계좌는 그대로 둔다.
     * 잘못된 값은 아무것도 남기지 않고 400으로 거절한다(일부만 남으면 화면이 어긋난다).
     */
    public Reported report(long memberId, JsonNode a) {
        if (!props.enabled()) throw ApiException.badRequest("TRADES_DISABLED", "매매 기능이 꺼져 있어요.");
        List<Account> accounts = a.has("accounts") ? accounts(a.path("accounts")) : null;
        List<EventIn> events = events(a.path("events"));
        JsonNode ai = a.path("ai");
        Instant now = Times.now(clock);
        return tx.execute(st -> {
            // 같은 회원의 알림은 한 줄로 세워 하루 상한을 넘기지 않게 한다 (봇을 여러 개 돌려도)
            jdbc.queryForObject("SELECT pg_advisory_xact_lock(?, ?)", Object.class, 101, (int) memberId);
            jdbc.update("""
                    INSERT INTO trade_bot_status (member_id, ai_enabled, ai_model, ai_calls_today, bot_version, reported_at)
                    VALUES (?, ?, ?, ?, ?, ?)
                    ON CONFLICT (member_id) DO UPDATE SET ai_enabled = EXCLUDED.ai_enabled, ai_model = EXCLUDED.ai_model,
                        ai_calls_today = EXCLUDED.ai_calls_today, bot_version = EXCLUDED.bot_version, reported_at = EXCLUDED.reported_at
                    """, memberId, ai.path("enabled").asBoolean(false), text(ai.path("model").asString(""), 60),
                    Math.clamp(ai.path("calls_today").asLong(0), 0, 1_000_000), text(a.path("bot_version").asString(""), 30), Timestamp.from(now));
            if (accounts != null) {
                jdbc.update("DELETE FROM trade_bot_account WHERE member_id = ?", memberId);
                for (Account acc : accounts) {
                    jdbc.update("""
                            INSERT INTO trade_bot_account (member_id, account, exchange, market, currency, cash, unrealized_pnl, positions, rules, reported_at)
                            VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?)
                            """, memberId, acc.account(), acc.exchange(), acc.market(), acc.currency(), acc.cash(), acc.unrealizedPnl(),
                            json.writeValueAsString(acc.positions()), json.writeValueAsString(acc.rules()), Timestamp.from(now));
                }
            }
            jdbc.update("DELETE FROM trade_bot_event WHERE member_id = ? AND occurred_at < ?", memberId, Timestamp.from(now.minus(KEEP)));
            Instant dayStart = LocalDate.ofInstant(now, TradeTexts.KST).atStartOfDay(TradeTexts.KST).toInstant();
            Integer today = jdbc.queryForObject("SELECT count(*) FROM trade_bot_event WHERE member_id = ? AND created_at >= ?",
                    Integer.class, memberId, Timestamp.from(dayStart));
            int added = 0;
            int duplicates = 0;
            for (EventIn e : events) {
                if (e.at().isBefore(now.minus(KEEP))) continue; // 지울 만큼 오래된 사건은 받지 않는다
                if ((today == null ? 0 : today) + added >= EVENTS_PER_DAY) {
                    throw new ApiException(org.springframework.http.HttpStatus.TOO_MANY_REQUESTS, "TRADE_BOT_DAILY_LIMIT",
                            "오늘 받은 사건이 " + EVENTS_PER_DAY + "건을 넘었어요. 봇이 같은 사건을 되풀이해 보내는지 확인해 주세요.");
                }
                int n = jdbc.update("""
                        INSERT INTO trade_bot_event (member_id, external_id, kind, account, symbol, side, quantity, price, currency, realized_pnl,
                            verdict, text, occurred_at, created_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        ON CONFLICT (member_id, external_id) DO NOTHING
                        """, memberId, e.externalId(), e.kind().name(), e.account(), e.symbol(), e.side(), e.quantity(), e.price(),
                        e.currency(), e.realizedPnl(), e.verdict(), e.text(), Timestamp.from(e.at()), Timestamp.from(now));
                if (n == 1) added++;
                else duplicates++;
            }
            return new Reported(accounts == null ? -1 : accounts.size(), added, duplicates);
        });
    }

    /** 화면 /trade: 계좌·체결·Gemini 판단·하루 요약 */
    public View view(long memberId) {
        if (!props.enabled()) throw new NotFoundException();
        Status status = jdbc.query("SELECT ai_enabled, ai_model, ai_calls_today, bot_version, reported_at FROM trade_bot_status WHERE member_id = ?",
                rs -> rs.next() ? new Status(rs.getBoolean(1), rs.getString(2), rs.getInt(3), rs.getString(4), rs.getTimestamp(5).toInstant())
                        : new Status(false, "", 0, "", null), memberId);
        List<Account> accounts = jdbc.query("""
                SELECT account, exchange, market, currency, cash, unrealized_pnl, positions::text, rules::text, reported_at
                FROM trade_bot_account WHERE member_id = ? ORDER BY account
                """, (rs, i) -> new Account(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getBigDecimal(5),
                rs.getBigDecimal(6), json.readValue(rs.getString(7), new TypeReference<List<Position>>() {}),
                json.readValue(rs.getString(8), new TypeReference<List<Rule>>() {}), rs.getTimestamp(9).toInstant()), memberId);
        Instant since = Times.now(clock).minus(KEEP);
        return new View(status, accounts, events(memberId, Kind.FILL, since, 50), events(memberId, Kind.AI, since, 30),
                events(memberId, Kind.SUMMARY, since, 7));
    }

    public void purge(long memberId) {
        jdbc.update("DELETE FROM trade_bot_event WHERE member_id = ?", memberId);
        jdbc.update("DELETE FROM trade_bot_account WHERE member_id = ?", memberId);
        jdbc.update("DELETE FROM trade_bot_status WHERE member_id = ?", memberId);
    }

    private List<Event> events(long memberId, Kind kind, Instant since, int limit) {
        return jdbc.query("""
                SELECT id, kind, account, symbol, side, quantity, price, currency, realized_pnl, verdict, text, occurred_at
                FROM trade_bot_event WHERE member_id = ? AND kind = ? AND occurred_at >= ? ORDER BY occurred_at DESC, id DESC LIMIT ?
                """, TradeBotSim::event, memberId, kind.name(), Timestamp.from(since), limit);
    }

    private static Event event(ResultSet rs, int i) throws SQLException {
        return new Event(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), strip(rs.getBigDecimal(6)),
                strip(rs.getBigDecimal(7)), rs.getString(8), strip(rs.getBigDecimal(9)), rs.getString(10), rs.getString(11),
                rs.getTimestamp(12).toInstant());
    }

    // ---- 입력 검사

    private List<Account> accounts(JsonNode arr) {
        if (!arr.isArray()) throw bad("accounts는 배열이에요.");
        if (arr.size() > ACCOUNTS_MAX) throw bad("계좌는 한 번에 " + ACCOUNTS_MAX + "개까지예요.");
        List<Account> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (JsonNode n : arr) {
            String name = n.path("account").asString("").strip();
            if (!ACCOUNT.matcher(name).matches()) throw bad("account는 영문·숫자·._- 80자까지예요: " + name);
            if (name.startsWith("trade-live-") || "LIVE".equalsIgnoreCase(n.path("mode").asString(""))) {
                throw bad("실계좌(" + name + ")는 이 화면에 보내지 않아요. 모의 계좌만 보내 주세요.");
            }
            if (!seen.add(name)) throw bad("같은 계좌가 두 번 있어요: " + name);
            String exchange = n.path("exchange").asString("").strip().toUpperCase(Locale.ROOT);
            if (!EXCHANGE.matcher(exchange).matches()) throw bad("exchange는 BINANCE·UPBIT 같은 코드예요.");
            String market = n.path("market").asString("spot").strip().toUpperCase(Locale.ROOT);
            if (!market.equals("SPOT") && !market.equals("FUTURES")) throw bad("market은 spot이나 futures예요.");
            String currency = n.path("currency").asString("").strip().toUpperCase(Locale.ROOT);
            if (!CURRENCY.matcher(currency).matches()) throw bad("currency는 KRW·USDT 같은 통화 코드예요.");
            BigDecimal cash = amount(n, "cash", true, false);
            JsonNode ps = n.path("positions");
            if (!ps.isMissingNode() && !ps.isNull() && !ps.isArray()) throw bad("positions는 배열이에요.");
            if (ps.size() > POSITIONS_MAX) throw bad("포지션은 계좌마다 " + POSITIONS_MAX + "개까지예요.");
            List<Position> positions = new ArrayList<>();
            for (JsonNode p : ps) {
                String symbol = p.path("symbol").asString("").strip();
                if (!SYMBOL.matcher(symbol).matches()) throw bad("포지션 symbol이 이상해요: " + symbol);
                String side = p.path("side").asString("").strip().toUpperCase(Locale.ROOT);
                if (!side.isEmpty() && !side.equals("LONG") && !side.equals("SHORT")) throw bad("포지션 side는 LONG이나 SHORT예요.");
                BigDecimal avg = p.has("avg_price") ? amount(p, "avg_price", false, false) : amount(p, "entry", false, false);
                positions.add(new Position(symbol, side, amount(p, "quantity", true, false), avg, amount(p, "price", false, false),
                        amount(p, "unrealized_pnl", false, true)));
            }
            out.add(new Account(name, exchange, market, currency, cash, amount(n, "unrealized_pnl", false, true), positions, rules(n.path("rules")), null));
        }
        return out;
    }

    /** 규칙은 {"전략": "이동평균 20봉 돌파"} 같은 객체나 [{label, value}] 배열로 받는다 */
    private List<Rule> rules(JsonNode n) {
        List<Rule> out = new ArrayList<>();
        if (n.isMissingNode() || n.isNull()) return out;
        if (n.isObject()) {
            for (Map.Entry<String, JsonNode> e : n.properties()) out.add(rule(e.getKey(), e.getValue()));
        } else if (n.isArray()) {
            for (JsonNode r : n) out.add(rule(r.path("label").asString(""), r.path("value")));
        } else {
            throw bad("rules는 객체나 배열이에요.");
        }
        if (out.size() > RULES_MAX) throw bad("규칙은 계좌마다 " + RULES_MAX + "개까지예요.");
        return out;
    }

    private static Rule rule(String label, JsonNode value) {
        String l = label.strip();
        if (l.isEmpty() || l.length() > 40) throw bad("규칙 이름은 1~40자예요.");
        String v = value.isValueNode() ? value.asString("") : value.toString();
        if (v.length() > 200) throw bad("규칙 값은 200자까지예요: " + l);
        return new Rule(l, v.strip());
    }

    private List<EventIn> events(JsonNode arr) {
        List<EventIn> out = new ArrayList<>();
        if (arr.isMissingNode() || arr.isNull()) return out;
        if (!arr.isArray()) throw bad("events는 배열이에요.");
        if (arr.size() > EVENTS_PER_CALL) throw bad("사건은 한 번에 " + EVENTS_PER_CALL + "건까지예요. 나눠 보내 주세요.");
        Instant now = Times.now(clock);
        for (JsonNode n : arr) {
            String id = n.path("id").asString("").strip();
            if (!EXTERNAL.matcher(id).matches()) throw bad("사건 id는 빈칸 없는 영문·숫자·기호 120자까지예요: " + id);
            Kind kind;
            try {
                kind = Kind.valueOf(n.path("kind").asString("").strip().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw bad("사건 kind는 FILL·AI·SUMMARY 중 하나예요.");
            }
            Instant at;
            try {
                at = n.hasNonNull("at") ? OffsetDateTime.parse(n.path("at").asString()).toInstant() : now;
            } catch (DateTimeParseException e) {
                throw bad("at은 2026-10-15T13:05:00+09:00처럼 시간대를 붙여 적어 주세요.");
            }
            if (at.isAfter(now.plus(Duration.ofMinutes(10)))) throw bad("at이 미래예요: " + n.path("at").asString());
            String account = n.path("account").asString("").strip();
            if (!account.isEmpty() && !ACCOUNT.matcher(account).matches()) throw bad("사건 account가 이상해요: " + account);
            if (account.startsWith("trade-live-")) throw bad("실계좌 사건은 이 화면에 보내지 않아요.");
            String symbol = n.path("symbol").asString("").strip();
            if (!symbol.isEmpty() && !SYMBOL.matcher(symbol).matches()) throw bad("사건 symbol이 이상해요: " + symbol);
            String side = n.path("side").asString("").strip().toUpperCase(Locale.ROOT);
            if (!side.isEmpty() && !SIDES.contains(side)) throw bad("side는 BUY·SELL·LONG·SHORT·CLOSE 중 하나예요.");
            String verdict = n.path("verdict").asString("").strip().toUpperCase(Locale.ROOT);
            if (!verdict.isEmpty() && !verdict.equals("ALLOW") && !verdict.equals("BLOCK")) throw bad("verdict는 ALLOW나 BLOCK이에요.");
            String currency = n.path("currency").asString("").strip().toUpperCase(Locale.ROOT);
            if (!currency.isEmpty() && !CURRENCY.matcher(currency).matches()) throw bad("currency는 KRW·USDT 같은 통화 코드예요.");
            String text = n.path("text").asString(n.path("reason").asString("")).strip();
            if (text.length() > TEXT_MAX) throw bad("text는 " + TEXT_MAX + "자까지예요.");
            if (kind == Kind.FILL && (symbol.isEmpty() || side.isEmpty())) throw bad("체결(FILL)에는 symbol과 side가 있어야 해요.");
            if (kind == Kind.AI && verdict.isEmpty()) throw bad("AI 판단에는 verdict(ALLOW·BLOCK)가 있어야 해요.");
            if (kind == Kind.SUMMARY && text.isEmpty()) throw bad("하루 요약에는 text가 있어야 해요.");
            out.add(new EventIn(id, kind, account, symbol, side, amount(n, "quantity", false, false), amount(n, "price", false, false),
                    currency, amount(n, "realized_pnl", false, true), verdict, text, at));
        }
        return out;
    }

    private static BigDecimal amount(JsonNode n, String field, boolean required, boolean signed) {
        JsonNode v = n.path(field);
        BigDecimal d = null;
        if (v.isNumber()) d = v.decimalValue();
        else if (v.isString() && !v.asString().isBlank()) {
            try {
                d = new BigDecimal(v.asString().strip());
            } catch (NumberFormatException e) {
                throw bad(field + "는 숫자예요.");
            }
        }
        if (d == null) {
            if (required) throw bad(field + "가 있어야 해요.");
            return null;
        }
        if (d.abs().compareTo(AMOUNT_MAX) > 0 || (!signed && d.signum() < 0)) throw bad(field + " 값이 범위를 벗어났어요.");
        return d.setScale(Math.min(Math.max(d.scale(), 0), 10), RoundingMode.DOWN);
    }

    private static String text(String s, int max) {
        String t = s.strip();
        return t.length() > max ? t.substring(0, max) : t;
    }

    private static BigDecimal strip(BigDecimal v) {
        return v == null ? null : v.stripTrailingZeros();
    }

    private static ApiException bad(String message) {
        return ApiException.badRequest("INVALID_TRADE_BOT_REPORT", message);
    }
}
