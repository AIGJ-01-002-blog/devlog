"""쇼츠 품질(#277): 구절 자막·시각, 장면 시간표, 신경망 음성 실패 시 내려가기, 배경음, 글 이미지."""

import shutil
import threading
import unittest
import wave
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
from functools import partial
from pathlib import Path
from tempfile import TemporaryDirectory
from unittest import mock

from devlog_render import bgm, captions, layout, render, tts
from devlog_render.captions import Word
from devlog_render.render import Renderer, Script, find_font, plan, probe_ms, split_comment


class PhraseTest(unittest.TestCase):
    def test_each_phrase_fits_two_lines_without_ellipsis(self):
        text = ("useEffect 안에서 상태를 바꾸는데, 그 상태를 의존성 배열에 넣어서 끝없이 다시 돌고 있었어요. "
                "compose down 뒤에 -v를 붙이면, 컨테이너뿐 아니라 데이터가 든 볼륨까지 함께 지워져요.")
        parts = captions.phrases(text)
        self.assertGreater(len(parts), 3)
        self.assertEqual("".join("".join(p.split()) for p in parts), "".join(text.split()))
        for p in parts:
            lines = captions.display_lines(p)
            self.assertLessEqual(len(lines), 2, p)
            for line in lines:
                self.assertLessEqual(layout.text_width(line), captions.LINE_UNITS, line)
                self.assertNotIn("…", line)

    def test_does_not_break_before_bound_word(self):
        parts = captions.phrases("새 대시보드에서는 실패한 단계를 한눈에 볼 수 있어요.")
        self.assertFalse(any(p.startswith("수 ") for p in parts), parts)

    def test_trailing_comma_and_period_dropped_question_kept(self):
        self.assertEqual(captions.display_lines("목록이 4초 걸렸어요."), ["목록이 4초 걸렸어요"])
        self.assertEqual(captions.display_lines("API가 100번?"), ["API가 100번?"])


class CueTest(unittest.TestCase):
    def test_follows_word_timings(self):
        text = "어제 서버를 정리하다가, 블로그 글이 전부 사라졌어요."
        words = [Word(0.0, 0.3, "어제"), Word(0.3, 0.7, "서버를"), Word(0.7, 1.3, "정리하다가"),
                 Word(2.0, 2.4, "블로그"), Word(2.4, 2.7, "글이"), Word(2.7, 3.0, "전부"), Word(3.0, 3.8, "사라졌어요")]
        cues = captions.cues(text, 4.0, words, tail=0.15)
        self.assertEqual(len(cues), 2)
        self.assertEqual(cues[0].start, 0.0)
        self.assertAlmostEqual(cues[1].start, 2.0)  # 둘째 구절은 '블로그'가 소리 나는 때
        self.assertAlmostEqual(cues[0].end, cues[1].start)
        self.assertAlmostEqual(cues[-1].end, 4.15)

    def test_without_words_spreads_by_length_and_pauses(self):
        cues = captions.cues("화면 하나 열었을 뿐인데, 서버 로그에 같은 요청이 1초에 100번 찍혔어요.", 6.0)
        starts = [c.start for c in cues]
        self.assertEqual(starts, sorted(starts))
        self.assertEqual(starts[0], 0.0)
        self.assertAlmostEqual(cues[-1].end, 6.0)

    def test_unmatched_words_fall_back_between_known(self):
        # 엔진이 '100'을 '백'으로 읽어 단어를 못 찾아도 시각이 거꾸로 가지 않는다
        cues = captions.cues("요청이 1초에 100번, 찍혔어요.", 3.0, [Word(0, 0.5, "요청이"), Word(0.5, 1, "일초에"), Word(2, 3, "찍혔어요")])
        starts = [c.start for c in cues]
        self.assertEqual(starts, sorted(starts))


class PlanTest(unittest.TestCase):
    def test_scenes_overlap_by_transition_and_voice_waits_for_fade(self):
        timings = plan([2.0, 3.0, 1.5], [None, None, None], ["가", "나", "다"])
        self.assertEqual(timings[0].start, 0)
        self.assertAlmostEqual(timings[0].voice_at, render.FIRST_LEAD)  # 첫 장면은 바로 말한다(훅)
        for prev, cur in zip(timings, timings[1:]):
            self.assertAlmostEqual(cur.start, prev.start + prev.length - render.TRANSITION)
            # 앞 말이 끝나고 다음 말까지의 쉼은 짧다(0.4초 정지 화면이 없다)
            gap = cur.voice_at - (prev.voice_at + prev.speech)
            self.assertLess(gap, 0.5)
        self.assertAlmostEqual(timings[-1].length, render.LEAD + 1.5 + render.TAIL)


