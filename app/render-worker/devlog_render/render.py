"""쇼츠 대본 → 9:16 MP4 (1080x1920, 30fps, H.264 + AAC).

한 장면은 [진행 막대 · 제목 · 요점 자막(떠오름) · (코드: 한 줄씩 / 글 이미지) · 구절 자막 · 블로그 주소]이고,
배경은 천천히 움직이는 그라데이션(글 이미지가 있으면 흐리게 깐 그 이미지)이다 (#277).

1. 장면마다 내레이션 음성을 만든다. 신경망 음성(edge)이 실패하면 영상 전체를 기기 음성으로 다시 만든다(목소리가 섞이지 않게).
2. 장면 영상(소리 없음)을 화질 손실이 거의 없게 따로 만든다. 장면 길이 = 앞 여유 + 말 + 끝 쉼(0.15초) + 전환 겹침.
3. 마지막에 한 번에 잇는다: 장면 사이 xfade, 내레이션은 장면 시작 시각에 맞춰 놓고, 배경음은 말할 때 낮춘다.
   최종 인코딩은 crf 20 · preset medium, 20MB를 넘지 않게 길이에 맞춰 비트레이트를 묶는다.

drawtext가 쓰는 글꼴·글자 파일은 작업 폴더 안의 짧은 상대 경로로 넘겨서
Windows 경로(C:\\…)의 콜론·역슬래시를 필터 문법에서 따로 이스케이프하지 않아도 되게 한다.
"""

from __future__ import annotations

import json
import logging
import math
import os
import platform
import shutil
import subprocess
import tempfile
import urllib.request
from dataclasses import dataclass, field
from pathlib import Path

from . import acting, bgm, candles, captions, diagram, draw, layout, remotion, replay, sfx, trade_bgm, trade_sfx, tts

try:  # 캐릭터 무대는 Pillow가 있을 때만. 없으면 글자 화면으로 만든다
    from . import stage
except ImportError:  # pragma: no cover - Pillow 없는 환경
    stage = None

log = logging.getLogger("devlog_render")

FPS = 30
#: 말이 끝나고 다음 장면으로 넘어가기 전 쉼(초). 0.4초는 늘어져 보였다
SCENE_PADDING = 0.15
#: 장면 사이 전환(xfade) 길이
TRANSITION = 0.3
#: 빠른 견본 스타일(참고 영상 분석 #277): 목소리를 빠르게(초당 8~9음절). 컷은 장면 안에서 화면이 끊어 바뀐다(Scene.tsx)
FAST_VOICE = 1.25
#: 대본이 캐릭터를 고르지 않았을 때 연기하는 캐릭터(민서님 선택, #277). 지금 고양이는 "character": "cat"
DEFAULT_CHARACTER = "lognyang"
TRANSITION_KIND = "fade"
#: 첫 장면은 바로 말을 시작하고(훅), 나머지는 전환이 거의 끝난 뒤 말한다
FIRST_LEAD = 0.1
LEAD = 0.2
#: 마지막 장면은 끝나고 조금 더 머문다
TAIL = 0.8
#: ffmpeg 한 번이 멈췄을 때 기다리는 한도. 장면 하나는 보통 몇 초면 끝난다
FFMPEG_TIMEOUT = 300
#: 마지막 이어 붙이기는 preset medium이라 오래 걸릴 수 있다
FINAL_TIMEOUT = 1200
#: 인그레스 본문 상한(21MB)과 서버 상한(20MB) 안에 들어가게 비트레이트를 묶는다
VIDEO_MAXRATE = 3_000_000
MAX_BYTES = 19 * 1024 * 1024
AUDIO_BITRATE = 160_000
#: 배경음 크기(내레이션 대비)와 말할 때 얼마나 낮출지
BGM_VOLUME = 0.22
#: 매매 재현 결산 화면에서 배경음을 줄이는 배율
MUSIC_SOFT = 0.4
#: 글 이미지 받기 한도
IMAGE_MAX_BYTES = 10 * 1024 * 1024
IMAGE_TIMEOUT = 15

ACCENT = "0x38bdf8"
MUTED = "0xcbd5e1"
CODE_BG = "0x0b1220@0.88"
CODE_TEXT = "0xe2e8f0"
CODE_COMMENT = "0x7dd3fc"
CAPTION_BAR = "0x38bdf8"
#: 가운데(코드 카드·글 이미지)가 시작하는 높이와 이미지 카드 크기, 구절 자막 높이. 쇼츠 화면 아래쪽 버튼·설명에 가리지 않게 1500 위에 둔다
MIDDLE_TOP = 680
#: 캐릭터 무대(1080×640)를 놓는 높이
STAGE_Y = 630
IMAGE_BOX = (920, 540)
SUBTITLE_Y = 1330
#: 배경을 움직이려고 화면보다 조금 크게 만들어 두고 잘라 낸다
BG_SCALE = 1.12

FONT_CANDIDATES = {
    "Darwin": ["/System/Library/Fonts/AppleSDGothicNeo.ttc", "/System/Library/Fonts/Supplemental/AppleGothic.ttf"],
    "Windows": [r"C:\Windows\Fonts\malgunbd.ttf", r"C:\Windows\Fonts\malgun.ttf"],
    "Linux": ["/usr/share/fonts/opentype/noto/NotoSansCJK-Bold.ttc", "/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc",
              "/usr/share/fonts/truetype/nanum/NanumGothicBold.ttf", "/usr/share/fonts/truetype/nanum/NanumGothic.ttf"],
}
MONO_CANDIDATES = {
    "Darwin": ["/System/Library/Fonts/Menlo.ttc", "/System/Library/Fonts/Monaco.ttf"],
    "Windows": [r"C:\Windows\Fonts\consola.ttf", r"C:\Windows\Fonts\cour.ttf"],
    "Linux": ["/usr/share/fonts/truetype/dejavu/DejaVuSansMono.ttf"],
}


