package com.team.blog.trade.application;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.team.blog.shorts.application.ShortsScript;

/**
 * 하루 매매 기록을 글·쇼츠 대본으로 바꾸는 틀 (#243). AI 없이 숫자로만 만든다.
 * 숫자만 읽는 영상은 양산형으로 보이므로, 쇼츠 대본은 회원이 그날 남긴 메모가 있어야 만든다(메모가 영상마다 다른 이야기가 된다).
 * 투자 권유로 읽히지 않게 글과 영상 모두 고지 문구를 넣는다.
 */
public final class TradeTexts {
    static final ZoneId KST = ZoneId.of("Asia/Seoul");
    static final String DISCLAIMER = "이 기록은 제 매매 봇의 결과를 남긴 개인 기록이에요. 투자 권유가 아니며, 지난 결과가 앞으로의 수익을 보장하지 않아요.";
    /** 쇼츠에서 메모를 읽는 길이. 60초 안에 들어가게 자른다 */
    static final int SHORTS_MEMO_MAX = 110;
    static final int SHORTS_FILLS_MAX = 2;
    static final double SHORTS_SECONDS_TARGET = 57;

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm").withZone(KST);

    private TradeTexts() {}

    /** 통화별 금액(손익·수수료 합). 통화가 없으면 그날 첫 체결의 통화로 0을 쓴다. sign이면 +를 붙인다 */
    static String money(TradeJournal.Day day, Map<String, BigDecimal> amounts, boolean sign) {
        String fallback = day.fills().isEmpty() ? Money.KRW : day.fills().getFirst().currency();
        return Money.format(amounts, fallback, sign);
    }

    /** 체결 하나의 금액(가격·손익)을 그 체결의 통화로 */
    static String money(TradeJournal.Fill f, BigDecimal amount, boolean sign) {
        return Money.format(amount, f.currency(), sign);
    }

    /** 수량. 소수점 아래 0은 지운다 */
    static String qty(BigDecimal q) {
        return q.stripTrailingZeros().toPlainString();
    }

    /** 수량과 단위: 주식 "10주", 코인 "0.0012 BTC" */
    static String amountOf(TradeJournal.Fill f) {
        String unit = Money.unit(f.exchange(), f.symbol(), f.currency());
        return unit.equals("주") ? qty(f.quantity()) + "주" : qty(f.quantity()) + " " + unit;
    }

    static String modeLabel(TradeJournal.Day day) {
        boolean live = day.fills().stream().anyMatch(f -> f.mode().equals("LIVE"));
        boolean paper = day.fills().stream().anyMatch(f -> f.mode().equals("PAPER"));
        if (live && paper) return "모의투자·실계좌";
        return live ? "실계좌" : "모의투자";
    }

    private static String name(TradeJournal.Fill f) {
        return f.symbolName().isBlank() ? f.symbol() : f.symbolName();
    }

    /** 표의 종목 칸: 거래소를 알리면 이름 뒤에 붙인다 (비트코인 · 바이낸스) */
    private static String nameWithExchange(TradeJournal.Fill f) {
        return f.exchange().isBlank() ? name(f) : name(f) + " · " + Money.exchangeName(f.exchange());
    }

    static String draftTitle(TradeJournal.Day day) {
        TradeJournal.Summary s = day.summary();
        String head = "매매 일지 " + day.date() + " (" + modeLabel(day) + ")";
        return s.sells() > 0 ? head + ": 실현 손익 " + money(day, s.realizedPnl(), true) : head + ": 체결 " + s.trades() + "건";
    }

    /** 하루 요약 임시글 본문 (Markdown). 체결 표·요약·메모·고지 */
    static String draftBody(TradeJournal.Day day) {
        TradeJournal.Summary s = day.summary();
        StringBuilder md = new StringBuilder();
        md.append("> ").append(DISCLAIMER).append("\n\n");
        if (!day.memo().isBlank()) md.append("## 오늘 메모\n\n").append(day.memo().strip()).append("\n\n");
        md.append("## 요약\n\n");
        md.append("- 체결 ").append(s.trades()).append("건 (매수 ").append(s.buys()).append(" · 매도 ").append(s.sells()).append(")\n");
        if (s.sells() > 0) {
            md.append("- 실현 손익: ").append(money(day, s.realizedPnl(), true))
                    .append(" (이익 본 매도 ").append(s.wins()).append(" · 손해 본 매도 ").append(s.losses()).append(")\n");
        }
        md.append("- 수수료·세금: ").append(money(day, s.fees(), false)).append('\n');
        md.append("- 지금까지 실현 손익: ").append(money(day, day.cumulativePnl(), true)).append("\n\n");
        md.append("## 체결\n\n| 시각 | 종목 | 구분 | 수량 | 가격 | 실현 손익 | 이유 |\n|---|---|---|---:|---:|---:|---|\n");
        for (TradeJournal.Fill f : day.fills().stream().sorted(Comparator.comparing(TradeJournal.Fill::executedAt)).toList()) {
            md.append("| ").append(TIME.format(f.executedAt()))
                    .append(" | ").append(cell(nameWithExchange(f))).append(f.mode().equals("LIVE") ? " (실계좌)" : "")
                    .append(" | ").append(f.side().equals("BUY") ? "매수" : "매도")
                    .append(" | ").append(Money.crypto(f.exchange()) ? amountOf(f) : qty(f.quantity()))
                    .append(" | ").append(money(f, f.price(), false))
                    .append(" | ").append(f.realizedPnl() == null ? "" : money(f, f.realizedPnl(), true))
                    .append(" | ").append(cell(f.reason().isBlank() ? f.strategy() : f.reason()))
                    .append(" |\n");
        }
        return md.toString();
    }

