"""매매 재현 효과음 (#265). 음원 파일 없이 표준 라이브러리로 직접 합성한다(저작권 걱정 없음, 운영비 0원).

replay.cues()가 낸 순서표(시각, 종류)를 영상 길이의 스테레오 WAV 한 개로 만든다.
렌더러는 이 트랙을 내레이션·배경음과 섞기만 하면 되므로, 렌더 방식이 바뀌어도 그대로 쓸 수 있다.
"""

from __future__ import annotations

import math
import random
import sys
import wave
from array import array
from pathlib import Path

RATE = 44_100
#: 효과음 전체 크기. 내레이션보다 작게 깐다
MASTER = 0.55


def _tone(freqs: list[tuple[float, float]], decay: float, gain: float, harmonics: float = 0.35) -> list[float]:
    """(주파수, 길이) 음을 차례로 이어 친다. 각 음은 짧게 올라왔다가 decay로 줄어드는 종소리 같은 소리"""
    out: list[float] = []
    for freq, seconds in freqs:
        n = int(RATE * seconds)
        for i in range(n):
            t = i / RATE
            env = min(1.0, t / 0.004) * math.exp(-decay * t)
            v = math.sin(2 * math.pi * freq * t) + harmonics * math.sin(4 * math.pi * freq * t)
            out.append(gain * env * v)
    return out


def _tick() -> list[float]:
    """캔들 한 개가 찍힐 때의 짧은 딸깍 소리"""
    n = int(RATE * 0.035)
    return [0.22 * math.exp(-140 * i / RATE) * math.sin(2 * math.pi * 2100 * i / RATE) for i in range(n)]


def _whoosh(seed: int) -> list[float]:
    """띠가 밀려 들어올 때의 바람 소리: 잡음을 부드럽게 걸러 올라갔다 내려가게"""
    rnd = random.Random(seed)
    n = int(RATE * 0.32)
    out, low = [], 0.0
    for i in range(n):
        t = i / n
        alpha = 0.04 + 0.25 * t  # 점점 밝아지는 잡음
        low += alpha * ((rnd.random() * 2 - 1) - low)
        out.append(0.5 * math.sin(math.pi * t) ** 2 * low)
    return out


def _cash() -> list[float]:
    """매수: 위로 올라가는 두 음 + 동전 짤랑"""
    body = _tone([(880, 0.09), (1318.5, 0.32)], 9, 0.42)
    coin = _tone([(2637, 0.18)], 22, 0.18, 0.6)
    return _add(body, coin, int(RATE * 0.09))


def _sell() -> list[float]:
    """매도: 내려가는 두 음 + 낮은 쿵"""
    body = _tone([(1318.5, 0.09), (880, 0.32)], 9, 0.42)
    thud = [0.5 * math.exp(-18 * i / RATE) * math.sin(2 * math.pi * (95 - 40 * i / RATE) * i / RATE) for i in range(int(RATE * 0.25))]
    return _add(body, thud, 0)


def _win() -> list[float]:
    """익절 결과: 도·미·솔·도 올라가는 아르페지오"""
    return _tone([(523.3, 0.1), (659.3, 0.1), (784.0, 0.1), (1046.5, 0.5)], 5, 0.4)


def _loss() -> list[float]:
    """손절 결과: 내려가는 낮은 두 음"""
    return _tone([(392.0, 0.16), (261.6, 0.55)], 4, 0.42, 0.5)


def _add(a: list[float], b: list[float], offset: int) -> list[float]:
    out = a + [0.0] * max(0, offset + len(b) - len(a))
    for i, v in enumerate(b):
        out[offset + i] += v
    return out


SOUNDS = {"tick": _tick, "buy": _cash, "sell": _sell, "win": _win, "loss": _loss}


def render(cues, seconds: float, out: Path) -> Path:
    """cues(at, kind 속성을 가진 것들)를 seconds 길이의 44.1kHz 스테레오 16비트 WAV로 쓴다."""
    n = int(RATE * seconds) + 1
    track = [0.0] * n
    cache: dict[str, list[float]] = {}
    for k, cue in enumerate(cues):
        if cue.kind == "whoosh":
            sound = _whoosh(k)
        else:
            if cue.kind not in SOUNDS:
                continue
            sound = cache.setdefault(cue.kind, SOUNDS[cue.kind]())
        start = int(cue.at * RATE)
        for i, v in enumerate(sound):
            if 0 <= start + i < n:
                track[start + i] += v
    frames = array("h")
    for v in track:
        s = max(-32767, min(32767, int(v * MASTER * 32767)))
        frames.append(s)
        frames.append(s)
    if sys.byteorder == "big":
        frames.byteswap()
    with wave.open(str(out), "wb") as w:
        w.setnchannels(2)
        w.setsampwidth(2)
        w.setframerate(RATE)
        w.writeframes(frames.tobytes())
    return out


#: 종류별 크기 (장면 효과음 훅에 넘길 때)
VOLUMES = {"tick": 0.35, "whoosh": 0.6, "buy": 0.9, "sell": 0.9, "win": 1.0, "loss": 1.0}


def sound_file(kind: str, folder: Path) -> Path:
    """효과음 한 종류를 WAV 파일로. 다른 렌더러(장면 효과음 훅 등)가 파일로 받아 쓸 때"""
    path = folder / f"trade-{kind}.wav"
    if not path.exists():
        render([_At(0.0, kind)], len(_whoosh(0) if kind == "whoosh" else SOUNDS[kind]()) / RATE + 0.05, path)
    return path


def per_scene(cues, starts: list[float]) -> list[tuple[int, float, str, float]]:
    """영상 전체 시각의 순서표를 (장면 번호, 장면 안 시각, 종류, 크기)로. 장면 시작 시각 기준으로 효과음을 놓는 렌더러용"""
    out = []
    for cue in cues:
        scene = max((k for k, s in enumerate(starts) if s <= cue.at), default=0)
        out.append((scene, round(cue.at - starts[scene], 3), cue.kind, VOLUMES.get(cue.kind, 0.8)))
    return out


class _At:
    def __init__(self, at: float, kind: str):
        self.at, self.kind = at, kind