#: 말 없는 장면(매매 재현 끝 고지 카드)의 길이. 앞 여유·끝 머묾을 더하면 1.3초 남짓
SILENT_SECONDS = 0.35
#: 모든 쇼츠 맨 끝에 붙는 devlog 블로그 광고 길이(초). 화면·문구는 remotion/src/Outro.tsx에 고정돼 있다(Remotion으로 그릴 때만)
OUTRO_SECONDS = 3.0
OUTRO = "outro"


@dataclass
class Scene:
    caption: str
    narration: str
    code: str | None = None
    #: 바탕 글의 이미지 주소(있으면 장면 배경으로 쓴다. 캐릭터가 없으면 가운데 카드로도)
    image: str | None = None
    #: 캐릭터 연기 지시. 문자열(연기 이름)이나 {"action": …}. 없으면 장면 글에서 상황을 읽어 고른다
    visual: str | dict | None = None
    #: 캐릭터 대신 쓸 Remotion 장면(remotion/src/custom.tsx에 등록한 kind)과 그 데이터. 예: 매매 재현 {"kind": "replay", "data": {...}}
    custom: dict | None = None
    #: CS 설명 도식(diagram.parse로 다듬은 것). 있으면 고양이 연기 대신 도식을 그리고 고양이는 옆에서 가리킨다
    diagram: dict | None = None


@dataclass
class Script:
    title: str
    scenes: list[Scene]
    footer: str = "devlog.life"
    #: 장면별 효과음(다른 연출이 얹는 소리). 캐릭터 연기 효과음은 렌더할 때 따로 더한다
    effects: list = field(default_factory=list)
    #: 화면 스타일. "clean"(기본, 승인된 깔끔한 스타일) 또는 "fast"(참고 영상 분석 견본: 위 그림·아래 캐릭터, 빠른 컷·자막·목소리)
    style: str = "clean"
    #: 연기할 캐릭터 이름(remotion/src/character/<이름>/). None이면 Noto 고양이
    character: str | None = None

    @property
    def fast(self) -> bool:
        return self.style == "fast"
    #: 매매 재현 영상(#265)이면 서버가 준 거래소·종목·봉·구간·체결
    replay: dict | None = None

    @staticmethod
    def from_job(job: dict, site: str = "devlog.life") -> "Script":
        origin = ("http://" if site.startswith(("localhost", "127.")) else "https://") + site
        cover = job.get("coverImage")
        scenes = []
        for k, s in enumerate(job.get("scenes", [])):
            image = s.get("image") or (cover if k == 0 else None)
            if image and image.startswith("/"):
                image = origin + image
            custom = s.get("custom")
            if not (isinstance(custom, dict) and isinstance(custom.get("kind"), str) and custom["kind"]):
                custom = None
            else:
                custom = {"kind": custom["kind"], "data": custom.get("data")}
            scenes.append(Scene(s.get("caption") or "", s.get("narration") or "", s.get("code"), image, s.get("visual"), custom,
                                diagram.parse(s.get("diagram"))))
        post_url = job.get("postUrl")
        handle = job.get("handle")
        if post_url:  # 바탕 글이 있으면 그 글 주소(서버는 사이트 안 경로를 준다)
            footer = post_url.split("://", 1)[-1] if "://" in post_url else site + post_url
        else:
            footer = f"{site}/@{handle}" if handle else site
        effects = [sfx.Effect(int(e["scene"]), float(e.get("at") or 0), str(e["sound"]), 0.6 if e.get("volume") is None else float(e["volume"]))
                   for e in job.get("effects") or [] if isinstance(e, dict) and e.get("sound") in sfx.SOUNDS]
        character = job.get("character") or DEFAULT_CHARACTER
        if not isinstance(character, str) or character not in remotion.characters():  # "cat"이나 모르는 이름이면 기본 고양이(Noto)
            character = None
        return Script(job.get("title") or "", scenes, footer, effects, style="fast" if job.get("style") == "fast" else "clean",
                      character=character, replay=job.get("replay"))


@dataclass
class Timing:
    """장면 하나의 시각(초). start는 영상 전체에서 이 장면 영상이 시작하는 때(앞 장면과 전환으로 겹친다)"""
    start: float
    lead: float
    speech: float
    length: float
    cues: list[captions.Cue] = field(default_factory=list)

    @property
    def voice_at(self) -> float:
        return self.start + self.lead


def plan(speech: list[float], words: list[list[captions.Word] | None], narrations: list[str],
         max_units: float = captions.PHRASE_UNITS) -> list[Timing]:
    """장면 길이와 시작 시각. 전환이 겹치는 만큼 다음 장면이 일찍 시작한다."""
    out: list[Timing] = []
    start = 0.0
    last = len(speech) - 1
    for i, seconds in enumerate(speech):
        lead = FIRST_LEAD if i == 0 else LEAD
        length = lead + seconds + (TAIL if i == last else SCENE_PADDING + TRANSITION)
        length = math.ceil(length * FPS - 1e-6) / FPS  # 프레임 단위로 맞춰 화면(Remotion)과 소리가 어긋나지 않게
        cues = captions.cues(narrations[i], seconds, words[i], tail=SCENE_PADDING, max_units=max_units)
        out.append(Timing(start, lead, seconds, length, cues))
        start += length - TRANSITION
    return out


