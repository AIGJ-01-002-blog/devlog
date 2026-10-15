"""매매 재현 배경음 (#265). 차트를 빨리감기하는 영상에 맞춘 긴장감 있는 반복 음악을 워커가 직접 합성한다(저작권 걱정 없음).

참고 영상(매매 기록 빨리감기)의 배경음처럼 136bpm 다크 트랩이다. 저음이 두껍고 박자가 또렷하다.
4마디 진행(Am → F → Dm → E)을 돌며 24마디(약 42초)를 한 고리로 만든다.
- 0마디: 들어오는 마디. 패드와 하이햇 위로 잡음이 차오른다.
- 1~8마디: 킥·808 저음·2·4박 박수·8분 하이햇(두 마디마다 끝에 셋잇단 굴림).
- 9~16마디: 고조. 16분 하이햇, 열린 하이햇, 높은 플럭 선율이 더해지고 끝 마디에 스네어 굴림과 잡음이 차오른다.
- 17~23마디: 다시 1~8마디처럼.
킥마다 나머지 소리를 살짝 눌러(펌핑) 박이 앞에 서게 한다. 808은 휴대폰 스피커에서도 들리게 배음을 섞고 살짝 찌그러뜨린다.
들어오는 때·결산에서 줄이기·내레이션 때 줄이기는 렌더러 믹서(render.py _join)가 맡는다.
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

VERSION = 3
RATE = 32000
BPM = 136
BEAT = 60 / BPM
STEP = BEAT / 4
#: (808 MIDI, 패드·선율 MIDI 세 음). 한 마디씩
PROGRESSION = [
    (33, [57, 60, 64]),  # Am
    (29, [53, 57, 60]),  # F
    (38, [62, 65, 69]),  # Dm
    (28, [56, 59, 64]),  # E (G#)
]
BARS = 24
#: 고조 구간(마디 번호)
LIFT = range(9, 17)
#: 마디 안 16분 칸: 킥(808이 같이 친다)과 박수
KICKS = [0, 6, 10]
LIFT_KICKS = [0, 3, 6, 10, 14]
CLAPS = [4, 12]


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


def _hz(midi: float) -> float:
    return 440.0 * 2 ** ((midi - 69) / 12)


def samples() -> array:
    bar = BEAT * 4
    total = int(RATE * bar * BARS)
    drums = array("d", [0.0]) * total
    bass = array("d", [0.0]) * total
    music = array("d", [0.0]) * total
    rng = random.Random(265)

    def add(buf: array, start: float, seconds: float, fn) -> None:
        i0 = int(start * RATE)
        for k in range(max(0, min(int(seconds * RATE), total - i0))):
            buf[i0 + k] += fn(k / RATE)

    def noise(t: float) -> float:
        return rng.random() * 2 - 1

    for b in range(BARS):
        root, chord = PROGRESSION[b % len(PROGRESSION)]
        t0 = b * bar
        intro = b == 0
        lift = b in LIFT
        # 패드: 어두운 화음이 마디 내내 낮게 깔린다(배음 몇 개를 겹친 톱니 비슷한 소리, 살짝 어긋난 두 겹)
        for note in chord:
            f = _hz(note - 12)
            add(music, t0, bar, lambda t, f=f: 0.022 * min(1.0, t / 0.15) * min(1.0, (bar - t) / 0.1)
                * sum(math.sin(2 * math.pi * f * h * t) / h + math.sin(2 * math.pi * f * 1.004 * h * t) / h for h in (1, 2, 3)))
        # 하이햇: 8분(고조 구간은 16분), 두 마디마다 끝 박에 16분 셋잇단 굴림
        roll = b % 2 == 1 or b == LIFT[-1]
        for s in range(16):
            if roll and s >= 12:
                continue
            if not lift and s % 2:
                continue
            accent = 0.06 if s % 4 == 2 else 0.035
            add(drums, t0 + s * STEP, 0.04, lambda t, a=accent: a * math.exp(-t * 150) * noise(t))
        if roll:
            for k in range(6):
                add(drums, t0 + 3 * BEAT + k * BEAT / 6, 0.03, lambda t, a=0.03 + 0.006 * k: a * math.exp(-t * 170) * noise(t))
        if lift:  # 열린 하이햇: 엇박마다 길게
            for beat in range(4):
                add(drums, t0 + beat * BEAT + BEAT / 2, 0.16, lambda t: 0.035 * math.exp(-t * 22) * noise(t))
        if intro or b == LIFT[-1]:  # 잡음이 차오르며 다음 마디로 넘긴다
            add(drums, t0, bar, lambda t: 0.09 * (t / bar) ** 2.5 * noise(t))
        if intro:
            continue
        # 킥과 808: 같은 칸에 치고, 808은 다음 킥 직전까지 길게 울린다. 4마디 끝은 한 옥타브 위로 미끄러진다
        kicks = LIFT_KICKS if lift else KICKS
        for j, s in enumerate(kicks):
            at = t0 + s * STEP
            add(drums, at, 0.22, lambda t: 0.85 * math.exp(-t * 18)
                * math.sin(2 * math.pi * (50 * t + 100 * (1 - math.exp(-t * 40)) / 40)) + 0.12 * math.exp(-t * 500))
            end = (kicks[j + 1] if j + 1 < len(kicks) else 16) * STEP
            length = end - s * STEP
            f = _hz(root)
            glide = b % 4 == 3 and j == len(kicks) - 1

            def tone(t: float, f=f, length=length, glide=glide) -> float:
                env = min(1.0, t / 0.004) * math.exp(-t * 1.6) * min(1.0, (length - t) / 0.02)
                pitch = f * (1 + (2 ** (min(1.0, t / length) ** 2) - 1) if glide else 1)
                phase = 2 * math.pi * pitch * t
                return 0.5 * env * math.tanh(1.8 * (math.sin(phase) + 0.3 * math.sin(2 * phase) + 0.1 * math.sin(3 * phase)))

            add(bass, at, length, tone)
        for s in CLAPS:  # 박수: 짧은 잡음 세 겹 + 몸통
            for d in (0.0, 0.009, 0.018):
                add(drums, t0 + s * STEP + d, 0.16, lambda t: 0.17 * math.exp(-t * 34) * noise(t))
            add(drums, t0 + s * STEP, 0.08, lambda t: 0.12 * math.exp(-t * 40) * math.sin(2 * math.pi * 190 * t))
        if b == LIFT[-1]:  # 스네어 굴림: 마지막 박을 16분 셋잇단으로 몰아친다
            for k in range(6):
                add(drums, t0 + 3 * BEAT + k * BEAT / 6, 0.07, lambda t, a=0.06 + 0.018 * k: a * math.exp(-t * 40) * noise(t))
        if lift:  # 높은 플럭 선율: 화음 음을 8분으로 오르내린다
            order = [0, 2, 1, 2, 0, 2, 1, 0] if b % 2 == 0 else [2, 1, 2, 0, 2, 1, 0, 1]
            for k, j in enumerate(order):
                f = _hz(chord[j] + 12)
                add(music, t0 + k * BEAT / 2, BEAT * 0.5,
                    lambda t, f=f: 0.05 * min(1.0, t / 0.003) * math.exp(-t * 11) * (math.sin(2 * math.pi * f * t) + 0.35 * math.sin(4 * math.pi * f * t)))
    # 펌핑: 박마다 패드·선율을 눌렀다가 풀어 킥이 앞에 서게 한다
    beat_len = BEAT * RATE
    out = array("d", [0.0]) * total
    for i in range(total):
        phase = (i % beat_len) / beat_len
        out[i] = drums[i] + bass[i] + music[i] * (0.3 + 0.7 * min(1.0, phase / 0.5))
    peak = max(abs(v) for v in out) or 1.0
    for i in range(total):
        out[i] = out[i] / peak * 0.7
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
