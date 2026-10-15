"""Remotion으로 화면을 그린다 (#277).

블로그 주인 요청(2026-10-14): 직접 짠 그리기는 품질이 낮으니 검증된 영상 도구 위에서 만든다.
Remotion(React로 영상을 만드는 도구, 개인·3명 이하 회사 무료 라이선스)과 Remotion 공식 에이전트 스킬의 규칙
(useCurrentFrame + interpolate/spring으로만 움직이기, TransitionSeries 전환, 번들 글꼴)을 따른다.

- 워커(파이썬)는 음성·장면 시간·자막 시각·입 모양·연기를 정해 props(JSON)로 넘긴다.
- Remotion은 소리 없는 화면만 그린다(remotion/ 폴더, 장면 전환 포함). 소리는 워커가 예전처럼 섞는다(배경음 줄이기·효과음).
- Node 18 이상이 있어야 한다. 처음 쓸 때 remotion/node_modules가 없으면 `npm ci`를 한 번 돌린다.
  Remotion이 처음 렌더할 때 헤드리스 크롬을 내려받는다. 이미 있는 크롬을 쓰려면 DEVLOG_BROWSER에 실행 파일 경로를 넣는다.
"""

from __future__ import annotations

import json
import logging
import os
import platform
import shutil
import subprocess
from pathlib import Path

log = logging.getLogger("devlog_render")

PROJECT = Path(__file__).resolve().parent.parent / "remotion"


def characters() -> set[str]:
    """고양이 대신 쓸 수 있는 캐릭터 이름(remotion/src/character/<이름>/idle.json이 있는 폴더)"""
    root = PROJECT / "src" / "character"
    return {d.name for d in root.iterdir() if (d / "idle.json").is_file()} if root.is_dir() else set()
COMPOSITION = "Shorts"
RENDER_TIMEOUT = 1800
INSTALL_TIMEOUT = 600


def _bin(name: str) -> Path:
    return PROJECT / "node_modules" / ".bin" / (name + (".cmd" if platform.system() == "Windows" else ""))


def available() -> bool:
    """node·npm이 있고 Remotion 프로젝트가 있으면 True. 꺼 두려면 DEVLOG_REMOTION=0"""
    if os.environ.get("DEVLOG_REMOTION", "1") == "0":
        return False
    return bool(shutil.which("node") and shutil.which("npm") and (PROJECT / "package.json").is_file())


def ensure_installed() -> None:
    if _bin("remotion").exists():
        return
    log.info("Remotion 패키지를 설치해요(처음 한 번): %s", PROJECT)
    npm = shutil.which("npm") or "npm"
    lock = (PROJECT / "package-lock.json").is_file()
    result = subprocess.run([npm, "ci" if lock else "install", "--no-audit", "--no-fund"], cwd=PROJECT, capture_output=True, text=True,
                            encoding="utf-8", errors="replace", timeout=INSTALL_TIMEOUT)
    if result.returncode != 0:
        raise RuntimeError(f"npm 설치 실패: {result.stderr.strip()[-400:]}")


def render(props: dict, public_dir: Path, out: Path, crf: int = 16) -> None:
    """props로 소리 없는 1080×1920 H.264 화면을 out에 만든다. public_dir의 파일(글 이미지)을 staticFile로 쓸 수 있다."""
    ensure_installed()
    props_file = public_dir / "props.json"
    props_file.write_text(json.dumps(props, ensure_ascii=False), encoding="utf-8")
    args = [str(_bin("remotion")), "render", "src/index.ts", COMPOSITION, str(out.resolve()),
            f"--props={props_file.resolve()}", f"--public-dir={public_dir.resolve()}",
            "--codec=h264", f"--crf={crf}", "--muted", "--log=error"]
    browser = os.environ.get("DEVLOG_BROWSER")
    if browser:
        args.append(f"--browser-executable={browser}")
    concurrency = os.environ.get("DEVLOG_REMOTION_CONCURRENCY")
    if concurrency:
        args.append(f"--concurrency={concurrency}")
    try:
        result = subprocess.run(args, cwd=PROJECT, capture_output=True, text=True, encoding="utf-8", errors="replace", timeout=RENDER_TIMEOUT)
    except subprocess.TimeoutExpired:
        raise RuntimeError(f"Remotion 렌더가 {RENDER_TIMEOUT}초 안에 끝나지 않았어요.") from None
    if result.returncode != 0 or not out.is_file():
        raise RuntimeError(f"Remotion 렌더 실패 (코드 {result.returncode}): {(result.stderr or result.stdout).strip()[-600:]}")
