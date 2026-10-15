package com.team.blog.trade.application;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Service;

import com.team.blog.shared.error.ApiException;
import com.team.blog.shared.error.FieldErrorItem;
import com.team.blog.shared.error.NotFoundException;
import com.team.blog.shared.time.Times;
import com.team.blog.shorts.application.ShortsScript;
import com.team.blog.shorts.application.ShortsScript.Scene;
import com.team.blog.shorts.application.ShortsService;
import com.team.blog.shorts.application.TradeReplay;

/**
 * 매매 재현 쇼츠 만들기 (#265). 회원이 매매 일지에서 고른 체결로 숏폼 스튜디오에 새 대본을 만든다.
 * <ul>
 *   <li>같은 거래소·종목·모드(모의/실계좌)의 체결 1~6개만 묶는다. 공개 캔들을 받을 수 있는 거래소(바이낸스·업비트)여야 한다.</li>
 *   <li>체결이 있는 날마다 메모가 있어야 한다(매매 숏폼은 메모를 남긴 날만, 2026-10-14 블로그 주인 결정). 숫자만 읽는 영상은 양산형이다.</li>
 *   <li>서버는 유료 AI를 부르지 않는다. 처음 대본은 체결 이유와 메모로 채운 틀이고, 사용자 쪽 AI가 MCP get_shorts로 읽고
 *       update_shorts_script로 "지금 사는 이유" 식으로 다시 쓴다. 영상 만들기는 회원이 스튜디오에서 누른다.</li>
 * </ul>
 * 체결은 매매 일지의 trade 표를 읽기만 한다.
 */
@Service
public class TradeReplays {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm").withZone(TradeTexts.KST);
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("M월 d일").withZone(TradeTexts.KST);
    static final int REASON_SPOKEN_MAX = 90;
    static final int REASON_SPOKEN_MIN = 30;
    static final int MEMO_SPOKEN_MAX = 150;
    static final int MEMO_SPOKEN_MIN = 40;

    /** 화면에서 받는 요청. interval이 비면 자동 */
    public record Request(List<Long> tradeIds, String interval) {}

    private final JdbcTemplate jdbc;
    private final TradeJournal journal;
    private final ShortsService shorts;
    private final Clock clock;

    public TradeReplays(JdbcTemplate jdbc, TradeJournal journal, ShortsService shorts, Clock clock) {
        this.jdbc = jdbc;
        this.journal = journal;
        this.shorts = shorts;
        this.clock = clock;
    }

    /** 매매 일지와 쇼츠가 모두 켜져 있어야 한다. 아니면 없는 주소처럼 404 */
    public boolean enabled() {
        return journal.enabled() && shorts.enabled();
    }

