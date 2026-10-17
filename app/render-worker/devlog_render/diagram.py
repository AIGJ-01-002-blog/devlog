"""CS 설명 도식(#277): 대본의 scenes[].diagram을 다듬고, 칸마다 나타날 때를 내레이션에 맞춘다.

대본 모양(모두 선택):
- {"type": "flow", "items": ["브라우저", "서버", {"label": "DB", "icon": "database", "note": "디스크"}]}  왼쪽→오른쪽 흐름
- {"type": "stack", "items": ["컨테이너", "볼륨", "디스크"], "highlight": 1}  위→아래 층, highlight 층을 끝에 강조
- {"type": "steps", "items": ["덤프 뜨기", "외장 디스크로 복사", "복원 연습"]}  순서
- {"type": "compare", "left": {"title": "캐시 없음", "items": ["매번 DB 조회"]}, "right": {"title": "캐시 있음", "items": ["메모리에서 바로"]}}
- {"type": "timeline", "items": [{"label": "2019", "note": "도커 도입"}, "2022", "2024"]}  왼쪽→오른쪽 시간 흐름(점과 선)
- {"type": "define", "term": "인덱스", "items": ["책 뒤 찾아보기처럼", "원하는 줄로 바로 간다"]}  개념 정의 카드(용어 + 풀이 1~3줄)
- {"type": "number", "label": "목록 조회", "from": 4000, "to": 100, "unit": "ms"}  숫자가 from에서 to로 바뀌며 올라가거나 내려간다

칸은 내레이션이 그 이름표의 첫 낱말을 말할 때 나타난다. 말하지 않는 칸은 앞뒤 칸 사이(또는 말의 앞 70%)에 고르게 놓는다.
"""

from __future__ import annotations

import re

TYPES = ("flow", "stack", "steps", "compare", "timeline", "define", "number")
MAX_ITEMS = {"flow": 4, "stack": 5, "steps": 5, "compare": 4, "timeline": 5, "define": 3}
LABEL_MAX = 20
UNIT_MAX = 8
#: number 도식의 숫자 범위. 화면 한 줄에 들어가는 크기까지
NUMBER_MAX = 1e9
#: 이름표가 나타나는 간격의 최소(ms). 한꺼번에 튀어나오지 않게
MIN_GAP_MS = 250


def parse(raw) -> dict | None:
    """대본의 diagram을 다듬는다. 모양이 틀리면 None(그 장면은 캐릭터 연기로 그린다)."""
    if not isinstance(raw, dict) or raw.get("type") not in TYPES:
        return None
    kind = raw["type"]
    if kind == "compare":
        left, right = _side(raw.get("left")), _side(raw.get("right"))
        return {"type": kind, "left": left, "right": right} if left and right else None
    if kind == "number":
        return _number(raw)
    items = _items(raw.get("items"), MAX_ITEMS[kind])
    if kind == "define":
        term = _label(raw.get("term"))
        return {"type": kind, "term": term, "items": items} if term and items else None
    if len(items) < 2:
        return None
    out: dict = {"type": kind, "items": items}
    if kind == "stack":
        h = raw.get("highlight")
        out["highlight"] = h if isinstance(h, int) and not isinstance(h, bool) and 0 <= h < len(items) else None
    return out


def _number(raw: dict) -> dict | None:
    """숫자 하나가 바뀌는 도식. 바뀌는 때는 이름표(label)를 말할 때다(items 한 칸으로 둔다)"""
    label = _label(raw.get("label"))
    values = [raw.get("from"), raw.get("to")]
    if not label or not all(isinstance(v, (int, float)) and not isinstance(v, bool) and abs(v) <= NUMBER_MAX for v in values):
        return None
    unit = " ".join(raw["unit"].split())[:UNIT_MAX] if isinstance(raw.get("unit"), str) else ""
    return {"type": "number", "from": values[0], "to": values[1], "unit": unit,
            "items": [{"label": label, "icon": None, "note": None}]}


def _side(raw) -> dict | None:
    if not isinstance(raw, dict):
        return None
    title = _label(raw.get("title"))
    items = _items(raw.get("items"), MAX_ITEMS["compare"])
    return {"title": title, "items": items} if title and items else None