class CodeTest(unittest.TestCase):
    def test_split_comment(self):
        self.assertEqual(split_comment("}, [posts]); // posts가 바뀌면"), ("}, [posts]); ", "// posts가 바뀌면"))
        self.assertEqual(split_comment("# -v: 볼륨까지 삭제"), ("", "# -v: 볼륨까지 삭제"))
        self.assertEqual(split_comment("git checkout -- app"), ("git checkout -- app", ""))


class VoiceFallbackTest(unittest.TestCase):
    def test_neural_voice_failure_redoes_every_scene_with_device_voice(self):
        calls = []

        def fake(text, out, engine, ffmpeg="ffmpeg", voice=None):
            calls.append((engine, voice))
            if engine == "edge" and len(calls) == 2:  # 둘째 장면에서 실패
                raise RuntimeError("인터넷 없음")
            Path(out).write_bytes(b"")
            return None

        r = Renderer.__new__(Renderer)
        r.engine, r.voice, r.ffmpeg = "edge", "ko-KR-SunHiNeural", "ffmpeg"
        script = Script("t", [render.Scene("a", "가"), render.Scene("b", "나")])
        with TemporaryDirectory() as tmp, mock.patch.object(tts, "synthesize", fake), mock.patch.object(tts, "device", return_value="silent"):
            audios, _ = r._voices(Path(tmp), script)
        self.assertEqual(len(audios), 2)
        self.assertEqual(calls[-2:], [("silent", None), ("silent", None)])  # 목소리가 섞이지 않게 처음부터

    def test_auto_prefers_edge_when_installed(self):
        with mock.patch.object(tts, "edge_available", return_value=True):
            self.assertEqual(tts.pick("auto"), "edge")
        with mock.patch.object(tts, "edge_available", return_value=False), mock.patch.object(tts, "device", return_value="espeak"):
            self.assertEqual(tts.pick("auto"), "espeak")


class MusicTest(unittest.TestCase):
    def test_generated_once_and_reused(self):
        with TemporaryDirectory() as tmp, mock.patch.dict("os.environ", {"DEVLOG_RENDER_CACHE": tmp}):
            first = bgm.path()
            stamp = first.stat().st_mtime_ns
            self.assertEqual(bgm.path(), first)
            self.assertEqual(first.stat().st_mtime_ns, stamp)
            with wave.open(str(first)) as w:
                seconds = w.getnframes() / w.getframerate()
            self.assertAlmostEqual(seconds, 4 * 4 * 60 / bgm.BPM * bgm.LOOPS, delta=0.05)


class ImageJobTest(unittest.TestCase):
    def test_cover_image_goes_to_first_scene_with_site_origin(self):
        script = Script.from_job({"title": "t", "coverImage": "/uploads/a.png",
                                  "scenes": [{"caption": "a", "narration": "가"}, {"caption": "b", "narration": "나", "image": "https://x/b.png"}]},
                                 "devlog.life")
        self.assertEqual(script.scenes[0].image, "https://devlog.life/uploads/a.png")
        self.assertEqual(script.scenes[1].image, "https://x/b.png")
        self.assertEqual(Script.from_job({"coverImage": "/a.png", "scenes": [{}]}, "localhost:8080").scenes[0].image,
                         "http://localhost:8080/a.png")


@unittest.skipUnless(shutil.which("ffmpeg") and shutil.which("ffprobe"), "ffmpeg 없음")
class QualityRenderTest(unittest.TestCase):
    def test_image_scene_and_missing_image_both_render(self):
        with TemporaryDirectory() as tmp:
            root = Path(tmp)
            import subprocess
            subprocess.run(["ffmpeg", "-loglevel", "error", "-f", "lavfi", "-i", "testsrc2=s=640x400", "-frames:v", "1",
                            str(root / "cover.png")], check=True)
            httpd = ThreadingHTTPServer(("127.0.0.1", 0), partial(QuietHandler, directory=str(root)))
            threading.Thread(target=httpd.serve_forever, daemon=True).start()
            try:
                job = {"title": "이미지", "coverImage": "/cover.png",
                       "scenes": [{"caption": "배포 화면", "narration": "새 화면이에요."},
                                  {"caption": "없는 이미지", "narration": "그래도 만들어요.", "image": "/missing.png"}]}
                out = root / "a.mp4"
                renderer = Renderer(find_font(), find_font(mono=True), "silent", music="none")
                ms = renderer.render(Script.from_job(job, f"127.0.0.1:{httpd.server_port}"), out)
            finally:
                httpd.shutdown()
                httpd.server_close()
            self.assertGreater(ms, 2000)
            self.assertAlmostEqual(probe_ms(out), ms, delta=50)


