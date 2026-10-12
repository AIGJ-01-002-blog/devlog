<!-- base 브랜치: 기능·수정은 develop, 릴리스 PR과 hotfix만 main (CONTRIBUTING.md) -->
## Summary
<!-- 무엇이 바뀌었는지 한두 줄 -->

## Why
<!-- 왜 필요한지, 관련 spec 번호(예: spec 002) -->

## 목표
<!-- 어느 마일스톤의 어느 이슈인지. 예: v1.67.0 · Closes #170 -->

## 검증
- [ ] 백엔드 `./mvnw verify` 통과 (커버리지 줄 40% 이상)
- [ ] 화면 `npm run typecheck && npm test` 통과
- [ ] 배포 구성을 바꿨다면 `kustomize build deploy/k8s/overlays/local` 확인
- [ ] 비밀값을 커밋하지 않음 (`deploy/scripts/check-no-secrets.sh`)

## UX (모든 릴리스, spec 055 §4)
- [ ] 아이콘·기호만 있는 버튼·링크에 `aria-label`이나 `data-tip`을 달아 마우스를 올리면 툴팁이 뜬다 (`controlLabels.test.ts` 통과)
- [ ] 새 화면·바뀐 화면을 375px로 열어 봤다: 가로 스크롤 없음, 누르는 것 40px 안팎, 글자 꺾임 없음
- [ ] 다크 모드에서도 한 번 봤다
- 확인한 화면: <!-- 예: 홈·글 상세(375px·1280px) -->

## 참고
<!-- 스크린샷, 남은 일, 리뷰어가 볼 곳 -->
