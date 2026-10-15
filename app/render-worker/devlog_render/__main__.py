"""사용법

  python -m devlog_render work   --server https://devlog.life        # 대기열을 계속 처리 (토큰은 DEVLOG_RENDER_TOKEN)
  python -m devlog_render work   --server https://devlog.life --once # 쌓인 작업만 처리하고 끝
  python -m devlog_render render script.json out.mp4                 # 서버 없이 대본 파일로 영상 만들기
  python -m devlog_render check                                       # ffmpeg·글꼴·음성 확인
"""

from __future__ import annotations

import argparse
import json
import logging
import os
import shutil
import sys
from pathlib import Path

from . import __version__, tts
from .render import Renderer, Script, find_font
from .worker import Client, loop, site_of


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(prog="devlog_render", description=f"devlog 쇼츠 렌더 워커 {__version__}")
    parser.add_argument("--font", help="한국어 글꼴 파일 (기본: 운영체제 기본 한국어 글꼴)")
    parser.add_argument("--mono-font", help="코드 글꼴 파일")
    parser.add_argument("--tts", default=os.environ.get("DEVLOG_TTS", "auto"), choices=["auto", "edge", "supertonic", "say", "windows", "espeak", "silent"],
                        help="음성 엔진 (기본: edge-tts 신경망 음성, 안 되면 컴퓨터 안 신경망 음성 supertonic, 그것도 없으면 맥 say·Windows 기본 음성·리눅스 espeak-ng)")
    parser.add_argument("--voice", help="목소리 이름 (edge 기본 ko-KR-SunHiNeural, 남자 목소리는 ko-KR-InJoonNeural. 맥 예: Yuna)")
    parser.add_argument("--bgm", default=os.environ.get("DEVLOG_BGM"),
                        help="배경음 파일 (기본: 워커가 직접 합성한 잔잔한 음악, none이면 배경음 없음)")
    parser.add_argument("--ffmpeg", default=os.environ.get("DEVLOG_FFMPEG", "ffmpeg"))
    sub = parser.add_subparsers(dest="command", required=True)
    work = sub.add_parser("work", help="서버 대기열 처리")
    work.add_argument("--server", default=os.environ.get("DEVLOG_SERVER", "https://devlog.life"))
    work.add_argument("--interval", type=float, default=15, help="대기열이 비었을 때 다시 볼 간격(초)")
    work.add_argument("--once", action="store_true", help="쌓인 작업만 처리하고 끝내기")
    render = sub.add_parser("render", help="대본 JSON으로 영상 만들기")
    render.add_argument("script", type=Path)
    render.add_argument("out", type=Path)
    render.add_argument("--site", default="devlog.life")
    sub.add_parser("check", help="실행 환경 확인")
    args = parser.parse_args(argv)
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")

    if args.command == "check":
        return check(args)
    renderer = Renderer(find_font(args.font), find_font(args.mono_font, mono=True), args.tts, args.ffmpeg, args.voice, args.bgm)
    if args.command == "render":
        job = json.loads(args.script.read_text(encoding="utf-8"))
        ms = renderer.render(Script.from_job(job, args.site), args.out)
        print(f"{args.out} ({ms / 1000:.1f}초, {args.out.stat().st_size / 1024 / 1024:.1f}MB)")
        return 0
    client = Client(args.server, os.environ.get("DEVLOG_RENDER_TOKEN", ""))
    logging.info("워커 시작: %s (음성 %s)", args.server, renderer.engine)
    loop(client, renderer, site_of(args.server), args.interval, args.once)
    return 0


def check(args) -> int:
    ok = True
    for tool in (args.ffmpeg, "ffprobe"):
        found = shutil.which(tool)
        print(f"{'✓' if found else '✗'} {tool}: {found or '없음 (README의 설치 방법을 봐 주세요)'}")
        ok &= bool(found)
    for mono in (False, True):
        try:
            print(f"✓ {'코드' if mono else '한국어'} 글꼴: {find_font(args.mono_font if mono else args.font, mono)}")
        except FileNotFoundError as e:
            print(f"✗ {e}")
            ok = False
    engine = tts.pick(args.tts)
    print(f"✓ 음성: {engine}" + ("" if engine == "edge" else f" (신경망 음성을 쓰려면 pip install edge-tts, 실패하면 {tts.offline()}로 내려가요)"))
    print(f"{'✓' if os.environ.get('DEVLOG_RENDER_TOKEN') else '·'} DEVLOG_RENDER_TOKEN {'있음' if os.environ.get('DEVLOG_RENDER_TOKEN') else '없음 (work에만 필요)'}")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
