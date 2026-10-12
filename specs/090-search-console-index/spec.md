# Feature Specification: Search Console 색인 경고 점검

**Feature Branch**: `090-search-console-index`
**Created**: 2026-10-12
**Status**: Implemented (v1.66.1)
**근거**: 2026-10-12 Google Search Console 메일 두 통(블로그 주인이 캡처로 전달). "사이트맵에 포함된 페이지가 robots.txt에 의해 차단됨", "색인이 생성되지 않는 새 이유: robots.txt에 의해 차단됨 · 적절한 표준 태그가 포함된 대체 페이지 · Soft 404 · 찾을 수 없음(404)".

## 점검 결과 (2026-10-12, 운영 사이트)
- 사이트맵 57개 주소는 모두 200, 수집 허용, 자기 자신을 가리키는 표준 주소였다. robots.txt가 막은 경로(`/api/`, `/write`, `/settings`, `/login` 등)로 시작하는 주소는 없었다.
- robots.txt 앞부분은 Cloudflare가 붙이는 AI 수집 차단 목록이다. Googlebot은 막지 않는다(`User-agent: *`는 `Allow: /`). 우리 규칙은 그 뒤에 붙는다.
- 표준 태그가 포함된 대체 페이지: `/@handle?tag=…`(정규 주소 `/@handle`), `/index.html`(정규 주소 `/`)처럼 의도한 중복 주소다.
- 찾을 수 없음(404): 지운 글·없는 주소다. 의도한 응답이다.
- Soft 404: 공개 글이 없는 태그 주소(`/tags/없는태그`)가 200과 빈 화면을 돌려줬다. 의도하지 않았다.

## Requirements
- **FR-001**: 공개 글이 없는 태그 주소의 첫 화면은 404로 내린다(010 FR-022의 "200 + 빈 상태"를 바꾼다). 화면 안 이동은 API로 빈 상태를 그대로 보여 준다.
- **FR-002**: 수집을 여는 `/mcp`·`/releases`를 사이트맵에 넣는다.
- **FR-003**: 사이트맵의 모든 주소가 robots.txt의 Disallow 경로로 시작하지 않는지 테스트로 지킨다.