def stretch(timings: list[Timing], scenes: set[int], minimum: float) -> list[Timing]:
    """scenes 장면을 minimum초보다 짧지 않게 늘리고 뒤 장면 시작 시각을 민다(목소리는 장면 앞에 그대로 둔다)."""
    out: list[Timing] = []
    start = 0.0
    for i, t in enumerate(timings):
        length = max(t.length, math.ceil(minimum * FPS - 1e-6) / FPS) if i in scenes else t.length
        out.append(Timing(start, t.lead, t.speech, length, t.cues))
        start += length - TRANSITION
    return out


def find_font(explicit: str | None = None, mono: bool = False) -> str:
    if explicit:
        if not Path(explicit).is_file():
            raise FileNotFoundError(f"글꼴 파일이 없어요: {explicit}")
        return explicit
    env = os.environ.get("DEVLOG_MONO_FONT" if mono else "DEVLOG_FONT")
    if env and Path(env).is_file():
        return env
    for path in (MONO_CANDIDATES if mono else FONT_CANDIDATES).get(platform.system(), []):
        if Path(path).is_file():
            return path
    if shutil.which("fc-match"):
        query = "monospace" if mono else ":lang=ko"
        found = subprocess.run(["fc-match", "-f", "%{file}", query], capture_output=True, text=True).stdout.strip()
        if found and Path(found).is_file():
            return found
    if mono:
        return find_font()
    raise FileNotFoundError("한국어 글꼴을 찾지 못했어요. --font로 글꼴 파일(.ttf/.ttc)을 알려 주세요.")


