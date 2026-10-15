"""매매 재현 화면 (#265). 실시간 분봉 차트를 빨리감기하듯 오른쪽 끝에서 캔들이 자라다 체결 시각에 매수·매도 표시와 작은 자막이 뜨는 차트의 시간표를 만든다.

장면과 차트는 이렇게 맞물린다(서버가 만드는 처음 대본과 AI 다시 쓰기 안내가 같은 규칙을 쓴다):
- 영상 첫 TITLE초: 검은 연기 바탕의 제목 카드. 걷히면 1번 장면 말이 이어지는 동안 차트가 흐르기 시작한다.
- 차트는 줌아웃한 한 화면(첫 체결~마지막 체결 폭이 다 들어가는 캔들 수)에 가격 눈금을 고정하고, 거래소 화면을 녹화한 것처럼
  오른쪽 끝에서 캔들이 자라며 장면이 바뀌어도 끊김 없이 왼쪽으로 흘러간다.
- 2번 장면부터 체결 하나에 장면 하나. 장면이 나오고 LEAD초 뒤에 그 체결이 찍히고 아래 가운데에 작은 자막이 뜬다.
- 그다음 장면: 차트가 멈추고 화면이 어두워지며 결산(손익 합계)이 뜬다. 남은 장면(배운 점 등)도 결산 화면을 이어 간다.
장면이 체결보다 적으면 남은 체결은 마지막 체결 장면에서 조금씩 늦춰 찍는다.

여기서는 데이터만 만든다. 화면은 Remotion 장면(remotion/src/Replay.tsx)이 그리고, 효과음 순서표(cues)는 렌더러가 믹서에 얹는다.
"""

from __future__ import annotations

import math
from dataclasses import dataclass, field
from datetime import datetime, timedelta, timezone

from .candles import MAX_CANDLES, Candle, INTERVAL_SECONDS, parse_time

KST = timezone(timedelta(hours=9))

#: 영상에 흘릴 캔들 수 상한. 이 안에 드는 가장 짧은 봉으로 빨리감기한다
PLAY_MAX = 480
#: 구간 앞에 더 받는 캔들 수. 줌아웃한 화면 왼쪽이 처음부터 차 있게(거래소 화면을 켜 둔 것처럼)
HISTORY = 240
#: 장면 안에서 1초에 자라는 캔들 수(1분봉이면 1초에 3분)
SPEED = 3.0
#: 체결 장면이 화면에 나오고 체결이 찍히기까지(초)
LEAD = 1.0
#: 영상 첫머리 제목 카드 길이(초). 그 뒤로는 1번 장면 말이 이어지는 동안 차트가 흐른다
TITLE = 1.6
#: 체결 장면 하나의 최소 길이(초). 말이 짧아도 거래 하나는 이만큼 보여 준다
MIN_TRADE = 4.0
#: 한 화면에 보이는 캔들 수: 첫 체결부터 마지막 체결까지 폭의 SPAN_MARGIN배. 지금 캔들은 화면 가운데에 둔다
MIN_VISIBLE = 80
MAX_VISIBLE = 300
SPAN_MARGIN = 1.1
#: 카메라 세로 폭: 지금 캔들 앞 반 화면의 가격 폭 가운데 이 백분위 × CAMERA_ROOM
CAMERA_PERCENTILE = 0.8
CAMERA_ROOM = 1.35
#: 카메라가 지금 가격을 따라가는 느림(초). 클수록 부드럽다
CAMERA_LAG = 0.6
#: 체결 자막이 떠 있는 시간(초)
SUBTITLE = 3.0
#: 결산 장면이 시작하고 결산이 뜨기까지(초)
RESULT_DELAY = 0.35
#: 장면 가격 눈금의 위아래 여유(가격 폭 대비)
PAD = 0.12

EXCHANGE_NAMES = {"BINANCE": "바이낸스", "UPBIT": "업비트", "BYBIT": "바이비트"}
INTERVAL_LABELS = {"1m": "1분봉", "5m": "5분봉", "15m": "15분봉", "1h": "1시간봉", "4h": "4시간봉", "1d": "일봉"}


@dataclass(frozen=True)
class Fill:
    side: str  # BUY | SELL
    price: float
    at: int  # epoch 초
    pnl: float | None = None
    quantity: float | None = None


