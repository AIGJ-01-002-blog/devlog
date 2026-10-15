"""Remotion 연결(#277): 자막 페이지, props 모양, 맞춤 장면 자리, 화면 한 편 + 소리 섞기."""

import os
import shutil
import subprocess
import unittest
from pathlib import Path
from tempfile import TemporaryDirectory
from unittest import mock

from devlog_render import captions, remotion
from devlog_render.captions import Word
from devlog_render.render import FPS, Renderer, Script, find_font, probe_ms


class PagesTest(unittest.TestCase):
    def test_words_are_ordered_inside_their_page(self):
        text = "어제 서버를 정리하다가 글이 전부 사라졌어요. 범인은 명령어 한 줄이었어요."
        pages = captions.pages(text, 6.0)
        self.assertGreaterEqual(len(pages), 2)
        joined = " ".join(w["text"] for p in pages for w in p["words"])
        self.assertIn("서버를", joined)
        for p in pages:
            self.assertLess(p["startMs"], p["endMs"])
            starts = [w["startMs"] for w in p["words"]]
            self.assertEqual(starts, sorted(starts))
            self.assertGreaterEqual(starts[0], p["startMs"])
            self.assertLessEqual(p["words"][-1]["endMs"], p["endMs"])

    def test_word_timings_are_used_when_known(self):
        words = [Word(0.0, 0.4, "안녕"), Word(1.5, 2.2, "반가워요")]
        pages = captions.pages("안녕 반가워요", 3.0, words)
        self.assertEqual([w["startMs"] for w in pages[0]["words"]], [0, 1500])


class CustomSceneJobTest(unittest.TestCase):
    def test_custom_scene_is_read_from_job(self):
        script = Script.from_job({"title": "t", "scenes": [
            {"caption": "매매", "narration": "샀어요.", "custom": {"kind": "replay", "data": {"cues": [1]}}},
            {"caption": "잘못", "narration": "무시해요.", "custom": {"data": 1}},
        ]})
        self.assertEqual(script.scenes[0].custom, {"kind": "replay", "data": {"cues": [1]}})
        self.assertIsNone(script.scenes[1].custom)


class AvailableTest(unittest.TestCase):
    def test_can_be_turned_off(self):
        with mock.patch.dict(os.environ, {"DEVLOG_REMOTION": "0"}):
            self.assertFalse(remotion.available())


def _fake_remotion(props: dict, public_dir: Path, out: Path, crf: int = 16) -> None:
    """Remotion 대신 props 길이만큼 소리 없는 화면을 만든다(크롬 없이 소리 섞기를 확인)"""
    _fake_remotion.props = props
    subprocess.run(["ffmpeg", "-loglevel", "error", "-y", "-f", "lavfi", "-i", f"color=c=navy:s=1080x1920:r={FPS}",
                    "-frames:v", str(props["totalFrames"]), "-c:v", "libx264", "-pix_fmt", "yuv420p", str(out)], check=True)


@unittest.skipUnless(shutil.which("ffmpeg"), "ffmpeg 없음")
class RemotionPathTest(unittest.TestCase):
    def test_props_match_types_and_audio_is_mixed_onto_one_video(self):
        job = {"title": "도커 볼륨", "postUrl": "/@minseo/posts/12", "effects": [{"scene": 1, "at": 0.2, "sound": "ding"}],
               "scenes": [{"caption": "글이 사라졌어요", "narration": "어제 서버를 정리하다가 글이 전부 사라졌어요."},
                          {"caption": "원인", "narration": "범인은 명령어 한 줄이었어요.", "code": "docker compose down -v"},
                          {"caption": "재현", "narration": "이때 샀어요.", "custom": {"kind": "replay", "data": {}}}]}
        with TemporaryDirectory() as tmp, mock.patch.object(remotion, "render", _fake_remotion):
            out = Path(tmp) / "a.mp4"
            renderer = Renderer(find_font(), find_font(mono=True), "silent", music=None, use_remotion=True)
            ms = renderer.render(Script.from_job(job), out)
            self.assertAlmostEqual(probe_ms(out), ms, delta=60)
            streams = subprocess.run(["ffprobe", "-v", "error", "-show_entries", "stream=codec_type", "-of", "csv=p=0", str(out)],
                                     capture_output=True, text=True, check=True).stdout.split()
            self.assertEqual(sorted(streams), ["audio", "video"])

        props = _fake_remotion.props
        self.assertEqual(set(props), {"title", "footer", "transitionFrames", "totalFrames", "scenes", "style"})
        self.assertEqual(len(props["scenes"]), 3)
        keys = {"caption", "code", "action", "params", "durationInFrames", "leadFrames", "speechFrames", "pages", "mouth", "image", "custom", "cut", "diagram"}
        for s in props["scenes"]:
            self.assertEqual(set(s), keys)
            self.assertTrue(all(0 <= m <= 1 for m in s["mouth"]))
            self.assertTrue(s["pages"] and s["pages"][0]["words"])
        self.assertEqual(props["scenes"][1]["code"], ["docker compose down -v"])
        self.assertEqual(props["scenes"][2]["custom"]["kind"], "replay")
        # 장면 길이 합 - 전환 겹침 = 전체 길이
        total = sum(s["durationInFrames"] for s in props["scenes"]) - props["transitionFrames"] * 2
        self.assertLessEqual(abs(total - props["totalFrames"]), 3)


NODE_READY = bool(shutil.which("node")) and (remotion.PROJECT / "node_modules" / ".bin" / "remotion").exists()


@unittest.skipUnless(NODE_READY and os.environ.get("DEVLOG_REMOTION_TEST") == "1", "Remotion 실제 렌더는 DEVLOG_REMOTION_TEST=1일 때만")
class RealRemotionTest(unittest.TestCase):
    def test_renders_real_video(self):
        job = {"title": "짧은 견본", "scenes": [{"caption": "되돌렸어요", "narration": "백업으로 다 되돌렸어요."}]}
        with TemporaryDirectory() as tmp:
            out = Path(tmp) / "a.mp4"
            ms = Renderer(find_font(), find_font(mono=True), "silent", music="none", use_remotion=True).render(Script.from_job(job), out)
            self.assertGreater(ms, 1000)


if __name__ == "__main__":
    unittest.main()
