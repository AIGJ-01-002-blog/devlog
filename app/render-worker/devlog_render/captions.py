"""내레이션 자막을 짧은 구절로 나누고, 음성에 맞춰 언제 띄울지 정한다 (#277).

쇼츠 자막은 5줄 덩어리보다 1~2줄 구절이 음성과 함께 바뀌어야 읽힌다.
- 나누기: 문장 부호에서 먼저 끊고, 긴 구절은 띄어쓰기 단위로 max_units 안에 담는다. 너무 짧은 꼬리는 앞 구절에 붙인다.
- 시각: 음성 엔진이 단어 시각을 주면(edge-tts) 그 시각을, 아니면 글자 수(문장 부호는 쉼으로 더 길게)에 비례해 나눈다.
"""

from __future__ import annotations

import re
from dataclasses import dataclass

from . import layout

#: 한 구절에 담는 글자 폭(한글 1칸). 두 줄(LINE_UNITS씩) 안에 들어가게 둔다
PHRASE_UNITS = 22
LINE_UNITS = 13
#: 빠른 견본 스타일(참고 영상 분석 #277): 한 줄에 2~6낱말, 1초 안팎으로 바뀌는 자막
FAST_PHRASE_UNITS = 11
#: 빠른 견본 스타일에서 영상 한 편에 색으로 강조할 낱말 수(빨강·노랑을 번갈아)
EMPHASIS_LIMIT = 3
#: 이보다 짧은 꼬리 구절은 앞 구절에 붙인다
MIN_TAIL_UNITS = 6
#: 시각 정보가 없을 때 문장 부호 뒤 쉼을 글자 몇 개로 칠지
PAUSE_WEIGHT = {",": 2.5, ".": 4.0, "!": 4.0, "?": 4.0, "…": 4.0, "。": 4.0}

_CLAUSE = re.compile(r"(?<=[,.!?…。])\s+")
_TRAILING = re.compile(r"[,.。]+$")


@dataclass(frozen=True)
class Word:
    """음성 엔진이 알려 준 단어 하나의 소리 구간(초)"""
    start: float
    end: float
    text: str


@dataclass(frozen=True)
class Cue:
    start: float
    end: float
    lines: list[str]


def phrases(text: str, max_units: float = PHRASE_UNITS) -> list[str]:
    out: list[str] = []
    for clause in _CLAUSE.split(" ".join(text.split())):
        words = [w for w in clause.split(" ") if w]
        if not words:
            continue
        pieces = _split_clause(words, max_units)
        tail = pieces[-1]
        if out and len(pieces) == 1 and layout.text_width(tail) < MIN_TAIL_UNITS and _fits(out[-1] + " " + tail, max_units + 5):
            out[-1] += " " + tail
        else:
            out.extend(pieces)
    return out


#: 다음 말에 붙어야 자연스러운 말(의존 명사·보조 용언). 이 앞에서는 끊지 않는다
_BOUND_NEXT = ("수", "것", "거", "줄", "데", "때", "적", "뿐", "만큼", "있", "없", "않", "못", "줘", "주", "봐", "버", "돼", "해요", "했어요")
#: 이걸로 끝나는 말 뒤는 끊기 좋다(이어 주는 말끝 > 조사)
_CLAUSE_END = ("는데", "니까", "어서", "아서", "해서", "워서", "면서", "지만", "으면", "면", "고", "며", "다가", "도록")
_PARTICLE_END = ("은", "는", "이", "가", "을", "를", "에", "에서", "로", "으로", "도", "와", "과", "만")


