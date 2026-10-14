"""한국어 음성 만들기. 운영비 0원을 지킨다.

- edge: 무료 신경망 음성(edge-tts, 기본 ko-KR-SunHiNeural). `pip install edge-tts`가 있으면 기본으로 쓰고,
  단어마다 소리 나는 시각을 함께 받아 자막을 음성에 맞춘다. 인터넷이 막히거나 실패하면 Renderer가 supertonic으로 내려간다.
- supertonic: 컴퓨터 안에서 도는 오픈소스 신경망 음성(Supertone Supertonic-3, 코드 MIT·모델 OpenRAIL-M, 한국어 지원).
  `pip install supertonic`이 있으면 edge 다음으로 쓴다. 처음 쓸 때 모델을 한 번 내려받고 그 뒤로는 인터넷 없이 돈다.
  기계음(espeak 등)보다 훨씬 자연스러워서, 인터넷이 막혀도 로봇 같은 목소리가 나오지 않게 한다(#277)
- macOS: `say` (한국어 목소리 Yuna 등)
- Windows: System.Speech (PowerShell, 한국어 목소리 Heami 등)
- Linux: espeak-ng (있으면)
- silent: 소리 없이 예상 길이만큼 (시험·CI용, 또는 음성 없이 자막만)
"""

from __future__ import annotations

import os
import platform
import shutil
import subprocess
import tempfile
from pathlib import Path

from .captions import Word

EDGE_VOICE = "ko-KR-SunHiNeural"
#: 쇼츠는 조금 빠른 말이 덜 늘어진다
EDGE_RATE = "+8%"
SUPERTONIC_VOICE = "F1"
SUPERTONIC_SPEED = 1.1
CHARS_PER_SECOND = 6.5
#: 음성 엔진 한 번이 멈췄을 때 기다리는 한도 (문장 하나는 200자까지)
TTS_TIMEOUT = 120


def estimate_seconds(text: str) -> float:
    return max(1.5, len(text.replace(" ", "")) / CHARS_PER_SECOND)


def edge_available() -> bool:
    try:
        import edge_tts  # noqa: F401
    except ImportError:
        return False
    return True


def supertonic_available() -> bool:
    try:
        import supertonic  # noqa: F401
    except ImportError:
        return False
    return True


def pick(engine: str = "auto") -> str:
    if engine != "auto":
        return engine
    if edge_available():
        return "edge"
    return offline()


def offline() -> str:
    """인터넷 없이 쓸 음성: supertonic이 있으면 그것, 없으면 기기 음성"""
    return "supertonic" if supertonic_available() else device()


def device() -> str:
    """기기 안 음성 엔진 (edge를 못 쓸 때 내려갈 곳)"""
    system = platform.system()
    if system == "Darwin" and shutil.which("say"):
        return "say"
    if system == "Windows" and shutil.which("powershell"):
        return "windows"
    if shutil.which("espeak-ng"):
        return "espeak"
    return "silent"


