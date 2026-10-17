"""영상마다 다른 화면 연출 (2026-10-17 민서님 요청: 단조롭거나 반복적인 영상은 수익화가 안 된다).

유튜브 파트너 프로그램은 '보는 사람이 채널의 영상끼리 서로 다르다고 알아볼 수 있어야' 수익을 낸다(진정성 없는 콘텐츠 정책).
그래서 영상 한 편마다 룩(색·배경 무늬·자막 모양·요점 자막 등장 방식·배경음)을 하나 고르고,
장면마다 전환·카메라 움직임·화면 배치를 앞 장면과 겹치지 않게 고른다.

고르는 일은 작업 번호와 제목으로 만든 씨앗(seed)으로 정해서, 같은 작업을 다시 그려도 같은 영상이 나온다(대본을 고치면 달라진다).
서버가 같은 회원의 최근 영상 연출(룩:배경음)을 함께 주면 그 조합을 피해서 고른다(채널에서 이어 보는 영상끼리 겹치지 않게).
룩 이름과 값은 remotion/src/looks.ts와 맞춘다.
"""

from __future__ import annotations

import hashlib
import random
from dataclasses import dataclass, field

#: 룩 이름. remotion/src/looks.ts의 LOOKS 키와 같아야 한다
LOOKS = ["paper", "night", "mint", "sunset", "mono", "grape", "ocean"]

#: 장면 전환 이름. remotion/src/looks.ts의 transition()과 같다. cut은 전환 없이 끊는다(휙 소리도 없다)
TRANSITIONS = ["slide-right", "slide-left", "slide-up", "slide-down", "fade", "wipe-left", "wipe-right", "wipe-diagonal",
               "flip", "flip-up", "clock", "iris", "push", "cut"]
#: 룩마다 자주 쓰는 전환(앞쪽일수록 자주). 룩끼리 전환 느낌도 다르게
TRANSITION_POOLS = {
    "paper": ["slide-right", "fade", "slide-up", "wipe-left", "cut"],
    "night": ["push", "cut", "iris", "slide-left", "fade"],
    "mint": ["wipe-right", "slide-up", "fade", "clock", "cut"],
    "sunset": ["flip", "slide-left", "fade", "push", "cut"],
    "mono": ["cut", "push", "wipe-diagonal", "slide-down", "cut"],
    "grape": ["iris", "flip-up", "slide-right", "fade", "cut"],
    "ocean": ["slide-up", "wipe-left", "fade", "push", "clock"],
}
#: 휙 소리 대신 낼 소리. 끊는 전환(cut)은 소리 없이, 나머지는 전환 느낌에 맞춘다
TRANSITION_SOUND = {"cut": None, "fade": None, "push": "boing", "iris": "pop", "clock": "tick", "flip": "whoosh", "flip-up": "whoosh"}

#: 무대 카메라 움직임. remotion/src/looks.ts의 camera()와 같다
CAMERAS = ["punch", "zoom-in", "zoom-out", "pan-left", "pan-right", "tilt-up", "still"]

#: 장면 화면 배치. stage: 위 요점·가운데 무대·아래 자막(Scene.tsx), split: 위 그림·아래 캐릭터·가운데 자막(FastScene.tsx)
LAYOUTS = ["stage", "split"]
#: 룩마다 split을 쓸 몫(0~1). 같은 영상 안에서도 배치가 바뀌게 한다
SPLIT_SHARE = {"paper": 0.3, "night": 0.5, "mint": 0.35, "sunset": 0.4, "mono": 0.6, "grape": 0.4, "ocean": 0.35}

#: 배경음 갈래 수(bgm.VARIANTS와 같다)
MUSIC_VARIANTS = 4
#: 최근 몇 편의 룩을 피할지(룩이 7개라 3편을 빼도 넷이 남는다). 룩:배경음 조합은 서버가 준 만큼(최근 5편) 모두 피한다
AVOID_LOOKS = 3

#: 영상 중간의 '사건'(참고 영상 분석 2026-10-17: 잘 되는 릴스는 한 편에 한두 번 흔들림·섬광·도장 같은 순간이 있다).
#: remotion/src/looks.ts의 Event와 같다. 사건이 나는 장면은 목소리가 시작할 때 소리도 함께 낸다
EVENTS = ["shake", "flash", "stamp"]
EVENT_SOUND = {"shake": "boing", "flash": "ding", "stamp": "pop"}
#: 놀람·당황 연기는 사건이 어울린다
EVENT_ACTIONS = {"shock", "panic", "celebrate", "fast"}

#: 장면 성격에 맞는 카메라(참고 유튜브 영상의 '문장 성격 → 장면 스타일' 규칙). 코드는 짚어 줄 곳을 확대하고,
#: 도식은 칸이 차례로 나타나므로 크게 움직이지 않는다. 나머지는 CAMERAS에서 고른다
CAMERA_BY_KIND = {"code": ["zoom-in", "punch"], "diagram": ["still", "tilt-up", "punch"]}

#: 점프 컷 확대(참고 영상 분석 2026-10-17, 온크트리 편집 규칙: 같은 화면이 6초 넘게 이어지지 않게 하고, 컷마다 확대 배율을
#: 번갈아 쓴다). 가운데 무대 장면이 MAX_HOLD초보다 길면 카메라를 JUMP로 바꿔 구절 자막이 바뀔 때마다 확대를 번갈아 끊는다.
#: 도식 장면은 칸이 차례로 나타나 화면이 스스로 바뀌므로 그대로 둔다
JUMP = "jump"
MAX_HOLD = 6.0


def hold_camera(camera: str, layout: str, kind: str, seconds: float) -> str:
    """장면이 한 화면으로 너무 오래 머물면 점프 컷 카메라로 바꾼다"""
    if layout == "stage" and kind in ("character", "code") and seconds > MAX_HOLD:
        return JUMP
    return camera


