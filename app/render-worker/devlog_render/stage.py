"""무대: 2D 고양이 캐릭터가 장면의 상황을 연기한다 (#277).

블로그 주인 요청(2026-10-14): 글자를 늘어놓는 화면이 아니라, 귀여운 2D 캐릭터가 대본의 상황을 연기해야 한다.

- 캐릭터(크림색 고양이)와 소품(모니터·서버·DB·휴지통·전구·방패 등)을 Pillow로 매 프레임 그린다.
  2배 크기로 그린 뒤 반으로 줄여 외곽선이 매끄럽게 나오게 한다.
- 입은 그 장면 내레이션 소리 크기에 맞춰 움직이고(립싱크), 몇 초마다 눈을 깜빡이고, 숨 쉬듯 위아래로 움직인다.
- 연기(action)는 대본의 `visual`(서버·AI가 정할 수 있음)이 있으면 그걸, 없으면 장면 글에서 상황을 읽어 고른다
  (사라짐 → 당황, 코드 → 모니터 앞 타이핑, 요청 폭주 → 머리 감싸기, 느림 → 기다리며 졸기, 빨라짐 → 환호 …).
- 결과는 RGBA 원시 프레임(1080×STAGE_H)이고, render.py가 ffmpeg에 파이프로 넘겨 배경 위에 겹친다.
"""

from __future__ import annotations

import math
from dataclasses import dataclass

from PIL import Image, ImageDraw, ImageFont

from . import acting, layout
from .acting import ACTIONS, Visual, _seconds, infer, mouth_envelope  # noqa: F401 (예전 이름 그대로 쓰게)

W = layout.WIDTH
STAGE_H = 640
S = 2  # 그릴 때 배율
CW, CH = W * S, STAGE_H * S

OUT = (54, 38, 32, 255)
FUR = (255, 221, 168, 255)
FUR_DARK = (240, 176, 102, 255)
BELLY = (255, 244, 224, 255)
PINK = (255, 160, 170, 255)
BLUSH = (255, 170, 170, 255)
WHITE = (255, 255, 255, 255)
BLACK = (34, 26, 24, 255)
RED = (239, 68, 68, 255)
GREEN = (34, 197, 94, 255)
BLUE = (56, 189, 248, 255)
YELLOW = (250, 204, 21, 255)
SLATE = (30, 41, 59, 255)
SLATE_D = (15, 23, 42, 255)
MUTED = (148, 163, 184, 255)
CODE = (226, 232, 240, 255)
COMMENT = (125, 211, 252, 255)
SHADOW = (0, 0, 0, 70)

def clamp(x: float, a: float = 0.0, b: float = 1.0) -> float:
    return max(a, min(b, x))


def ease_out(x: float) -> float:
    x = clamp(x)
    return 1 - (1 - x) ** 3


def back_out(x: float) -> float:
    """살짝 지나쳤다 돌아오는 튀어나옴"""
    x = clamp(x)
    c = 1.70158
    return 1 + (c + 1) * (x - 1) ** 3 + c * (x - 1) ** 2


def lerp(a: float, b: float, x: float) -> float:
    return a + (b - a) * x


class Fonts:
    def __init__(self, font: str, mono: str):
        self.font, self.mono = font, mono
        self._cache: dict[tuple[str, int], ImageFont.FreeTypeFont] = {}

    def get(self, size: int, mono: bool = False) -> ImageFont.FreeTypeFont:
        key = (self.mono if mono else self.font, size)
        if key not in self._cache:
            self._cache[key] = ImageFont.truetype(key[0], size)
        return self._cache[key]


def ellipse(d: ImageDraw.ImageDraw, cx, cy, rx, ry, fill, outline=OUT, width=10):
    d.ellipse((cx - rx, cy - ry, cx + rx, cy + ry), fill=fill, outline=outline, width=width if outline else 0)


def rrect(d, x0, y0, x1, y1, r, fill, outline=OUT, width=10):
    d.rounded_rectangle((x0, y0, x1, y1), r, fill=fill, outline=outline, width=width if outline else 0)


def text(d, fonts: Fonts, xy, value: str, size: int, fill, anchor="mm", mono=False):
    d.text(xy, value, font=fonts.get(size, mono), fill=fill, anchor=anchor)


def star(d, cx, cy, r, fill):
    pts = []
    for k in range(10):
        a = -math.pi / 2 + k * math.pi / 5
        rr = r if k % 2 == 0 else r * 0.45
        pts.append((cx + rr * math.cos(a), cy + rr * math.sin(a)))
    d.polygon(pts, fill=fill)


def check(d, cx, cy, r, fill=GREEN):
    ellipse(d, cx, cy, r, r, fill, width=8)
    d.line([(cx - r * 0.45, cy), (cx - r * 0.1, cy + r * 0.35), (cx + r * 0.5, cy - r * 0.35)], fill=WHITE, width=int(r * 0.22),
           joint="curve")


