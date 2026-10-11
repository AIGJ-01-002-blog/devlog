# Feature Specification: 화면 코드의 .js 사본 지우기

**Feature Branch**: `084-drop-js-copies`
**Created**: 2026-10-11
**Status**: Implemented
**근거**: 2026-10-11 일일 보고 질문 2 "화면 코드의 .js 사본 224개" ★.js를 모두 지우는 정리 PR 내기(답이 없어 기본값으로 진행).

## 문제

- `app/frontend/src`에 tsc가 만든 `.js` 사본 233개와 `vite.config.js`가 커밋돼 있었다(#86 무렵 `tsc`를 `--noEmit` 없이 돌려 생김).
- Vite와 vitest는 `./foo`를 찾을 때 `.ts`·`.tsx`보다 `.js`를 먼저 읽는다. `.tsx`만 고치고 `.js`를 다시 만들지 않으면 옛 코드가 배포·테스트된다.
- 테스트 사본(`*.test.js`) 68개도 함께 돌아 같은 테스트를 두 번 셌다.

## Requirements

- **FR-001**: 같은 이름의 `.ts`·`.tsx`가 있는 `.js`는 모두 지운다. 원본 자바스크립트인 `public/theme.js`는 남긴다.
- **FR-002**: `tsconfig.json`에 `"noEmit": true`를 두어 `tsc`만 돌려도 사본이 생기지 않게 한다.
- **FR-003**: `app/frontend/.gitignore`에 `src/**/*.js`와 `vite.config.js`를 넣어 사본이 다시 커밋되지 않게 한다.
- **FR-004**: 화면 동작은 바뀌지 않는다. `npm run typecheck`, `npm test`, `vite build`가 그대로 통과한다.

## 결과

- 테스트 파일 71개, 테스트 309개 통과(사본이 빠져 중복 집계가 사라짐).
- 이후 화면 PR은 `.tsx`만 고치면 된다. tsc로 `.js` 사본을 다시 만드는 절차는 없어진다.
