"""장면 위에 글자·상자를 올리는 공통 그리기 도구 (#277).

일반 장면과 매매 재현 장면(#265)이 같은 방식으로 글자를 그리도록, ffmpeg drawtext·drawbox 필터 문자열을 여기서 만든다.
- 글자는 작업 폴더의 짧은 상대 경로 textfile로 넘긴다(Windows 경로의 콜론·역슬래시를 이스케이프하지 않게).
- 시간에 따라 바뀌는 값(나타나기·움직임)은 작은따옴표로 감싼 ffmpeg 식으로 넘긴다. 식 안의 쉼표가 필터 구분자로 읽히지 않는다.
"""

from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path

from . import layout

#: 요점 자막이 떠오르는 시간(초). 이슈 #277: 첫 0.3초 안에 떠오른다
POP_SECONDS = 0.25
POP_DISTANCE = 46


@dataclass(frozen=True)
class Palette:
    """장면 배경. base는 바탕, glow1·glow2는 화면 위·아래에 번지는 빛"""
    base: tuple[int, int, int]
    glow1: tuple[int, int, int]
    glow2: tuple[int, int, int]


#: 장면마다 돌려 쓰는 어두운 배경. 글자(흰색)가 어디서나 4.5:1 이상 대비가 나도록 빛을 약하게 둔다
PALETTES = [
    Palette((11, 16, 36), (37, 99, 235), (14, 165, 233)),     # 남색 + 파랑
    Palette((16, 12, 34), (124, 58, 237), (236, 72, 153)),    # 보라 + 분홍
    Palette((7, 24, 30), (13, 148, 136), (59, 130, 246)),     # 청록 + 파랑
    Palette((24, 14, 20), (225, 29, 72), (249, 115, 22)),     # 자주 + 주황
]


def fade_in(start: float, seconds: float = POP_SECONDS) -> str:
    """start초부터 seconds 동안 0 → 1로 진해지는 alpha 식"""
    return f"'clip((t-{start:.3f})/{seconds:.3f},0,1)'"


def rise(y: int, start: float, seconds: float = POP_SECONDS, distance: int = POP_DISTANCE) -> str:
    """start초에 distance만큼 아래에서 떠올라 y에 멈추는 y 식 (끝으로 갈수록 느려진다)"""
    return f"'{y}+{distance}*pow(1-clip((t-{start:.3f})/{seconds:.3f},0,1),3)'"


def between(start: float, end: float) -> str:
    return f"'between(t,{start:.3f},{end:.3f})'"


def after(start: float) -> str:
    return f"'gte(t,{start:.3f})'"


class Canvas:
    """한 장면의 필터 목록. text·box를 부른 순서대로 겹쳐 그린다."""

    def __init__(self, work: Path, prefix: str, font: str, mono: str):
        self.work = work
        self.prefix = prefix
        self.font = font
        self.mono = mono
        self.parts: list[str] = []
        self._n = 0

    def text(self, value: str, size: int, color: str, y, x="(w-text_w)/2", *, mono: bool = False, border: int = 0,
             border_color: str = "black@0.9", shadow: int = 0, alpha: str | None = None, enable: str | None = None,
             box: str | None = None) -> None:
        if not value:
            return
        name = f"{self.prefix}_{self._n}.txt"
        self._n += 1
        (self.work / name).write_text(value, encoding="utf-8")
        # 고정폭 글꼴에 한글이 없으면 □로 나오므로, 한글·한자가 섞인 줄은 한국어 글꼴로 그린다
        font = self.mono if mono and not has_wide(value) else self.font
        f = f"drawtext=fontfile={font}:textfile={name}:expansion=none:fontsize={size}:fontcolor={color}:x={x}:y={y}"
        if border:
            f += f":borderw={border}:bordercolor={border_color}"
        if shadow:
            f += f":shadowx=0:shadowy={shadow}:shadowcolor=black@0.55"
        if box:
            f += f":box=1:boxcolor={box}:boxborderw=14"
        if alpha:
            f += f":alpha={alpha}"
        if enable:
            f += f":enable={enable}"
        self.parts.append(f)

    def box(self, x, y, w, h, color: str, enable: str | None = None) -> None:
        f = f"drawbox=x={x}:y={y}:w={w}:h={h}:color={color}:t=fill"
        if enable:
            f += f":enable={enable}"
        self.parts.append(f)

    def progress(self, start: float, total: float, color: str, height: int = 10) -> None:
        """영상 전체 진행 막대. 장면 안 시각 t에 장면 시작 시각을 더해 영상 전체에서 어디쯤인지 보여 준다"""
        self.box(0, 0, f"'max(2,{layout.WIDTH}*({start:.3f}+t)/{total:.3f})'", height, color)

    def filters(self) -> str:
        return ",".join(self.parts) if self.parts else "null"


def has_wide(text: str) -> bool:
    return any(layout.char_width(c) == 1.0 for c in text)


def background_expr(p: Palette, width: int, height: int) -> str:
    """배경 한 장을 만드는 geq 식. 두 빛이 퍼진 그라데이션 + 띠가 생기지 않게 아주 약한 잡음."""
    def channel(k: int) -> str:
        base, g1, g2 = p.base[k], p.glow1[k], p.glow2[k]
        d1 = f"(pow(X-{width * 0.15:.0f},2)+pow(Y-{height * 0.12:.0f},2))"
        d2 = f"(pow(X-{width * 0.9:.0f},2)+pow(Y-{height * 0.85:.0f},2))"
        s1, s2 = (width * 0.75) ** 2, (width * 0.8) ** 2
        return (f"clip({base}+{(g1 - base) * 0.42:.1f}*exp(-{d1}/{s1:.0f})+{(g2 - base) * 0.32:.1f}*exp(-{d2}/{s2:.0f})"
                f"+(random(1)-0.5)*3,0,255)")
    return f"geq=r='{channel(0)}':g='{channel(1)}':b='{channel(2)}'"