def xmark(d, cx, cy, r, fill=RED):
    ellipse(d, cx, cy, r, r, fill, width=8)
    k = r * 0.4
    d.line([(cx - k, cy - k), (cx + k, cy + k)], fill=WHITE, width=int(r * 0.22))
    d.line([(cx - k, cy + k), (cx + k, cy - k)], fill=WHITE, width=int(r * 0.22))


# ── 캐릭터 ───────────────────────────────────────────────────────────────────

@dataclass
class Pose:
    x: float = CW * 0.5
    y: float = CH - 40  # 발바닥
    scale: float = 1.35
    squash: float = 1.0  # 1보다 작으면 납작, 크면 길쭉
    lean: float = 0.0  # 머리를 좌우로 기울이는 정도(px)
    eyes: str = "open"  # open, blink, happy, shock, sad, bored, closed
    look: tuple[float, float] = (0, 0)
    mouth: str = "smile"  # smile, talk, o, frown, flat
    talk: float = 0.0
    left: tuple[float, float] = (-0.9, 0.2)  # 팔 끝 위치(몸 중심 기준, 머리 반지름 배수)
    right: tuple[float, float] = (0.9, 0.2)
    tail: float = 0.0
    blush: bool = True
    sweat: bool = False
    tears: float = 0.0
    brows: str = ""  # worried, angry


def cat(d: ImageDraw.ImageDraw, p: Pose) -> None:
    k = p.scale
    hr = 150 * k  # 머리 반지름
    bw, bh = 120 * k / p.squash ** 0.5, 115 * k * p.squash
    by = p.y - 30 * k - bh  # 몸 중심
    hx, hy = p.x + p.lean, by - bh * 0.55 - hr * 0.78 * p.squash

    # 그림자
    d.ellipse((p.x - 170 * k, p.y - 26 * k, p.x + 170 * k, p.y + 18 * k), fill=SHADOW)
    # 꼬리
    sway = math.sin(p.tail) * 40 * k
    d.line([(p.x + bw * 0.7, by + bh * 0.5), (p.x + bw * 1.5, by + bh * 0.1), (p.x + bw * 1.75 + sway, by - bh * 0.8)],
           fill=OUT, width=int(44 * k), joint="curve")
    d.line([(p.x + bw * 0.7, by + bh * 0.5), (p.x + bw * 1.5, by + bh * 0.1), (p.x + bw * 1.75 + sway, by - bh * 0.8)],
           fill=FUR_DARK, width=int(26 * k), joint="curve")
    # 발
    for side in (-1, 1):
        ellipse(d, p.x + side * bw * 0.55, p.y - 34 * k, 52 * k, 34 * k, FUR, width=int(9 * k))
    # 몸
    ellipse(d, p.x, by, bw, bh, FUR, width=int(10 * k))
    ellipse(d, p.x, by + bh * 0.15, bw * 0.62, bh * 0.68, BELLY, outline=None)
    # 팔(몸 뒤로 가는 팔은 없음: 늘 앞에 그린다)
    arms = []
    for (ax, ay), side in ((p.left, -1), (p.right, 1)):
        sx, sy = p.x + side * bw * 0.72, by - bh * 0.35
        tx, ty = p.x + ax * hr, by + ay * hr
        arms.append((sx, sy, tx, ty))

    # 머리
    ear_y = hy - hr * 0.55
    for side in (-1, 1):
        ex = hx + side * hr * 0.62
        outer = [(ex - side * hr * 0.42, ear_y + hr * 0.15), (ex + side * hr * 0.18, ear_y - hr * 0.72), (ex + side * hr * 0.42, ear_y + hr * 0.3)]
        d.polygon(outer, fill=FUR, outline=OUT, width=int(10 * k))
        inner = [(ex - side * hr * 0.22, ear_y + hr * 0.12), (ex + side * hr * 0.13, ear_y - hr * 0.42), (ex + side * hr * 0.27, ear_y + hr * 0.22)]
        d.polygon(inner, fill=PINK)
    d.ellipse((hx - hr * 1.12, hy - hr * 0.92, hx + hr * 1.12, hy + hr * 0.92), fill=FUR, outline=OUT, width=int(10 * k))
    # 이마 줄무늬
    for dx in (-0.22, 0, 0.22):
        d.line([(hx + dx * hr, hy - hr * 0.88), (hx + dx * hr * 0.8, hy - hr * 0.62)], fill=FUR_DARK, width=int(14 * k))

    _face(d, p, hx, hy, hr, k)

    for sx, sy, tx, ty in arms:
        d.line([(sx, sy), (tx, ty)], fill=OUT, width=int(58 * k))
        d.line([(sx, sy), (tx, ty)], fill=FUR, width=int(40 * k))
        ellipse(d, tx, ty, 30 * k, 28 * k, FUR, width=int(9 * k))

    if p.sweat:
        sx, sy = hx + hr * 1.05, hy - hr * 0.45
        d.polygon([(sx, sy - 40 * k), (sx - 20 * k, sy + 4 * k), (sx + 20 * k, sy + 4 * k)], fill=(125, 211, 252, 255))
        d.ellipse((sx - 20 * k, sy - 14 * k, sx + 20 * k, sy + 24 * k), fill=(125, 211, 252, 255))