@dataclass(frozen=True)
class Cue:
    """효과음 한 번. kind: buy · sell(체결) · win · loss(결산)"""
    at: float
    kind: str


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
                      None if f.get("realizedPnl") is None else float(f["realizedPnl"]),
                      None if f.get("quantity") is None else float(f["quantity"])) for f in data.get("fills", [])]
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


def history_start(start: int, end: int, interval: str) -> int:
    """화면 왼쪽을 처음부터 채우도록 구간 앞 HISTORY개 캔들까지 받는다(한 번에 받을 수 있는 수 안에서)."""
    sec = INTERVAL_SECONDS.get(interval, 900)
    room = MAX_CANDLES - (end - start) // sec
    return start - max(0, min(HISTORY, room)) * sec


def play_interval(start: int, end: int) -> str:
    """영상으로 흘릴 봉. 서버가 정한 구간을 PLAY_MAX개 안에 담는 가장 짧은 봉(대개 1분봉)으로 빨리감기한다."""
    for code, sec in INTERVAL_SECONDS.items():
        if (end - start) // sec <= PLAY_MAX:
            return code
    return "1d"


@dataclass(frozen=True)
class Timeline:
    """영상 전체의 차트 진행. points는 (영상 초, 차트 위치) 기준점이고 그 사이는 일정한 속도로 흐른다.
    visible은 한 화면에 들어오는 캔들 수, lo~hi는 영상 내내 고정된 가격 눈금, fills는 체결마다 찍히는 영상 시각이다."""
    points: list[tuple[float, float]]
    visible: int
    lo: float
    hi: float
    fills: list[float]

    def head(self, t: float) -> float:
        if t <= self.points[0][0]:
            return self.points[0][1]
        for (t0, p0), (t1, p1) in zip(self.points, self.points[1:]):
            if t <= t1:
                return p0 + (p1 - p0) * (t - t0) / (t1 - t0)
        return self.points[-1][1]


def fill_scene(k: int, scenes: int) -> int:
    """k번째 체결이 찍히는 장면 번호. scenes는 고지 카드를 뺀 장면 수(0번은 제목 카드로 시작)"""
    return max(1, min(k + 1, scenes - 1))


def _spot(candles: list[Candle], f: Fill, sec: int) -> float:
    """체결 시각의 차트 위치(캔들 단위 실수). 그 캔들이 그려지는 도중에 찍힌다"""
    i = candle_index(candles, f.at, sec)
    return i + min(0.9, max(0.1, (f.at - candles[i].t) / sec)) if candles else 0.0


def timeline(r: Replay, starts: list[float], end: float) -> Timeline:
    """starts는 고지 카드를 뺀 장면 시작 시각, end는 마지막 장면이 끝나는 시각.
    k번째 체결은 (k+1)번 장면이 나오고 LEAD초 뒤에 찍히고, 차트는 제목 카드가 걷힌 뒤부터 결산 장면까지 끊김 없이 흐른다."""
    sec = INTERVAL_SECONDS.get(r.interval, 900)
    n = len(r.candles)
    spots = [_spot(r.candles, f, sec) for f in r.fills]
    times: list[float] = []
    for k in range(len(r.fills)):
        scene = fill_scene(k, len(starts))
        t = starts[scene] + LEAD if scene < len(starts) else end - 0.5
        t = max(t, TITLE + 0.8, times[-1] + 0.8 if times else 0.0)
        times.append(min(t, max(TITLE + 0.1, end - 0.3)))
    span = (spots[-1] - spots[0]) if spots else 0.0
    visible = max(MIN_VISIBLE, min(MAX_VISIBLE, math.ceil(span * SPAN_MARGIN)))
    visible = max(10, min(visible, n)) if n else MIN_VISIBLE
    first = spots[0] if spots else 0.0
    h0 = max(0.0, first - (times[0] - TITLE) * SPEED) if times else 0.0
    points = [(TITLE, h0)]
    for t, p in zip(times, spots):
        if t > points[-1][0]:
            points.append((t, max(p, points[-1][1])))
    scene = result_scene(r, starts)
    t_end = starts[scene] if scene is not None else end
    last_t, last_p = points[-1]
    if t_end > last_t:
        points.append((t_end, min(float(n), last_p + (t_end - last_t) * SPEED)))
    a = max(0, math.floor(h0 - visible))
    b = min(n, math.ceil(points[-1][1]) + 1)
    lows = [c.l for c in r.candles[a:b]] + [f.price for f in r.fills]
    highs = [c.h for c in r.candles[a:b]] + [f.price for f in r.fills]
    lo, hi = (min(lows), max(highs)) if lows else (0.0, 1.0)
    pad = (hi - lo) * PAD or abs(hi) * 0.001 or 1.0
    return Timeline(points, visible, lo - pad, hi + pad, times)