def _split_clause(words: list[str], max_units: float) -> list[str]:
    """한 절을 두 줄 안에 드는 구절로 나눈다. 가장 적은 구절 수 안에서 길이가 고르고 말이 자연스럽게 끊기는 곳을 고른다."""
    n = len(words)
    target = max_units * 0.7
    best: list[tuple[float, int]] = [(0.0, -1)] + [(float("inf"), -1)] * n
    for j in range(1, n + 1):
        for i in range(j - 1, -1, -1):
            piece = " ".join(words[i:j])
            if not _fits(piece, max_units) and j - i > 1:
                break
            cost = best[i][0] + 100 + (layout.text_width(piece) - target) ** 2 * 0.5
            if j < n:
                cost += _break_cost(words[j - 1], words[j])
            if cost < best[j][0]:
                best[j] = (cost, i)
    pieces, j = [], n
    while j > 0:
        i = best[j][1]
        pieces.append(" ".join(words[i:j]))
        j = i
    return pieces[::-1]


def _break_cost(before: str, after: str) -> float:
    before = before.rstrip(",.!?…")
    if after.startswith(_BOUND_NEXT) and len(after) <= 5:
        return 60.0
    if before.endswith(_CLAUSE_END):
        return -25.0
    if before.endswith(_PARTICLE_END):
        return -8.0
    return 0.0


def _fits(phrase: str, max_units: float) -> bool:
    """줄임표 없이 두 줄 안에 들어가는지"""
    return layout.text_width(phrase) <= max_units and len(layout.wrap(_TRAILING.sub("", phrase), LINE_UNITS)) <= 2


def display_lines(phrase: str) -> list[str]:
    """화면에 보일 줄. 끝의 쉼표·마침표는 떼고(물음표·느낌표는 둔다) 두 줄 안으로 나눈다.
    두 줄이면 위아래 길이가 비슷하고 말이 덜 어색하게 끊기는 곳을 고른다."""
    text = _TRAILING.sub("", phrase)
    if layout.text_width(text) <= LINE_UNITS:
        return [text]
    words = text.split(" ")
    best: tuple[float, list[str]] | None = None
    for k in range(1, len(words)):
        top, bottom = " ".join(words[:k]), " ".join(words[k:])
        widest = max(layout.text_width(top), layout.text_width(bottom))
        if widest > LINE_UNITS:
            continue
        cost = widest + abs(layout.text_width(top) - layout.text_width(bottom)) * 0.3 + _break_cost(words[k - 1], words[k]) / 10
        if best is None or cost < best[0]:
            best = (cost, [top, bottom])
    return best[1] if best else layout.wrap(text, LINE_UNITS, 2)


def cues(text: str, duration: float, words: list[Word] | None = None, tail: float = 0.0, max_units: float = PHRASE_UNITS) -> list[Cue]:
    """구절마다 [시작, 끝) 시각. 다음 구절이 뜰 때까지 앞 구절을 남겨 빈 화면이 없게 한다. 마지막 구절은 duration+tail까지."""
    parts = phrases(text, max_units)
    if not parts:
        return []
    lengths = [len(_compact(p)) for p in parts]
    char_times = _char_times(_compact(text), words) if words else None
    starts: list[float] = []
    if char_times:
        pos = 0
        for n in lengths:
            starts.append(_first_known(char_times, pos, pos + n))
            pos += n
        starts = _fill_unknown(starts, duration)
    else:
        weights = [sum(1 + PAUSE_WEIGHT.get(c, 0) for c in _compact(p)) for p in parts]
        total = sum(weights) or 1
        acc = 0.0
        for w in weights:
            starts.append(duration * acc / total)
            acc += w
    starts[0] = 0.0
    out = []
    for k, part in enumerate(parts):
        end = starts[k + 1] if k + 1 < len(parts) else duration + tail
        out.append(Cue(starts[k], max(end, starts[k] + 0.05), display_lines(part)))
    return out


def _compact(text: str) -> str:
    return "".join(text.split())


def _char_times(compact: str, words: list[Word]) -> list[float | None]:
    """내레이션 글자(띄어쓰기 뺀)마다 소리가 나는 시각. 단어를 찾지 못한 글자는 None"""
    times: list[float | None] = [None] * len(compact)
    cursor = 0
    for word in words:
        token = _compact(word.text)
        if not token:
            continue
        at = compact.find(token, cursor)
        if at < 0 or at - cursor > 12:  # 엔진이 숫자·기호를 읽는 말로 바꾼 단어는 건너뛴다
            continue
        span = max(word.end - word.start, 0.01)
        for k in range(len(token)):
            times[at + k] = word.start + span * k / len(token)
        cursor = at + len(token)
    return times