def _face(d, p: Pose, hx, hy, hr, k) -> None:
    ey = hy - hr * 0.02
    lx, ly = p.look
    for side in (-1, 1):
        ex = hx + side * hr * 0.42
        if p.eyes == "blink" or p.eyes == "closed":
            d.arc((ex - 30 * k, ey - 18 * k, ex + 30 * k, ey + 22 * k), 20, 160, fill=BLACK, width=int(9 * k))
        elif p.eyes == "happy":
            d.arc((ex - 32 * k, ey - 10 * k, ex + 32 * k, ey + 44 * k), 200, 340, fill=BLACK, width=int(10 * k))
        elif p.eyes == "shock":
            ellipse(d, ex, ey, 40 * k, 46 * k, WHITE, width=int(8 * k))
            d.ellipse((ex - 11 * k + lx * 8, ey - 11 * k + ly * 8, ex + 11 * k + lx * 8, ey + 11 * k + ly * 8), fill=BLACK)
        else:
            ry = 40 * k if p.eyes != "bored" else 40 * k
            d.ellipse((ex - 30 * k + lx * 10, ey - ry + ly * 8, ex + 30 * k + lx * 10, ey + ry + ly * 8), fill=BLACK)
            d.ellipse((ex - 14 * k + lx * 10, ey - 26 * k + ly * 8, ex + 4 * k + lx * 10, ey - 8 * k + ly * 8), fill=WHITE)
            d.ellipse((ex + 6 * k + lx * 10, ey + 8 * k + ly * 8, ex + 14 * k + lx * 10, ey + 16 * k + ly * 8), fill=WHITE)
            if p.eyes == "bored":  # 반쯤 감은 눈꺼풀
                d.rectangle((ex - 36 * k, ey - 48 * k, ex + 36 * k, ey - 2 * k), fill=FUR)
                d.line([(ex - 34 * k, ey - 2 * k), (ex + 34 * k, ey - 2 * k)], fill=BLACK, width=int(9 * k))
            if p.eyes == "sad":
                pass
        if p.brows == "worried":
            d.line([(ex - side * 34 * k, ey - 58 * k), (ex + side * 30 * k, ey - 74 * k)], fill=BLACK, width=int(9 * k))
        elif p.brows == "angry":
            d.line([(ex - side * 34 * k, ey - 76 * k), (ex + side * 30 * k, ey - 58 * k)], fill=BLACK, width=int(9 * k))
        if p.tears > 0:
            ty = ey + 40 * k + p.tears * 60 * k
            d.ellipse((ex - 12 * k, ty - 16 * k, ex + 12 * k, ty + 16 * k), fill=(125, 211, 252, 255))
    if p.blush:
        for side in (-1, 1):
            d.ellipse((hx + side * hr * 0.72 - 34 * k, hy + hr * 0.24 - 16 * k, hx + side * hr * 0.72 + 34 * k, hy + hr * 0.24 + 16 * k),
                      fill=BLUSH)
    # 코와 입
    ny = hy + hr * 0.2
    d.polygon([(hx - 14 * k, ny - 8 * k), (hx + 14 * k, ny - 8 * k), (hx, ny + 8 * k)], fill=(232, 120, 132, 255))
    my = ny + 16 * k
    if p.mouth == "talk" and p.talk > 0.05:
        h = 10 * k + 44 * k * p.talk
        d.ellipse((hx - 26 * k, my - 4 * k, hx + 26 * k, my + h), fill=(120, 40, 50, 255), outline=BLACK, width=int(6 * k))
        d.ellipse((hx - 14 * k, my + h * 0.45, hx + 14 * k, my + h - 2 * k), fill=(255, 140, 150, 255))
    elif p.mouth == "o":
        d.ellipse((hx - 20 * k, my, hx + 20 * k, my + 46 * k), fill=(120, 40, 50, 255), outline=BLACK, width=int(6 * k))
    elif p.mouth == "frown":
        d.arc((hx - 26 * k, my + 4 * k, hx + 26 * k, my + 40 * k), 200, 340, fill=BLACK, width=int(7 * k))
    elif p.mouth == "flat":
        d.line([(hx - 18 * k, my + 12 * k), (hx + 18 * k, my + 12 * k)], fill=BLACK, width=int(7 * k))
    else:  # 고양이 입 ω
        d.arc((hx - 30 * k, my - 14 * k, hx, my + 16 * k), 0, 180, fill=BLACK, width=int(7 * k))
        d.arc((hx, my - 14 * k, hx + 30 * k, my + 16 * k), 0, 180, fill=BLACK, width=int(7 * k))
    # 수염
    for side in (-1, 1):
        for dy in (-10, 12):
            d.line([(hx + side * hr * 0.62, ny + dy * k), (hx + side * hr * 1.05, ny + dy * k * 1.8 - 6 * k)], fill=OUT, width=int(5 * k))