def total_pnl(r: Replay) -> float | None:
    values = [f.pnl for f in r.fills if f.pnl is not None]
    return sum(values) if values else None


def result_time(r: Replay, starts: list[float]) -> float | None:
    """결산 장면(마지막 체결 장면 바로 다음)이 시작하고 조금 뒤. 대본에 그 장면이 없으면 None"""
    k = result_scene(r, starts)
    return starts[k] + RESULT_DELAY if k is not None else None


def result_scene(r: Replay, starts: list[float]) -> int | None:
    k = fill_scene(len(r.fills) - 1, len(starts)) + 1 if r.fills else len(starts)
    return k if k < len(starts) else None


def cues(r: Replay, starts: list[float], end: float) -> list[Cue]:
    """효과음 순서표. 음악이 끌고 가므로 체결 때 작은 소리와 결산 소리만 낸다."""
    out = [Cue(t, "buy" if f.side == "BUY" else "sell") for f, t in zip(r.fills, timeline(r, starts, end).fills)]
    at, pnl = result_time(r, starts), total_pnl(r)
    if at is not None and pnl is not None:
        out.append(Cue(at, "win" if pnl >= 0 else "loss"))
    return sorted(out, key=lambda c: c.at)


def base_asset(r: Replay) -> str:
    """BTCUSDT → BTC, KRW-BTC → BTC"""
    sym = r.symbol.upper()
    if "-" in sym:
        parts = [p for p in sym.split("-") if p != r.currency.upper()]
        return parts[-1] if parts else sym
    cur = r.currency.upper()
    return sym[: -len(cur)] if cur and sym.endswith(cur) and len(sym) > len(cur) else sym


def _qty(value: float | None, unit: str) -> str:
    if value is None:
        return ""
    text = f"{value:,.8f}".rstrip("0").rstrip(".")
    return f"{text} {unit} "


def subtitles(r: Replay) -> list[dict]:
    """체결마다 아래 가운데 작은 자막. 진입은 흰 한 줄, 익절은 초록·손절은 빨강 두 줄(둘째 줄은 손익과 지금까지 누적)."""
    unit = base_asset(r)
    out = []
    total = 0.0
    for f in r.fills:
        qty = _qty(f.quantity, unit)
        if f.side == "BUY" or f.pnl is None:
            out.append({"text": f"{qty}{'매수' if f.side == 'BUY' else '매도'} 체결", "detail": None, "tone": "entry"})
            continue
        total += f.pnl
        good = f.pnl >= 0
        out.append({"text": f"{qty}매도 {'익절' if good else '손절'}",
                    "detail": f"( {money(f.pnl, r.currency, sign=True)} {'수익' if good else '손실'} | 누적 {money(total, r.currency, sign=True)} )",
                    "tone": "win" if good else "loss"})
    return out


def title_data(r: Replay, title: str) -> dict:
    """제목 카드(remotion/src/Replay.tsx의 ReplayTitle) 데이터"""
    day = datetime.fromtimestamp(r.fills[0].at, KST).strftime("%Y.%m.%d") if r.fills else ""
    return {"label": f"{'실계좌' if r.mode == 'LIVE' else '모의투자'} 매매 재현", "title": title,
            "line": f"{EXCHANGE_NAMES.get(r.exchange, r.exchange)} {r.symbol} · 체결 {len(r.fills)}번", "date": day}


def _price_at(candles: list[Candle], head: float) -> float:
    """차트 위치 head의 대략적인 지금 가격(그리는 중인 캔들의 시가→종가)"""
    i = min(len(candles) - 1, max(0, math.floor(head)))
    c = candles[i]
    u = min(1.0, max(0.0, head - i))
    return c.o + (c.c - c.o) * u


