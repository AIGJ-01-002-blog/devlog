# 기능 spec 목록

`docs/`의 요구사항·설계 문서를 Spec Kit spec으로 옮긴 목록이다. 우선순위 단계는 MVP → MVP 직후(Tier A 나머지) →
다음 단계(Tier B) → 이후 단계(Tier C) 순이다. 각 spec의 상세 수치와 완료 기준은 근거 문서를 따른다.

| spec | 기능 | 기능 ID | 우선순위 단계 | 근거 문서 |
|---|---|---|---|---|
| [001-auth-ownership](./001-auth-ownership/spec.md) | GitHub 가입·로그인, 블로그 주소·닉네임, 소유 권한, 내 글 관리(본인 글만) | C-AUTH-1(일부), C-AUTH-2(닉네임), C-OWN-1, C-MANAGE-1(일부) | MVP | 07, 08, 09, 41, 42 |
| [002-write-publish](./002-write-publish/spec.md) | Markdown 작성·미리보기, 서버 자동 저장, 발행·다시 발행, 공개 범위 | C-POST-1, C-POST-2(일부), C-POST-3, C-POST-4 | MVP | 04, 05, 06, 12 |
| [003-read-share](./003-read-share/spec.md) | 글 상세·링크 미리보기, 홈 최신 글, 개인 블로그 | C-READ-1, C-READ-2, C-BLOG-1 | MVP | 10, 40 |
| [004-email-google-login](./004-email-google-login/spec.md) | 이메일 가입·인증·비밀번호, Google 로그인 | C-AUTH-1(나머지) | MVP 직후 (Tier A 나머지) | 07, 08, 11 |
| [005-profile-settings](./005-profile-settings/spec.md) | 프로필(소개·사진)·계정 설정 | C-AUTH-2 | MVP 직후 (Tier A 나머지) | 11, 09 |
| [006-offline-autosave](./006-offline-autosave/spec.md) | 브라우저 저장·오프라인 복구 | C-POST-2(나머지) | MVP 직후 (Tier A 나머지) | 04 |
| [007-delete-trash-manage](./007-delete-trash-manage/spec.md) | 글 삭제·휴지통·복구, 내 글 관리 전체 | C-POST-5, C-MANAGE-1(나머지) | MVP 직후 (Tier A 나머지) | 13, 41 |
| [008-friends-activity](./008-friends-activity/spec.md) | 친구 맺기, 최근 활동 표시, (선택) 친구 공개 | C-FRIEND-1, C-ACT-1 | MVP 직후 (Tier A 나머지) | 06, 07, 11 |
| [009-image-upload](./009-image-upload/spec.md) | 사진 업로드·썸네일·정리 | C-IMG-1 | 다음 단계 (Tier B) | 23, 04, 05, 10 |
| [010-tags](./010-tags/spec.md) | 태그·태그별 목록 | C-TAG-1 | 다음 단계 (Tier B) | 22 |
| [011-comments](./011-comments/spec.md) | 댓글·답글 | C-CMT-1 | 다음 단계 (Tier B) | 21 |
| [012-likes](./012-likes/spec.md) | 글 좋아요 | C-LIKE-1 | 다음 단계 (Tier B) | 30 |
| [013-view-count](./013-view-count/spec.md) | 조회수 | C-VIEW-1 | 다음 단계 (Tier B) | 31, 40 |
| [014-search](./014-search/spec.md) | 검색 | Tier C | 이후 단계 (Tier C) | 33 |
| [015-notifications](./015-notifications/spec.md) | 인앱 알림 | Tier C | 이후 단계 (Tier C) | 25, 20 |
| [016-follow-feed](./016-follow-feed/spec.md) | 팔로우·피드 | Tier C | 이후 단계 (Tier C) | 24 |
| [017-trending](./017-trending/spec.md) | 트렌딩 | Tier C | 이후 단계 (Tier C) | 32 |
| [018-ai-tag-suggest](./018-ai-tag-suggest/spec.md) | AI 태그 추천 | Tier C | 이후 단계 (Tier C) | 34 |
| [019-report-hide](./019-report-hide/spec.md) | 신고·관리자 숨김·정지 | Tier C | 이후 단계 (Tier C) | 43 |
| [020-withdraw](./020-withdraw/spec.md) | 회원 탈퇴 | Tier C (데이터 정책은 확정) | 이후 단계 (Tier C) | 44, 13 |
| [021-dark-mode](./021-dark-mode/spec.md) | 다크 모드 | Tier C | 이후 단계 (Tier C) | 45 |
| [022-file-attach](./022-file-attach/spec.md) | 글 첨부파일 (사진과 리소스 통합 관리) | C-FILE-1 (2026-10-07 추가) | 다음 단계 (Tier B) | 23 |
| [023-telegram](./023-telegram/spec.md) | 텔레그램 연결 (알림 받기, 메모로 임시글) | 2026-10-07 추가 (민서 답변) | 이후 단계 (Tier C) | 15, 34 |
| [024-series](./024-series/spec.md) | 시리즈 (글 묶기, 순서, 이전·다음 글) | 2026-10-08 추가 (velog 비교) | 이후 단계 (Tier C) | 03, 10 |
| [025-toc-reading-time](./025-toc-reading-time/spec.md) | 글 목차·읽는 시간 | 2026-10-08 추가 (velog 비교) | 이후 단계 (Tier C) | 03 |
| [026-rss](./026-rss/spec.md) | RSS 구독 (블로그별·전체) | 2026-10-08 추가 (velog 비교) | 이후 단계 (Tier C) | 03 |
| [027-liked-posts](./027-liked-posts/spec.md) | 좋아한 글 모아 보기 | 2026-10-08 추가 (velog 비교) | 이후 단계 (Tier C) | 12 |
| [028-first-load-split](./028-first-load-split/spec.md) | 첫 화면 JS 줄이기 | 2026-10-08 추가 (최적화) | 이후 단계 (Tier C) | 2 |
| [029-keyboard-reader-a11y](./029-keyboard-reader-a11y/spec.md) | 키보드·화면 읽기 프로그램 이동 개선 | 2026-10-08 추가 (최적화) | 이후 단계 (Tier C) | 42 |
| [030-static-delivery](./030-static-delivery/spec.md) | 화면 파일 캐시와 응답 압축 | 2026-10-08 추가 (최적화) | 이후 단계 (Tier C) | 2 |
| [031-reduced-motion](./031-reduced-motion/spec.md) | 움직임 줄이기와 카드 키보드 초점 | 2026-10-08 추가 (최적화) | 이후 단계 (Tier C) | 42 |
| [032-image-dimensions](./032-image-dimensions/spec.md) | 본문 사진 자리 먼저 잡기 | 2026-10-08 추가 (최적화) | 이후 단계 (Tier C) | 2 |
| [033-client-ip-hardening](./033-client-ip-hardening/spec.md) | 사용자 IP 판정 다지기 | 2026-10-08 추가 (테스트 보강) | 이후 단계 (Tier C) | 2 |
| [034-framework-error-status](./034-framework-error-status/spec.md) | 요청 쪽 오류를 서버 오류로 보지 않기 | 2026-10-08 추가 (테스트 보강) | 이후 단계 (Tier C) | 2 |
| [035-modal-focus](./035-modal-focus/spec.md) | 대화상자 초점 다루기 | 2026-10-08 추가 (접근성) | 이후 단계 (Tier C) | 2 |
| [036-session-store-down](./036-session-store-down/spec.md) | 세션 저장소가 멈췄을 때 가입·로그인 응답 | 2026-10-08 추가 (장애 처리) | 이후 단계 (Tier C) | 2 |
| [037-telegram-token-logs](./037-telegram-token-logs/spec.md) | 잘못 들어간 텔레그램 토큰이 로그에 남지 않게 | 2026-10-08 추가 (보안) | 이후 단계 (Tier C) | 2 |
| [038-social-email-rules](./038-social-email-rules/spec.md) | 소셜 로그인 이메일을 가입과 같은 규칙으로 | 2026-10-08 추가 (버그) | 이후 단계 (Tier C) | 2 |
| [039-share-link](./039-share-link/spec.md) | 글 공유 버튼 | 2026-10-08 추가 (velog 기능) | 이후 단계 (Tier C) | 2 |
| [040-adjacent-posts](./040-adjacent-posts/spec.md) | 이전·다음 글 | 2026-10-08 추가 (velog 기능) | 이후 단계 (Tier C) | 2 |
| [041-mail-failure-log](./041-mail-failure-log/spec.md) | 메일 발송 실패 기록에서 받는 사람 주소 빼기 | 2026-10-08 추가 (테스트 보강) | 이후 단계 (Tier C) | 2 |
| [042-blog-about](./042-blog-about/spec.md) | 블로그 소개 탭 | 2026-10-08 추가 (velog 기능) | 이후 단계 (Tier C) | 2 |
| [043-social-links](./043-social-links/spec.md) | 블로그 소셜 정보 | 2026-10-08 추가 (velog 기능) | 이후 단계 (Tier C) | 2 |
| [044-author-social-links](./044-author-social-links/spec.md) | 글 아래 작성자 소셜 정보 | 2026-10-08 추가 (velog 기능) | 이후 단계 (Tier C) | 2 |
| [045-post-summary](./045-post-summary/spec.md) | 글 짧은 소개 | 2026-10-08 추가 (velog 기능) | 이후 단계 (Tier C) | 5 |
| [046-logo](./046-logo/spec.md) | 새 로고 (파비콘·헤더) | 2026-10-08 추가 (민서님 로고) | 이후 단계 (Tier C) | 3 |
| [047-post-thumbnail](./047-post-thumbnail/spec.md) | 글 썸네일 고르기 | 2026-10-08 추가 (velog 기능) | 이후 단계 (Tier C) | 7 |
| [048-modern-ui](./048-modern-ui/spec.md) | 모던 개발 블로그 화면 | 2026-10-08 추가 (민서님 UI 방향) | 이후 단계 (Tier C) | 9 |
| [049-signup-ai-consent](./049-signup-ai-consent/spec.md) | 가입 화면의 AI 기능 동의 (선택) | 2026-10-08 추가 (민서님 답 7-1) | 이후 단계 (Tier C) | 5 |
| [050-post-query-module](./050-post-query-module/spec.md) | 글 조회를 글 모듈로 옮기기 (리팩터링) | 2026-10-08 추가 (민서님 답 14) | 이후 단계 (Tier C) | 4 |
| [051-mcp-devlog-home](./051-mcp-devlog-home/spec.md) | MCP 개발 일지 중심 첫 화면 | 2026-10-08 추가 (민서님 답 5·6번) | 이후 단계 (Tier C) | 6 |
| [052-mcp-server](./052-mcp-server/spec.md) | devlog MCP 서버와 접근 토큰·OAuth | 2026-10-08 추가 (민서님 답 5번) | 이후 단계 (Tier C) | 9 |
| [053-ai-publish-toggle](./053-ai-publish-toggle/spec.md) | AI 발행·삭제 허용 설정 | 2026-10-08 추가 (블로그 주인 요청) | 이후 단계 (Tier C) | 7 |
| [054-inquiry-bug-report](./054-inquiry-bug-report/spec.md) | 문의·신고 접수와 AI 버그 신고, 릴리스 노트 | 2026-10-08 추가 (민서님 요청) | 이후 단계 (Tier C) | 10 |
| [055-ux-tooltips-mobile](./055-ux-tooltips-mobile/spec.md) | 툴팁·모바일 아래 탭·에디터 서식 도구 | 2026-10-08 추가 (블로그 주인 요청, UX 점검) | 이후 단계 (Tier C) | - |
| [056-hybrid-search](./056-hybrid-search/spec.md) | 하이브리드 검색 (키워드 + 의미 검색) | 2026-10-08 추가 (블로그 주인 요청) | 이후 단계 (Tier C) | - |
| [057-prepublish-check](./057-prepublish-check/spec.md) | 발행 전 점검 | 2026-10-08 추가 (블로그 주인 요청, Crowfoot 설계 검증) | 이후 단계 (Tier C) | 3 |
| [058-post-revisions](./058-post-revisions/spec.md) | 글 수정 이력 | 2026-10-08 추가 (블로그 주인 요청, Crowfoot 버전 기록) | 이후 단계 (Tier C) | 7 |
| [059-post-export](./059-post-export/spec.md) | 내 글 내보내기 | 2026-10-08 추가 (블로그 주인 요청, Crowfoot Markdown 내보내기) | 이후 단계 (Tier C) | 6 |
| [060-mcp-tools-plus](./060-mcp-tools-plus/spec.md) | MCP 도구 강화 1차 | 2026-10-08 추가 (블로그 주인 요청) | 이후 단계 (Tier C) | - |
| [062-admin-console](./062-admin-console/spec.md) | 관리자 페이지: 대시보드 통계, 글·회원 관리, 매니저 권한 | 2026-10-08 추가 (블로그 주인 요청) | 이후 단계 (Tier C) | 42, 43 |
| [064-site-visitors](./064-site-visitors/spec.md) | 관리자 대시보드에 사이트 방문자 수(하루 순방문자·방문 수) | 2026-10-08 추가 (블로그 주인 요청) | 이후 단계 (Tier C) | 42, 43 |
| [066-active-members-daily](./066-active-members-daily/spec.md) | 관리자 대시보드에 날짜별 활동한 회원 그래프 | 2026-10-09 추가 (블로그 주인 요청) | 이후 단계 (Tier C) | 42, 43 |
| [070-visit-sources-pages](./070-visit-sources-pages/spec.md) | 관리자 대시보드에 유입 경로(검색·SNS·직접)와 많이 본 화면 | 2026-10-09 추가 (블로그 주인 요청) | 이후 단계 (Tier C) | 42, 43 |
| [072-branch-redesign](./072-branch-redesign/spec.md) | 새 디자인: 홈 브랜치 그래프(시리즈·비슷한 주제), 이어 읽기, 포트폴리오 모드 | 2026-10-09 추가 (블로그 주인 요청) | 이후 단계 (Tier C) | 10, 52 |
| [073-mcp-series](./073-mcp-series/spec.md) | AI 연결(MCP)로 시리즈 만들기·글 넣기·포트폴리오 프로젝트 칸 쓰기 | 2026-10-09 추가 (블로그 주인 요청) | 이후 단계 (Tier C) | 52 |
| [075-suggest-topics](./075-suggest-topics/spec.md) | AI 연결(MCP)로 메모·일기·제안·임시글에서 전공자용 글감 추천 받기 | 2026-10-09 추가 (블로그 주인 요청) | 이후 단계 (Tier C) | 52 |
| [076-adsense](./076-adsense/spec.md) | 구글 애드센스 광고 싣기: 공개 화면 광고 코드·엄격한 CSP·ads.txt·광고 쿠키 안내 | 2026-10-10 추가 (블로그 주인 요청) | 이후 단계 (Tier C) | - |
| [077-site-about](./077-site-about/spec.md) | 사이트 소개 화면(/about), 맨 아래 링크, 처리방침 광고 항목 재동의 | 2026-10-10 추가 (블로그 주인 답) | 이후 단계 (Tier C) | - |
| [078-discord-webhook](./078-discord-webhook/spec.md) | 디스코드 웹훅으로 블로그 알림 받기(회원이 자기 채널 주소를 넣음) | 2026-10-10 추가 (블로그 주인 요청) | 이후 단계 (Tier C) | - |
| [079-follow-list-private](./079-follow-list-private/spec.md) | 팔로워·팔로잉 목록 공개/비공개 설정, 비공개면 "비공개 계정입니다" | 2026-10-10 추가 (블로그 주인 요청) | 이후 단계 (Tier C) | - |
| [080-kakao-login](./080-kakao-login/spec.md) | 카카오 로그인(`ka-` 주소, 키가 있을 때만 버튼) | 2026-10-10 추가 (블로그 주인 질문) | 이후 단계 (Tier C) | 07, 08 |
| [083-facebook-login](./083-facebook-login/spec.md) | Facebook 로그인(`fb-` 주소, 키가 있을 때만 버튼) | 2026-10-10 추가 (블로그 주인 요청) | 이후 단계 (Tier C) | 07, 08 |
| [084-drop-js-copies](./084-drop-js-copies/spec.md) | 화면 코드의 tsc `.js` 사본 지우기, 다시 생기지 않게 막기 | 2026-10-11 추가 (일일 보고 질문 2 ★) | 정리 | 07 |
| [081-i18n](./081-i18n/spec.md) | 화면 다국어 1단계(영어·일본어·중국어, 맨 아래 언어 상자) | 2026-10-10 추가 (블로그 주인 질문) | 이후 단계 (Tier C) | 10 |
| [074-admin-kpi-board](./074-admin-kpi-board/spec.md) | 관리자 대시보드 지표 칸을 방문·회원·글·반응 묶음으로, 증감 표시와 날짜별 흐름 막대 | 2026-10-09 추가 (블로그 주인 요청) | 이후 단계 (Tier C) | 42, 43 |

## 여러 spec에 걸치는 문서

별도 spec이 아니라 헌법과 각 spec의 요구사항에 반영되어 있다.

| 문서 | 반영 위치 |
|---|---|
| 01 공통 요구사항 | 헌법(공통 원칙·기술 제약), 모든 spec의 기능 ID와 범위 |
| 02 아키텍처, 03·51 ERD | 헌법 기술 제약, 각 spec의 plan 단계 근거 |
| 12 본문 정화 | 002(작성), 011(댓글)과 헌법 원칙 III |
| 20 도메인 사건 | 헌법 원칙 IV, 015 알림과 사건을 내는 각 spec |
| 42 권한 매트릭스 | 헌법 원칙 II, 모든 spec의 권한 요구사항 |
| 52 기능 총정리 | 이 목록의 기준 |