# ── 소품 ─────────────────────────────────────────────────────────────────────

def monitor(d, fonts: Fonts, box, lines: list[str], typed: float, focus: int | None, focus_color, pulse: float, shell: bool):
    """코드가 써지는 모니터. typed는 보인 글자 수 비율(0~1)."""
    x0, y0, x1, y1 = box
    rrect(d, x0, y0, x1, y1, 36, SLATE_D, width=12)
    d.rectangle((x0 + 30, y1, x1 - 30, y1 + 6), fill=OUT)
    d.rounded_rectangle(((x0 + x1) / 2 - 70, y1, (x0 + x1) / 2 + 70, y1 + 70), 10, fill=SLATE, outline=OUT, width=10)
    # 창 위 막대
    for k, c in enumerate((RED, YELLOW, GREEN)):
        d.ellipse((x0 + 40 + k * 46, y0 + 30, x0 + 68 + k * 46, y0 + 58), fill=c)
    text(d, fonts, (x1 - 40, y0 + 44), "terminal" if shell else "editor", 36, MUTED, anchor="rm", mono=True)
    size, lh = 62, 90
    total = sum(len(line) for line in lines) or 1
    budget = typed * total
    from .render import split_comment  # 늦은 import: render가 stage를 불러서
    for n, line in enumerate(lines):
        y = y0 + 110 + n * lh
        if y + lh > y1 - 10:
            break
        shown = line[:max(0, int(budget))]
        budget -= len(line)
        if focus == n and pulse > 0:
            hl = (*focus_color[:3], int(60 + 70 * pulse))
            d.rectangle((x0 + 20, y - 12, x1 - 20, y + lh - 18), fill=hl)
        prefix = "$ " if shell and line.strip() and not line.lstrip().startswith(("#", "//", "--")) else ""
        code_part, comment = split_comment(shown)
        x = x0 + 50
        if prefix:
            text(d, fonts, (x, y), prefix, size, GREEN, anchor="la", mono=True)
            x += fonts.get(size, True).getlength(prefix)
        if code_part:
            text(d, fonts, (x, y), code_part, size, CODE, anchor="la", mono=not _wide(code_part))
            x += fonts.get(size, not _wide(code_part)).getlength(code_part)
        if comment:
            text(d, fonts, (x, y), comment, size, COMMENT, anchor="la", mono=not _wide(comment))
        if 0 < len(shown) < len(line) or (n == len(lines) - 1 and typed < 1 and len(shown) == len(line)):
            cx = x + (fonts.get(size, not _wide(comment)).getlength(comment) if comment else 0)
            d.rectangle((cx + 4, y, cx + 28, y + size), fill=CODE)


def _wide(s: str) -> bool:
    return any(layout.char_width(c) == 1.0 for c in s)


def server(d, cx, cy, alert: float = 0.0, scale: float = 1.0):
    w, h = 220 * scale, 300 * scale
    for k in range(3):
        y = cy - h / 2 + k * h / 3
        rrect(d, cx - w / 2, y, cx + w / 2, y + h / 3 - 10, 18, SLATE, width=10)
        led = RED if alert > 0.5 and k % 2 == 0 else GREEN
        d.ellipse((cx + w / 2 - 60, y + 30, cx + w / 2 - 32, y + 58), fill=led)
        d.line([(cx - w / 2 + 30, y + 44), (cx + 10, y + 44)], fill=MUTED, width=10)


def database(d, cx, cy, fill: float = 1.0, color=BLUE, scale: float = 1.0, alpha: int = 255):
    w, h, e = 200 * scale, 240 * scale, 50 * scale
    body = (*SLATE[:3], alpha)
    d.rectangle((cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2), fill=body)
    level = cy + h / 2 - h * clamp(fill)
    if fill > 0:
        d.rectangle((cx - w / 2, level, cx + w / 2, cy + h / 2), fill=(*color[:3], alpha))
        d.ellipse((cx - w / 2, cy + h / 2 - e, cx + w / 2, cy + h / 2 + e), fill=(*color[:3], alpha))
    d.line([(cx - w / 2, cy - h / 2), (cx - w / 2, cy + h / 2)], fill=OUT, width=10)
    d.line([(cx + w / 2, cy - h / 2), (cx + w / 2, cy + h / 2)], fill=OUT, width=10)
    d.arc((cx - w / 2, cy + h / 2 - e, cx + w / 2, cy + h / 2 + e), 0, 180, fill=OUT, width=10)
    d.ellipse((cx - w / 2, cy - h / 2 - e, cx + w / 2, cy - h / 2 + e), fill=(*SLATE[:3], alpha), outline=OUT, width=10)