    public ShortsService.Item create(long memberId, Request req) {
        if (!enabled()) throw new NotFoundException();
        List<Long> ids = req == null || req.tradeIds() == null ? List.of() : List.copyOf(new LinkedHashSet<>(req.tradeIds()));
        if (ids.isEmpty()) throw invalid("tradeIds", "REQUIRED", "재현할 체결을 하나 이상 골라 주세요.");
        if (ids.size() > TradeReplay.FILLS_MAX) {
            throw invalid("tradeIds", "TOO_MANY", "체결은 한 영상에 " + TradeReplay.FILLS_MAX + "개까지 고를 수 있어요.");
        }
        Optional<TradeReplay.Interval> interval = Optional.empty();
        if (req.interval() != null && !req.interval().isBlank()) {
            interval = TradeReplay.Interval.parse(req.interval());
            if (interval.isEmpty()) throw invalid("interval", "INVALID", "봉은 1m, 5m, 15m, 1h, 4h, 1d 가운데 골라 주세요.");
        }
        List<TradeJournal.Fill> fills = fills(memberId, ids);
        if (fills.size() != ids.size()) throw invalid("tradeIds", "NOT_FOUND", "고른 체결 가운데 내 매매 일지에 없는 것이 있어요. 새로 불러온 뒤 다시 골라 주세요.");
        TradeJournal.Fill head = fills.getFirst();
        for (TradeJournal.Fill f : fills) {
            if (!f.exchange().equals(head.exchange()) || !f.symbol().equals(head.symbol())) {
                throw invalid("tradeIds", "MIXED_SYMBOL", "한 영상에는 같은 거래소·같은 종목의 체결만 담을 수 있어요.");
            }
            if (!f.mode().equals(head.mode())) throw invalid("tradeIds", "MIXED_MODE", "모의투자와 실계좌 체결은 따로 만들어 주세요.");
        }
        if (!TradeReplay.EXCHANGES.contains(head.exchange())) {
            throw invalid("tradeIds", "EXCHANGE_UNSUPPORTED", "재현 영상은 바이낸스·업비트 체결만 만들 수 있어요. 공개 캔들을 받아 차트를 그리기 때문이에요.");
        }
        Map<LocalDate, String> memos = memos(memberId, fills);
        List<LocalDate> missing = memos.entrySet().stream().filter(e -> e.getValue().isBlank()).map(Map.Entry::getKey).toList();
        if (!missing.isEmpty()) {
            throw ApiException.conflict("TRADE_MEMO_REQUIRED", missing.stream().map(LocalDate::toString).reduce((a, b) -> a + ", " + b).orElse("")
                    + " 메모를 먼저 남겨 주세요. 메모를 남긴 날의 매매만 영상으로 만들어요(숫자만 읽는 영상은 양산형으로 보여요).");
        }
        TradeReplay.Window window = TradeReplay.window(head.executedAt(), fills.getLast().executedAt(), interval, Times.now(clock));
        if (window instanceof TradeReplay.Window.TooLong(String message)) throw invalid("interval", "TOO_LONG", message);
        TradeReplay.Window.Ok ok = (TradeReplay.Window.Ok) window;
        TradeReplay replay = new TradeReplay(head.exchange(), head.symbol(), head.symbolName(), head.currency(), head.mode(), ok.interval().code,
                ok.from(), ok.to(), fills.stream().map(f -> new TradeReplay.Fill(f.id(), f.side(), f.price(), f.quantity(), f.realizedPnl(),
                f.executedAt(), f.reason())).toList());
        return shorts.createReplay(memberId, new ShortsService.Draft(sourcePost(memberId, memos.keySet()), title(replay),
                scenes(replay, String.join(" ", memos.values()))), replay);
    }

    /** AI가 대본을 다시 쓸 때 읽는 그날 메모들 (날짜 → 메모). 매매 일지가 꺼져 있으면 비어 있다 */
    public Map<LocalDate, String> memos(long memberId, TradeReplay replay) {
        if (!journal.enabled()) return Map.of();
        Set<LocalDate> days = new LinkedHashSet<>();
        for (TradeReplay.Fill f : replay.fills()) days.add(LocalDate.ofInstant(f.executedAt(), TradeTexts.KST));
        return memos(memberId, days);
    }

    private Map<LocalDate, String> memos(long memberId, List<TradeJournal.Fill> fills) {
        Set<LocalDate> days = new LinkedHashSet<>();
        for (TradeJournal.Fill f : fills) days.add(LocalDate.ofInstant(f.executedAt(), TradeTexts.KST));
        return memos(memberId, days);
    }

    private Map<LocalDate, String> memos(long memberId, Set<LocalDate> days) {
        Map<LocalDate, String> out = new LinkedHashMap<>();
        for (LocalDate d : days) out.put(d, "");
        if (days.isEmpty()) return out;
        List<Object> args = new ArrayList<>();
        args.add(memberId);
        args.addAll(days);
        jdbc.query("SELECT day, memo FROM trade_day WHERE member_id = ? AND day IN (" + String.join(", ", Collections.nCopies(days.size(), "?")) + ")",
                (RowCallbackHandler) rs -> out.put(rs.getObject("day", LocalDate.class), rs.getString("memo").strip()), args.toArray());
        return out;
    }

    /** 하루짜리 재현이고 그날 요약 글이 남아 있으면 원본 글로 잇는다(영상 설명에 글 주소가 들어간다) */
    private Long sourcePost(long memberId, Set<LocalDate> days) {
        if (days.size() != 1) return null;
        return jdbc.query("""
                SELECT d.post_id FROM trade_day d JOIN post p ON p.id = d.post_id AND p.deleted_at IS NULL
                WHERE d.member_id = ? AND d.day = ?
                """, rs -> rs.next() ? rs.getLong(1) : null, memberId, days.iterator().next());
    }

