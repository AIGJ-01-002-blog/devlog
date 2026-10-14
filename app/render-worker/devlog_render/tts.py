"""한국어 음성 만들기. 비용이 들지 않는 기기 안 음성만 쓴다.

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

CHARS_PER_SECOND = 6.5
#: 음성 엔진 한 번이 멈췄을 때 기다리는 한도 (문장 하나는 200자까지)
TTS_TIMEOUT = 120


def estimate_seconds(text: str) -> float:
    return max(1.5, len(text.replace(" ", "")) / CHARS_PER_SECOND)


def pick(engine: str = "auto") -> str:
    if engine != "auto":
        return engine
    system = platform.system()
    if system == "Darwin" and shutil.which("say"):
        return "say"
    if system == "Windows" and shutil.which("powershell"):
        return "windows"
    if shutil.which("espeak-ng"):
        return "espeak"
    return "silent"


def synthesize(text: str, out_wav: Path, engine: str, ffmpeg: str = "ffmpeg", voice: str | None = None) -> None:
    """text를 읽은 음성을 out_wav(44.1kHz 스테레오)로 만든다."""
    with tempfile.TemporaryDirectory() as tmp:
        raw = Path(tmp) / "raw"
        text_file = Path(tmp) / "text.txt"
        text_file.write_text(text, encoding="utf-8")
        if engine == "say":
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
            return
        else:
            raise ValueError(f"모르는 음성 엔진이에요: {engine}")
        _run([ffmpeg, "-y", "-loglevel", "error", "-i", str(raw), "-ar", "44100", "-ac", "2", str(out_wav)])


def _ps_quote(path: Path) -> str:
    return str(path).replace("'", "''")


def _run(args: list[str]) -> None:
    try:
        result = subprocess.run(args, capture_output=True, text=True, encoding="utf-8", errors="replace", timeout=TTS_TIMEOUT)
    except subprocess.TimeoutExpired:
        raise RuntimeError(f"{Path(args[0]).name}가 {TTS_TIMEOUT}초 안에 끝나지 않았어요.") from None
    if result.returncode != 0:
        raise RuntimeError(f"{Path(args[0]).name} 실패 (코드 {result.returncode}): {result.stderr.strip()[:300]}")
