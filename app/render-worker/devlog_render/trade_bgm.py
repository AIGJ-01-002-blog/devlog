"""매매 재현 배경음 (#265). 차트를 빨리감기하는 영상에 맞춘 긴장감 있는 반복 음악을 워커가 직접 합성한다(저작권 걱정 없음).

126bpm 단조, 4마디 진행(Am → Am → F → E)을 네 번 도는 약 30초짜리 고리다. E(딸림화음, G#)에서 풀리지 않고 다시 Am으로 돌아가
계속 조여 오는 느낌을 낸다. 네 박 킥, 2·4박 박수, 16분 하이햇, 쉬지 않는 8분 베이스, 반음이 섞인 16분 아르페지오,
낮게 깔린 지속음, 여덟 마디마다 끝에서 올라가는 잡음(라이저)을 두고, 킥마다 나머지 소리를 살짝 눌러(펌핑) 앞으로 밀어붙인다. 소리 크기·내레이션 때 줄이기는 다른 쇼츠 배경음과 같은 믹서가 맡는다.
품질 스레드(#277)가 고른 배경음 파일이 있으면(--bgm) 그 파일이 우선한다.
"""

from __future__ import annotations

import math
import os
import random
import struct
import tempfile
import wave
from array import array
from pathlib import Path

VERSION = 2
RATE = 32000
BPM = 126
BEAT = 60 / BPM
#: (베이스 MIDI, 아르페지오 MIDI 네 음). 한 마디씩
PROGRESSION = [
    (45, [69, 72, 76, 81]),  # Am
    (45, [69, 72, 76, 80]),  # Am(G#를 얹어 불안하게)
    (41, [65, 69, 72, 77]),  # F
    (40, [64, 68, 71, 76]),  # E (G#)
]
LOOPS = 4


def path() -> Path:
    """만들어 둔 매매 재현 배경음 파일 경로. 없으면 만든다."""
    base = Path(os.environ.get("DEVLOG_RENDER_CACHE") or Path.home() / ".cache" / "devlog-render")
    try:
        base.mkdir(parents=True, exist_ok=True)
    except OSError:
        base = Path(tempfile.gettempdir())
    out = base / f"bgm-trade-v{VERSION}.wav"
    if not out.is_file() or out.stat().st_size == 0:
        tmp = out.with_suffix(f".{os.getpid()}.tmp")
        write(tmp)
        os.replace(tmp, out)
    return out


def _hz(midi: int) -> float:
    return 440.0 * 2 ** ((midi - 69) / 12)


def samples() -> array:
    bar = BEAT * 4
    bars = len(PROGRESSION) * LOOPS
    total = int(RATE * bar * bars)
    drums = array("d", [0.0]) * total
    music = array("d", [0.0]) * total
    rng = random.Random(265)

    def add(buf: array, start: float, seconds: float, fn) -> None:
        i0 = int(start * RATE)
        for k in range(min(int(seconds * RATE), total - i0)):
            buf[i0 + k] += fn(k / RATE)

    for b in range(bars):
        root, notes = PROGRESSION[b % len(PROGRESSION)]
        t0 = b * bar
        fill = b % 8 >= 6  # 여덟 마디마다 끝 두 마디는 하이햇을 촘촘하게
        for beat in range(4):
            at = t0 + beat * BEAT
            # 킥: 떨어지는 사인 + 짧은 딸깍
            add(drums, at, 0.3, lambda t: 0.9 * math.exp(-t * 14) * math.sin(2 * math.pi * (48 * t + 110 * (1 - math.exp(-t * 35)) / 35))
                + 0.15 * math.exp(-t * 400))
            if beat in (1, 3):  # 박수: 잡음 세 번 겹치기
                for d in (0.0, 0.011, 0.022):
                    add(drums, at + d, 0.18, lambda t: 0.16 * math.exp(-t * 38) * (rng.random() * 2 - 1))
            for s in range(4):  # 16분 하이햇, 엇박에 힘
                accent = 0.07 if s == 2 else 0.035
                if fill or s % 2 == 0 or s == 3:
                    add(drums, at + s * BEAT / 4, 0.04, lambda t, a=accent: a * math.exp(-t * 140) * (rng.random() * 2 - 1))
            # 쉬지 않는 8분 베이스: 몇 개 배음을 겹친 톱니 비슷한 소리, 엇박을 조금 세게
            f = _hz(root)
            for half in (0, 1):
                add(music, at + half * BEAT / 2, BEAT * 0.42, lambda t, f=f, a=0.16 + 0.06 * half: a * min(1.0, t / 0.005) * math.exp(-t * 7)
                    * sum(math.sin(2 * math.pi * f * h * t) / h for h in (1, 2, 3, 4)))
        # 16분 플럭 아르페지오: 위아래로 오간다
        order = [0, 1, 2, 3, 2, 1, 2, 3, 0, 1, 2, 3, 2, 3, 2, 1]
        for k, j in enumerate(order):
            f = _hz(notes[j])
            add(music, t0 + k * BEAT / 4, BEAT * 0.6,
                lambda t, f=f: 0.06 * min(1.0, t / 0.003) * math.exp(-t * 16) * (math.sin(2 * math.pi * f * t) + 0.3 * math.sin(4 * math.pi * f * t)))
        # 지속음: 근음 한 옥타브 아래가 낮게 웅웅거린다
        f = _hz(root - 12)
        add(music, t0, bar, lambda t, f=f: 0.05 * min(1.0, t / 0.05) * (math.sin(2 * math.pi * f * t) + 0.3 * math.sin(4 * math.pi * f * 1.003 * t)))
        # 라이저: 여덟 마디마다 마지막 마디에 잡음이 점점 커지며 다음 고리로 넘긴다
        if b % 8 == 7:
            add(drums, t0, bar, lambda t: 0.10 * (t / bar) ** 2 * (rng.random() * 2 - 1))
        # 패드: 마디 내내 낮게 깔린다
        for note in notes[:3]:
            f = _hz(note - 12)
            add(music, t0, bar, lambda t, f=f: 0.025 * min(1.0, t / 0.2) * min(1.0, (bar - t) / 0.2)
                * (math.sin(2 * math.pi * f * t) + 0.5 * math.sin(2 * math.pi * f * 1.005 * t)))
    # 펌핑: 박마다 음악을 눌렀다가 풀어 킥이 앞에 서게 한다
    beat_len = BEAT * RATE
    out = array("d", [0.0]) * total
    for i in range(total):
        phase = (i % beat_len) / beat_len
        out[i] = drums[i] + music[i] * (0.35 + 0.65 * min(1.0, phase / 0.45))
    peak = max(abs(v) for v in out) or 1.0
    for i in range(total):
        out[i] = out[i] / peak * 0.6
    return out


def write(target: Path) -> None:
    data = samples()
    pcm = array("h", (int(max(-1.0, min(1.0, v)) * 32767) for v in data))
    if pcm.itemsize != 2:
        raise RuntimeError("16비트 정수 배열을 만들 수 없어요.")
    if struct.pack("=h", 1) != struct.pack("<h", 1):
        pcm.byteswap()
    with wave.open(str(target), "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(RATE)
        w.writeframes(pcm.tobytes())
