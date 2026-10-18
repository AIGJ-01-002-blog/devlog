"""실제 ffmpeg로 영상을 만들고, 가짜 devlog 서버와 주고받는 흐름을 확인한다. ffmpeg가 없으면 건너뛴다."""

import json
import shutil
import threading
import unittest
from unittest import mock
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from tempfile import TemporaryDirectory

from devlog_render.render import Renderer, Script, find_font, probe_ms
from devlog_render.worker import Client, loop, run_once

JOB = {
    "id": 7, "claim": "c-1", "attempt": 1, "title": "도커 볼륨 날린 날: 100% 복구한 방법", "handle": "minseo",
    "postUrl": "https://devlog.life/@minseo/1", "recentLooks": ["paper:0", "night:1", "mint:2"],
    "scenes": [
        {"caption": "볼륨이 사라졌어요", "narration": "어제 도커 볼륨을 지웠더니 글이 전부 사라졌어요.", "code": None},
        {"caption": "원인: down -v", "narration": "compose down 뒤에 -v를 붙이면 볼륨까지 지워져요.", "code": "docker compose down -v\n# %{pts} 그대로 보여야 해요"},
        {"caption": "이렇게 막았어요", "narration": "매일 새벽 pg_dump로 외장 디스크에 백업을 남겨요.", "code": None},
    ],
}


def renderer():
    return Renderer(find_font(), find_font(mono=True), "silent")


@unittest.skipUnless(shutil.which("ffmpeg") and shutil.which("ffprobe"), "ffmpeg 없음")
class RenderTest(unittest.TestCase):
    def test_renders_vertical_mp4_with_audio(self):
        with TemporaryDirectory() as tmp:
            out = Path(tmp) / "a.mp4"
            ms = renderer().render(Script.from_job(JOB), out)
            self.assertGreater(ms, 5000)
            self.assertEqual(out.read_bytes()[4:8], b"ftyp")
            self.assertLess(out.stat().st_size, 20 * 1024 * 1024)
            import subprocess
            info = json.loads(subprocess.run(["ffprobe", "-v", "error", "-show_streams", "-of", "json", str(out)],
                                             capture_output=True, text=True).stdout)
            kinds = {s["codec_type"]: s for s in info["streams"]}
            self.assertEqual((kinds["video"]["width"], kinds["video"]["height"]), (1080, 1920))
            self.assertIn("audio", kinds)
            self.assertAlmostEqual(probe_ms(out), ms, delta=50)

    def test_footer_prefers_post_url(self):
        self.assertEqual(Script.from_job({**JOB, "postUrl": "/@minseo/posts/1"}, "devlog.life").footer, "devlog.life/@minseo/posts/1")
        self.assertEqual(Script.from_job({**JOB, "postUrl": "https://devlog.life/@minseo/posts/1"}).footer, "devlog.life/@minseo/posts/1")
        self.assertEqual(Script.from_job({**JOB, "postUrl": None}, "devlog.life").footer, "devlog.life/@minseo")
        self.assertEqual(Script.from_job({**JOB, "postUrl": None, "handle": None}).footer, "devlog.life")


class FakeServer:
    """devlog 렌더 API 흉내: 작업을 한 번 주고, 올라온 영상·실패를 기록한다."""

    def __init__(self, job, upload_status=200):
        self.jobs = [job] if job else []
        self.uploads, self.failures, self.tokens, self.looks = [], [], [], []
        outer = self

        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *args):
                pass

            def _body(self):
                return self.rfile.read(int(self.headers.get("Content-Length") or 0))

            def do_POST(self):
                outer.tokens.append(self.headers.get("X-Render-Token"))
                body = self._body()
                if self.path == "/api/render/jobs/claim":
                    if not outer.jobs:
                        self.send_response(204)
                        self.end_headers()
                        return
                    data = json.dumps(outer.jobs.pop(0)).encode()
                    self.send_response(200)
                    self.send_header("Content-Type", "application/json")
                    self.send_header("Content-Length", str(len(data)))
                    self.end_headers()
                    self.wfile.write(data)
                elif "/fail" in self.path:
                    outer.failures.append((self.path, json.loads(body)))
                    self.send_response(204)
                    self.end_headers()

            def do_PUT(self):
                outer.tokens.append(self.headers.get("X-Render-Token"))
                body = self._body()
                outer.uploads.append((self.path, self.headers.get("X-Duration-Ms"), body))
                outer.looks.append(self.headers.get("X-Look"))
                self.send_response(upload_status)
                self.send_header("Content-Length", "2")
                self.end_headers()
                self.wfile.write(b"{}")

        self.httpd = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        threading.Thread(target=self.httpd.serve_forever, daemon=True).start()
        self.url = f"http://127.0.0.1:{self.httpd.server_port}"

    def close(self):
        self.httpd.shutdown()


class BrokenRenderer:
    def render(self, script, out):
        raise RuntimeError("글꼴이 깨졌어요")