    private static String cell(String s) {
        return s.replace("|", "\\|").replace("\n", " ");
    }

    static String shortsTitle(TradeJournal.Day day) {
        String t = day.date().getMonthValue() + "월 " + day.date().getDayOfMonth() + "일 매매 봇 일지";
        TradeJournal.Summary s = day.summary();
        return s.sells() > 0 ? t + " " + money(day, s.realizedPnl(), true) : t;
    }

    /**
     * 쇼츠 대본. 질문으로 시작해 하루 성적, 눈에 띄는 체결(손익이 큰 매도부터, 같은 종목은 한 번), 누적 손익, 내 메모, 고지 순서.
     * 60초를 넘으면 체결 장면부터 뺀다.
     */
    static List<ShortsScript.Scene> shortsScenes(TradeJournal.Day day) {
        TradeJournal.Summary s = day.summary();
        LocalDate d = day.date();
        String mode = modeLabel(day);
        List<ShortsScript.Scene> head = new ArrayList<>();
        head.add(new ShortsScript.Scene(d.getMonthValue() + "/" + d.getDayOfMonth() + " 봇 성적은?",
                d.getMonthValue() + "월 " + d.getDayOfMonth() + "일, 제 " + mode + " 봇은 돈을 벌었을까요?", null));
        String result = s.sells() > 0
                ? "체결 " + s.trades() + "건 가운데 매도 " + s.sells() + "번에서 실현 손익은 " + money(day, s.realizedPnl(), true) + "이었어요."
                : "체결은 " + s.trades() + "건이었고 모두 매수라서 아직 확정된 손익은 없어요.";
        head.add(new ShortsScript.Scene(s.sells() > 0 ? "실현 손익 " + money(day, s.realizedPnl(), true) : "매수 " + s.buys() + "건", result, null));

        List<ShortsScript.Scene> fills = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        List<TradeJournal.Fill> notable = day.fills().stream()
                .sorted(Comparator.comparing((TradeJournal.Fill f) -> f.realizedPnl() == null ? BigDecimal.ZERO : f.realizedPnl().abs()).reversed()
                        .thenComparing(TradeJournal.Fill::executedAt))
                .toList();
        for (TradeJournal.Fill f : notable) {
            if (fills.size() >= SHORTS_FILLS_MAX || !seen.add(f.symbol())) continue;
            String why = f.reason().isBlank() ? f.strategy() : f.reason();
            String verb = f.side().equals("BUY") ? "샀어요" : "팔았어요";
            String pnl = f.realizedPnl() == null ? "" : " 손익은 " + money(f, f.realizedPnl(), true) + ".";
            String narration = name(f) + " " + amountOf(f) + "를 " + money(f, f.price(), false) + "에 " + verb + "." + pnl
                    + (why.isBlank() ? "" : " 신호는 " + why + ".");
            fills.add(new ShortsScript.Scene(clip(name(f) + (f.side().equals("BUY") ? " 매수" : " 매도"), ShortsScript.CAPTION_MAX),
                    clip(narration, ShortsScript.NARRATION_MAX), null));
        }

        List<ShortsScript.Scene> tail = new ArrayList<>();
        String cumulative = money(day, day.cumulativePnl(), true);
        tail.add(new ShortsScript.Scene("누적 " + cumulative, "봇을 돌린 뒤 지금까지 확정된 손익은 " + cumulative + "이에요.", null));
        tail.add(new ShortsScript.Scene("오늘 배운 점", "제가 오늘 느낀 건, " + clip(day.memo().strip().replaceAll("\\s+", " "), SHORTS_MEMO_MAX), null));
        tail.add(new ShortsScript.Scene("투자 권유 아님", "투자 권유가 아니라 제 " + mode + " 기록이에요.", null));

        List<ShortsScript.Scene> out = new ArrayList<>();
        while (true) {
            out.clear();
            out.addAll(head);
            out.addAll(fills);
            out.addAll(tail);
            if (fills.isEmpty() || ShortsScript.estimateSeconds(out) <= SHORTS_SECONDS_TARGET) return List.copyOf(out);
            fills.removeLast();
        }
    }

    static String clip(String s, int max) {
        if (s.codePointCount(0, s.length()) <= max) return s;
        return s.substring(0, s.offsetByCodePoints(0, max - 1)) + "…";
    }
}
