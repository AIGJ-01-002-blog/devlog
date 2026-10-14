"""쇼츠 대본 → 9:16 MP4 (1080x1920, 30fps, H.264 + AAC).

장면마다 [제목 · 큰 자막 · (코드) · 내레이션 자막 · 블로그 주소 · 진행 막대] 화면을 그리고 그 장면의 내레이션 음성 길이만큼 보여 준 뒤,
장면 영상을 이어 붙인다. drawtext가 쓰는 글꼴·글자 파일은 작업 폴더 안의 짧은 상대 경로로 넘겨서
Windows 경로(C:\\…)의 콜론·역슬래시를 필터 문법에서 따로 이스케이프하지 않아도 되게 한다.
"""

from __future__ import annotations

import json
import os
import platform
import shutil
import subprocess
import tempfile
from dataclasses import dataclass
from pathlib import Path

from . import layout, tts

FPS = 30
SCENE_PADDING = 0.4
#: ffmpeg 한 번이 멈췄을 때 기다리는 한도. 장면 하나는 보통 몇 초면 끝난다
FFMPEG_TIMEOUT = 300
#: 인그레스 본문 상한(21MB)과 서버 상한(20MB) 안에 들어가게 비트레이트를 묶는다
VIDEO_MAXRATE = "3M"

BG = "0x0f172a"
ACCENT = "0x38bdf8"
MUTED = "0x94a3b8"
CODE_BG = "0x1e293b"

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


@dataclass
class Scene:
    caption: str
    narration: str
    code: str | None = None


@dataclass
class Script:
    title: str
    scenes: list[Scene]
    footer: str = "devlog.life"

    @staticmethod
    def from_job(job: dict, site: str = "devlog.life") -> "Script":
        scenes = [Scene(s.get("caption") or "", s.get("narration") or "", s.get("code")) for s in job.get("scenes", [])]
        post_url = job.get("postUrl")
        handle = job.get("handle")
        if post_url:  # 바탕 글이 있으면 그 글 주소(서버는 사이트 안 경로를 준다)
            footer = post_url.split("://", 1)[-1] if "://" in post_url else site + post_url
        else:
            footer = f"{site}/@{handle}" if handle else site
        return Script(job.get("title") or "", scenes, footer)


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
    def __init__(self, font: str, mono_font: str, engine: str = "auto", ffmpeg: str = "ffmpeg", voice: str | None = None):
        self.font = font
        self.mono_font = mono_font
        self.engine = tts.pick(engine)
        self.ffmpeg = ffmpeg
        self.voice = voice

    def render(self, script: Script, out: Path) -> int:
        """MP4를 만들고 길이(ms)를 돌려준다."""
        if not script.scenes:
            raise ValueError("장면이 없어요.")
        with tempfile.TemporaryDirectory(prefix="devlog-render-") as tmp:
            work = Path(tmp)
            shutil.copyfile(self.font, work / ("font" + Path(self.font).suffix))
            shutil.copyfile(self.mono_font, work / ("mono" + Path(self.mono_font).suffix))
            segments = []
            for i, scene in enumerate(script.scenes):
                segments.append(self._scene(work, script, i, scene))
            listing = work / "segments.txt"
            listing.write_text("".join(f"file '{s.name}'\n" for s in segments), encoding="utf-8")
            joined = work / "out.mp4"
            self._ffmpeg(work, ["-f", "concat", "-safe", "0", "-i", listing.name, "-c", "copy", "-movflags", "+faststart", joined.name])
            out.parent.mkdir(parents=True, exist_ok=True)
            shutil.move(str(joined), str(out))
        return probe_ms(out, self.ffmpeg)

    def _scene(self, work: Path, script: Script, i: int, scene: Scene) -> Path:
        audio = work / f"a{i}.wav"
        tts.synthesize(scene.narration, audio, self.engine, self.ffmpeg, self.voice)
        seconds = probe_ms(audio, self.ffmpeg) / 1000 + SCENE_PADDING
        filters = self._filters(work, script, i, scene)
        seg = work / f"s{i}.mp4"
        self._ffmpeg(work, [
            "-f", "lavfi", "-i", f"color=c={BG}:s={layout.WIDTH}x{layout.HEIGHT}:r={FPS}",
            "-i", audio.name,
            "-filter_complex", f"[0:v]{filters}[v];[1:a]apad[a]",
            "-map", "[v]", "-map", "[a]", "-t", f"{seconds:.3f}",
            "-c:v", "libx264", "-preset", "veryfast", "-crf", "23", "-maxrate", VIDEO_MAXRATE, "-bufsize", "6M",
            "-pix_fmt", "yuv420p", "-r", str(FPS),
            "-c:a", "aac", "-b:a", "128k", "-ar", "44100", "-ac", "2",
            seg.name])
        return seg

    def _filters(self, work: Path, script: Script, i: int, scene: Scene) -> str:
        font = "font" + Path(self.font).suffix
        mono = "mono" + Path(self.mono_font).suffix
        parts: list[str] = []
        n = 0

        def text(value: str, size: int, color: str, y: int, x: str = "(w-text_w)/2", fontfile: str = font) -> None:
            nonlocal n
            name = f"t{i}_{n}.txt"
            n += 1
            (work / name).write_text(value, encoding="utf-8")
            parts.append(f"drawtext=fontfile={fontfile}:textfile={name}:expansion=none:fontsize={size}:fontcolor={color}:x={x}:y={y}")

        parts.append(f"drawbox=x=0:y=0:w={layout.WIDTH}:h=12:color={ACCENT}:t=fill")
        for k, line in enumerate(layout.wrap(script.title, 20, 2)):
            text(line, 44, MUTED, 120 + k * 60)

        caption = layout.wrap(scene.caption, 10.5, 3)
        top = 640 - len(caption) * 55
        for k, line in enumerate(caption):
            text(line, 92, "white", top + k * 112)

        if scene.code:
            lines = layout.code_lines(scene.code)
            parts.append(f"drawbox=x=60:y=900:w={layout.WIDTH - 120}:h={len(lines) * 50 + 50}:color={CODE_BG}:t=fill")
            for k, line in enumerate(lines):
                if line:
                    text(line, 36, "0xe2e8f0", 925 + k * 50, x="100", fontfile=mono)

        narration = layout.wrap(scene.narration, 18, 5)
        base = 1400
        for k, line in enumerate(narration):
            text(line, 50, "white", base + k * 68)

        text(script.footer, 36, MUTED, 1790)
        progress = round(layout.WIDTH * (i + 1) / len(script.scenes))
        parts.append(f"drawbox=x=0:y={layout.HEIGHT - 10}:w={progress}:h=10:color={ACCENT}:t=fill")
        return ",".join(parts)

    def _ffmpeg(self, work: Path, args: list[str]) -> None:
        try:
            result = subprocess.run([self.ffmpeg, "-y", "-loglevel", "error", *args], cwd=work, capture_output=True, text=True,
                                    encoding="utf-8", errors="replace", timeout=FFMPEG_TIMEOUT)
        except subprocess.TimeoutExpired:
            raise RuntimeError(f"ffmpeg가 {FFMPEG_TIMEOUT}초 안에 끝나지 않았어요.") from None
        if result.returncode != 0:
            raise RuntimeError(f"ffmpeg 실패 (코드 {result.returncode}): {result.stderr.strip()[-400:]}")


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