def synthesize(text: str, out_wav: Path, engine: str, ffmpeg: str = "ffmpeg", voice: str | None = None) -> list[Word] | None:
    """text를 읽은 음성을 out_wav(44.1kHz 스테레오)로 만든다. 엔진이 단어 시각을 주면 돌려준다."""
    words = None
    with tempfile.TemporaryDirectory() as tmp:
        raw = Path(tmp) / "raw"
        text_file = Path(tmp) / "text.txt"
        text_file.write_text(text, encoding="utf-8")
        if engine == "edge":
            raw = raw.with_suffix(".mp3")
            words = _edge(text, raw, voice or os.environ.get("DEVLOG_TTS_VOICE") or EDGE_VOICE)
        elif engine == "supertonic":
            raw = raw.with_suffix(".wav")
            _supertonic(text, raw, voice or os.environ.get("DEVLOG_TTS_VOICE") or SUPERTONIC_VOICE)
        elif engine == "say":
            raw = raw.with_suffix(".aiff")
            args = ["say", "-f", str(text_file), "-o", str(raw)]
            args[1:1] = ["-v", voice or os.environ.get("DEVLOG_TTS_VOICE", "Yuna")]
            _run(args)
        elif engine == "windows":
            raw = raw.with_suffix(".wav")
            script = (
                "Add-Type -AssemblyName System.Speech;"
                "$s = New-Object System.Speech.Synthesis.SpeechSynthesizer;"
                "$v = $s.GetInstalledVoices() | Where-Object { $_.VoiceInfo.Culture.Name -eq 'ko-KR' } | Select-Object -First 1;"
                "if ($v) { $s.SelectVoice($v.VoiceInfo.Name) };"
                f"$s.SetOutputToWaveFile('{_ps_quote(raw)}');"
                f"$s.Speak([IO.File]::ReadAllText('{_ps_quote(text_file)}', [Text.Encoding]::UTF8));"
                "$s.Dispose()"
            )
            _run(["powershell", "-NoProfile", "-NonInteractive", "-Command", script])
        elif engine == "espeak":
            raw = raw.with_suffix(".wav")
            _run(["espeak-ng", "-v", voice or "ko", "-f", str(text_file), "-w", str(raw)])
        elif engine == "silent":
            seconds = estimate_seconds(text)
            _run([ffmpeg, "-y", "-loglevel", "error", "-f", "lavfi", "-i", "anullsrc=r=44100:cl=stereo",
                  "-t", f"{seconds:.2f}", str(out_wav)])
            return None
        else:
            raise ValueError(f"모르는 음성 엔진이에요: {engine}")
        _run([ffmpeg, "-y", "-loglevel", "error", "-i", str(raw), "-ar", "44100", "-ac", "2", str(out_wav)])
    return words


def _edge(text: str, out: Path, voice: str) -> list[Word]:
    """edge-tts로 mp3를 받으며 단어 시각(100ns 단위)을 초로 모은다. aiohttp는 HTTPS_PROXY를 스스로 읽지 않아 넘겨 준다."""
    import asyncio

    import edge_tts

    proxy = os.environ.get("HTTPS_PROXY") or os.environ.get("https_proxy") or None
    rate = os.environ.get("DEVLOG_TTS_RATE", EDGE_RATE)

    async def run() -> list[Word]:
        words: list[Word] = []
        communicate = edge_tts.Communicate(text, voice, rate=rate, boundary="WordBoundary", proxy=proxy)
        with out.open("wb") as f:
            async for chunk in communicate.stream():
                if chunk["type"] == "audio":
                    f.write(chunk["data"])
                elif chunk["type"] == "WordBoundary":
                    start = chunk["offset"] / 1e7
                    words.append(Word(start, start + chunk["duration"] / 1e7, chunk["text"]))
        return words

    try:
        words = asyncio.run(asyncio.wait_for(run(), TTS_TIMEOUT))
    except asyncio.TimeoutError:
        raise RuntimeError(f"edge-tts가 {TTS_TIMEOUT}초 안에 끝나지 않았어요.") from None
    if not out.exists() or out.stat().st_size == 0:
        raise RuntimeError("edge-tts가 소리를 돌려주지 않았어요.")
    return words


_supertonic_model = None


def _supertonic(text: str, out: Path, voice: str) -> None:
    """Supertonic-3으로 한국어를 읽는다. 모델은 한 번만 불러 장면마다 다시 쓴다."""
    global _supertonic_model
    from supertonic import TTS

    if _supertonic_model is None:
        _supertonic_model = TTS(auto_download=True)
    style = _supertonic_model.get_voice_style(voice)
    speed = float(os.environ.get("DEVLOG_TTS_SPEED", SUPERTONIC_SPEED))
    wav, _ = _supertonic_model.synthesize(text, voice_style=style, lang="ko", speed=speed)
    _supertonic_model.save_audio(wav, str(out))
    if not out.exists() or out.stat().st_size == 0:
        raise RuntimeError("supertonic이 소리를 만들지 못했어요.")


def _ps_quote(path: Path) -> str:
    return str(path).replace("'", "''")


def _run(args: list[str]) -> None:
    try:
        result = subprocess.run(args, capture_output=True, text=True, encoding="utf-8", errors="replace", timeout=TTS_TIMEOUT)
    except subprocess.TimeoutExpired:
        raise RuntimeError(f"{Path(args[0]).name}가 {TTS_TIMEOUT}초 안에 끝나지 않았어요.") from None
    if result.returncode != 0:
        raise RuntimeError(f"{Path(args[0]).name} 실패 (코드 {result.returncode}): {result.stderr.strip()[:300]}")
