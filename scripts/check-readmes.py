#!/usr/bin/env python3
"""언어별 README(한·영·일·중)가 서로, 그리고 저장소와 맞는지 확인한다.

README.md(한국어)를 기준으로 삼는다. 번역판은 문장만 다르고 구조는 같아야 한다.
  1. 네 파일이 있고, 맨 위 언어 줄이 나머지 세 파일로 이어진다
  2. 제목·표 줄·목록·코드 블록·그림 수가 기준과 같다
  3. 릴리스 표의 버전 목록이 기준과 같고, CHANGELOG.md 맨 위 버전이 들어 있다
  4. Flyway 범위(V1~Vn)가 실제 마이그레이션 파일 수와 같다
  5. 기술 스택 버전(Spring Boot, React, TypeScript, Vite)이 pom.xml·package.json과 같다
  6. 상대 경로 링크·그림이 실제로 있고, 문서 안 앵커(#...)가 제목과 맞는다
  7. 조직 프로필 README(scripts/build-org-profile.py가 profile/README.md로 만든 것)에 상대 링크가 남지 않는다

실패하면 무엇이 어긋났는지 적고 1로 끝난다.
"""
import json
import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
FILES = ["README.md", "README.en.md", "README.ja.md", "README.zh.md"]
errors = []


def fail(msg):
    errors.append(msg)


def slug(heading):
    # GitHub 앵커 규칙: 소문자, 문장부호 제거, 공백은 하이픈
    return re.sub(r"[^\w\- ]", "", heading.strip().lower()).replace(" ", "-")


def strip_code(text):
    return re.sub(r"```.*?```", "", text, flags=re.S)


def shape(text):
    body = strip_code(text)
    return {
        "제목": len(re.findall(r"^#{1,4} ", body, re.M)),
        "표 줄": len(re.findall(r"^\|", text, re.M)),
        "목록": len(re.findall(r"^- ", body, re.M)),
        "코드 블록": text.count("```") // 2,
        "그림": len(re.findall(r"<img |!\[", text)),
    }


def releases(text):
    return re.findall(r"^\| (v\d+\.\d+\.\d+[^|]*?) \|", text, re.M)


docs = {}
for name in FILES:
    path = ROOT / name
    if not path.exists():
        fail(f"{name}: 파일이 없습니다")
    else:
        docs[name] = path.read_text(encoding="utf-8")
if len(docs) != len(FILES):
    print("\n".join(errors))
    sys.exit(1)

base = docs["README.md"]
base_shape = shape(base)
base_releases = releases(base)

top = re.search(r"^## \[(\d+\.\d+\.\d+)\]", (ROOT / "CHANGELOG.md").read_text(encoding="utf-8"), re.M)
top_version = f"v{top.group(1)}" if top else None
migrations = sorted(ROOT.glob("app/backend/src/main/resources/db/migration/V*__*.sql"))
flyway = f"V1~V{max(int(re.match(r'V(\d+)', p.name).group(1)) for p in migrations)}" if migrations else None

pom = (ROOT / "app/backend/pom.xml").read_text(encoding="utf-8")
boot = re.search(r"spring-boot-starter-parent</artifactId>\s*<version>([^<]+)<", pom).group(1)
pkg = json.loads((ROOT / "app/frontend/package.json").read_text(encoding="utf-8"))
deps = {**pkg.get("dependencies", {}), **pkg.get("devDependencies", {})}


def minor(spec):
    return ".".join(re.sub(r"^[^\d]*", "", spec).split(".")[:2])


stack = {
    "Spring Boot": f"| Spring Boot | {boot} |",
    "React": f"| React | {minor(deps['react'])} |",
    "TypeScript": f"| TypeScript | {minor(deps['typescript'])} |",
    "Vite": f"| Vite | {minor(deps['vite'])} |",
}

for name, text in docs.items():
    # 1. 언어 줄
    first = next((l for l in text.splitlines() if l.startswith("🌐")), "")
    for other in FILES:
        if other != name and f"(./{other})" not in first:
            fail(f"{name}: 맨 위 언어 줄에 {other} 링크가 없습니다")

    # 2. 구조
    if name != "README.md":
        for key, count in shape(text).items():
            if count != base_shape[key]:
                fail(f"{name}: {key} {count}개, README.md는 {base_shape[key]}개")

    # 3. 릴리스 표
    rel = releases(text)
    if name != "README.md" and rel != base_releases:
        missing = [v for v in base_releases if v not in rel]
        extra = [v for v in rel if v not in base_releases]
        fail(f"{name}: 릴리스 표가 README.md와 다릅니다 (빠짐 {missing or '-'}, 더 있음 {extra or '-'}, 순서도 확인)")
    if top_version and not any(v.split()[0] == top_version for v in rel):
        fail(f"{name}: CHANGELOG.md 맨 위 버전 {top_version} 행이 릴리스 표에 없습니다")

    # 4. Flyway 범위
    if flyway:
        for found in set(re.findall(r"V1~V\d+", text)):
            if found != flyway:
                fail(f"{name}: {found}라고 적혀 있지만 마이그레이션은 {flyway}입니다")

    # 5. 기술 스택 버전
    for label, row in stack.items():
        if row not in text:
            fail(f"{name}: 기술 스택 표의 {label} 버전이 실제와 다릅니다 (기대: `{row}`)")

    # 6. 링크와 앵커
    anchors = {slug(re.sub(r"^#+ ", "", l)) for l in strip_code(text).splitlines() if re.match(r"^#{1,4} ", l)}
    for a in re.findall(r"\]\(#([^)]+)\)", text):
        if a not in anchors:
            fail(f"{name}: #{a} 앵커에 맞는 제목이 없습니다")
    targets = re.findall(r"\]\(([^)#\s]+)(?:#[^)]*)?\)", text) + re.findall(r'(?:src|srcset)="([^"]+)"', text)
    for t in targets:
        if t.startswith(("http://", "https://", "mailto:")):
            continue
        if not (ROOT / t.removeprefix("./")).exists():
            fail(f"{name}: {t} 경로가 저장소에 없습니다")

# 7. 조직 프로필
built = subprocess.run([sys.executable, str(ROOT / "scripts/build-org-profile.py"), "--stdout"],
                       capture_output=True, text=True, check=True).stdout
if re.search(r'\]\((?!https?://|#|mailto:)', built) or re.search(r'(?:src|srcset)="(?!https?://)', built):
    fail("조직 프로필: 절대 주소로 바뀌지 않은 상대 링크가 있습니다")

if errors:
    print("README 검사 실패:")
    print("\n".join(f"  - {e}" for e in errors))
    sys.exit(1)
print(f"README 검사 통과: {len(FILES)}개 언어, 릴리스 {len(base_releases)}행 (맨 위 {top_version}), {flyway}, Spring Boot {boot}")
