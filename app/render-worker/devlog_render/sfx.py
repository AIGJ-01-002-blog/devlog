"""효과음 (#277). 캐릭터 연기에 맞춰 짧은 소리를 넣는다. 배경음처럼 워커가 직접 합성해 캐시에 둔다(저작권 걱정 없음).

whoosh·ding·whip·click·pageturn은 Remotion 공식 효과음 라이브러리(@remotion/sfx, MIT)의 녹음 소리를 처음 한 번 받아 캐시에 두고 쓴다.
밈 소리는 쓰지 않는다. 받지 못하면(인터넷 없음) 합성 소리로 내려간다. 끄려면 DEVLOG_SFX_RECORDED=0.

다른 스레드(매매 재현 #265)도 같은 믹서에 효과음을 얹을 수 있게 `Effect`(장면 번호, 장면 안 시각, 파일, 크기)를 받는다.
"""

from __future__ import annotations

import math
import os
import random
import struct
import tempfile
import wave
from dataclasses import dataclass
from pathlib import Path

VERSION = 1
RATE = 44100


@dataclass(frozen=True)
class Effect:
    """믹서에 얹을 소리 하나. scene번째 장면이 화면에 나오기 시작한 뒤 at초에 volume 크기(1 = 원래 크기)로 튼다.
    path는 소리 파일(wav·mp3 등 ffmpeg가 읽는 것) 경로이거나 여기서 합성하는 이름(pop, ding, whoosh, boing, tick, typing)."""
    scene: int
    at: float
    path: str
    volume: float = 0.6


def _tone(seconds, fn):
    n = int(seconds * RATE)
    return [fn(k / RATE, k / max(1, n - 1)) for k in range(n)]


def _pop():
    return _tone(0.09, lambda t, x: 0.8 * math.exp(-t * 55) * math.sin(2 * math.pi * (900 - 500 * x) * t))


def _ding():
    return _tone(0.7, lambda t, x: 0.5 * math.exp(-t * 6) * (math.sin(2 * math.pi * 1318.5 * t) + 0.5 * math.sin(2 * math.pi * 1975.5 * t)))


def _whoosh():
    rng = random.Random(7)
    out, prev = [], 0.0
    for v in _tone(0.35, lambda t, x: math.sin(math.pi * x) ** 2):
        prev = prev * 0.85 + (rng.random() * 2 - 1) * 0.15  # 낮은 쪽만 남긴 잡음
        out.append(prev * v * 2.2)
    return out


def _boing():
    return _tone(0.45, lambda t, x: 0.6 * math.exp(-t * 7) * math.sin(2 * math.pi * (180 * t + 60 * math.sin(2 * math.pi * 9 * t) / (2 * math.pi * 9))))


def _typing():
    """자판 두드리는 소리 1.6초"""
    rng = random.Random(11)
    out = [0.0] * int(1.6 * RATE)
    t = 0.0
    while t < 1.5:
        start, pitch = int(t * RATE), rng.uniform(1800, 2800)
        for k in range(int(0.03 * RATE)):
            if start + k < len(out):
                out[start + k] += 0.35 * math.exp(-k / RATE * 180) * math.sin(2 * math.pi * pitch * k / RATE)
        t += rng.uniform(0.07, 0.14)
    return out


def _tick():
    return _tone(0.04, lambda t, x: 0.5 * math.exp(-t * 160) * math.sin(2 * math.pi * 2400 * t))


SOUNDS = {"typing": _typing, "pop": _pop, "ding": _ding, "whoosh": _whoosh, "boing": _boing, "tick": _tick}
#: 워커 안에서만 쓰는 이름(서버 대본의 effects는 SOUNDS만 받는다). 녹음을 못 받으면 합성 소리로 대신한다
EXTRA = {"whip": _whoosh, "click": _tick, "pageturn": _whoosh}
#: @remotion/sfx 4.0.535가 가리키는 녹음 파일
RECORDED = {
    "whoosh": "https://remotion.media/whoosh.wav",
    "ding": "https://remotion.media/ding.wav",
    "whip": "https://remotion.media/whip.wav",
    "click": "https://remotion.media/mouse-click.wav",
    "pageturn": "https://remotion.media/page-turn.wav",
}
_recorded_failed = False


def path(name: str) -> Path:
    """이름이 합성 소리면 캐시 파일 경로(없으면 만든다), 아니면 그대로 파일 경로."""
    if name not in SOUNDS and name not in EXTRA:
        return Path(name)
    base = _cache()
    recorded = _recorded(base, name)
    if recorded is not None:
        return recorded
    out = base / f"sfx-{name}-v{VERSION}.wav"
    if not out.is_file() or out.stat().st_size == 0:
        tmp = out.with_suffix(f".{os.getpid()}.tmp")
        data = (SOUNDS.get(name) or EXTRA[name])()
        with wave.open(str(tmp), "wb") as w:
            w.setnchannels(1)
            w.setsampwidth(2)
            w.setframerate(RATE)
            w.writeframes(b"".join(struct.pack("<h", int(max(-1.0, min(1.0, v)) * 32767)) for v in data))
        os.replace(tmp, out)
    return out


def _cache() -> Path:
    base = Path(os.environ.get("DEVLOG_RENDER_CACHE") or Path.home() / ".cache" / "devlog-render")
    try:
        base.mkdir(parents=True, exist_ok=True)
    except OSError:
        base = Path(tempfile.gettempdir())
    return base


def _recorded(base: Path, name: str) -> Path | None:
    """Remotion 효과음 녹음을 캐시에서 찾거나 한 번 받는다. 한 번 실패하면 이번 실행 동안은 다시 시도하지 않는다."""
    global _recorded_failed
    url = RECORDED.get(name)
    if url is None or os.environ.get("DEVLOG_SFX_RECORDED", "1") == "0":
        return None
    out = base / f"sfx-rec-{name}.wav"
    if out.is_file() and out.stat().st_size > 0:
        return out
    if _recorded_failed:
        return None
    import urllib.request

    try:
        tmp = out.with_suffix(f".{os.getpid()}.tmp")
        with urllib.request.urlopen(urllib.request.Request(url, headers={"User-Agent": "devlog-render"}), timeout=10) as r:
            data = r.read(5_000_000)
        if not data.startswith(b"RIFF"):
            raise ValueError("wav가 아니에요")
        tmp.write_bytes(data)
        os.replace(tmp, out)
        return out
    except Exception:
        _recorded_failed = True
        return None