def paper(d, fonts, cx, cy, label: str, scale=1.0, alpha=255):
    w, h = 130 * scale, 160 * scale
    fill = (255, 255, 255, alpha)
    d.polygon([(cx - w / 2, cy - h / 2), (cx + w / 2 - 40 * scale, cy - h / 2), (cx + w / 2, cy - h / 2 + 40 * scale),
               (cx + w / 2, cy + h / 2), (cx - w / 2, cy + h / 2)], fill=fill, outline=(*OUT[:3], alpha), width=8)
    for k in range(3):
        d.line([(cx - w / 2 + 22 * scale, cy - 20 * scale + k * 34 * scale), (cx + w / 2 - 24 * scale, cy - 20 * scale + k * 34 * scale)],
               fill=(*MUTED[:3], alpha), width=int(10 * scale))
    if label:
        text(d, fonts, (cx, cy - h / 2 - 34 * scale), label, int(34 * scale), (*CODE[:3], alpha))


def trash(d, cx, cy, scale=1.0, lid=0.0):
    w, h = 190 * scale, 220 * scale
    d.polygon([(cx - w / 2, cy - h / 2), (cx + w / 2, cy - h / 2), (cx + w / 2 - 22, cy + h / 2), (cx - w / 2 + 22, cy + h / 2)],
              fill=(100, 116, 139, 255), outline=OUT, width=10)
    for dx in (-0.22, 0, 0.22):
        d.line([(cx + dx * w, cy - h / 2 + 30), (cx + dx * w * 0.85, cy + h / 2 - 26)], fill=SLATE, width=10)
    lx = cx + lid * 60
    ly = cy - h / 2 - 22 - lid * 40
    d.rounded_rectangle((lx - w / 2 - 16, ly - 18, lx + w / 2 + 16, ly + 18), 10, fill=(148, 163, 184, 255), outline=OUT, width=10)


def bulb(d, cx, cy, glow: float, scale=1.0):
    if glow > 0:
        for k in range(10):
            a = k * math.pi / 5 + glow
            r0, r1 = 120 * scale, (150 + 40 * glow) * scale
            d.line([(cx + r0 * math.cos(a), cy + r0 * math.sin(a)), (cx + r1 * math.cos(a), cy + r1 * math.sin(a))], fill=YELLOW, width=14)
    ellipse(d, cx, cy, 90 * scale, 90 * scale, (253, 224, 71, 255))
    rrect(d, cx - 45 * scale, cy + 70 * scale, cx + 45 * scale, cy + 130 * scale, 12, (148, 163, 184, 255))


def shield(d, cx, cy, scale=1.0):
    w, h = 220 * scale, 260 * scale
    pts = [(cx - w / 2, cy - h / 2 + 30), (cx, cy - h / 2), (cx + w / 2, cy - h / 2 + 30), (cx + w / 2, cy), (cx, cy + h / 2),
           (cx - w / 2, cy)]
    d.polygon(pts, fill=GREEN, outline=OUT, width=12)
    d.line([(cx - w * 0.22, cy - 10), (cx - w * 0.04, cy + h * 0.12), (cx + w * 0.26, cy - h * 0.16)], fill=WHITE, width=int(28 * scale),
           joint="curve")


def bubble(d, fonts, cx, cy, value: str, size=60, fill=WHITE, color=BLACK, tail=(0, 1)):
    f = fonts.get(size)
    tw = f.getlength(value)
    w, h = tw + 70, size + 50
    rrect(d, cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2, h / 2, fill, width=10)
    text(d, fonts, (cx, cy), value, size, color)


def bar(d, fonts, x, y, w, frac, color, label, value):
    rrect(d, x, y, x + w, y + 96, 48, SLATE, width=10)
    if frac > 0:
        d.rounded_rectangle((x + 10, y + 10, x + 10 + max(76, (w - 20) * frac), y + 86), 38, fill=color)
    text(d, fonts, (x - 34, y + 48), label, 66, CODE, anchor="rm")
    if value:
        text(d, fonts, (x + w + 34, y + 48), value, 72, color, anchor="lm")


# ── 연기 ─────────────────────────────────────────────────────────────────────