@unittest.skipUnless(shutil.which("ffmpeg") and shutil.which("ffprobe"), "ffmpeg 없음")
class WorkerLoopTest(unittest.TestCase):
    def test_claims_renders_and_uploads_until_queue_empty(self):
        server = FakeServer(JOB)
        try:
            loop(Client(server.url, "tok"), renderer(), "devlog.life", interval=0.01, once=True)
        finally:
            server.close()
        self.assertEqual(len(server.uploads), 1)
        path, duration, body = server.uploads[0]
        self.assertEqual(path, "/api/render/jobs/7/video?claim=c-1")
        self.assertGreater(int(duration), 5000)
        self.assertEqual(body[4:8], b"ftyp")
        self.assertTrue(all(t == "tok" for t in server.tokens))
        self.assertRegex(server.looks[0], r"^[a-z]+:[0-9]$")
        self.assertNotIn(server.looks[0].split(":")[0], {"paper", "night", "mint"})  # 최근 3편 룩은 피한다

    def test_render_error_is_reported_as_failure(self):
        server = FakeServer(JOB)
        try:
            self.assertTrue(run_once(Client(server.url, "tok"), BrokenRenderer(), "devlog.life"))
        finally:
            server.close()
        self.assertEqual(server.failures, [("/api/render/jobs/7/fail?claim=c-1", {"message": "글꼴이 깨졌어요"})])

    def test_stale_claim_result_is_dropped(self):
        server = FakeServer(JOB, upload_status=409)
        try:
            self.assertTrue(run_once(Client(server.url, "tok"), renderer(), "devlog.life"))
        finally:
            server.close()
        self.assertEqual(len(server.uploads), 1)


class ClientTest(unittest.TestCase):
    def test_requires_token(self):
        with self.assertRaises(ValueError):
            Client("https://devlog.life", "")

    def test_wrong_token_stops_worker(self):
        class Deny(BaseHTTPRequestHandler):
            def log_message(self, *args):
                pass

            def do_POST(self):
                self.send_response(401)
                self.send_header("Content-Length", "0")
                self.end_headers()

        httpd = ThreadingHTTPServer(("127.0.0.1", 0), Deny)
        threading.Thread(target=httpd.serve_forever, daemon=True).start()
        try:
            with self.assertRaises(SystemExit):
                loop(Client(f"http://127.0.0.1:{httpd.server_port}", "bad"), BrokenRenderer(), "x", interval=0.01, once=True)
        finally:
            httpd.shutdown()

    def test_server_blip_after_start_does_not_stop_worker(self):
        """배포 중 잠깐 404·끊긴 응답이 와도 처음에 받아 준 워커는 끄지 않고 다시 묻는다"""
        answers = [204, 404, "broken", 401, 204]

        class Blip(BaseHTTPRequestHandler):
            def log_message(self, *args):
                pass

            def do_POST(self):
                code = answers.pop(0) if answers else 204
                if code == "broken":  # 길이만 말하고 몸통 없이 끊는다
                    self.send_response(200)
                    self.send_header("Content-Length", "50")
                    self.end_headers()
                    return
                self.send_response(code)
                self.send_header("Content-Length", "0")
                self.end_headers()

        httpd = ThreadingHTTPServer(("127.0.0.1", 0), Blip)
        threading.Thread(target=httpd.serve_forever, daemon=True).start()
        client = Client(f"http://127.0.0.1:{httpd.server_port}", "tok", timeout=2)
        calls = []

        def stop_after_five(*a, **k):  # 다섯 번 물은 뒤 Ctrl+C처럼 끊는다
            calls.append(1)
            if len(calls) > 5:
                raise KeyboardInterrupt
            return run_once(*a, **k)

        try:
            with mock.patch("devlog_render.worker.time.sleep"), mock.patch("devlog_render.worker.run_once", side_effect=stop_after_five):
                with self.assertRaises(KeyboardInterrupt):
                    loop(client, BrokenRenderer(), "x", interval=0.01)
        finally:
            httpd.shutdown()
        self.assertEqual(answers, [])


@unittest.skipUnless(shutil.which("ffmpeg") and shutil.which("ffprobe"), "ffmpeg 없음")
class UploadBlipTest(unittest.TestCase):
    def test_upload_404_on_first_job_does_not_stop_worker(self):
        """첫 작업을 받아 렌더하는 사이 서버가 배포돼 올리기가 404여도 워커는 끄지 않는다"""
        server = FakeServer(JOB, upload_status=404)
        calls = []

        def stop_after_three(*a, **k):
            calls.append(1)
            if len(calls) > 3:
                raise KeyboardInterrupt
            return run_once(*a, **k)

        try:
            with mock.patch("devlog_render.worker.time.sleep"), mock.patch("devlog_render.worker.run_once", side_effect=stop_after_three):
                with self.assertRaises(KeyboardInterrupt):
                    loop(Client(server.url, "tok"), renderer(), "devlog.life", interval=0.01)
        finally:
            server.close()
        self.assertEqual(len(server.uploads), 1)


if __name__ == "__main__":
    unittest.main()