@dataclass
class Look:
    name: str
    music: int
    transitions: list[str] = field(default_factory=list)  # i번째 값: i-1번 장면에서 i번 장면으로 넘어갈 때(0번은 쓰지 않는다)
    cameras: list[str] = field(default_factory=list)
    layouts: list[str] = field(default_factory=list)
    #: 장면마다 사건(EVENTS) 또는 None. 영상 한 편에 한두 번
    events: list[str | None] = field(default_factory=list)

    @property
    def tag(self) -> str:
        """서버에 알려 줄 연출 이름(룩:배경음). 다음 영상이 이 조합을 피한다"""
        return f"{self.name}:{self.music}"

    def sound(self, i: int) -> str | None:
        """i번 장면으로 넘어갈 때 낼 전환 소리(None이면 소리 없음)"""
        name = self.transitions[i] if i < len(self.transitions) else "slide-right"
        return TRANSITION_SOUND.get(name, "whip")


def seed_of(*parts: object) -> int:
    text = "\x1f".join(str(p) for p in parts)
    return int.from_bytes(hashlib.sha256(text.encode("utf-8")).digest()[:8], "big")


def _no_repeat(rng: random.Random, pool: list[str], prev: str | None) -> str:
    choices = [p for p in pool if p != prev] or pool
    return rng.choice(choices)


def _recent(avoid: list[str] | None) -> list[tuple[str, int]]:
    """서버가 준 최근 연출("룩:배경음", 최근 것부터)에서 읽을 수 있는 것만"""
    out = []
    for tag in avoid or []:
        name, _, music = str(tag).partition(":")
        if name in LOOKS and music.isdigit():
            out.append((name, int(music)))
    return out


def _pick_look(rng: random.Random, recent: list[tuple[str, int]]) -> str:
    used = {n for n, _ in recent[:AVOID_LOOKS]}
    pool = [n for n in LOOKS if n not in used] or LOOKS
    return pool[rng.randrange(len(pool))]


def _pick_music(rng: random.Random, look: str, recent: list[tuple[str, int]]) -> int:
    """최근 영상과 같은 룩:배경음 조합과 바로 앞 영상의 배경음을 피한다. 다 막히면 조합만 피하고, 그래도 막히면 아무거나"""
    combos = {m for n, m in recent if n == look}
    last = recent[0][1] if recent else None
    for banned in (combos | {last}, combos, set()):
        pool = [m for m in range(MUSIC_VARIANTS) if m not in banned]
        if pool:
            return pool[rng.randrange(len(pool))]
    return 0


def choose(seed: int, scenes: int, splittable: list[bool], name: str | None = None,
           kinds: list[str] | None = None, actions: list[str] | None = None, avoid: list[str] | None = None) -> Look:
    """영상 한 편의 연출을 고른다. splittable[i]: i번 장면을 split 배치로 그려도 되는지(캐릭터 장면만).
    kinds[i]: 장면 성격("code"·"diagram"·"character"·"custom"), actions[i]: 캐릭터 연기 이름. 없으면 모두 캐릭터 장면으로 본다.
    avoid: 같은 회원의 최근 영상 연출("룩:배경음", 최근 것부터). 룩을 정해 두지 않았으면 최근 AVOID_LOOKS편의 룩을 빼고 고르고,
    배경음은 최근 조합과 바로 앞 영상의 것을 피한다."""
    kinds = kinds or ["character"] * scenes
    actions = actions or [""] * scenes
    rng = random.Random(seed)
    recent = _recent(avoid)
    look = name if name in LOOKS else _pick_look(rng, recent)
    pool = TRANSITION_POOLS[look]
    transitions: list[str] = [""]
    for _ in range(1, scenes):
        transitions.append(_no_repeat(rng, pool, transitions[-1] or None))
    cameras: list[str] = []
    for i in range(scenes):
        pool = CAMERA_BY_KIND.get(kinds[i] if i < len(kinds) else "", CAMERAS)
        cameras.append(_no_repeat(rng, pool, cameras[-1] if cameras else None))
    share = SPLIT_SHARE[look]
    layouts = []
    for i in range(scenes):
        ok = splittable[i] if i < len(splittable) else False
        # 같은 배치가 세 번 이어지지 않게 한다
        pick = "split" if ok and rng.random() < share else "stage"
        if ok and len(layouts) >= 2 and layouts[-1] == layouts[-2] == pick:
            pick = "stage" if pick == "split" else "split"
        layouts.append(pick if ok else "stage")
    music = _pick_music(rng, look, recent)
    return Look(look, music, transitions, cameras, layouts, _events(rng, scenes, kinds, actions))


def _events(rng: random.Random, scenes: int, kinds: list[str], actions: list[str]) -> list[str | None]:
    """사건 장면 고르기: 캐릭터·코드·도식 장면 가운데 첫 장면(훅)은 빼고, 놀람·당황 연기를 먼저 고른다. 세 장면 이상이면 하나, 여섯 이상이면 둘."""
    events: list[str | None] = [None] * scenes
    body = [i for i in range(1, scenes) if i < len(kinds) and kinds[i] != "custom"]
    if len(body) < 2:
        return events
    want = 2 if len(body) >= 5 else 1
    hot = [i for i in body if i < len(actions) and actions[i] in EVENT_ACTIONS]
    rest = [i for i in body if i not in hot]
    rng.shuffle(hot)
    rng.shuffle(rest)
    kinds_left = EVENTS[:]
    rng.shuffle(kinds_left)
    for i in (hot + rest)[:want]:
        events[i] = kinds_left.pop()
    return events