class Stage:
    """한 장면의 무대. frame(t)이 장면 안 시각 t(초)의 RGBA 프레임 바이트를 돌려준다."""

    def __init__(self, visual: Visual, fonts: Fonts, seconds: float, lead: float, speech: float, mouth: list[float], fps: int,
                 first: bool = False):
        self.v, self.fonts, self.seconds, self.lead, self.speech = visual, fonts, seconds, lead, speech
        self.mouth, self.fps, self.first = mouth, fps, first

    def effects(self) -> list[tuple[str, float, float]]:
        return acting.effects(self.v, self.lead, self.speech, self.seconds)

    def talk(self, t: float) -> float:
        i = int((t - self.lead) * self.fps)
        return self.mouth[i] if 0 <= i < len(self.mouth) else 0.0

    def frame(self, t: float) -> bytes:
        img = Image.new("RGBA", (CW, CH), (0, 0, 0, 0))
        d = ImageDraw.Draw(img, "RGBA")
        getattr(self, "_" + self.v.action, self._explain)(d, t)
        return img.reduce(S).tobytes()

    # 공통 몸짓: 숨쉬기, 깜빡임, 등장
    def pose(self, t: float, **kw) -> Pose:
        p = Pose(**kw)
        p.squash *= 1 + 0.015 * math.sin(t * 2.6)
        p.tail = t * 2.2
        blink = (t + 0.7) % 3.1
        if p.eyes == "open" and blink < 0.12:
            p.eyes = "blink"
        talk = self.talk(t)
        if p.mouth in ("smile", "talk") and talk > 0.05:
            p.mouth, p.talk = "talk", talk
        if self.first:  # 첫 장면은 통 튀어나오며 등장(훅)
            pop = back_out(t / 0.35)
            p.scale *= 0.4 + 0.6 * pop
        return p

    def _explain(self, d, t):
        wave = math.sin(t * 7) * 0.25 if t < 1.5 else 0.0
        p = self.pose(t, left=(-0.9, 0.25), right=(1.05, -0.9 + wave), y=CH - 40 - abs(math.sin(t * 3)) * 10)
        cat(d, p)

    def _shock(self, d, t):
        # 처음엔 자료가 옆에 있다가 0.6초에 흔들리고 1.0초부터 휴지통으로 빨려 들어간다. 고양이는 0.9초에 깜짝
        cx = CW * 0.3
        items = 3
        for k in range(items):
            start = 1.0 + k * 0.18
            x0, y0 = CW * 0.56 + k * 230, CH * 0.3 + (k % 2) * 70
            x1, y1 = CW * 0.82, CH * 0.6
            f = ease_out((t - start) / 0.5)
            shake = math.sin(t * 60) * 10 if 0.6 < t < start else 0
            alpha = int(255 * (1 - clamp((t - start - 0.35) / 0.2)))
            if alpha > 0:
                paper(d, self.fonts, lerp(x0, x1, f) + shake, lerp(y0, y1, f), "" if f > 0 else self.v.label if k == 1 else "",
                      scale=1.4 * (1 - 0.6 * f), alpha=alpha)
        trash(d, CW * 0.82, CH * 0.74, scale=1.3, lid=clamp((t - 0.9) / 0.2) * (1 - clamp((t - 2.0) / 0.3)))
        shocked = t > 0.9
        jump = math.sin(clamp((t - 0.9) / 0.35) * math.pi) * 60 if shocked else 0
        p = self.pose(t, x=cx, y=CH - 40 - jump, eyes="shock" if shocked else "open", mouth="o" if 0.9 < t < 1.6 else "frown" if shocked else "smile",
                      left=(-0.7, -1.4) if shocked else (-0.9, 0.2), right=(0.7, -1.4) if shocked else (0.9, 0.2),
                      sweat=shocked, brows="worried" if shocked else "", tears=((t - 2.0) % 1.0) if t > 2.0 else 0.0,
                      look=(1, 0))
        if t > 2.0 and p.eyes == "shock":
            p.eyes = "sad"
        cat(d, p)
        if shocked:
            pop = back_out((t - 0.9) / 0.3)
            text(d, self.fonts, (cx + 300, CH * 0.12), "!", int(200 * pop) or 1, RED)

    def _typing(self, d, t):
        v = self.v
        box = (CW * 0.03, 16, CW * 0.97, CH * 0.6)
        type_end = self.lead + max(0.8, self.speech * 0.6)
        typed = clamp((t - self.lead) / (type_end - self.lead))
        pulse = 0.0
        if v.focus is not None and t > type_end:
            pulse = 0.5 + 0.5 * math.cos((t - type_end) * 6) if t < type_end + 1.6 else 0.5
        monitor(d, self.fonts, box, v.code, typed, v.focus, GREEN if v.good else RED, pulse, v.shell)
        typing = typed < 1
        tap = math.sin(t * 22)
        done = t > type_end + 0.2
        p = self.pose(t, x=CW * 0.2, y=CH - 20, scale=1.0, look=(1, -0.6),
                      left=(-0.2 + 0.1 * tap, -0.55) if typing else (-0.9, 0.2),
                      right=(0.5 - 0.1 * tap, -0.6) if typing else ((0.9, -1.4) if done and v.good else (0.9, 0.2)),
                      eyes="happy" if done and v.good else "open", sweat=done and v.focus is not None and not v.good,
                      brows="worried" if done and v.focus is not None and not v.good else "")
        cat(d, p)
        if done and v.focus is not None:
            pop = back_out((t - type_end - 0.2) / 0.3)
            if v.good:
                check(d, CW * 0.62, CH * 0.8, 90 * pop + 1)
            else:
                text(d, self.fonts, (CW * 0.4, CH * 0.72), "?", int(170 * pop) or 1, YELLOW)

    def _panic(self, d, t):
        target = self.v.count or 100
        sx, sy = CW * 0.78, CH * 0.5
        alert = clamp((t - 1.0) / 0.5)
        server(d, sx, sy, alert, 1.2)
        # 요청이 오른쪽 서버로 계속 날아든다
        for k in range(14):
            phase = (t * 1.6 + k / 14) % 1.0
            if t * 1.6 + k / 14 < 1.0:
                continue
            x = lerp(CW * 0.02, sx - 160, phase)
            y = CH * (0.12 + 0.72 * ((k * 37) % 14) / 14)
            bubble(d, self.fonts, x, y, "GET", 42, fill=(56, 189, 248, 255), color=SLATE_D)
        n = int(target * ease_out((t - 0.3) / max(0.5, self.speech * 0.7)))
        bubble(d, self.fonts, sx, sy - 270, f"{n}번", 84, fill=RED if alert > 0.5 else WHITE, color=WHITE if alert > 0.5 else BLACK)
        shake = math.sin(t * 40) * 8 * alert
        p = self.pose(t, x=CW * 0.32 + shake, eyes="shock" if alert > 0.5 else "open", mouth="o" if alert > 0.5 and self.talk(t) < 0.05 else "smile",
                      left=(-0.85, -1.45) if alert > 0.5 else (-0.9, 0.2), right=(0.85, -1.45) if alert > 0.5 else (0.9, 0.2),
                      sweat=alert > 0.5, brows="worried" if alert > 0.5 else "", look=(1, 0))
        cat(d, p)

    def _restore(self, d, t):
        fill = ease_out((t - 0.5) / max(1.0, self.speech * 0.6))
        database(d, CW * 0.78, CH * 0.48, fill, GREEN if fill >= 1 else BLUE, 1.15)
        # 백업 상자에서 DB로 자료가 흘러간다
        bx = CW * 0.47
        rrect(d, bx - 100, CH * 0.62, bx + 100, CH * 0.82, 20, (180, 140, 90, 255))
        text(d, self.fonts, (bx, CH * 0.72), "백업", 56, WHITE)
        if fill < 1:
            for k in range(4):
                ph = (t * 1.5 + k / 4) % 1
                paper(d, self.fonts, lerp(bx, CW * 0.72, ph), lerp(CH * 0.6, CH * 0.4, ph) - math.sin(ph * math.pi) * 120, "", 0.45)
        done = fill >= 1
        if done:
            check(d, CW * 0.78, CH * 0.12, 70 * back_out((t - 0.5 - max(1.0, self.speech * 0.6)) / 0.3) + 1)
        pump = math.sin(t * 6) * 0.15
        p = self.pose(t, x=CW * 0.2, eyes="happy" if done else "open", right=(0.9, -1.5) if done else (1.0, -0.4 + pump),
                      left=(-0.9, -1.5) if done else (-0.9, 0.2), look=(1, 0),
                      y=CH - 40 - (abs(math.sin(t * 8)) * 30 if done else 0))
        cat(d, p)

    def _wait(self, d, t):
        total = _seconds(self.v.before) or 4.0
        shown = min(total, total * clamp((t - 0.2) / max(1.0, self.speech * 0.8)))
        box = (CW * 0.45, 40, CW * 0.95, CH * 0.62)
        rrect(d, *box, 30, SLATE_D, width=12)
        for k in range(3):
            y = box[1] + 120 + k * 110
            d.rounded_rectangle((box[0] + 50, y, box[2] - 50 - k * 80, y + 60), 20, fill=SLATE)
        cx, cy = (box[0] + box[2]) / 2, box[3] - 120
        a = t * 300
        d.arc((cx - 50, cy - 50, cx + 50, cy + 50), a, a + 270, fill=BLUE, width=16)
        unit = "초" if total < 60 else "분"
        bubble(d, self.fonts, cx, box[1] + 40, f"{shown:.1f}{unit}" if unit == "초" else f"{shown / 60:.0f}분", 80, fill=RED, color=WHITE)
        sleepy = t > 1.2
        p = self.pose(t, x=CW * 0.22, eyes="bored" if sleepy else "open", mouth="flat" if sleepy and self.talk(t) < 0.05 else "smile",
                      left=(-0.2, -0.95) if sleepy else (-0.9, 0.2), right=(0.9, 0.2), lean=-20 if sleepy else 0, look=(1, -0.3))
        cat(d, p)
        if sleepy:
            for k in range(3):
                ph = ((t - 1.2) * 0.8 + k / 3) % 1
                text(d, self.fonts, (CW * 0.3 + ph * 120, CH * 0.25 - ph * 160), "z", int(40 + 30 * ph), (*CODE[:3], int(255 * (1 - ph))))

    def _fast(self, d, t):
        v = self.v
        before_s, after_s = _seconds(v.before), _seconds(v.after)
        x, w = CW * 0.12, CW * 0.6
        slow = clamp((t - 0.3) / 1.6)
        fast_frac = (after_s / before_s) if before_s and after_s and before_s > after_s else 0.08
        quick = clamp((t - 0.6) / 0.25)
        bar(d, self.fonts, x, CH * 0.06, w, slow, RED, "전", v.before or "")
        bar(d, self.fonts, x, CH * 0.24, w, quick * max(0.06, fast_frac), GREEN, "후", v.after or "")
        cheer = t > 1.0
        if cheer:
            pop = back_out((t - 1.0) / 0.35)
            bubble(d, self.fonts, CW * 0.72, CH * 0.6, v.label, int(90 * pop) or 1, fill=YELLOW, color=SLATE_D)
            for k in range(5):
                a = k * 1.3 + t * 2
                star(d, CW * 0.5 + 300 * math.cos(a), CH * 0.62 + 120 * math.sin(a), 26, YELLOW)
        jump = abs(math.sin(t * 7)) * 40 if cheer else 0
        p = self.pose(t, x=CW * 0.28, y=CH - 30 - jump, scale=1.05, eyes="happy" if cheer else "open",
                      left=(-0.85, -1.5) if cheer else (-0.9, 0.2), right=(0.85, -1.5) if cheer else (0.9, 0.2))
        cat(d, p)

    def _idea(self, d, t):
        on = back_out((t - 0.5) / 0.4)
        cx = CW * 0.5
        if t > 0.5:
            bulb(d, cx + 280, CH * 0.25, (t - 0.5) * 1.5 if t > 0.7 else 0, 0.6 + 0.4 * on)
        p = self.pose(t, x=cx - 60, eyes="happy" if t > 0.7 else "open", right=(1.0, -1.6) if t > 0.5 else (0.9, 0.2),
                      look=(0.6, -1), y=CH - 40 - (math.sin(clamp((t - 0.5) / 0.3) * math.pi) * 40))
        cat(d, p)

    def _protect(self, d, t):
        on = back_out((t - 0.4) / 0.4)
        if t > 0.4:
            shield(d, CW * 0.7, CH * 0.45, 0.4 + 0.8 * on)
            for k in range(4):
                a = k * 1.57 + t * 1.5
                star(d, CW * 0.7 + 230 * math.cos(a), CH * 0.45 + 200 * math.sin(a), 22, YELLOW)
        p = self.pose(t, x=CW * 0.3, eyes="happy" if t > 0.6 else "open", right=(1.05, -1.2) if t > 0.4 else (0.9, 0.2),
                      brows="angry" if t > 0.6 else "")
        cat(d, p)

    def _think(self, d, t):
        p = self.pose(t, x=CW * 0.42, right=(0.25, -0.6), lean=18 * math.sin(t * 1.4), look=(0.8, -0.8), eyes="open")
        cat(d, p)
        for k, (dx, dy) in enumerate(((330, -380), (430, -470), (250, -500))):
            pop = back_out((t - 0.3 - k * 0.3) / 0.3)
            if pop > 0:
                text(d, self.fonts, (CW * 0.42 + dx, CH - 40 + dy + math.sin(t * 3 + k) * 12), "?", int(110 * pop) or 1, YELLOW)

    def _celebrate(self, d, t):
        jump = abs(math.sin(t * 7)) * 50
        p = self.pose(t, y=CH - 40 - jump, eyes="happy", left=(-0.85, -1.5), right=(0.85, -1.5))
        cat(d, p)
        for k in range(8):
            ph = (t * 0.7 + k / 8) % 1
            star(d, CW * (0.1 + 0.8 * ((k * 0.37) % 1)), CH * (0.05 + ph * 0.8), 24, (YELLOW, PINK, BLUE, GREEN)[k % 4])