    private List<TradeJournal.Fill> fills(long memberId, List<Long> ids) {
        String marks = String.join(", ", Collections.nCopies(ids.size(), "?"));
        List<Object> args = new ArrayList<>();
        args.add(memberId);
        args.addAll(ids);
        return jdbc.query("""
                SELECT id, external_id, mode, exchange, symbol, symbol_name, side, quantity, price, currency, fee, realized_pnl, strategy,
                       reason, executed_at, market, position_side, leverage, timeframe
                FROM trade WHERE member_id = ? AND id IN (""" + marks + ") ORDER BY executed_at, id",
                (rs, i) -> new TradeJournal.Fill(rs.getLong("id"), rs.getString("external_id"), rs.getString("mode"), rs.getString("exchange"),
                        rs.getString("symbol"), rs.getString("symbol_name"), rs.getString("side"), plain(rs.getBigDecimal("quantity")),
                        plain(rs.getBigDecimal("price")), rs.getString("currency"), plain(rs.getBigDecimal("fee")),
                        plain(rs.getBigDecimal("realized_pnl")), rs.getString("strategy"), rs.getString("reason"),
                        rs.getTimestamp("executed_at").toInstant(), rs.getString("market"), rs.getString("position_side"),
                        rs.getObject("leverage", Integer.class), rs.getString("timeframe")), args.toArray());
    }

    private static BigDecimal plain(BigDecimal v) {
        if (v == null) return null;
        BigDecimal s = v.stripTrailingZeros();
        return s.scale() < 0 ? s.setScale(0) : s;
    }

    static String coin(TradeReplay r) {
        return r.symbolName().isBlank() ? Money.unit(r.exchange(), r.symbol(), r.currency()) : r.symbolName();
    }

    static String title(TradeReplay r) {
        String day = DATE.format(r.fills().getFirst().executedAt());
        String t = day + " " + coin(r) + " 매매 다시 보기";
        return t.length() > ShortsScript.TITLE_MAX ? t.substring(0, ShortsScript.TITLE_MAX) : t;
    }

    /**
     * 처음 대본. 장면 순서가 영상의 차트 진행과 맞물린다: 1번 장면은 체결 전 흐름, 2번 장면부터 체결 하나씩, 나머지는 정리.
     * 사용자 쪽 AI가 이 틀을 다시 쓰더라도 이 순서를 지키면 체결 표시가 그 장면에 찍힌다(워커가 같은 규칙으로 맞춘다).
     */
    static List<Scene> scenes(TradeReplay r, String memo) {
        String spokenMemo = memo.replaceAll("\\s+", " ").strip();
        // 60초 안에 들 때까지 줄인다. 체결 이유는 이 영상의 핵심이라 가장 늦게 줄인다:
        // 도입·메모 문장 짧게 → 메모 줄이기 → 체결 이유 줄이기 → 체결 장면의 손익 문장 빼기(결과 장면에 합이 남는다) → 체결 이유 빼기
        List<Scene> out = draft(r, spokenMemo, MEMO_SPOKEN_MAX, REASON_SPOKEN_MAX, false, false);
        if (fits(out)) return out;
        for (int max = MEMO_SPOKEN_MAX; max >= MEMO_SPOKEN_MIN; max -= 10) {
            out = draft(r, spokenMemo, max, REASON_SPOKEN_MAX, true, false);
            if (fits(out)) return out;
        }
        for (int reasonMax = REASON_SPOKEN_MAX - 20; reasonMax >= REASON_SPOKEN_MIN; reasonMax -= 20) {
            out = draft(r, spokenMemo, MEMO_SPOKEN_MIN, reasonMax, true, false);
            if (fits(out)) return out;
        }
        out = draft(r, spokenMemo, MEMO_SPOKEN_MIN, REASON_SPOKEN_MIN, true, true);
        if (fits(out)) return out;
        return draft(r, spokenMemo, MEMO_SPOKEN_MIN, 0, true, true);
    }

    private static boolean fits(List<Scene> scenes) {
        return ShortsScript.estimateSeconds(scenes) <= ShortsScript.AUTO_SECONDS_MAX - 1;
    }