def _items(raw, limit: int) -> list[dict]:
    out = []
    for it in raw if isinstance(raw, list) else []:
        if isinstance(it, str):
            it = {"label": it}
        if not isinstance(it, dict):
            continue
        label = _label(it.get("label"))
        if not label:
            continue
        icon = it.get("icon") if isinstance(it.get("icon"), str) and it.get("icon") else None
        note = _label(it.get("note"))
        out.append({"label": label, "icon": icon, "note": note or None})
    return out[:limit]


def _label(v) -> str:
    return " ".join(v.split())[:LABEL_MAX] if isinstance(v, str) else ""


def timed(d: dict, pages: list[dict], speech: float) -> dict:
    """칸마다 atMs(목소리 시작 기준 나타날 때)를 붙인 새 dict. pages는 captions.pages() 결과."""
    words = [(_compact(w["text"]).lower(), w["startMs"]) for p in pages for w in p["words"]]
    if d["type"] == "compare":
        seq = [{"label": d["left"]["title"]}, *d["left"]["items"], {"label": d["right"]["title"]}, *d["right"]["items"]]
    else:
        seq = d["items"]
    times = _times([s["label"] for s in seq], words, speech)
    if d["type"] == "compare":
        nl = len(d["left"]["items"])
        it = iter(times)
        left = {"title": d["left"]["title"], "atMs": next(it), "items": [{**x, "atMs": next(it)} for x in d["left"]["items"]]}
        right = {"title": d["right"]["title"], "atMs": next(it), "items": [{**x, "atMs": next(it)} for x in d["right"]["items"]]}
        assert len(left["items"]) == nl
        return {"type": "compare", "left": left, "right": right}
    if d["type"] == "define":
        # 용어 칸이 먼저 나오고 풀이 줄이 차례로 나온다
        times = _times([d["term"], *[x["label"] for x in d["items"]]], words, speech)
        return {**d, "term": {"label": d["term"], "atMs": times[0]},
                "items": [{**x, "atMs": t} for x, t in zip(d["items"], times[1:])]}
    return {**d, "items": [{**x, "atMs": t} for x, t in zip(d["items"], times)]}


def _compact(text: str) -> str:
    return re.sub(r"[\s,.!?…·:;\"'()\[\]{}]", "", text)


def _times(labels: list[str], words: list[tuple[str, int]], speech: float) -> list[int]:
    """이름표 순서대로 내레이션에서 첫 낱말을 찾는다(앞에서 찾은 자리 뒤로만). 못 찾으면 None으로 두고 나중에 채운다."""
    found: list[int | None] = []
    cursor = 0
    for label in labels:
        first = _compact(label.split(" ")[0]).lower()
        key = first[:4] if len(first) > 4 else first
        at = None
        if key:
            for k in range(cursor, len(words)):
                if key in words[k][0]:
                    at, cursor = words[k][1], k + 1
                    break
        found.append(at)
    end = max(1, int(speech * 1000 * 0.7))
    anchors = [(-1, 0)] + [(k, t) for k, t in enumerate(found) if t is not None] + [(len(labels), end)]
    out = list(found)
    for (a, ta), (b, tb) in zip(anchors, anchors[1:]):
        for k in range(a + 1, b):
            out[k] = round(ta + (tb - ta) * (k - a) / (b - a)) if a >= 0 else round(tb * k / b) if b else 0
    result: list[int] = []
    for t in out:
        t = int(t or 0)
        result.append(max(t, result[-1] + MIN_GAP_MS) if result else t)
    return result


def reveal_times(d: dict) -> list[int]:
    """timed()가 붙인 atMs 모두(효과음용). 비교의 제목 칸도 포함"""
    if d["type"] == "compare":
        return [d[s]["atMs"] for s in ("left", "right")] + [x["atMs"] for s in ("left", "right") for x in d[s]["items"]]
    if d["type"] == "define":
        return [d["term"]["atMs"]] + [x["atMs"] for x in d["items"]]
    return [x["atMs"] for x in d["items"]]