class QuietHandler(SimpleHTTPRequestHandler):
    def log_message(self, *args):
        pass


if __name__ == "__main__":
    unittest.main()


try:
    from devlog_render import stage
except ImportError:  # Pillow 없음
    stage = None


@unittest.skipIf(stage is None, "Pillow 없음")
class StageTest(unittest.TestCase):
    def scene(self, caption, narration, code=None, visual=None):
        return render.Scene(caption, narration, code, None, visual)

    def test_infers_action_from_context(self):
        cases = [
            (self.scene("글이 전부 사라졌어요", "블로그 글이 전부 사라졌어요."), "shock"),
            (self.scene("API가 1초에 100번?", "같은 요청이 1초에 100번 찍혔어요."), "panic"),
            (self.scene("목록이 4초 걸렸어요", "목록이 뜨는 데 4초나 걸렸어요."), "wait"),
            (self.scene("원인", "의존성 배열 때문이었어요.", "useEffect(() => {\n}, [posts]); // 문제"), "typing"),
            (self.scene("백업에서 되살렸어요", "복원 한 줄로 되돌렸어요.", "pg_restore -d blog x.dump"), "restore"),
            (self.scene("린트를 켜 두세요", "규칙을 켜 두면 알려 줘요."), "idea"),
            (self.scene("이렇게 막았어요", "이제는 백업을 남겨요."), "protect"),
            (self.scene("안녕하세요", "오늘 이야기를 시작할게요."), "explain"),
        ]
        for scene, action in cases:
            self.assertEqual(stage.infer(scene).action, action, scene.caption)

    def test_speed_takes_before_value_from_other_scene(self):
        v = stage.infer(self.scene("정렬 열에 인덱스", "100밀리초 안으로 떨어졌어요."), "목록이 4초 걸렸어요. 100밀리초 안으로 떨어졌어요.")
        self.assertEqual((v.action, v.before, v.after), ("fast", "4초", "100ms"))

    def test_explicit_visual_wins_and_unknown_is_ignored(self):
        self.assertEqual(stage.infer(self.scene("글이 사라졌어요", "사라졌어요.", visual="celebrate")).action, "celebrate")
        self.assertEqual(stage.infer(self.scene("글이 사라졌어요", "사라졌어요.", visual={"action": "idea"})).action, "idea")
        self.assertEqual(stage.infer(self.scene("글이 사라졌어요", "사라졌어요.", visual="dance")).action, "shock")

    def test_every_action_draws_a_frame_and_effects_fit_scene(self):
        fonts = stage.Fonts(find_font(), find_font(mono=True))
        for action in stage.ACTIONS:
            v = stage.Visual(action, code=["a = 1  # 한글"], focus=0, count=10, before="4초", after="1초", label="빨라짐")
            st = stage.Stage(v, fonts, 3.0, 0.2, 2.5, [0.5] * 90, 30, first=True)
            for t in (0.0, 1.0, 2.9):
                self.assertEqual(len(st.frame(t)), layout.WIDTH * stage.STAGE_H * 4, action)
            for name, at, volume in st.effects():
                self.assertIn(name, render.sfx.SOUNDS)
                self.assertLess(at, 3.0)

    def test_mouth_follows_loudness(self):
        with TemporaryDirectory() as tmp:
            path = Path(tmp) / "a.wav"
            with wave.open(str(path), "wb") as w:
                w.setnchannels(1)
                w.setsampwidth(2)
                w.setframerate(8000)
                import math
                import struct
                loud = [int(12000 * math.sin(k / 3)) for k in range(4000)]
                w.writeframes(struct.pack(f"<{8000}h", *([0] * 4000 + loud)))
            env = stage.mouth_envelope(path, 10)
        self.assertLess(max(env[:4]), 0.05)  # 조용한 앞 0.5초는 입을 다문다
        self.assertGreater(max(env[6:]), 0.5)


class EffectJobTest(unittest.TestCase):
    def test_job_effects_only_accept_built_in_sounds(self):
        script = Script.from_job({"scenes": [{}], "effects": [{"scene": 0, "at": 0.5, "sound": "ding"},
                                                              {"scene": 0, "at": 1, "sound": "/etc/passwd"}]})
        self.assertEqual(script.effects, [render.sfx.Effect(0, 0.5, "ding", 0.6)])
