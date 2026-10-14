"""매매 재현 화면 (#265). 캔들이 한 개씩 흘러가다 체결 시각에 매수·매도 표시가 찍히는 차트를 장면 위에 그린다.

장면과 차트는 이렇게 맞물린다(서버가 만드는 처음 대본과 AI 다시 쓰기 안내가 같은 규칙을 쓴다):
- 1번 장면: 첫 체결 전 흐름. 영상이 시작할 때 체결 전 캔들의 40%가 이미 보이고 나머지가 흘러간다.
- 2번 장면부터: 체결 하나에 장면 하나. 그 장면이 시작하고 0.6초 뒤에 그 체결의 캔들과 표시가 나온다.
- 남은 장면: 마지막 캔들까지 흘러가 결과를 보여 준다.
장면이 체결보다 적으면 남은 체결은 마지막 장면에 조금씩 늦춰 찍는다.

여기서는 화면에 그릴 상자(box)와 글자(text)만 계산한다. ffmpeg 필터로 바꾸는 일은 render.py가 한다.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from datetime import datetime, timedelta, timezone

from . import layout
from .candles import Candle, INTERVAL_SECONDS, parse_time

KST = timezone(timedelta(hours=9))
UP = "0x26a69a"
DOWN = "0xef5350"
MUTED = "0x94a3b8"
GRID = "0x334155"
CHART_BG = "0x111c33"

# 차트 자리 (1080x1920). 오른쪽 130px은 가격 눈금 자리
X0, X1 = 60, layout.WIDTH - 60
PLOT_X1 = X1 - 130
Y0, Y1 = 470, 1230
PLOT_Y0, PLOT_Y1 = Y0 + 80, Y1 - 30

FIRST_VISIBLE = 0.4
FILL_DELAY = 0.6
BANNER_SECONDS = 1.8

EXCHANGE_NAMES = {"BINANCE": "바이낸스", "UPBIT": "업비트", "BYBIT": "바이비트"}
INTERVAL_LABELS = {"1m": "1분봉", "5m": "5분봉", "15m": "15분봉", "1h": "1시간봉", "4h": "4시간봉", "1d": "일봉"}


@dataclass(frozen=True)
class Fill:
    side: str  # BUY | SELL
    price: float
    at: int  # epoch 초
    pnl: float | None = None


@dataclass(frozen=True)
class Box:
    x: int
    y: int
    w: int
    h: int
    color: str
    start: float = 0.0  # 영상 전체에서 나타나는 시각(초)
    end: float | None = None


@dataclass(frozen=True)
class Text:
    value: str
    size: int
    color: str
    x: str
    y: int
    start: float = 0.0
    end: float | None = None
    box: str | None = None  # 글자 뒤 바탕색


@dataclass
class Replay:
    exchange: str
    symbol: str
    symbol_name: str
    currency: str
    mode: str
    interval: str
    fills: list[Fill]
    candles: list[Candle] = field(default_factory=list)

    @staticmethod
    def from_job(data: dict) -> "Replay":
        fills = [Fill(f["side"], float(f["price"]), parse_time(f["executedAt"]),
                      None if f.get("realizedPnl") is None else float(f["realizedPnl"])) for f in data.get("fills", [])]
        return Replay(data.get("exchange", ""), data.get("symbol", ""), data.get("symbolName") or "", data.get("currency") or "KRW",
                      data.get("mode") or "PAPER", data.get("interval") or "15m", sorted(fills, key=lambda f: f.at))


def window_of(data: dict) -> tuple[int, int]:
    return parse_time(data["from"]), parse_time(data["to"])


def money(value: float, currency: str, sign: bool = False) -> str:
    """서버 Money와 같은 표기: 원화는 1,234원, 다른 통화는 소수 둘째 자리(1보다 작으면 여덟째 자리까지)."""
    if currency == "KRW":
        body = f"{abs(round(value)):,}원"
        v = round(value)
    else:
        digits = 2 if abs(value) >= 1 or value == 0 else 8
        v = round(value, digits)
        body = f"{abs(v):,.{digits}f}"
        if digits == 8:
            body = body.rstrip("0")
            if len(body.split(".")[1]) < 2:
                body = f"{abs(v):,.2f}"
        body += f" {currency}"
    if v < 0:
        return "-" + body
    return ("+" + body) if sign and v > 0 else body


def candle_index(candles: list[Candle], at: int, sec: int) -> int:
    for i, c in enumerate(candles):
        if c.t <= at < c.t + sec:
            return i
    return 0 if not candles or at < candles[0].t else len(candles) - 1


def reveal_times(candles: list[Candle], fills: list[Fill], sec: int, starts: list[float], total: float) -> tuple[list[float], list[float]]:
    """캔들마다, 체결마다 나타나는 시각(초). 장면 시작 시각(starts)과 전체 길이(total)에 맞춘다."""
    n = len(candles)
    if n == 0:
        return [], []
    idx = [candle_index(candles, f.at, sec) for f in fills]
    first = idx[0] if idx else n - 1
    visible = max(1, int(first * FIRST_VISIBLE))
    anchors: list[tuple[int, float]] = [(visible - 1, 0.0)]
    fill_times: list[float] = []
    last_time = 0.0
    for k, i in enumerate(idx):
        scene = min(k + 1, len(starts) - 1)
        t = max(starts[scene] + FILL_DELAY, last_time + 0.4 if fill_times else 0.0)
        t = min(t, max(0.0, total - 0.5))
        fill_times.append(t)
        last_time = t
        if i > anchors[-1][0]:
            anchors.append((i, t))
    end_time = min(max(last_time + 0.5, total - 1.0), max(0.0, total - 0.2))
    if n - 1 > anchors[-1][0]:
        anchors.append((n - 1, max(end_time, anchors[-1][1])))
    times = [0.0] * n
    for (i0, t0), (i1, t1) in zip(anchors, anchors[1:]):
        for i in range(i0 + 1, i1 + 1):
            times[i] = t0 + (t1 - t0) * (i - i0) / (i1 - i0)
    # 체결 표시는 그 캔들보다 먼저 나오지 않는다
    fill_times = [max(t, times[i]) for t, i in zip(fill_times, idx)]
    return times, fill_times


def scene_layers(r: Replay, starts: list[float], total: float) -> list[Box | Text]:
    """영상 전체에 걸친 차트 요소. 각 요소의 start(·end)로 언제 보일지 정한다."""
    sec = INTERVAL_SECONDS.get(r.interval, 900)
    candles = r.candles
    times, fill_times = reveal_times(candles, r.fills, sec, starts, total)
    out: list[Box | Text] = [Box(X0, Y0, X1 - X0, Y1 - Y0, CHART_BG)]
    coin = r.symbol_name or r.symbol
    head = f"{EXCHANGE_NAMES.get(r.exchange, r.exchange)} · {coin} · {INTERVAL_LABELS.get(r.interval, r.interval)}"
    out.append(Text(head, 34, "white", str(X0 + 30), Y0 + 24))
    mode = "실계좌" if r.mode == "LIVE" else "모의투자"
    out.append(Text(mode, 30, MUTED, f"{X1 - 30}-text_w", Y0 + 28))
    if not candles:
        return out

    prices = [p for c in candles for p in (c.h, c.l)] + [f.price for f in r.fills]
    lo, hi = min(prices), max(prices)
    pad = (hi - lo) * 0.06 or max(abs(hi) * 0.01, 1e-9)
    lo, hi = lo - pad, hi + pad

    def y(p: float) -> int:
        return round(PLOT_Y1 - (p - lo) / (hi - lo) * (PLOT_Y1 - PLOT_Y0))

    # 가격 눈금 4칸
    for k in range(4):
        p = lo + (hi - lo) * (k + 0.5) / 4
        out.append(Box(X0 + 20, y(p), PLOT_X1 - X0 - 20, 1, GRID))
        out.append(Text(money(p, r.currency).replace(f" {r.currency}", ""), 24, MUTED, str(PLOT_X1 + 12), y(p) - 12))
    # 시간 눈금: 처음과 끝
    out.append(Text(_clock(candles[0].t, sec), 24, MUTED, str(X0 + 20), Y1 + 10))
    out.append(Text(_clock(candles[-1].t, sec), 24, MUTED, f"{PLOT_X1}-text_w", Y1 + 10))

    slot = (PLOT_X1 - X0 - 30) / len(candles)
    body_w = max(2, round(slot * 0.68))
    wick_w = max(2, round(slot * 0.14))

    def cx(i: int) -> int:
        return round(X0 + 20 + slot * (i + 0.5))

    for i, c in enumerate(candles):
        color = UP if c.c >= c.o else DOWN
        top, bottom = y(max(c.o, c.c)), y(min(c.o, c.c))
        out.append(Box(cx(i) - wick_w // 2, y(c.h), wick_w, max(2, y(c.l) - y(c.h)), color, times[i]))
        out.append(Box(cx(i) - body_w // 2, top, body_w, max(3, bottom - top), color, times[i]))

    # 지금 가격: 마지막으로 나온 캔들의 종가를 오른쪽 눈금에 띄워 시세가 실시간으로 움직이는 것처럼 보인다
    for i, c in enumerate(candles):
        until = times[i + 1] if i + 1 < len(candles) and times[i + 1] > times[i] else None
        if i + 1 < len(candles) and until is None:
            continue  # 같은 순간에 나오는 캔들은 마지막 것만 띄운다
        color = UP if c.c >= c.o else DOWN
        out.append(Box(X0 + 20, y(c.c), PLOT_X1 - X0 - 20, 2, color + "@0.45", times[i], until))
        out.append(Text(money(c.c, r.currency).replace(f" {r.currency}", ""), 24, "white", str(PLOT_X1 + 8), y(c.c) - 14, times[i], until,
                        box=color))

    for k, (f, t) in enumerate(zip(r.fills, fill_times)):
        i = candle_index(candles, f.at, sec)
        buy = f.side == "BUY"
        color = UP if buy else DOWN
        py = y(f.price)
        word = "매수" if buy else "매도"
        # 매수 가격 선은 다음 매도까지 이어 그려 얼마에 들고 있었는지 보인다
        if buy:
            close = next((candle_index(candles, g.at, sec) for g in r.fills[k + 1:] if g.side == "SELL"), None)
            line_end = cx(close) if close is not None else PLOT_X1
            out.append(Box(cx(i), py - 1, max(2, line_end - cx(i)), 3, color + "@0.7", t))
        label_y = min(y(candles[i].l) + 18, PLOT_Y1 - 4) if buy else max(y(candles[i].h) - 66, PLOT_Y0 - 40)
        out.append(Box(cx(i) - 3, min(py, label_y + 24), 6, abs(label_y + 24 - py), color, t))
        out.append(Text(word, 30, "white", f"{cx(i)}-text_w/2", label_y + 8, t, box=color))
        # 체결 순간 크게 알리는 띠
        banner = f"{word} {money(f.price, r.currency)}"
        if f.pnl is not None:
            banner += f"  {money(f.pnl, r.currency, sign=True)}"
        out.append(Text(banner, 50, "white", "(w-text_w)/2", PLOT_Y0 + 6, t, t + BANNER_SECONDS, box=color + "@0.92"))
    return out


def _clock(epoch: int, sec: int) -> str:
    d = datetime.fromtimestamp(epoch, KST)
    return d.strftime("%m/%d") if sec >= 86_400 else d.strftime("%m/%d %H:%M")
