# 릴리스 노트 사진

릴리스 노트의 화면 전/후 사진을 둔다(CONTRIBUTING.md 2·3번, spec 093).

- 작업 중에는 `unreleased/`에, 릴리스 PR에서 `v<버전>/`으로 옮긴다. `unreleased/`의 사진은 사이트에 배포되지 않는다.
- webp, 가로 1280px 이하. 파일 이름은 `<화면>-before.webp`, `<화면>-after.webp`.
- CHANGELOG에는 GitHub 원본 주소(`https://raw.githubusercontent.com/AIGJ-01-002-blog/devlog/main/assets/releases/v<버전>/…`)로 적는다.
  GitHub Release는 그 주소로 보이고, 사이트 릴리스 노트(/releases)는 jar에 실린 사본(`/release-assets/v<버전>/…`)으로 바꿔 보여 준다.
