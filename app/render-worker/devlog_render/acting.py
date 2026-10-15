"""연출: 장면 글에서 캐릭터 연기를 고르고, 효과음 시각과 입 모양을 정한다 (#277).

그리는 쪽(Remotion 화면 remotion/, Pillow 무대 stage.py)과 상관없는 판단만 여기 둔다. Pillow 없이도 불러올 수 있다.
"""

from __future__ import annotations

import math
import re
import struct
import wave
from dataclasses import dataclass, field
from pathlib import Path

from . import layout

ACTIONS = ("explain", "shock", "typing", "panic", "restore", "wait", "fast", "idea", "protect", "think", "celebrate")


@dataclass
class Visual:
    action: str
    code: list[str] = field(default_factory=list)
    focus: int | None = None  # 강조할 코드 줄
    good: bool = False  # 강조가 고친 줄(초록)인지 문제 줄(빨강)인지
    count: int | None = None  # 요청 수 등
    before: str | None = None  # 느렸던 값(예: 4초)
    after: str | None = None  # 빨라진 값
    label: str = ""  # 소품에 적을 짧은 말
    shell: bool = False


# ── 상황 읽기 ────────────────────────────────────────────────────────────────

_DURATION = re.compile(r"(\d+(?:[.,]\d+)?)\s*(밀리초|ms|초|분|시간)")
_COUNT = re.compile(r"(\d[\d,]*)\s*(번|회|개|건)")
_SHELL = re.compile(r"^\s*(\$|docker|git|npm|npx|yarn|pnpm|kubectl|pg_|psql|curl|sudo|apt|brew|pip|python|java|mvn|\./|cd |ls|rm |cp |mv )")
_LABELS = ["볼륨", "DB", "데이터", "파일", "글", "서버", "백업"]


def infer(scene, all_text: str = "") -> Visual:
    """장면 글(요점·내레이션·코드)에서 상황을 읽어 연기를 고른다. scene.visual이 있으면 그걸 먼저 쓴다."""
    text = f"{scene.caption} {scene.narration}"
    code = layout.code_lines(scene.code, 34, 8) if scene.code else []
    label = next((w for w in _LABELS if w in text), "데이터")
    explicit = getattr(scene, "visual", None)
    action = explicit.get("action") if isinstance(explicit, dict) else explicit
    if action not in ACTIONS:
        action = None

    durations = _DURATION.findall(text)
    if action in (None, "fast") and re.search(r"빨라|떨어졌|줄었|단축|배\s*빠", text) and (durations or action):
        others = [d for d in _DURATION.findall(all_text) if d not in durations]
        if action or len(durations) >= 2 or others:
            after = durations[-1] if durations else None
            before = durations[0] if len(durations) >= 2 else (others[0] if others else None)
            return Visual("fast", before=_fmt(before), after=_fmt(after), label=_ratio(text))
    if action is None and code:
        fixed = bool(re.search(r"해결|고쳤|비워|바꿨|막았|걸었|추가", text))
        focus = next((k for k, line in enumerate(code) if re.search(r"(//|#|--)", line) and k > 0), None)
        if focus is None and fixed:
            focus = max((k for k, line in enumerate(code) if line.strip()), default=None)
        if re.search(r"복원|되살|백업", text):
            return Visual("restore", code=code, label=label)
        return Visual("typing", code=code, focus=focus, good=fixed and not re.search(r"원인|문제|범인|함정|버그", text),
                      shell=any(_SHELL.match(line) for line in code))
    if action is None:
        rules = [
            ("shock", r"사라졌|지웠|삭제|날렸|날아|지워|없어졌|망가|터졌"),
            ("restore", r"복원|되살|백업에서|살렸"),
            ("panic", r"요청|트래픽|폭주|무한|로그에|에러가 쏟"),
            # 권하는 말(~보세요)은 느리다는 말이 함께 있어도 팁 장면이다(예: 느리면 먼저 실행 계획부터 보세요)
            ("idea", r"(?<!녕)하세요|보세요|두세요|팁|켜 두|기억|추천|먼저"),
            ("wait", r"걸렸|느려|느리|로딩|오래|기다"),
            ("protect", r"막았|막아|지켜|보안|안전"),
            ("think", r"원인|왜|범인|이유|문제는|알고 보니"),
            ("celebrate", r"성공|해결|완성|빨라|끝"),
        ]
        action = next((name for name, pattern in rules if re.search(pattern, text)), "explain")
    count = _COUNT.search(text)
    v = Visual(action, code=code, label=label)
    if action == "panic":
        v.count = int(count.group(1).replace(",", "")) if count else 100
    if action == "wait":
        v.before = _fmt(durations[0]) if durations else None
    return v


