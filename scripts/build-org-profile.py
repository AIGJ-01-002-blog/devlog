#!/usr/bin/env python3
"""profile/README.md로 조직 프로필 README(AIGJ-01-002-blog/.github의 profile/README.md)를 만든다.

조직 프로필은 기능 소개(README.md)가 아니라 만들면서 고른 기술·이유·과정을 보여 준다(2026-10-12 강사님 피드백).

조직 페이지에서는 상대 경로가 열리지 않으므로 링크와 그림을 이 저장소 main의 절대 주소로 바꾼다.
  scripts/build-org-profile.py            # build/org-profile-README.md에 쓴다
  scripts/build-org-profile.py --stdout   # 화면에 출력
만든 파일을 .github 저장소의 profile/README.md에 붙여 넣는다.
"""
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
REPO = "https://github.com/AIGJ-01-002-blog/devlog"
RAW = "https://raw.githubusercontent.com/AIGJ-01-002-blog/devlog/main/"
IMAGES = (".png", ".webp", ".jpg", ".jpeg", ".gif", ".svg")


def absolute(path):
    if path.startswith(("http://", "https://", "#", "mailto:")):
        return path
    path, _, anchor = path.removeprefix("./").partition("#")
    if path.lower().endswith(IMAGES):
        return RAW + path
    path = path.rstrip("/")
    kind = "tree" if (ROOT / path).is_dir() else "blob"
    return f"{REPO}/{kind}/main/{path}" + (f"#{anchor}" if anchor else "")


def build():
    text = (ROOT / "profile/README.md").read_text(encoding="utf-8")
    text = re.sub(r"\]\(([^)\s]+)\)", lambda m: "](" + absolute(m.group(1)) + ")", text)
    return re.sub(r'(src|srcset)="([^"]+)"', lambda m: f'{m.group(1)}="{absolute(m.group(2))}"', text)


if __name__ == "__main__":
    out = build()
    if "--stdout" in sys.argv:
        sys.stdout.write(out)
    else:
        target = ROOT / "build/org-profile-README.md"
        target.parent.mkdir(exist_ok=True)
        target.write_text(out, encoding="utf-8")
        print(target.relative_to(ROOT))
