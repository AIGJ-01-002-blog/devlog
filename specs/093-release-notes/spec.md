# Feature Specification: 릴리스 노트에 화면 사진·PR·비교 링크

**Feature Branch**: `feature/093-release-notes`
**Created**: 2026-10-13
**Status**: Implemented (develop)
**근거**: 목표 v1.67.0(#169)의 할 일 #171. 2026-10-12 강사님 중간 점검 피드백 5번 "사진을 포함해 기록하고 feature 브랜치로 개발했는지 드러나게".

## 사용자 시나리오

1. **Given** 릴리스 PR이 CHANGELOG의 새 버전을 [릴리스 노트 틀](../../.github/RELEASE_TEMPLATE.md)대로 쓰고 사진을 `assets/releases/v<버전>/`에 둔 상태, **When** main에 머지되면, **Then** GitHub Release에 그 노트가 그대로 실리고 끝에 "머지된 PR (자동)" 표(번호·제목·브랜치)와 이전 태그와의 비교 링크가 붙는다.
2. **Given** 같은 버전, **When** devlog.life/releases를 열면, **Then** 화면 전/후 사진 표와 포함된 작업 표가 보인다. 375px 휴대폰에서는 두 사진이 칸을 반씩 나눠 줄어들고 가로로 넘치지 않는다.
3. **Given** 틀의 안내 주석(`<!-- -->`)을 지우지 않은 노트, **Then** 사이트 화면에 주석 글자가 보이지 않는다.

## Requirements

- **FR-001**: release.yml은 이전 태그(`git describe --tags --abbrev=0`)부터 지금까지의 커밋 제목에서 PR 번호를 모은다. squash 머지(`제목 (#12)`)와 merge commit(`Merge pull request #12 …`)을 모두 읽는다. 각 PR의 제목과 head 브랜치를 표로 붙인다. 이전 태그가 없거나 같은 버전이면 붙이지 않는다.
- **FR-002**: 노트에 `/compare/` 링크가 이미 있으면(틀대로 쓴 경우) 비교 링크를 다시 붙이지 않는다.
- **FR-003**: 빌드가 저장소의 `assets/releases/` 사진(webp·png·jpg·gif)을 jar의 `static/release-assets/`에 넣고, 사이트가 `/release-assets/<폴더>/<파일>`로 30일 캐시와 함께 내보낸다.
- **FR-004**: 사이트 릴리스 노트는 CHANGELOG의 GitHub 원본 사진 주소(`raw.githubusercontent.com/AIGJ-01-002-blog/devlog/<브랜치>/assets/releases/`)를 사이트 사본 주소로 바꿔 그린다. 정화는 이 사이트의 `/release-assets/<폴더>/<파일>` 꼴만 사진으로 받고(폴더·파일 이름은 영숫자로 시작, 상위 폴더 경로 불가), 그 밖의 바깥 사진은 지금처럼 링크로만 남긴다.
- **FR-005**: 사이트 화면에서는 HTML 주석을 뺀다.
- **FR-006**: 릴리스 노트의 표는 화면 폭을 꽉 채우고 칸을 고르게 나눈다. 표 안 사진은 칸 폭에 맞춘다.

## 고른 것과 이유 (질문 없이 진행)

- **사진을 사이트에서 직접 내보냄**: 사이트 CSP의 img-src는 'self'와 사진 저장소만 허용한다. GitHub 원본 주소를 허용 목록에 더하면 글 본문 정화 규칙도 함께 넓어진다. jar에 사본을 실으면 정화·CSP 규칙을 넓히지 않고, 배포한 버전과 사진이 늘 함께 간다.
- **자동 PR 표는 GitHub Release에만**: 사이트는 빌드 때의 CHANGELOG만 읽는다. 사람이 다듬은 "포함된 작업" 표(이슈·브랜치·PR)는 CHANGELOG에 있어 사이트에도 보인다.