def _fmt(d) -> str | None:
    if not d:
        return None
    number, unit = d
    return f"{number}{'ms' if unit in ('밀리초', 'ms') else unit}"


def _seconds(value: str | None) -> float | None:
    if not value:
        return None
    m = re.match(r"([\d.,]+)(ms|초|분|시간)", value)
    if not m:
        return None
    n = float(m.group(1).replace(",", "."))
    return n / 1000 if m.group(2) == "ms" else n * {"초": 1, "분": 60, "시간": 3600}[m.group(2)]


def _ratio(text: str) -> str:
    m = re.search(r"(\d+)\s*배", text)
    return f"{m.group(1)}배 빨라짐" if m else "빨라짐!"


# ── 입 모양: 소리 크기 ─────────────────────────────────────────────────────────

def mouth_envelope(wav: Path, fps: int) -> list[float]:
    """프레임마다 0~1 입 벌림. 16비트 PCM wav의 RMS를 쓰고, 조금 부드럽게 만든다."""
    try:
        with wave.open(str(wav)) as w:
            rate, channels, width = w.getframerate(), w.getnchannels(), w.getsampwidth()
            raw = w.readframes(w.getnframes())
    except (wave.Error, OSError, EOFError):
        return []
    if width != 2 or not raw:
        return []
    count = len(raw) // 2
    samples = struct.unpack(f"<{count}h", raw[:count * 2])
    step = int(rate * channels / fps)
    levels = []
    for i in range(0, len(samples), step):
        chunk = samples[i:i + step:4] or (0,)
        levels.append(math.sqrt(sum(v * v for v in chunk) / len(chunk)))
    if not levels:
        return []
    peak = sorted(levels)[int(len(levels) * 0.95)] or 1.0
    out, prev = [], 0.0
    for v in levels:
        target = min(1.0, v / peak)
        target = 0.0 if target < 0.12 else target
        prev = prev * 0.35 + target * 0.65
        out.append(prev)
    return out


# ── 그리기 도구 ───────────────────────────────────────────────────────────────


def effects(v: Visual, lead: float, speech: float, seconds: float) -> list[tuple[str, float, float]]:
    """연기에 맞춘 효과음 (소리 이름, 장면 안 시각, 크기). 시각은 화면 쪽 연기의 움직임과 맞춘다."""
    type_end = lead + max(0.8, speech * 0.3)  # remotion/src/actions.tsx Typing과 같게
    fill_end = 0.5 + max(1.0, speech * 0.6)
    table = {
        "shock": [("boing", 0.9, 0.55), ("whoosh", 1.0, 0.45)],
        "typing": [("typing", min(0.2, lead), 0.35)] + ([("ding" if v.good else "boing", type_end + 0.15, 0.5)] if v.focus is not None else []),
        "panic": [("whoosh", 0.3, 0.35), ("boing", 0.9, 0.45)],
        "restore": [("whoosh", 0.5, 0.35), ("ding", fill_end, 0.55)],
        "fast": [("whoosh", 0.6, 0.45), ("ding", 1.0, 0.55)],
        "idea": [("ding", 0.5, 0.55)],
        "protect": [("ding", 0.4, 0.55)],
        "think": [("pop", 0.3, 0.4), ("pop", 0.55, 0.4), ("pop", 0.8, 0.4)],
        "celebrate": [("ding", 0.2, 0.55)],
        "wait": [("tick", 0.5 + k * 1.0, 0.35) for k in range(int(min(speech, 4)))],
    }
    pop = [("pop", 0.0, 0.4)]  # 요점 자막이 떠오를 때
    return pop + [e for e in table.get(v.action, []) if e[1] < seconds]
