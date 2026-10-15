"""매매 재현 화면 (#265). 실시간 분봉 차트를 빨리감기하듯 오른쪽 끝에서 캔들이 자라다 체결 시각에 매수·매도 표시와 작은 자막이 뜨는 차트의 시간표를 만든다.

장면과 차트는 이렇게 맞물린다(서버가 만드는 처음 대본과 AI 다시 쓰기 안내가 같은 규칙을 쓴다):
- 1번 장면: 검은 연기 바탕의 제목 카드.
- 2번 장면부터: 체결 하나에 장면 하나. 장면마다 그 체결 앞뒤 구간만 보여 주고 다음 장면으로는 점프 컷으로 넘어간다.
  장면 안에서 카메라(가격 눈금·캔들 폭)는 고정이고, 오른쪽 끝에서 새 캔들이 자라며 차트가 천천히 왼쪽으로 밀린다.
  체결은 장면이 화면에 나오고 LEAD초 뒤에 찍힌다.
- 그다음 장면: 차트가 멈추고 화면이 어두워지며 결산(손익 합계)이 뜬다. 남은 장면(배운 점 등)도 결산 화면을 이어 간다.
장면이 체결보다 적으면 남은 체결은 마지막 체결 장면에서 조금 더 빨리 흘려 함께 찍는다.

여기서는 데이터만 만든다. 화면은 Remotion 장면(remotion/src/Replay.tsx)이 그리고, 효과음 순서표(cues)는 렌더러가 믹서에 얹는다.
"""

from __future__ import annotations

import math
from dataclasses import dataclass, field
from datetime import datetime, timedelta, timezone

from .candles import Candle, INTERVAL_SECONDS, parse_time

KST = timezone(timedelta(hours=9))

#: 영상에 흘릴 캔들 수 상한. 이 안에 드는 가장 짧은 봉으로 빨리감기한다
PLAY_MAX = 720
#: 장면 안에서 1초에 자라는 캔들 수(1분봉이면 1초에 3분)
SPEED = 3.0
#: 체결 장면이 화면에 나오고 체결이 찍히기까지(초)
LEAD = 1.0
#: 체결 장면 하나의 최소 길이(초). 말이 짧아도 거래 하나는 이만큼 보여 준다
MIN_TRADE = 4.0
#: 한 화면에 보이는 캔들 수와, 지금 캔들 오른쪽에 비워 두는 칸
VISIBLE = 60
AHEAD = 3
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


def play_interval(start: int, end: int) -> str:
    """영상으로 흘릴 봉. 서버가 정한 구간을 PLAY_MAX개 안에 담는 가장 짧은 봉(대개 1분봉)으로 빨리감기한다."""
    for code, sec in INTERVAL_SECONDS.items():
        if (end - start) // sec <= PLAY_MAX:
            return code
    return "1d"


@dataclass(frozen=True)
class Segment:
    """체결 장면 하나의 차트 구간. 영상 start초에 차트 위치 begin에서 시작해 1초에 speed 캔들씩 자라고, stop초(장면 끝)나 끝 캔들에서 멈춘다.
    lo~hi는 이 장면 내내 고정된 가격 눈금이다."""
    scene: int
    start: float
    stop: float
    begin: float
    speed: float
    fills: list[int]
    lo: float = 0.0
    hi: float = 1.0

    def head(self, t: float, n: int) -> float:
        return min(float(n), self.begin + max(0.0, min(t, self.stop) - self.start) * self.speed)


def fill_scene(k: int, scenes: int) -> int:
    """k번째 체결이 찍히는 장면 번호. scenes는 고지 카드를 뺀 장면 수(0번은 제목 카드)"""
    return max(1, min(k + 1, scenes - 1))


def _spot(candles: list[Candle], f: Fill, sec: int) -> float:
    """체결 시각의 차트 위치(캔들 단위 실수). 그 캔들이 그려지는 도중에 찍힌다"""
    i = candle_index(candles, f.at, sec)
    return i + min(0.9, max(0.1, (f.at - candles[i].t) / sec)) if candles else 0.0


def segments(r: Replay, starts: list[float], end: float) -> list[Segment]:
    """체결 장면마다 차트 구간. starts는 고지 카드를 뺀 장면 시작 시각, end는 마지막 장면이 끝나는 시각."""
    sec = INTERVAL_SECONDS.get(r.interval, 900)
    n = len(r.candles)
    groups: dict[int, list[int]] = {}
    for k in range(len(r.fills)):
        groups.setdefault(fill_scene(k, len(starts)), []).append(k)
    out: list[Segment] = []
    last = 0.0
    for scene in sorted(groups):
        ks = groups[scene]
        t0 = starts[scene] if scene < len(starts) else end
        t1 = starts[scene + 1] if scene + 1 < len(starts) else end
        spots = [_spot(r.candles, r.fills[k], sec) for k in ks]
        begin = spots[0] - LEAD * SPEED
        # 바로 앞 장면이 끝난 자리와 가까우면 이어서 흘린다(같은 구간을 다시 보여 주지 않게)
        if last <= spots[0] - 0.3 * SPEED and begin < last:
            begin = last
        begin = max(0.0, begin)
        speed = SPEED
        if spots[-1] > spots[0]:  # 한 장면에 체결이 여럿이면 마지막 체결까지 장면 안에 들어오게 빨리 흘린다
            speed = max(SPEED, (spots[-1] - begin) / max(0.5, t1 - t0 - 0.8))
        seg = Segment(scene, t0, t1, begin, speed, ks)
        last = seg.head(t1, n)
        out.append(_framed(seg, r, t1))
    return out