class Renderer:
    def __init__(self, font: str, mono_font: str, engine: str = "auto", ffmpeg: str = "ffmpeg", voice: str | None = None,
                 music: str | None = None, character: bool = True, use_remotion: bool | None = None, fetch_candles=candles.fetch):
        self.font = font
        self.mono_font = mono_font
        self.engine = tts.pick(engine)
        self.ffmpeg = ffmpeg
        self.voice = voice
        #: None이면 직접 합성한 배경음, "none"이면 배경음 없음, 아니면 음악 파일 경로
        self.music = music
        #: 2D 캐릭터가 상황을 연기하는 무대를 쓸지(Pillow 필요)
        self.character = character
        #: 화면을 Remotion으로 그릴지. None이면 node가 있을 때 쓴다. 못 쓰면 Pillow 무대(또는 글자 화면)로 그린다
        self.use_remotion = remotion.available() if use_remotion is None else use_remotion
        self.fetch_candles = fetch_candles

    def render(self, script: Script, out: Path) -> int:
        """MP4를 만들고 길이(ms)를 돌려준다."""
        if not script.scenes:
            raise ValueError("장면이 없어요.")
        chart = self._replay(script) if script.replay else None
        if chart is not None:
            if not self.use_remotion:
                raise RuntimeError("매매 재현 영상은 Remotion(Node 18 이상)이 있어야 만들 수 있어요.")
            # 투자 권유 고지는 대본과 상관없이 맨 끝에 말 없는 짧은 카드 한 장으로 붙인다(AI가 대본을 다시 써도 빠지지 않는다)
            mode = "실계좌" if (script.replay or {}).get("mode") == "LIVE" else "모의투자"
            card = Scene("", "", custom={"kind": "replay-card", "data": {"mode": mode}})
            script = Script(script.title, [*script.scenes, card], script.footer, list(script.effects), style=script.style,
                            character=script.character, replay=script.replay)
        with tempfile.TemporaryDirectory(prefix="devlog-render-") as tmp:
            work = Path(tmp)
            shutil.copyfile(self.font, work / ("font" + Path(self.font).suffix))
            shutil.copyfile(self.mono_font, work / ("mono" + Path(self.mono_font).suffix))
            audios, words = self._voices(work, script)
            speech = [probe_ms(a, self.ffmpeg) / 1000 for a in audios]
            timings = plan(speech, words, [s.narration for s in script.scenes],
                           captions.FAST_PHRASE_UNITS if script.fast else captions.PHRASE_UNITS)
            if chart is not None:  # 거래 하나는 말이 짧아도 MIN_TRADE초는 보여 준다
                body = len(script.scenes) - 1
                timings = stretch(timings, {replay.fill_scene(k, body) for k in range(len(chart.fills))}, replay.MIN_TRADE + TRANSITION)
            total = timings[-1].start + timings[-1].length
            images = [self._image(work, i, s.image) for i, s in enumerate(script.scenes)]
            if chart is not None:
                self._attach_replay(script, chart, timings)
            joined = work / "out.mp4"
            if self.use_remotion:
                body = timings
                script, timings = self._with_outro(work, script, timings, audios, words, images)
                total = timings[-1].start + timings[-1].length
                # 화면은 Remotion이 한 번에(전환 포함) 그리고, 소리는 아래 _join이 섞는다
                visuals = self._visuals(script)
                video = work / "screen.mp4"
                props = self._props(script, timings, total, audios, words, images, visuals)
                remotion.render(props, work, video)
                effects = list(script.effects) + [
                    sfx.Effect(i, at, name, volume)
                    for i, v in enumerate(visuals) if script.scenes[i].custom is None and script.scenes[i].diagram is None
                    for name, at, volume in acting.effects(v, timings[i].lead, timings[i].speech, timings[i].length)]
                # 도식 칸이 나타날 때마다 톡
                effects += [sfx.Effect(i, timings[i].lead + at / 1000, "pop", 0.35)
                            for i, sc in enumerate(props["scenes"]) if sc["diagram"] for at in diagram.reveal_times(sc["diagram"])]
                # 장면이 넘어갈 때마다 휙 소리(전환과 같은 때). 점프 컷으로 넘어가는 장면은 소리 없이 끊는다
                effects += [sfx.Effect(i, 0.0, "whip", 0.3) for i in range(1, len(script.scenes)) if not (script.scenes[i].custom or {}).get("cut")]
                music = {}
                if chart is not None:  # 배경음은 제목 카드가 끝나면 들어오고 결산 화면부터 작아진다
                    soft = replay.result_scene(chart, [t.start for t in body[:-1]])
                    music = {"music_at": replay.TITLE,
                             "music_soft": body[soft].start if soft is not None else body[-1].start}
                self._join(work, [video], audios, timings, total, joined, effects, joined_video=True, upbeat=chart is not None, **music)
            else:
                stages = self._stages(script, audios, timings)
                segments = [self._encode(work, i, timings[i], images[i], stages[i],
                                         self._filters(work, script, i, scene, timings[i], total, images[i] is not None, stages[i] is not None))
                            for i, scene in enumerate(script.scenes)]
                effects = list(script.effects)
                for i, act in enumerate(stages):
                    if act is not None:
                        effects += [sfx.Effect(i, at, name, volume) for name, at, volume in act.effects()]
                self._join(work, segments, audios, timings, total, joined, effects)
            out.parent.mkdir(parents=True, exist_ok=True)
            shutil.move(str(joined), str(out))
        return probe_ms(out, self.ffmpeg)

    def _with_outro(self, work: Path, script: Script, timings: list[Timing], audios: list[Path], words: list, images: list):
        """맨 끝에 말 없는 블로그 광고 장면(OUTRO_SECONDS초)을 붙인다. 앞 장면과는 전환으로 겹치고, 주소 칩이 뜰 때 딩 소리를 낸다.
        audios·words·images에는 그 장면 몫을 더하고, 광고를 붙인 대본과 시각표를 돌려준다."""
        i = len(script.scenes)
        outro = Scene("", "", custom={"kind": OUTRO, "data": None})
        effects = [*script.effects, sfx.Effect(i, 0.6, "ding", 0.35)]
        script = Script(script.title, [*script.scenes, outro], script.footer, effects, style=script.style,
                        character=script.character, replay=script.replay)
        audio = work / f"a{i}.wav"
        self._ffmpeg(work, ["-f", "lavfi", "-i", "anullsrc=r=44100:cl=stereo", "-t", f"{SILENT_SECONDS:.2f}", audio.name])
        audios.append(audio)
        words.append(None)
        images.append(None)
        last = timings[-1]
        return script, [*timings, Timing(last.start + last.length - TRANSITION, 0.0, SILENT_SECONDS, OUTRO_SECONDS)]

    # ── 매매 재현 (#265) ───────────────────────────────────────────────

    def _replay(self, script: Script) -> replay.Replay:
        """서버가 준 체결·구간으로 차트를 준비한다. 캔들은 대본에 있으면 그대로, 없으면 거래소 공개 시세에서 받는다."""
        data = script.replay or {}
        chart = replay.Replay.from_job(data)
        if not chart.fills:
            raise ValueError("재현할 체결이 없어요.")
        if data.get("candles"):
            chart.candles = candles.from_json(data["candles"])
        else:
            # 서버가 정한 구간을 가능한 한 1분봉으로 빨리감기한다. 짧은 봉을 못 받으면 서버가 고른 봉으로 그린다
            start, end = replay.window_of(data)
            play = replay.play_interval(start, end)
            if play != chart.interval:
                try:
                    chart.candles = self.fetch_candles(chart.exchange, chart.symbol, play, replay.history_start(start, end, play), end)
                    chart.interval = play
                    return chart
                except candles.CandleError as e:
                    log.warning("%s 캔들을 받지 못해 %s로 그려요: %s", play, chart.interval, e)
            chart.candles = self.fetch_candles(chart.exchange, chart.symbol, chart.interval, replay.history_start(start, end, chart.interval), end)
        return chart

    @staticmethod
    def _attach_replay(script: Script, chart: replay.Replay, timings: list[Timing]) -> None:
        """대본 장면마다 Remotion 매매 재현 장면을 붙이고 체결 효과음을 얹는다. 0번 장면 첫머리는 제목 카드, 마지막 장면은 고지 카드다.
        차트는 영상 전체 프레임으로 그려 장면이 바뀌어도 끊김 없이 이어진다(장면 사이는 전환 없이 붙인다).
        큰 요점 자막은 쓰지 않는다(체결 자막은 차트 안에 작게 뜬다)."""
        body = timings[:-1]
        starts = [t.start for t in body]
        end = body[-1].start + body[-1].length
        data = replay.chart_data(chart, starts, end, FPS, script.scenes[0].caption.strip() or script.title)
        for i, t in enumerate(body):
            script.scenes[i].custom = {"kind": "replay", "cut": True, "data": {**data, "startFrame": round(t.start * FPS)}}
            script.scenes[i].caption = ""
        for scene, at, kind, volume in trade_sfx.per_scene(replay.cues(chart, starts, end), starts):
            script.effects.append(sfx.Effect(scene, at, str(trade_sfx.sound_file(kind, trade_sfx.cache_dir())), volume))

    def _visuals(self, script: Script) -> list:
        everything = " ".join(f"{s.caption} {s.narration}" for s in script.scenes)
        visuals = [acting.infer(scene, everything) for scene in script.scenes]
        for i, v in enumerate(visuals):
            log.info("장면 %d 연기: %s", i + 1, v.action)
        return visuals

    def _props(self, script: Script, timings: list[Timing], total: float, audios: list[Path], words, images, visuals) -> dict:
        """Remotion에 넘길 props (remotion/src/types.ts의 ShortsProps)"""
        scenes = []
        for i, scene in enumerate(script.scenes):
            t, v = timings[i], visuals[i]
            pages = captions.pages(scene.narration, t.speech, words[i], tail=SCENE_PADDING,
                                   max_units=captions.FAST_PHRASE_UNITS if script.fast else captions.PHRASE_UNITS)
            scenes.append({
                "caption": scene.caption,
                "code": layout.code_lines(scene.code, 40, 8) if scene.code else [],
                "action": v.action,
                "params": {"focus": v.focus, "good": v.good, "count": v.count, "before": v.before, "after": v.after,
                           "label": v.label, "shell": v.shell},
                "durationInFrames": round(t.length * FPS),
                "leadFrames": round(t.lead * FPS),
                "speechFrames": round(t.speech * FPS),
                "pages": pages,
                "mouth": [round(m, 3) for m in acting.mouth_envelope(audios[i], FPS)],
                "image": images[i].name if images[i] is not None else None,
                "custom": scene.custom,
                "cut": bool((scene.custom or {}).get("cut")),
                "diagram": diagram.timed(scene.diagram, pages, t.speech) if scene.diagram and scene.custom is None else None,
            })
        if script.fast:
            captions.emphasize([sc["pages"] for sc in scenes], [sc.caption for sc in script.scenes])
        return {"title": script.title, "footer": script.footer, "transitionFrames": round(TRANSITION * FPS),
                "totalFrames": round(total * FPS), "scenes": scenes, "style": script.style,
                "character": script.character}

    def _voices(self, work: Path, script: Script) -> tuple[list[Path], list[list[captions.Word] | None]]:
        """장면마다 음성. 한 장면이라도 실패하면 다음 엔진으로 처음부터 다시 만든다(목소리가 섞이지 않게).
        edge(인터넷 신경망) → supertonic(컴퓨터 안 신경망) → 기기 음성 순서다."""
        chain = [self.engine]
        if self.engine == "edge":
            chain.append(tts.offline())
        if chain[-1] == "supertonic":
            chain.append(tts.device())
        for k, engine in enumerate(chain):
            try:
                return self._synthesize(work, script, engine, self.voice if k == 0 else None)
            except Exception as e:
                if k == len(chain) - 1:
                    raise
                log.warning("음성(%s) 실패, %s로 다시 만들어요: %s", engine, chain[k + 1], e)
        raise AssertionError("unreachable")

    def _synthesize(self, work: Path, script: Script, engine: str, voice: str | None):
        audios, words = [], []
        speed = {"faster": FAST_VOICE} if script.fast else {}
        for i, scene in enumerate(script.scenes):
            audio = work / f"a{i}.wav"
            if not scene.narration.strip():  # 말 없는 장면(매매 재현 끝 고지 카드)
                self._ffmpeg(work, ["-f", "lavfi", "-i", "anullsrc=r=44100:cl=stereo", "-t", f"{SILENT_SECONDS:.2f}", audio.name])
                words.append(None)
            else:
                words.append(tts.synthesize(scene.narration, audio, engine, self.ffmpeg, voice, **speed))
            audios.append(audio)
        return audios, words

    def _image(self, work: Path, i: int, url: str | None) -> Path | None:
        """바탕 글 이미지를 받는다. 못 받으면 이미지 없이 만든다(영상 전체를 실패시키지 않는다)."""
        if not url or not url.startswith(("http://", "https://")):
            return None
        target = work / f"img{i}"
        try:
            request = urllib.request.Request(url, headers={"User-Agent": "devlog-render-worker"})
            with urllib.request.urlopen(request, timeout=IMAGE_TIMEOUT) as response:
                if not (response.headers.get("Content-Type") or "").startswith("image/"):
                    raise ValueError(f"이미지가 아니에요({response.headers.get('Content-Type')})")
                data = response.read(IMAGE_MAX_BYTES + 1)
            if len(data) > IMAGE_MAX_BYTES:
                raise ValueError("이미지가 너무 커요")
            target.write_bytes(data)
            # 움직이는 그림·이상한 형식은 여기서 걸러 낸다: 첫 장면을 PNG로 바꿔 둔다
            png = work / f"img{i}.png"
            self._ffmpeg(work, ["-i", target.name, "-frames:v", "1", "-vf", "scale='min(1600,iw)':-2", png.name])
            return png
        except Exception as e:  # noqa: BLE001 - 이미지 하나 때문에 영상을 버리지 않는다
            log.warning("장면 %d 이미지를 쓰지 않아요: %s", i + 1, e)
            return None

    def _background(self, work: Path, i: int, image: Path | None) -> list[str]:
        """천천히 떠다니는 배경 입력. 화면보다 크게 만든 한 장을 잘라 내는 위치를 시간에 따라 옮긴다."""
        bw, bh = round(layout.WIDTH * BG_SCALE / 2) * 2, round(layout.HEIGHT * BG_SCALE / 2) * 2
        bg = work / f"bg{i}.png"
        if image is not None:
            # 밝은 화면 캡처여도 흰 글자가 읽히게 흐리게 하고 밝기 상한을 45%로 누른다
            vf = (f"scale={bw}:{bh}:force_original_aspect_ratio=increase,crop={bw}:{bh},gblur=sigma=40,"
                  f"eq=saturation=0.7,colorlevels=romax=0.42:gomax=0.42:bomax=0.45")
            self._ffmpeg(work, ["-i", image.name, "-frames:v", "1", "-vf", vf, bg.name])
        else:
            palette = draw.PALETTES[i % len(draw.PALETTES)]
            self._ffmpeg(work, ["-f", "lavfi", "-i", f"color=c=black:s={bw}x{bh}", "-frames:v", "1",
                                "-vf", f"format=rgb24,{draw.background_expr(palette, bw, bh)}", bg.name])
        return ["-loop", "1", "-framerate", str(FPS), "-i", bg.name]

    def _stages(self, script: Script, audios: list[Path], timings: list[Timing]) -> list:
        """장면마다 캐릭터 무대. Pillow가 없거나 --no-character면 모두 None(글자 화면)."""
        if stage is None or not self.character:
            return [None] * len(script.scenes)
        fonts = stage.Fonts(self.font, self.mono_font)
        everything = " ".join(f"{s.caption} {s.narration}" for s in script.scenes)
        out = []
        for i, scene in enumerate(script.scenes):
            visual = stage.infer(scene, everything)
            t = timings[i]
            out.append(stage.Stage(visual, fonts, t.length, t.lead, t.speech, stage.mouth_envelope(audios[i], FPS), FPS, first=i == 0))
            log.info("장면 %d 연기: %s", i + 1, visual.action)
        return out

    def _encode(self, work: Path, i: int, timing: Timing, image: Path | None, act, filters: str) -> Path:
        """장면 영상(소리 없음). 마지막에 다시 인코딩하므로 여기서는 화질을 거의 잃지 않게 만든다."""
        seg = work / f"s{i}.mp4"
        bw, bh = round(layout.WIDTH * BG_SCALE / 2) * 2, round(layout.HEIGHT * BG_SCALE / 2) * 2
        dx, dy = (bw - layout.WIDTH) / 2, (bh - layout.HEIGHT) / 2
        sign = 1 if i % 2 == 0 else -1
        # 장면 길이 동안 한쪽 끝에서 반대쪽 끝으로 아주 천천히 흐른다(장면마다 방향을 바꾼다)
        drift = (f"crop={layout.WIDTH}:{layout.HEIGHT}:x='{dx:.1f}+{sign * dx * 0.8:.1f}*(t/{timing.length:.3f}-0.5)'"
                 f":y='{dy:.1f}-{dy * 0.8:.1f}*(t/{timing.length:.3f}-0.5)'")
        inputs = self._background(work, i, image)
        graph = f"[0:v]{drift},setsar=1,format=yuv420p[bg]"
        if act is not None:
            # 캐릭터 무대: 파이썬이 그린 RGBA 프레임을 표준 입력으로 받아 배경 위에 겹친다
            inputs += ["-f", "rawvideo", "-pix_fmt", "rgba", "-s", f"{layout.WIDTH}x{stage.STAGE_H}", "-r", str(FPS), "-i", "pipe:0"]
            graph += f";[1:v]format=rgba[stage];[bg][stage]overlay=x=0:y={STAGE_Y}:eof_action=repeat[bg]"
        elif image is not None:
            inputs += ["-loop", "1", "-framerate", str(FPS), "-i", image.name]
            graph += (f";[1:v]scale={IMAGE_BOX[0]}:{IMAGE_BOX[1]}:force_original_aspect_ratio=decrease,format=yuva420p,"
                      f"fade=in:st={timing.lead:.3f}:d=0.35:alpha=1[img];"
                      f"[bg][img]overlay=x=(W-w)/2:y={MIDDLE_TOP}+({IMAGE_BOX[1]}-h)/2[bg]")
        graph += f";[bg]{filters},format=yuv420p[v]"
        args = [*inputs, "-filter_complex", graph, "-map", "[v]", "-t", f"{timing.length:.3f}", "-r", str(FPS),
                "-c:v", "libx264", "-preset", "veryfast", "-crf", "12", "-pix_fmt", "yuv420p", "-an", seg.name]
        if act is None:
            self._ffmpeg(work, args)
        else:
            frames = (act.frame(n / FPS) for n in range(int(math.ceil(timing.length * FPS)) + 1))
            self._ffmpeg_pipe(work, args, frames)
        return seg

    def _ffmpeg_pipe(self, work: Path, args: list[str], frames) -> None:
        """프레임을 표준 입력으로 흘려 넣는 ffmpeg. 오류 메시지는 임시 파일로 받아 파이프가 막히지 않게 한다."""
        with tempfile.TemporaryFile() as err:
            proc = subprocess.Popen([self.ffmpeg, "-y", "-loglevel", "error", *args], cwd=work, stdin=subprocess.PIPE,
                                    stdout=subprocess.DEVNULL, stderr=err)
            try:
                for data in frames:
                    proc.stdin.write(data)
            except BrokenPipeError:
                pass  # ffmpeg가 먼저 끝났다(-t 도달 또는 오류). 아래에서 종료 코드로 가린다
            finally:
                try:
                    proc.stdin.close()
                except BrokenPipeError:
                    pass
            try:
                code = proc.wait(timeout=FFMPEG_TIMEOUT)
            except subprocess.TimeoutExpired:
                proc.kill()
                raise RuntimeError(f"ffmpeg가 {FFMPEG_TIMEOUT}초 안에 끝나지 않았어요.") from None
            if code != 0:
                err.seek(0)
                raise RuntimeError(f"ffmpeg 실패 (코드 {code}): {err.read().decode('utf-8', 'replace').strip()[-400:]}")

    def _filters(self, work: Path, script: Script, i: int, scene: Scene, timing: Timing, total: float, has_image: bool,
                 has_stage: bool = False) -> str:
        font = "font" + Path(self.font).suffix
        mono = "mono" + Path(self.mono_font).suffix
        c = draw.Canvas(work, f"t{i}", font, mono)
        lead = timing.lead
        c.progress(timing.start, total, ACCENT)

        title = layout.wrap(script.title, 22, 1)
        if title:
            c.text(title[0], 38, MUTED, 150, alpha="0.9")

        # 요점 자막: 가운데에 그릴 것(코드·이미지)이 있으면 위쪽에, 없으면 화면 가운데에 크게
        middle = bool(scene.code) or has_image or has_stage
        size = 88 if middle else 104
        lines = layout.wrap(scene.caption, 10.5 if middle else 9, 3)
        gap = round(size * 1.24)
        top = 300 if middle else 820 - len(lines) * gap // 2
        for k, line in enumerate(lines):
            c.text(line, size, "white", draw.rise(top + k * gap, k * 0.06), border=4, border_color="black@0.35",
                   shadow=6, alpha=draw.fade_in(k * 0.06))
        if lines:
            grow = "max(2,120*clip((t-0.15)/0.3,0,1))"
            c.box(f"'({layout.WIDTH}-{grow})/2'", top + len(lines) * gap + 10, f"'{grow}'", 8, CAPTION_BAR, enable=draw.after(0.15))

        if scene.code and not has_stage:  # 캐릭터 무대에서는 모니터 소품이 코드를 보여 준다
            self._code(c, scene.code, lead, timing.speech)

        # 구절 자막: 화면 아래 1/3, 음성에 맞춰 한 구절씩
        for cue in timing.cues:
            start, end = lead + cue.start, lead + cue.end
            for k, line in enumerate(cue.lines):
                y = SUBTITLE_Y + k * 88 - (len(cue.lines) - 1) * 44
                c.text(line, 66, "white", draw.rise(y, start, 0.12, 14), border=7, border_color="black@0.9",
                       alpha=draw.fade_in(start, 0.08), enable=draw.between(start, end))

        c.text(script.footer, 34, MUTED, 1560, alpha="0.85")
        return c.filters()

    def _code(self, c: draw.Canvas, code: str, lead: float, speech: float) -> None:
        """코드 카드: 줄마다 차례로 써진다. 말하는 시간의 앞 60% 안에 모두 나온다"""
        lines = layout.code_lines(code, 38, 8)
        top, line_h = MIDDLE_TOP, 56
        height = len(lines) * line_h + 70
        c.box(70, top, layout.WIDTH - 140, height, CODE_BG)
        c.box(70, top, 8, height, ACCENT)
        step = min(0.45, max(0.12, speech * 0.6 / max(1, len(lines))))
        for k, line in enumerate(lines):
            if not line.strip():
                continue
            at = lead + 0.15 + k * step
            code_part, comment = split_comment(line)
            y = top + 38 + k * line_h
            c.text(code_part, 38, CODE_TEXT, y, x="112", mono=True, alpha=draw.fade_in(at, 0.12), enable=draw.after(at))
            if comment:
                # 고정폭 글꼴은 글자 폭이 0.6em쯤이라 코드 뒤 자리를 셈으로 잡는다. 한글 주석은 한국어 글꼴로 그린다
                x = 112 + round(len(code_part) * 38 * MONO_ADVANCE)
                c.text(comment, 38, CODE_COMMENT, y, x=str(x), mono=True, alpha=draw.fade_in(at, 0.12), enable=draw.after(at))

    def _join(self, work: Path, segments: list[Path], audios: list[Path], timings: list[Timing], total: float, out: Path,
              effects: list | None = None, joined_video: bool = False, upbeat: bool = False,
              music_at: float = 0.0, music_soft: float | None = None) -> None:
        """장면을 xfade로 잇고, 내레이션을 제자리에 놓고, 배경음을 깔고, 효과음을 얹어 한 번에 최종 인코딩한다.

        효과음(sfx.Effect)은 장면이 화면에 나오기 시작한 시각(timings[scene].start) + at에 놓는다. 같은 소리 파일은 입력 하나를
        asplit으로 나눠 쓴다. 효과음은 배경음 줄이기(ducking)의 기준에 넣지 않는다(내레이션만 기준).
        배경음은 music_at초에 들어오고, music_soft초부터 MUSIC_SOFT배로 작아진다(매매 재현의 제목 카드·결산 화면)."""
        n = len(audios)
        args: list[str] = []
        if joined_video:  # 이미 이어진 화면 하나(Remotion). 입력 번호를 장면 수에 맞추려고 같은 파일을 한 번만 넣고 나머지 자리는 비운다
            args += ["-i", segments[0].name]
            for _ in range(n - 1):
                args += ["-f", "lavfi", "-t", "0.04", "-i", "anullsrc=r=44100:cl=stereo"]
        else:
            for seg in segments:
                args += ["-i", seg.name]
        for audio in audios:
            args += ["-i", audio.name]
        music = self._music(upbeat)
        if music:
            args += ["-stream_loop", "-1", "-i", str(music)]
        placed: dict[str, list[tuple[float, float]]] = {}
        for e in effects or []:
            if 0 <= e.scene < n:
                at = timings[e.scene].start + max(0.0, e.at)
                if at < total:
                    placed.setdefault(str(sfx.path(e.path).resolve()), []).append((at, e.volume))
        sfx_base = 2 * n + (1 if music else 0)
        for path in placed:
            args += ["-i", path]

        graph = []
        if n == 1 or joined_video:
            graph.append("[0:v]null[v]")
        else:
            prev = "[0:v]"
            for k in range(1, n):
                label = "[v]" if k == n - 1 else f"[x{k}]"
                graph.append(f"{prev}[{k}:v]xfade=transition={TRANSITION_KIND}:duration={TRANSITION}:offset={timings[k].start:.3f}{label}")
                prev = label
        for k, t in enumerate(timings):
            ms = round(t.voice_at * 1000)
            graph.append(f"[{n + k}:a]aresample=44100,adelay={ms}:all=1[n{k}]")
        mixed = "".join(f"[n{k}]" for k in range(n))
        graph.append(f"{mixed}amix=inputs={n}:normalize=0:duration=longest,apad[nar]")
        if music:
            graph.append("[nar]asplit=2[nar1][key]")
            at = min(max(0.0, music_at), max(0.0, total - 1.0))
            chain = f"[{2 * n}:a]aresample=44100,aformat=channel_layouts=stereo,atrim=0:{total - at:.3f},volume={BGM_VOLUME},afade=t=in:d=0.6"
            if at > 0:
                chain += f",adelay={round(at * 1000)}:all=1"
            if music_soft is not None and music_soft < total:
                chain += f",volume='1-{1 - MUSIC_SOFT:.2f}*clip((t-{music_soft:.3f})/1.2,0,1)':eval=frame"
            graph.append(f"{chain},afade=t=out:st={max(0.0, total - 1.5):.3f}:d=1.5[bgm]")
            graph.append("[bgm][key]sidechaincompress=threshold=0.015:ratio=6:attack=30:release=450:makeup=1[duck]")
            graph.append("[nar1][duck]amix=inputs=2:normalize=0:duration=first[mix]")
        else:
            graph.append("[nar]anull[mix]")
        if placed:
            labels = []
            for k, (path, uses) in enumerate(placed.items()):
                split = "".join(f"[e{k}_{j}]" for j in range(len(uses)))
                graph.append(f"[{sfx_base + k}:a]aresample=44100,aformat=channel_layouts=stereo,asplit={len(uses)}{split}")
                for j, (at, volume) in enumerate(uses):
                    graph.append(f"[e{k}_{j}]volume={volume:.2f},adelay={round(at * 1000)}:all=1[f{k}_{j}]")
                    labels.append(f"[f{k}_{j}]")
            graph.append(f"{''.join(labels)}amix=inputs={len(labels)}:normalize=0:duration=longest[fx]")
            graph.append("[mix][fx]amix=inputs=2:normalize=0:duration=first[mixfx]")
            graph.append("[mixfx]loudnorm=I=-14:TP=-1.5:LRA=11,aresample=44100[a]")
        else:
            graph.append("[mix]loudnorm=I=-14:TP=-1.5:LRA=11,aresample=44100[a]")

        rate = min(VIDEO_MAXRATE, int(MAX_BYTES * 8 / max(total, 1) - AUDIO_BITRATE))
        self._ffmpeg(work, [
            *args, "-filter_complex", ";".join(graph), "-map", "[v]", "-map", "[a]", "-t", f"{total:.3f}",
            "-c:v", "libx264", "-preset", "medium", "-crf", "20", "-maxrate", str(rate), "-bufsize", str(rate * 2),
            "-pix_fmt", "yuv420p", "-r", str(FPS),
            "-c:a", "aac", "-b:a", str(AUDIO_BITRATE), "-ar", "44100", "-ac", "2",
            "-movflags", "+faststart", out.name], timeout=FINAL_TIMEOUT)

    def _music(self, upbeat: bool = False) -> Path | None:
        """upbeat: 매매 재현처럼 빨리감기 영상이면 빠른 배경음(trade_bgm)을 쓴다. --bgm 파일이 있으면 그 파일이 우선한다."""
        if self.music == "none":
            return None
        if self.music:
            if not Path(self.music).is_file():
                raise FileNotFoundError(f"배경음 파일이 없어요: {self.music}")
            return Path(self.music).resolve()
        try:
            return (trade_bgm.path() if upbeat else bgm.path()).resolve()
        except OSError as e:
            log.warning("배경음을 만들지 못해 배경음 없이 만들어요: %s", e)
            return None

    def _ffmpeg(self, work: Path, args: list[str], timeout: int = FFMPEG_TIMEOUT) -> None:
        try:
            result = subprocess.run([self.ffmpeg, "-y", "-loglevel", "error", *args], cwd=work, capture_output=True, text=True,
                                    encoding="utf-8", errors="replace", timeout=timeout)
        except subprocess.TimeoutExpired:
            raise RuntimeError(f"ffmpeg가 {timeout}초 안에 끝나지 않았어요.") from None
        if result.returncode != 0:
            raise RuntimeError(f"ffmpeg 실패 (코드 {result.returncode}): {result.stderr.strip()[-400:]}")


