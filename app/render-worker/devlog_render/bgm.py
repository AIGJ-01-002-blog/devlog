"""배경음 (#277). 저작권 걱정이 없도록 워커가 직접 합성한 잔잔한 반복 음악을 쓴다.

기본(갈래 0)은 84bpm, 4마디 진행(Fmaj7 → Em7 → Dm7 → Cmaj7)을 두 번 도는 약 23초짜리 고리다. 부드러운 패드, 낮은 베이스,
작은 아르페지오, 약한 킥·하이햇만 둔다. 영상마다 같은 음악이 깔리지 않게 빠르기·조·진행·아르페지오 모양이 다른 갈래를
VARIANTS에 더 둔다(look.py가 영상마다 하나 고른다). 내레이션을 가리지 않도록 렌더할 때 소리를 낮추고 말할 때 더 낮춘다(ducking).
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

#: 아르페지오 모양: 화음 네 음(0~3)의 순서, 4는 맨 아래 음의 한 옥타브 위
ARP_UP_DOWN = [0, 1, 2, 3, 2, 1, 4, 2]
ARP_ROLL = [0, 2, 1, 3, 0, 2, 4, 3]
ARP_PULSE = [0, 3, 0, 3, 1, 3, 4, 3]
#: 배경음 갈래. 0번이 원래 음악이다
VARIANTS = [
    {"bpm": BPM, "progression": PROGRESSION, "arp": ARP_UP_DOWN, "hat": 1},
    {"bpm": 96, "progression": [(45, [69, 72, 76, 79]), (41, [65, 69, 72, 76]), (36, [64, 67, 72, 76]), (43, [67, 71, 74, 77])],  # Am7 F C G7
     "arp": ARP_ROLL, "hat": 2},
    {"bpm": 76, "progression": [(38, [62, 65, 69, 72]), (43, [67, 71, 74, 77]), (36, [60, 64, 67, 71]), (45, [64, 69, 72, 76])],  # Dm7 G7 Cmaj7 Am
     "arp": ARP_UP_DOWN, "hat": 0},
    {"bpm": 104, "progression": [(43, [67, 71, 74, 78]), (40, [64, 67, 71, 74]), (36, [60, 64, 67, 71]), (38, [62, 66, 69, 72])],  # Gmaj7 Em7 Cmaj7 D7
     "arp": ARP_PULSE, "hat": 2},
]


def path(variant: int = 0) -> Path:
    """만들어 둔 배경음 파일 경로. 없으면 만든다. variant: VARIANTS 번호(넘치면 나머지로 돈다)"""
    variant %= len(VARIANTS)
    base = Path(os.environ.get("DEVLOG_RENDER_CACHE") or Path.home() / ".cache" / "devlog-render")
    try:
        base.mkdir(parents=True, exist_ok=True)
    except OSError:
        base = Path(tempfile.gettempdir())
    out = base / (f"bgm-v{VERSION}.wav" if variant == 0 else f"bgm-v{VERSION}-{variant}.wav")
    if not out.is_file() or out.stat().st_size == 0:
        tmp = out.with_suffix(f".{os.getpid()}.tmp")
        write(tmp, variant)
        os.replace(tmp, out)
    return out


def _hz(midi: int) -> float:
    return 440.0 * 2 ** ((midi - 69) / 12)


def samples(variant: int = 0) -> list[float]:
    v = VARIANTS[variant % len(VARIANTS)]
    beat_s = 60 / v["bpm"]
    progression = v["progression"]
    bar = beat_s * 4
    total = int(RATE * bar * len(progression) * LOOPS)
    out = [0.0] * total
    rng = random.Random(277)

    def add(start: float, seconds: float, fn) -> None:
        i0 = int(start * RATE)
        for k in range(min(int(seconds * RATE), total - i0)):
            out[i0 + k] += fn(k / RATE)

    for loop in range(LOOPS):
        for b, (root, chord) in enumerate(progression):
            t0 = (loop * len(progression) + b) * bar
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
                add(t0 + beat * beat_s, beat_s * 1.8,
                    lambda t, f=f: 0.16 * math.exp(-t * 2.2) * min(1.0, t / 0.01) * math.sin(2 * math.pi * f * t))
            # 아르페지오: 8분음표, 한 옥타브 위
            notes = [*chord, chord[0] + 12]
            arp = [notes[k] for k in v["arp"]]
            for k, note in enumerate(arp):
                f = _hz(note + 12)
                add(t0 + k * beat_s / 2, beat_s * 1.5,
                    lambda t, f=f: 0.035 * math.exp(-t * 5) * min(1.0, t / 0.004) * math.sin(2 * math.pi * f * t))
            # 킥(1·3박)과 하이햇(엇박)
            for beat in (0, 2):
                add(t0 + beat * beat_s, 0.25, lambda t: 0.22 * math.exp(-t * 18) * math.sin(2 * math.pi * (45 * t + 70 * (1 - math.exp(-t * 30)) / 30)))
            # 하이햇 수: 0이면 없음, 1이면 엇박, 2면 8분음표마다
            hats = [] if v["hat"] == 0 else [beat + 0.5 for beat in range(4)] if v["hat"] == 1 else [k / 2 for k in range(8)]
            for at in hats:
                add(t0 + at * beat_s, 0.05, lambda t: 0.018 * math.exp(-t * 90) * (rng.random() * 2 - 1))
    peak = max(abs(v) for v in out) or 1.0
    return [v / peak * 0.6 for v in out]


def write(target: Path, variant: int = 0) -> None:
    data = samples(variant)
    with wave.open(str(target), "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(RATE)
        w.writeframes(b"".join(struct.pack("<h", int(max(-1.0, min(1.0, v)) * 32767)) for v in data))