def _first_known(times: list[float | None], start: int, end: int) -> float | None:
    for t in times[start:end]:
        if t is not None:
            return t
    return None


def _fill_unknown(starts: list[float | None], duration: float) -> list[float]:
    """단어 시각을 찾지 못한 구절은 앞뒤 구절 사이를 고르게 나눈다"""
    known = [(k, t) for k, t in enumerate(starts) if t is not None]
    if not known:
        return [duration * k / len(starts) for k in range(len(starts))]
    out = list(starts)
    anchors = [(-1, 0.0), *known, (len(starts), duration)]
    for (a, ta), (b, tb) in zip(anchors, anchors[1:]):
        for k in range(a + 1, b):
            out[k] = ta + (tb - ta) * (k - a) / (b - a)
    # 시각이 거꾸로 가지 않게
    for k in range(1, len(out)):
        out[k] = max(out[k], out[k - 1])
    return out  # type: ignore[return-value]


def pages(text: str, duration: float, words: list[Word] | None = None, tail: float = 0.0, max_units: float = PHRASE_UNITS) -> list[dict]:
    """Remotion 자막용: 구절(cues)마다 낱말과 낱말이 소리 나는 시각(ms). 단어 시각이 없으면 구절 안에서 글자 수로 나눈다.
    max_units를 줄이면 한 줄짜리 짧은 자막(빠른 견본 스타일은 FAST_PHRASE_UNITS)이 된다."""
    parts = phrases(text, max_units)
    timed = cues(text, duration, words, tail, max_units)
    char_times = _char_times(_compact(text), words) if words else None
    out, pos = [], 0
    for part, cue in zip(parts, timed):
        tokens = _TRAILING.sub("", part).split(" ")
        lengths = [len(_compact(tok)) for tok in tokens]
        total = sum(lengths) or 1
        starts, acc = [], 0
        for n in lengths:
            known = _first_known(char_times, pos + acc, pos + acc + n) if char_times else None
            guess = cue.start + (cue.end - cue.start) * acc / total
            starts.append(known if known is not None and cue.start <= known < cue.end else guess)
            acc += n
        pos += len(_compact(part))
        for k in range(1, len(starts)):
            starts[k] = max(starts[k], starts[k - 1])
        # 낱말 하나는 @remotion/captions의 Caption 모양(text·startMs·endMs·timestampMs·confidence)으로 넘긴다
        items = []
        for k, tok in enumerate(tokens):
            if not tok:
                continue
            start_ms = round(starts[k] * 1000)
            end_ms = round((starts[k + 1] if k + 1 < len(starts) else cue.end) * 1000)
            items.append({"text": tok, "startMs": start_ms, "endMs": end_ms, "timestampMs": (start_ms + end_ms) // 2, "confidence": None})
        out.append({"startMs": round(cue.start * 1000), "endMs": round(cue.end * 1000), "words": items})
    return out


def emphasize(scene_pages: list[list[dict]], keywords: list[str], limit: int = EMPHASIS_LIMIT) -> None:
    """빠른 견본 스타일: 장면마다 요점 자막(keywords[i])에 든 낱말이 내레이션에 처음 나올 때 한 번 색을 칠한다.
    영상 한 편에 limit번까지만, 빨강·노랑을 번갈아 쓴다. 낱말 dict에 "emph"를 더한다(제자리에서 고친다)."""
    used = 0
    for pages_, keyword in zip(scene_pages, keywords):
        if used >= limit:
            return
        keys = [_compact(_TRAILING.sub("", k)) for k in keyword.split() if len(_compact(k)) >= 2]
        hit = next((w for page in pages_ for w in page["words"]
                    if any(k[:2] in _compact(w["text"]) for k in keys)), None)
        if hit is not None:
            hit["emph"] = "red" if used % 2 == 0 else "yellow"
            used += 1
