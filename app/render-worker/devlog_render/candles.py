"""거래소 공개 캔들 받기 (#265 매매 재현 영상). API 키 없이 누구나 받는 시세만 쓴다.

- 바이낸스: GET /api/v3/klines (api.binance.com이 막히면 공개 시세 전용 data-api.binance.vision)
- 업비트: GET /v1/candles/minutes/{분} · /v1/candles/days (최근 것부터 200개까지, to 앞까지)

거래가 없던 시간에는 업비트가 캔들을 주지 않으므로 직전 종가로 평평한 캔들을 채워 시간 축을 고르게 한다.
"""

from __future__ import annotations

import json
import urllib.error
import urllib.parse
import urllib.request
from dataclasses import dataclass
from datetime import datetime, timezone

INTERVAL_SECONDS = {"1m": 60, "5m": 300, "15m": 900, "1h": 3600, "4h": 14_400, "1d": 86_400}
UPBIT_PATHS = {"1m": "minutes/1", "5m": "minutes/5", "15m": "minutes/15", "1h": "minutes/60", "4h": "minutes/240", "1d": "days"}
BINANCE_HOSTS = ("https://api.binance.com", "https://data-api.binance.vision")
UPBIT_HOST = "https://api.upbit.com"
#: 한 영상의 캔들 상한. 서버가 150개 안으로 구간을 정한다
MAX_CANDLES = 200
TIMEOUT = 15
USER_AGENT = "devlog-render-worker"


@dataclass(frozen=True)
class Candle:
    t: int  # 시작 시각 (epoch 초, UTC)
    o: float
    h: float
    l: float  # noqa: E741 - 시고저종 이름 그대로
    c: float


class CandleError(RuntimeError):
    pass


def parse_time(value: str) -> int:
    """ISO-8601 (끝이 Z이거나 +09:00) → epoch 초"""
    return int(datetime.fromisoformat(value.replace("Z", "+00:00")).timestamp())


def fetch(exchange: str, symbol: str, interval: str, start: int, end: int, get=None) -> list[Candle]:
    """[start, end) 구간의 캔들. get(url) → 응답 본문(bytes)은 테스트가 바꿔 끼운다."""
    if interval not in INTERVAL_SECONDS:
        raise CandleError(f"모르는 봉이에요: {interval}")
    sec = INTERVAL_SECONDS[interval]
    count = (end - start) // sec
    if count <= 0 or count > MAX_CANDLES:
        raise CandleError(f"캔들 수가 맞지 않아요({count}개).")
    get = get or _get
    exchange = exchange.upper()
    if exchange == "BINANCE":
        raw = _binance(symbol, interval, start, end, count, get)
    elif exchange == "UPBIT":
        raw = _upbit(symbol, interval, end, count, get)
    else:
        raise CandleError(f"{exchange} 공개 캔들은 아직 받을 수 없어요. 바이낸스·업비트만 돼요.")
    candles = fill_gaps([c for c in raw if start <= c.t < end], start, end, sec)
    if not candles:
        raise CandleError(f"{exchange} {symbol} {interval} 캔들을 받지 못했어요. 종목 코드와 구간을 확인해 주세요.")
    return candles


def _binance(symbol: str, interval: str, start: int, end: int, count: int, get) -> list[Candle]:
    query = urllib.parse.urlencode({"symbol": symbol.upper(), "interval": interval, "startTime": start * 1000,
                                    "endTime": end * 1000 - 1, "limit": min(1000, count + 1)})
    last: Exception | None = None
    for host in BINANCE_HOSTS:
        try:
            rows = json.loads(get(f"{host}/api/v3/klines?{query}"))
            return [Candle(int(r[0]) // 1000, float(r[1]), float(r[2]), float(r[3]), float(r[4])) for r in rows]
        except (urllib.error.URLError, TimeoutError, ConnectionError, ValueError, KeyError, IndexError) as e:
            last = e  # 지역에 따라 api.binance.com이 막혀 있으면 공개 시세 전용 주소로 다시 받는다
    raise CandleError(f"바이낸스 캔들을 받지 못했어요: {last}")


def _upbit(symbol: str, interval: str, end: int, count: int, get) -> list[Candle]:
    to = datetime.fromtimestamp(end, timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
    query = urllib.parse.urlencode({"market": symbol.upper(), "to": to, "count": min(200, count)})
    try:
        rows = json.loads(get(f"{UPBIT_HOST}/v1/candles/{UPBIT_PATHS[interval]}?{query}"))
        out = [Candle(parse_time(r["candle_date_time_utc"] + "Z"), float(r["opening_price"]), float(r["high_price"]),
                      float(r["low_price"]), float(r["trade_price"])) for r in rows]
    except (urllib.error.URLError, TimeoutError, ConnectionError, ValueError, KeyError, TypeError) as e:
        raise CandleError(f"업비트 캔들을 받지 못했어요: {e}") from None
    return sorted(out, key=lambda c: c.t)


def fill_gaps(candles: list[Candle], start: int, end: int, sec: int) -> list[Candle]:
    """빈 시간을 직전 종가의 평평한 캔들로 채운다. 맨 앞이 비면 첫 캔들의 시가로 채운다."""
    if not candles:
        return []
    by_time = {c.t: c for c in candles}
    prev = candles[0].o
    out = []
    for t in range(start, end, sec):
        c = by_time.get(t)
        if c is None:
            c = Candle(t, prev, prev, prev, prev)
        out.append(c)
        prev = c.c
    return out


def from_json(rows: list) -> list[Candle]:
    """대본 파일에 넣어 둔 캔들 ([t, o, h, l, c] 또는 {t,o,h,l,c}). 서버 없이 만들어 볼 때 쓴다."""
    out = []
    for r in rows:
        if isinstance(r, dict):
            t = r["t"] if isinstance(r["t"], int) else parse_time(r["t"])
            out.append(Candle(t, float(r["o"]), float(r["h"]), float(r["l"]), float(r["c"])))
        else:
            out.append(Candle(int(r[0]), float(r[1]), float(r[2]), float(r[3]), float(r[4])))
    return out


def _get(url: str) -> bytes:
    request = urllib.request.Request(url, headers={"User-Agent": USER_AGENT, "Accept": "application/json"})
    with urllib.request.urlopen(request, timeout=TIMEOUT) as response:
        return response.read()
