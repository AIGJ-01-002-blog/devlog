"""렌더 뒤 화면 변화 검사 (쇼츠 다양화 2026-10-17).

같은 화면이 오래 머무는 영상은 지루하고, 유튜브가 반복적인 영상으로 볼 수 있다. 다 그린 영상을 작은 흑백 그림으로
초당 SAMPLE_FPS장 뽑아 앞 그림과 거의 같은 그림이 가장 길게 이어진 시간을 잰다. 렌더를 막지는 않고 로그로만 알린다.
"""

from __future__ import annotations

import subprocess
from pathlib import Path

SAMPLE_FPS = 2
#: 뽑을 그림 크기(세로 영상 9:16). 작아도 자막·도식이 바뀌는 건 잡힌다
WIDTH, HEIGHT = 36, 64
#: 픽셀 평균 차이(0~255)가 이보다 작으면 같은 화면으로 본다. 캐릭터가 눈만 깜빡이는 정도는 같은 화면이다
THRESHOLD = 1.5
#: 이보다 오래 같은 화면이면 경고(look.MAX_HOLD와 같은 기준)
WARN_SECONDS = 6.0


def longest_still(video: Path, ffmpeg: str = "ffmpeg") -> float:
    """영상에서 화면이 거의 바뀌지 않고 이어진 가장 긴 시간(초)"""
    result = subprocess.run([ffmpeg, "-nostdin", "-v", "error", "-i", str(video), "-an",
                             "-vf", f"fps={SAMPLE_FPS},scale={WIDTH}:{HEIGHT},format=gray", "-f", "rawvideo", "-"],
                            stdin=subprocess.DEVNULL, capture_output=True, timeout=300)
    if result.returncode != 0:
        raise RuntimeError(f"ffmpeg 실패: {result.stderr.decode('utf-8', 'replace').strip()[:200]}")
    return still_seconds(result.stdout)


def still_seconds(raw: bytes) -> float:
    """WIDTH×HEIGHT 흑백 그림을 이어 붙인 바이트에서 같은 화면이 가장 길게 이어진 시간(초)"""
    size = WIDTH * HEIGHT
    frames = [raw[k:k + size] for k in range(0, len(raw) - size + 1, size)]
    longest = run = 0
    for prev, cur in zip(frames, frames[1:]):
        diff = sum(abs(a - b) for a, b in zip(prev, cur)) / size
        run = run + 1 if diff < THRESHOLD else 0
        longest = max(longest, run)
    return longest / SAMPLE_FPS