def _framed(seg: Segment, r: Replay, t1: float) -> Segment:
    """장면 내내 보이는 캔들과 체결 가격이 다 들어오는 고정 가격 눈금을 붙인다"""
    n = len(r.candles)
    first = max(0, math.floor(seg.begin - (VISIBLE - AHEAD)))
    last = min(n, math.ceil(seg.head(t1, n)) + 1)
    lows = [c.l for c in r.candles[first:last]]
    highs = [c.h for c in r.candles[first:last]]
    sec = INTERVAL_SECONDS.get(r.interval, 900)
    for f in r.fills:
        if first <= candle_index(r.candles, f.at, sec) < last:
            lows.append(f.price)
            highs.append(f.price)
    lo, hi = (min(lows), max(highs)) if lows else (0.0, 1.0)
    pad = (hi - lo) * PAD or abs(hi) * 0.001 or 1.0
    return Segment(seg.scene, seg.start, seg.stop, seg.begin, seg.speed, seg.fills, lo - pad, hi + pad)


def fill_times(r: Replay, segs: list[Segment]) -> list[float]:
    """체결마다 화면에 찍히는 영상 시각(초)"""
    sec = INTERVAL_SECONDS.get(r.interval, 900)
    out = [0.0] * len(r.fills)
    for seg in segs:
        for k in seg.fills:
            out[k] = seg.start + max(0.0, _spot(r.candles, r.fills[k], sec) - seg.begin) / seg.speed
    return out


def total_pnl(r: Replay) -> float | None:
    values = [f.pnl for f in r.fills if f.pnl is not None]
    return sum(values) if values else None


def result_time(r: Replay, starts: list[float]) -> float | None:
    """결산 장면(마지막 체결 장면 바로 다음)이 시작하고 조금 뒤. 대본에 그 장면이 없으면 None"""
    k = fill_scene(len(r.fills) - 1, len(starts)) + 1 if r.fills else len(starts)
    return starts[k] + RESULT_DELAY if k < len(starts) else None


def result_scene(r: Replay, starts: list[float]) -> int | None:
    k = fill_scene(len(r.fills) - 1, len(starts)) + 1 if r.fills else len(starts)
    return k if k < len(starts) else None


def cues(r: Replay, starts: list[float], end: float) -> list[Cue]:
    """효과음 순서표. 음악이 끌고 가므로 체결 때 작은 소리와 결산 소리만 낸다."""
    out = [Cue(t, "buy" if f.side == "BUY" else "sell") for f, t in zip(r.fills, fill_times(r, segments(r, starts, end)))]
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


def chart_data(r: Replay, starts: list[float], end: float, fps: int) -> dict:
    """Remotion 매매 재현 장면(remotion/src/Replay.tsx의 ReplayData)에 넘길 차트 전체 데이터. 시각은 영상 전체 프레임.
    head는 프레임마다 차트 위치(캔들 단위 실수)라, 화면은 이 값만 보고 캔들을 자라게 하고 지금 캔들을 그리는 중으로 보여 준다.
    segments는 체결 장면마다 시작 프레임과 고정 가격 눈금이다. 금액 글자는 서버와 같은 표기(money)로 여기서 만들어 넘긴다."""
    sec = INTERVAL_SECONDS.get(r.interval, 900)
    candles = r.candles
    n = len(candles)
    segs = segments(r, starts, end)
    times = fill_times(r, segs)
    frames = int(round(end * fps))

    def frame(t: float) -> int:
        return round(t * fps)

    head = []
    for g in range(frames + 1):
        t = g / fps
        seg = next((s for s in reversed(segs) if s.start <= t + 1e-9), segs[0] if segs else None)
        head.append(round(seg.head(t, n), 4) if seg else 0.0)

    fills = []
    for k, (f, t, sub) in enumerate(zip(r.fills, times, subtitles(r))):
        close = None
        if f.side == "BUY":  # 매수 가격 선은 다음 매도까지 이어 그려 얼마에 들고 있었는지 보인다
            close = next((candle_index(candles, g.at, sec) for g in r.fills[k + 1:] if g.side == "SELL"), None)
        fills.append({"index": candle_index(candles, f.at, sec), "at": frame(t), "side": f.side, "price": f.price,
                      "closeIndex": close, **sub})
    at, pnl, scene = result_time(r, starts), total_pnl(r), result_scene(r, starts)
    summary = None
    if at is not None and scene is not None:
        first, last = r.fills[0].at, r.fills[-1].at
        summary = {"at": frame(at), "count": f"매매 {len(r.fills)}번 결산",
                   "label": money(pnl, r.currency, sign=True) if pnl is not None else "청산 전",
                   "good": pnl is None or pnl >= 0,
                   "period": f"{_clock(first, 60)} → {_clock(last, 60)}"}
    krw = r.currency == "KRW"
    return {
        "header": f"{EXCHANGE_NAMES.get(r.exchange, r.exchange)} · {r.symbol_name or r.symbol} · {INTERVAL_LABELS.get(r.interval, r.interval)}",
        "mode": "실계좌" if r.mode == "LIVE" else "모의투자",
        "toast": f"{r.symbol} 주문 체결 완료",
        "digits": 0 if krw else 2,
        "unit": "원" if krw else "",
        "visible": VISIBLE,
        "ahead": AHEAD,
        "subtitleFrames": frame(SUBTITLE),
        "head": head,
        "segments": [{"start": frame(s.start), "lo": s.lo, "hi": s.hi} for s in segs],
        "candles": [{"o": c.o, "h": c.h, "l": c.l, "c": c.c, "time": _clock(c.t, sec)} for c in candles],
        "fills": fills,
        "summary": summary,
    }


def _clock(epoch: int, sec: int) -> str:
    d = datetime.fromtimestamp(epoch, KST)
    return d.strftime("%m/%d") if sec >= 86_400 else d.strftime("%H:%M")
