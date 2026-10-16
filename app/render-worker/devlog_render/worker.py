"""devlog 서버에서 렌더 작업을 받아 영상을 만들어 올리는 반복 실행기 (spec 099 FR-006).

워커가 서버로 나가는 요청만 한다(서버가 워커에 먼저 연결하지 않음). 그래서 학교 서버처럼 바깥 연결이 막힌 곳이나
집 PC·맥처럼 공유기 뒤에 있어도 동작한다. 토큰은 환경 변수 DEVLOG_RENDER_TOKEN으로만 받는다.
"""

from __future__ import annotations

import json
import logging
import tempfile
import time
import urllib.error
import urllib.request
from pathlib import Path
from urllib.parse import quote, urlsplit

from .render import Renderer, Script

log = logging.getLogger("devlog_render")

TOKEN_HEADER = "X-Render-Token"
USER_AGENT = "devlog-render-worker"
#: 서버에 닿지 않을 때 다시 묻는 간격의 상한(초)
MAX_BACKOFF = 300


class ServerError(RuntimeError):
    def __init__(self, status: int, body: str):
        super().__init__(f"서버 응답 {status}: {body[:300]}")
        self.status = status


class Client:
    def __init__(self, server: str, token: str, timeout: float = 30):
        if not token:
            raise ValueError("DEVLOG_RENDER_TOKEN 환경 변수가 비었어요.")
        self.server = server.rstrip("/")
        self.token = token
        self.timeout = timeout
        #: 서버가 이 토큰을 한 번이라도 받아 줬는지(2xx). 그 뒤의 401·404는 배포 중 잠깐 생긴 것으로 본다
        self.accepted = False

    def claim(self) -> dict | None:
        status, body = self._send("POST", "/api/render/jobs/claim", b"", "application/json")
        return None if status == 204 or not body else json.loads(body)

    def upload(self, job: dict, video: Path, duration_ms: int) -> None:
        self._send("PUT", f"/api/render/jobs/{job['id']}/video?claim={quote(job['claim'])}", video.read_bytes(),
                   "video/mp4", {"X-Duration-Ms": str(duration_ms)}, timeout=max(self.timeout, 300))

    def fail(self, job: dict, message: str) -> None:
        data = json.dumps({"message": message[:500]}, ensure_ascii=False).encode("utf-8")
        self._send("POST", f"/api/render/jobs/{job['id']}/fail?claim={quote(job['claim'])}", data, "application/json")

    def _send(self, method: str, path: str, data: bytes, content_type: str, headers: dict | None = None,
              timeout: float | None = None) -> tuple[int, bytes]:
        request = urllib.request.Request(self.server + path, data=data, method=method, headers={
            TOKEN_HEADER: self.token, "Content-Type": content_type, "User-Agent": USER_AGENT, **(headers or {})})
        try:
            with urllib.request.urlopen(request, timeout=timeout or self.timeout) as response:
                self.accepted = True
                return response.status, response.read()
        except urllib.error.HTTPError as e:
            raise ServerError(e.code, e.read().decode("utf-8", "replace")) from None


def site_of(server: str) -> str:
    """영상 아래 주소에 쓸 이름 (https://devlog.life → devlog.life)."""
    return urlsplit(server).netloc or server


def run_once(client: Client, renderer: Renderer, site: str) -> bool:
    """작업 하나를 처리했으면 True, 대기열이 비었으면 False."""
    job = client.claim()
    if job is None:
        return False
    log.info("작업 %s 받음: %s (%s번째 시도)", job["id"], job.get("title"), job.get("attempt"))
    with tempfile.TemporaryDirectory(prefix="devlog-job-") as tmp:
        out = Path(tmp) / "shorts.mp4"
        try:
            duration = renderer.render(Script.from_job(job, site), out)
        except Exception as e:  # 렌더 실패는 서버에 알려 다시 시도하거나 실패로 남게 한다
            log.exception("작업 %s 렌더 실패", job["id"])
            try:
                client.fail(job, str(e) or type(e).__name__)
            except ServerError as report:
                log.warning("실패 보고도 안 됐어요(시간 초과로 서버가 되돌릴 거예요): %s", report)
            return True
        try:
            client.upload(job, out, duration)
        except ServerError as e:
            if e.status == 409:  # 시간이 지나 다른 워커에게 넘어갔거나 대본이 바뀜. 결과는 버린다
                log.warning("작업 %s 결과를 버려요: %s", job["id"], e)
                return True
            raise
    log.info("작업 %s 올림 (%.1f초)", job["id"], duration / 1000)
    return True


def loop(client: Client, renderer: Renderer, site: str, interval: float = 15, once: bool = False) -> None:
    """켜 둔 채 두고 다니는 워커라 서버가 배포·재시작으로 잠깐 401·404·5xx를 줘도 끄지 않고 다시 묻는다.
    처음 켰을 때 한 번도 받아 주지 않으면(토큰이 틀림 등) 바로 알려 주고 끝낸다."""
    backoff = interval
    while True:
        try:
            worked = run_once(client, renderer, site)
            backoff = interval
        except ServerError as e:
            if e.status in (401, 404) and not client.accepted:
                raise SystemExit(f"서버가 워커를 받지 않아요({e.status}). 토큰과 서버의 SHORTS_ENABLED를 확인해 주세요.")
            if e.status in (401, 404):
                log.error("서버가 워커를 받지 않아요(%s). 서버 배포 중이면 곧 풀려요. 계속되면 토큰을 확인해 주세요.", e.status)
            else:
                log.warning("%s", e)
            worked, backoff = False, min(backoff * 2, MAX_BACKOFF)
        except (urllib.error.URLError, TimeoutError, ConnectionError) as e:
            log.warning("서버에 닿지 않아요: %s", e)
            worked, backoff = False, min(backoff * 2, MAX_BACKOFF)
        except Exception:  # 끊긴 응답·깨진 JSON 같은 뜻밖의 오류로 워커가 꺼지지 않게 한다
            log.exception("뜻밖의 오류, 잠시 뒤 다시 해요")
            worked, backoff = False, min(backoff * 2, MAX_BACKOFF)
        if once and not worked:
            return
        if not worked:
            time.sleep(backoff)