    private static List<Scene> draft(TradeReplay r, String memo, int memoMax, int reasonMax, boolean brief, boolean noPnl) {
        String coin = coin(r);
        List<Scene> out = new ArrayList<>();
        // 영상은 워커가 가능한 한 1분봉으로 빨리감기해 보여 주므로 제목에 봉 이름을 넣지 않는다
        out.add(new Scene(coin + " 매매 다시 보기", "그때 저는 왜 여기서 " + side(r.fills().getFirst().side()) + "했을까요? "
                + (brief ? "그 시각으로 돌려 볼게요." : DATE.format(r.fills().getFirst().executedAt()) + " " + coin + " 차트를 그 시각으로 돌려 볼게요."), null));
        for (TradeReplay.Fill f : r.fills()) out.add(fillScene(r, f, reasonMax, noPnl));
        out.add(resultScene(r));
        // 투자 권유 고지는 대본에 넣지 않는다. 워커가 영상 맨 끝에 짧은 카드 한 장("모의투자 기록 · 투자 권유 아님")을 붙인다
        out.add(new Scene("제가 배운 점", (brief ? "제 메모예요. " : "제가 그날 남긴 메모는 이래요. ") + clip(memo, memoMax), null));
        return out;
    }

    private static Scene fillScene(TradeReplay r, TradeReplay.Fill f, int reasonMax, boolean noPnl) {
        String side = side(f.side());
        String price = Money.format(f.price(), r.currency(), false);
        String caption = TIME.format(f.executedAt()) + " " + side + " " + price;
        StringBuilder n = new StringBuilder(spokenTime(f.executedAt())).append(", ");
        String reason = f.reason() == null ? "" : f.reason().strip();
        if (reasonMax > 0 && !reason.isEmpty()) n.append(clip(reason, reasonMax)).append(" 그래서 ");
        n.append(price).append("에 ").append(side).append("했어요.");
        if (f.realizedPnl() != null && !noPnl) n.append(" 이 매도로 ").append(Money.format(f.realizedPnl(), r.currency(), true)).append("이 확정됐어요.");
        return new Scene(clip(caption, ShortsScript.CAPTION_MAX), clip(n.toString(), ShortsScript.NARRATION_MAX), null);
    }

    /** 읽기 좋은 시각: 10시 15분, 정각이면 11시 */
    static String spokenTime(java.time.Instant at) {
        java.time.ZonedDateTime z = at.atZone(TradeTexts.KST);
        return z.getMinute() == 0 ? z.getHour() + "시" : z.getHour() + "시 " + z.getMinute() + "분";
    }

    private static Scene resultScene(TradeReplay r) {
        BigDecimal pnl = BigDecimal.ZERO;
        int sells = 0;
        for (TradeReplay.Fill f : r.fills()) {
            if (f.realizedPnl() == null) continue;
            pnl = pnl.add(f.realizedPnl());
            sells++;
        }
        if (sells == 0) return new Scene("아직 매도 전", "이 구간에서는 아직 팔지 않아서 손익이 확정되지 않았어요. 차트가 어디로 갔는지 끝까지 보세요.", null);
        String sum = Money.format(pnl, r.currency(), true);
        return new Scene(clip("결과 " + sum, ShortsScript.CAPTION_MAX), "매도 " + sells + "번으로 확정된 손익은 " + sum + "였어요.", null);
    }

    private static String side(String side) {
        return "BUY".equals(side) ? "매수" : "매도";
    }

    private static String clip(String s, int max) {
        if (s.codePointCount(0, s.length()) <= max) return s;
        return s.substring(0, s.offsetByCodePoints(0, max - 1)) + "…";
    }

    private static ApiException invalid(String field, String code, String message) {
        return ApiException.validation(List.of(new FieldErrorItem(field, code, message)));
    }

    /** 대본 다시 쓰기 안내에 쓰는 체결 줄 (MCP get_shorts). 예: 10:15 매수 0.01 BTC @ 92,100.00 USDT · 이유: … */
    public static List<String> fillLines(TradeReplay r) {
        List<String> out = new ArrayList<>();
        String unit = Money.unit(r.exchange(), r.symbol(), r.currency());
        for (TradeReplay.Fill f : r.fills()) {
            StringBuilder sb = new StringBuilder(DATE.format(f.executedAt())).append(' ').append(TIME.format(f.executedAt())).append(' ')
                    .append(side(f.side())).append(' ').append(f.quantity().stripTrailingZeros().toPlainString()).append(' ').append(unit)
                    .append(" @ ").append(Money.format(f.price(), r.currency(), false));
            if (f.realizedPnl() != null) sb.append(" · 손익 ").append(Money.format(f.realizedPnl(), r.currency(), true));
            if (f.reason() != null && !f.reason().isBlank()) sb.append(" · 이유: ").append(f.reason());
            out.add(sb.toString());
        }
        return out;
    }

    public static String exchangeName(String code) {
        return Money.exchangeName(code);
    }
}
