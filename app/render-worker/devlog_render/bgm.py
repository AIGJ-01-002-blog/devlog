"""배경음 (#277). 저작권 걱정이 없도록 워커가 직접 합성한 잔잔한 반복 음악을 쓴다.

84bpm, 4마디 진행(Fmaj7 → Em7 → Dm7 → Cmaj7)을 두 번 도는 약 23초짜리 고리다. 부드러운 패드, 낮은 베이스,
작은 아르페지오, 약한 킥·하이햇만 둔다. 내레이션을 가리지 않도록 렌더할 때 소리를 낮추고 말할 때 더 낮춘다(ducking).
만드는 데 몇 초 걸리므로 한 번 만든 파일을 캐시 폴더에 두고 다시 쓴다. 다른 음악을 쓰려면 --bgm 파일.
"""

from __future__ import annotations

import math
import os
import random
import struct
import tempfile
import wave
from pathlib import Path

VERSION = 1
RATE = 32000
BPM = 84
BEAT = 60 / BPM
#: (근음 MIDI, 화음 MIDI 목록). 한 마디(4박)씩
PROGRESSION = [
    (41, [65, 69, 72, 76]),  # Fmaj7
    (40, [64, 67, 71, 74]),  # Em7
    (38, [62, 65, 69, 72]),  # Dm7
    (36, [60, 64, 67, 71]),  # Cmaj7
]
LOOPS = 2


def path() -> Path:
    """만들어 둔 배경음 파일 경로. 없으면 만든다."""
    base = Path(os.environ.get("DEVLOG_RENDER_CACHE") or Path.home() / ".cache" / "devlog-render")
    try:
        base.mkdir(parents=True, exist_ok=True)
    except OSError:
        base = Path(tempfile.gettempdir())
    out = base / f"bgm-v{VERSION}.wav"
    if not out.is_file() or out.stat().st_size == 0:
        tmp = out.with_suffix(f".{os.getpid()}.tmp")
        write(tmp)
        os.replace(tmp, out)
    return out


def _hz(midi: int) -> float:
    return 440.0 * 2 ** ((midi - 69) / 12)


def samples() -> list[float]:
    bar = BEAT * 4
    total = int(RATE * bar * len(PROGRESSION) * LOOPS)
    out = [0.0] * total
    rng = random.Random(277)

    def add(start: float, seconds: float, fn) -> None:
        i0 = int(start * RATE)
        for k in range(min(int(seconds * RATE), total - i0)):
            out[i0 + k] += fn(k / RATE)

    for loop in range(LOOPS):
        for b, (root, chord) in enumerate(PROGRESSION):
            t0 = (loop * len(PROGRESSION) + b) * bar
            # 패드: 화음 네 음, 조금씩 어긋난 두 사인으로 부드럽게 떨리게. 마디 끝에서 다음 마디로 겹치며 사라진다
            for note in chord:
                f = _hz(note)

                def pad(t: float, f=f) -> float:
                    env = min(1.0, t / 0.6) * min(1.0, max(0.0, (bar + 0.4 - t) / 0.8))
                    return 0.05 * env * (math.sin(2 * math.pi * f * t) + 0.6 * math.sin(2 * math.pi * f * 1.004 * t + 1.3))
                add(t0, bar + 0.4, pad)
            # 베이스: 1·3박
            for beat in (0, 2):
                f = _hz(root)
                add(t0 + beat * BEAT, BEAT * 1.8,
                    lambda t, f=f: 0.16 * math.exp(-t * 2.2) * min(1.0, t / 0.01) * math.sin(2 * math.pi * f * t))
            # 아르페지오: 8분음표, 한 옥타브 위
            arp = [chord[0], chord[1], chord[2], chord[3], chord[2], chord[1], chord[0] + 12, chord[2]]
            for k, note in enumerate(arp):
                f = _hz(note + 12)
                add(t0 + k * BEAT / 2, BEAT * 1.5,
                    lambda t, f=f: 0.035 * math.exp(-t * 5) * min(1.0, t / 0.004) * math.sin(2 * math.pi * f * t))
            # 킥(1·3박)과 하이햇(엇박)
            for beat in (0, 2):
                add(t0 + beat * BEAT, 0.25, lambda t: 0.22 * math.exp(-t * 18) * math.sin(2 * math.pi * (45 * t + 70 * (1 - math.exp(-t * 30)) / 30)))
            for beat in range(4):
                add(t0 + (beat + 0.5) * BEAT, 0.05, lambda t: 0.018 * math.exp(-t * 90) * (rng.random() * 2 - 1))
    peak = max(abs(v) for v in out) or 1.0
    return [v / peak * 0.6 for v in out]


def write(target: Path) -> None:
    data = samples()
    with wave.open(str(target), "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(RATE)
        w.writeframes(b"".join(struct.pack("<h", int(max(-1.0, min(1.0, v)) * 32767)) for v in data))