def camera(r: Replay, line: Timeline, heads: list[float], fps: int) -> tuple[float, list[float]]:
    """카메라 세로 폭(가격, 영상 내내 고정)과 프레임마다 화면 가운데 가격.
    가운데는 지금 가격을 CAMERA_LAG초 늦게 부드럽게 따라가고, 지금 가격이 화면 위아래 15% 안쪽을 벗어나지 않게 붙잡는다."""
    candles = r.candles
    if not candles:
        return 1.0, [0.0] * len(heads)
    half = line.visible // 2
    widths = []
    for h in heads[:: max(1, fps // 6)]:
        i = min(len(candles), math.floor(h) + 1)
        window = candles[max(0, i - half):i] or candles[:1]
        widths.append(max(c.h for c in window) - min(c.l for c in window))
    widths.sort()
    span = widths[min(len(widths) - 1, int(len(widths) * CAMERA_PERCENTILE))] * CAMERA_ROOM
    span = min(span, line.hi - line.lo) or abs(candles[0].c) * 0.002 or 1.0
    alpha = 1 - math.exp(-1 / (CAMERA_LAG * fps))
    centers: list[float] = []
    center = _price_at(candles, heads[0])
    for h in heads:
        price = _price_at(candles, h)
        center += (price - center) * alpha
        center = min(max(center, price - span * 0.35), price + span * 0.35)
        centers.append(center)
    return span, centers


def chart_data(r: Replay, starts: list[float], end: float, fps: int, title: str = "") -> dict:
    """Remotion 매매 재현 장면(remotion/src/Replay.tsx의 ReplayData)에 넘길 차트 전체 데이터. 시각은 영상 전체 프레임.
    head는 프레임마다 차트 위치(캔들 단위 실수)라, 화면은 이 값만 보고 캔들을 자라게 하고 지금 캔들을 그리는 중으로 보여 준다.
    화면 하나에 visible개 캔들이 들어가고 지금 캔들은 가로 가운데에 있다. 세로는 폭(span)이 고정이고 가운데(center[프레임])가
    지금 가격을 부드럽게 따라가, 거래소 화면을 녹화하며 지금 가격에 초점을 맞춘 것처럼 보인다.
    처음 titleUntil 프레임은 제목 카드가 덮는다. 금액 글자는 서버와 같은 표기(money)로 여기서 만들어 넘긴다."""
    sec = INTERVAL_SECONDS.get(r.interval, 900)
    candles = r.candles
    line = timeline(r, starts, end)
    frames = int(round(end * fps))

    def frame(t: float) -> int:
        return round(t * fps)

    heads = [line.head(g / fps) for g in range(frames + 1)]
    span, centers = camera(r, line, heads, fps)
    fills = []
    for k, (f, t, sub) in enumerate(zip(r.fills, line.fills, subtitles(r))):
        close = None
        if f.side == "BUY":  # 매수 가격 선은 다음 매도까지 이어 그려 얼마에 들고 있었는지 보인다
            close = next((candle_index(candles, g.at, sec) for g in r.fills[k + 1:] if g.side == "SELL"), None)
        fills.append({"index": candle_index(candles, f.at, sec), "at": frame(t), "side": f.side, "price": f.price,
                      "closeIndex": close, **sub})
    at, pnl = result_time(r, starts), total_pnl(r)
    summary = None
    if at is not None and r.fills:
        summary = {"at": frame(at), "count": f"매매 {len(r.fills)}번 결산",
                   "label": money(pnl, r.currency, sign=True) if pnl is not None else "청산 전",
                   "good": pnl is None or pnl >= 0,
                   "period": f"{_clock(r.fills[0].at, 60)} → {_clock(r.fills[-1].at, 60)}"}
    krw = r.currency == "KRW"
    return {
        "header": f"{EXCHANGE_NAMES.get(r.exchange, r.exchange)} · {r.symbol_name or r.symbol} · {INTERVAL_LABELS.get(r.interval, r.interval)}",
        "mode": "실계좌" if r.mode == "LIVE" else "모의투자",
        "toast": f"{r.symbol} 주문 체결 완료",
        "digits": 0 if krw else 2,
        "unit": "원" if krw else "",
        "visible": line.visible,
        "span": span,
        "center": [round(c, 4) for c in centers],
        "minutes": sec // 60,
        "subtitleFrames": frame(SUBTITLE),
        "titleUntil": frame(TITLE),
        "title": title_data(r, title),
        "head": [round(h, 3) for h in heads],
        "candles": [{"o": c.o, "h": c.h, "l": c.l, "c": c.c, "time": _clock(c.t, sec)} for c in candles],
        "fills": fills,
        "summary": summary,
    }


def _clock(epoch: int, sec: int) -> str:
    d = datetime.fromtimestamp(epoch, KST)
    return d.strftime("%m/%d") if sec >= 86_400 else d.strftime("%H:%M")
