import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

from devlog_render import still


class StillSecondsTest(unittest.TestCase):
    def test_counts_longest_run_of_same_frames(self):
        size = still.WIDTH * still.HEIGHT
        a, b = bytes([10]) * size, bytes([200]) * size
        # a 5장 → b 3장 → a 1장: 가장 긴 건 a 5장
        self.assertEqual(still.still_seconds(a * 5 + b * 3 + a), 5 / still.SAMPLE_FPS)
        # 6.5초(13장) 같은 화면은 경고 기준(3초)을 넘는다
        self.assertGreater(still.still_seconds(a * 13 + b), still.WARN_SECONDS)
        self.assertEqual(still.still_seconds(b""), 0)


@unittest.skipUnless(shutil.which("ffmpeg"), "ffmpeg 없음")
class LongestStillTest(unittest.TestCase):
    def _video(self, tmp: str, source: str) -> Path:
        out = Path(tmp) / f"{source.split('=')[0]}.mp4"
        subprocess.run(["ffmpeg", "-nostdin", "-y", "-v", "error", "-f", "lavfi", "-i", source, "-t", "8", "-pix_fmt", "yuv420p", str(out)], check=True)
        return out

    def test_static_video_is_still_and_moving_video_is_not(self):
        with tempfile.TemporaryDirectory() as tmp:
            self.assertGreater(still.longest_still(self._video(tmp, "color=c=blue:s=360x640:r=30")), still.WARN_SECONDS)
            self.assertLess(still.longest_still(self._video(tmp, "testsrc2=s=360x640:r=30")), 1)