#: 고정폭 글꼴 글자 하나의 폭(글자 크기 대비). Menlo·DejaVu Sans Mono 0.6, Consolas 0.55
MONO_ADVANCE = 0.6
_COMMENT_MARKERS = ("//", "#", "--", "/*")


def split_comment(line: str) -> tuple[str, str]:
    """코드 줄을 [코드, 주석]으로 나눈다. 따옴표 안의 표시는 신경 쓰지 않는 어림이지만 화면 색 구분에는 충분하다."""
    stripped = line.lstrip()
    if stripped.startswith(_COMMENT_MARKERS) or stripped.startswith("*"):
        return "", line
    # 줄 중간 주석은 앞에 띄어쓰기가 있는 // # /* 만 본다(git checkout -- 같은 옵션을 주석으로 읽지 않게)
    cut = min((i + 1 for i in (line.find(" " + m) for m in ("//", "#", "/*")) if i >= 0), default=-1)
    if cut < 0:
        return line, ""
    return line[:cut], line[cut:]


def probe_ms(path: Path, ffmpeg: str = "ffmpeg") -> int:
    ffprobe = str(Path(ffmpeg).with_name("ffprobe" + (".exe" if ffmpeg.lower().endswith(".exe") else ""))) if os.sep in ffmpeg else "ffprobe"
    try:
        result = subprocess.run([ffprobe, "-v", "error", "-show_entries", "format=duration", "-of", "json", str(path)],
                                capture_output=True, text=True, timeout=60)
    except subprocess.TimeoutExpired:
        raise RuntimeError("ffprobe가 60초 안에 끝나지 않았어요.") from None
    if result.returncode != 0:
        raise RuntimeError(f"ffprobe 실패: {result.stderr.strip()[:200]}")
    return round(float(json.loads(result.stdout)["format"]["duration"]) * 1000)
