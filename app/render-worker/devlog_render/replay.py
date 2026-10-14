"""매매 재현 화면 (#265). 캔들이 한 개씩 흘러가다 체결 시각에 매수·매도 표시가 찍히는 차트의 시간표를 만든다.

장면과 차트는 이렇게 맞물린다(서버가 만드는 처음 대본과 AI 다시 쓰기 안내가 같은 규칙을 쓴다):
- 1번 장면: 첫 체결 전 흐름. 영상이 시작할 때 체결 전 캔들의 40%가 이미 보이고 나머지가 흘러간다.
- 2번 장면부터: 체결 하나에 장면 하나. 그 장면이 시작하고 0.6초 뒤에 그 체결의 캔들과 표시가 나온다.
- 남은 장면: 마지막 캔들까지 흘러가 결과를 보여 준다.
장면이 체결보다 적으면 남은 체결은 마지막 장면에 조금씩 늦춰 찍는다.

여기서는 데이터만 만든다. 화면은 Remotion 장면(remotion/src/Replay.tsx)이 그리고, 효과음 순서표(cues)는 렌더러가 믹서에 얹는다.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from datetime import datetime, timedelta, timezone

from .candles import Candle, INTERVAL_SECONDS, parse_time

KST = timezone(timedelta(hours=9))

FIRST_VISIBLE = 0.4
FILL_DELAY = 0.6
#: 캔들 틱 소리는 이 간격보다 촘촘하면 건너뛴다
TICK_GAP = 0.09
#: 결과 장면이 시작하고 손익 합계가 튀어나오기까지(초)
RESULT_DELAY = 0.35

EXCHANGE_NAMES = {"BINANCE": "바이낸스", "UPBIT": "업비트", "BYBIT": "바이비트"}
INTERVAL_LABELS = {"1m": "1분봉", "5m": "5분봉", "15m": "15분봉", "1h": "1시간봉", "4h": "4시간봉", "1d": "일봉"}


@dataclass(frozen=True)
class Fill:
    side: str  # BUY | SELL
    price: float
    at: int  # epoch 초
    pnl: float | None = None


@dataclass(frozen=True)
class Cue:
    """효과음 한 번. kind: tick(캔들) · whoosh(띠) · buy · sell · win · loss · card(고지)"""
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


def total_pnl(r: Replay) -> float | None:
    values = [f.pnl for f in r.fills if f.pnl is not None]
    return sum(values) if values else None


def result_time(r: Replay, starts: list[float]) -> float | None:
    """결과 장면(체결 장면 바로 다음)이 시작하고 조금 뒤. 대본에 그 장면이 없으면 None"""
    k = len(r.fills) + 1
    return starts[k] + RESULT_DELAY if k < len(starts) else None


def cues(r: Replay, starts: list[float], total: float) -> list[Cue]:
    """효과음 순서표. 화면 효과와 같은 시각을 쓴다(렌더러가 바뀌어도 그대로 쓸 수 있게 데이터로만 낸다)."""
    sec = INTERVAL_SECONDS.get(r.interval, 900)
    times, fill_times = reveal_times(r.candles, r.fills, sec, starts, total)
    out: list[Cue] = []
    last = -1.0
    for t in times:
        if t > 0 and t - last >= TICK_GAP:
            out.append(Cue(t, "tick"))
            last = t
    for f, t in zip(r.fills, fill_times):
        out.append(Cue(max(0.0, t - 0.12), "whoosh"))
        out.append(Cue(t, "buy" if f.side == "BUY" else "sell"))
    at, pnl = result_time(r, starts), total_pnl(r)
    if at is not None and pnl is not None:
        out.append(Cue(at, "win" if pnl >= 0 else "loss"))
    return sorted(out, key=lambda c: c.at)


def chart_data(r: Replay, starts: list[float], total: float, fps: int) -> dict:
    """Remotion 매매 재현 장면(remotion/src/Replay.tsx의 ReplayData)에 넘길 차트 전체 데이터. 시각은 영상 전체 프레임.
    금액 글자는 서버와 같은 표기(money)로 여기서 만들어 넘긴다."""
    sec = INTERVAL_SECONDS.get(r.interval, 900)
    candles = r.candles
    times, fill_times = reveal_times(candles, r.fills, sec, starts, total)

    def frame(t: float) -> int:
        return round(t * fps)

    def plain(v: float) -> str:
        return money(v, r.currency).replace(f" {r.currency}", "")

    prices = [p for c in candles for p in (c.h, c.l)] + [f.price for f in r.fills]
    lo, hi = (min(prices), max(prices)) if prices else (0.0, 1.0)
    pad = (hi - lo) * 0.06 or max(abs(hi) * 0.01, 1e-9)
    lo, hi = lo - pad, hi + pad
    fills = []
    for k, (f, t) in enumerate(zip(r.fills, fill_times)):
        buy = f.side == "BUY"
        close = None
        if buy:  # 매수 가격 선은 다음 매도까지 이어 그려 얼마에 들고 있었는지 보인다
            close = next((candle_index(candles, g.at, sec) for g in r.fills[k + 1:] if g.side == "SELL"), None)
        banner = f"{'매수' if buy else '매도'} {money(f.price, r.currency)}"
        if f.pnl is not None:
            banner += f"  {money(f.pnl, r.currency, sign=True)}"
        fills.append({"index": candle_index(candles, f.at, sec), "at": frame(t), "side": f.side, "price": f.price,
                      "banner": banner, "closeIndex": close, "good": f.pnl is None or f.pnl >= 0})
    at, pnl = result_time(r, starts), total_pnl(r)
    k = len(r.fills) + 2
    result = None if at is None or pnl is None else {
        "at": frame(at), "until": frame(starts[k]) if k < len(starts) else None,
        "label": money(pnl, r.currency, sign=True), "good": pnl >= 0}
    return {
        "header": f"{EXCHANGE_NAMES.get(r.exchange, r.exchange)} · {r.symbol_name or r.symbol} · {INTERVAL_LABELS.get(r.interval, r.interval)}",
        "mode": "실계좌" if r.mode == "LIVE" else "모의투자",
        "lo": lo, "hi": hi,
        "ticks": [{"price": p, "label": plain(p)} for p in (lo + (hi - lo) * (k + 0.5) / 4 for k in range(4))],
        "timeStart": _clock(candles[0].t, sec) if candles else "",
        "timeEnd": _clock(candles[-1].t, sec) if candles else "",
        "candles": [{"o": c.o, "h": c.h, "l": c.l, "c": c.c, "at": frame(t), "label": plain(c.c)} for c, t in zip(candles, times)],
        "fills": fills,
        "result": result,
    }


def _clock(epoch: int, sec: int) -> str:
    d = datetime.fromtimestamp(epoch, KST)
    return d.strftime("%m/%d") if sec >= 86_400 else d.strftime("%m/%d %H:%M")
