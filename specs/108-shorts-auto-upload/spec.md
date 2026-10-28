# Feature Specification: 쇼츠 렌더 후 유튜브·인스타 자동 올리기

**Feature Branch**: `feature/498-shorts-auto-upload`
**Created**: 2026-10-28
**Status**: Implemented (기본 꺼짐)
**근거**: 2026-10-28 블로그 주인 질문 "우리 블로그에서 만든 영상 우리 인스타랑 유튜브에 렌더하면 바로 자동올리기 할수있어?". 목표 이슈 #498.

## 고른 것과 이유

- **099·103의 "자동 올리기 하지 않음"을 주인 계정 한정으로 푼다**: 그때 막혔던 이유는 (1) 심사 전 API 영상이 비공개로 잠김, (2) Instagram 권한마다 App Review, (3) 회원마다 토큰을 보관할 부담이었다. 2026-10-28 다시 확인한 문서에서 YouTube는 심사 안 받은 API 프로젝트의 영상도 비공개로 잠그지 않고(videos.insert 문서), Instagram은 앱 역할이 있는 본인 계정이면 App Review 없이 게시 권한을 쓴다. 기본 사용자는 관리자(블로그 주인)만이라(`SHORTS_AUTO_UPLOAD_AUDIENCE=ADMINS`) 토큰을 보관하는 계정도 하나다.
- **처음에는 꺼져 있다**: 서버 값(토큰 열쇠·플랫폼 앱 값)이 없으면 화면에 보이지 않고, 값이 있어도 주인이 계정을 잇고 [시험 올리기]를 통과한 플랫폼만 켤 수 있다. 켠 뒤에 렌더가 끝난 영상만 올린다(켜기 전 영상은 올리지 않는다).
- **공개 시각은 화면과 같은 저녁 8시(한국 시간) 하루 한 편**: YouTube는 비공개 + `publishAt`으로 플랫폼이 공개하고, Instagram API에는 예약이 없어 devlog 서버가 그 시각에 게시한다.
- **문구는 화면 올리기 안내와 같게**: 서버 `ShortsCaption`이 `uploadGuide()`·`playlistName()`을 한국어 그대로 옮겼다. 견본 파일 `app/frontend/src/lib/shortsCaptionParity.json`을 화면(vitest)과 서버(JUnit) 테스트가 함께 읽어 둘이 어긋나면 깨진다.
- **재생목록은 만들지 않는다**: `playlistName()` 이름의 재생목록이 채널에 있으면 넣고, 없으면 알림만 남긴다(주인이 직접 만든다는 2026-10-21 결정 그대로).
- **라이브러리 없이 HTTP로 부른다**: 텔레그램(023)처럼 JDK HttpClient로 몇 가지 호출만 한다. 요청 주소·본문·예외 메시지에 토큰이 실릴 수 있어 로그·오류 문구에 남기지 않는다.

## 요구 사항

### 설정과 연결

- **FR-001**: `blog.shorts.auto-upload.*` 설정. `SHORTS_TOKEN_KEY`(AES-256, base64 32바이트), `YOUTUBE_CLIENT_ID`·`YOUTUBE_CLIENT_SECRET`, `INSTAGRAM_APP_ID`·`INSTAGRAM_APP_SECRET`. 쇼츠가 꺼졌거나, 열쇠가 없거나 잘못됐거나, 쓸 수 없는 회원이면 모든 API가 404이고 스튜디오에 [자동 올리기]가 보이지 않는다. 플랫폼 앱 값이 없으면 그 플랫폼만 [계정 연결]이 막힌다.
- **FR-002**: 연결은 `shorts_platform_connection`(V62, 회원×플랫폼 하나). 접근·갱신 토큰은 AES-GCM(12바이트 무작위 IV, AAD = 회원·플랫폼·칸)으로 암호화한 값만 둔다. 다른 행에 옮겨 붙인 암호문은 풀리지 않는다. `enabled` 기본 false. 다시 이으면 시험·켜짐을 처음으로 돌리고 아직 안 올린 작업을 멈춘다. 탈퇴 정리(54)가 연결과 작업을 지운다.
- **FR-003**: OAuth. `POST /api/me/shorts/auto-upload/{platform}/connect`가 32바이트 무작위 `state`를 세션에 두고(10분) 동의 화면 주소를 준다. `GET …/{platform}/callback`은 세션의 state와 상수 시간 비교로 맞춰 보고 한 번 쓰면 지운다. 끝나면 `/studio?auto=1&platform=…&result=connected|denied|state|failed`로 보낸다(문구는 화면이 고른다).
  - YouTube: `access_type=offline&prompt=consent`, 범위 `youtube.upload` + `youtube`(재생목록 읽기·넣기). 갱신 토큰을 못 받으면 실패. 채널(`channels?mine=true`) 이름을 보인다.
  - Instagram: Instagram Login(`instagram_business_basic`, `instagram_business_content_publish`) → 단기 토큰 → 장기 토큰(약 60일). 매일 04:20(KST) 만료 20일 안쪽 토큰을 `refresh_access_token`으로 늘린다.
- **FR-004**: [시험 올리기] `POST …/{platform}/test {shortsId}`. 다 만든 내 쇼츠 하나를 고른다.
  - YouTube: 공개 예약 없이 비공개로 올린다(채널에 비공개로 남는다. 확인 뒤 지워도 된다).
  - Instagram: 비공개 게시가 없어 토큰·계정을 확인하고 릴스 컨테이너만 만든다(게시하지 않는다. 컨테이너는 24시간 뒤 사라진다). 처리 실패(ERROR)면 시험 실패.
  - 통과하면 `tested_at`, 실패하면 `test_error`(422).
- **FR-005**: 켜기 `PUT …/{platform}/enabled {enabled}`는 시험을 통과한 연결만(아니면 409 TEST_REQUIRED). 켠 시각(`enabled_at`) 뒤에 렌더가 끝난 영상만 올린다. 끄면 새 작업을 넣지 않고, 남은 작업은 켤 때까지 미룬다(이미 YouTube에 예약한 영상은 그대로).

### 올리기 작업

- **FR-006**: `shorts_auto_upload`(V62, 쇼츠×플랫폼 하나): `QUEUED → UPLOADING → SCHEDULED → PUBLISHED`, 실패하면 `FAILED`. 1분마다(JobLock으로 한 서버만) 켠 연결의 READY 쇼츠 가운데 작업도 직접 남긴 주소(shorts_upload)도 없는 것을 렌더 순서대로 넣는다.
- **FR-007**: 공개 시각은 다음 빈 날 20:00(KST). 오늘 20:00까지 30분 넘게 남지 않았으면 다음 날부터, 같은 회원·플랫폼에 이미 잡힌 날(FAILED 빼고)은 건너뛴다. 하루에 두 편이 공개되지 않는다.
- **FR-008**: 진행.
  - YouTube: 바로 재개 가능 업로드(`uploadType=resumable`). `privacyStatus=private`, `publishAt`, `containsSyntheticMedia=true`(AI 목소리·그림), `selfDeclaredMadeForKids=false`, 범주 28(과학기술). 다시 하느라 공개 시각까지 30분이 안 남았으면 다음 빈 날로 다시 고른다. 올리면 SCHEDULED, 공개 시각이 지나면 PUBLISHED.
  - Instagram: 공개 15분 전에 `media_type=REELS` 컨테이너(공개 영상 주소)를 만들고 1분마다 `status_code`를 본다. FINISHED이고 공개 시각이 됐으면 `media_publish`. 한 시간 넘게 늦었으면 다음 빈 날로 미룬다. 20:00:05(KST)에 한 번 더 돌려 제 시각에 게시한다.
  - 공개되면 그 주소를 `shorts_upload`에 적는다(회원이 직접 남긴 주소가 있으면 그대로). 공개 전에는 적지 않는다(블로그 쇼츠 탭에 비공개 영상이 보이지 않게).
  - 영상을 다시 만드는 중(READY 아님)이면 기다린다.
- **FR-009**: 실패. 망·5xx·429·하루 한도는 다시 한다(5분, 10분 뒤). 3번째 실패, 연결 끊김(401·invalid_grant·Graph 190), 거절(400 등)은 바로 FAILED이고 한국어 이유를 남긴다. 스튜디오에서 [다시 올리기] `POST /api/me/shorts/{id}/auto-uploads/{platform}/retry`로 공개 시각을 다시 골라 처음부터 한다. 재생목록을 못 찾거나 못 넣은 것은 실패가 아니라 알림(note)이다.
- **FR-010**: 제목·설명·캡션·재생목록 이름은 `ShortsCaption`(서버)과 `uploadGuide()`(화면)가 같다. YouTube 제목 100자·설명 5,000자, Instagram 캡션 2,200자를 코드 포인트로 넘으면 자른다.

### 화면

- **FR-011**: 스튜디오 왼쪽에 [자동 올리기](쓸 수 있을 때만), `/studio?auto=1`에 플랫폼 카드: 연결 상태 배지, [계정 연결]·[연결 끊기], 시험으로 올릴 쇼츠 고르기와 [시험 올리기], 결과, "렌더가 끝나면 …에 자동으로 올리기" 체크(시험 전에는 막힘, 툴팁으로 이유), 앱에 등록할 콜백 주소(복사). 올리기 안내에는 쇼츠별 작업 상태(공개 예약 시각·주소·오류·[다시 올리기]).
- **FR-012**: 툴팁(data-tip), 375px 한 줄씩, 다크 모드는 기존 색 변수(spec 055 §4). 새 문장은 en·ja·zh 사전에 있다.

## 범위 밖

- TikTok 자동 올리기(Content Posting API는 심사 전 비공개로만 올라가 넣지 않았다).
- 회원 누구나 쓰기(`SHORTS_AUTO_UPLOAD_AUDIENCE=MEMBERS`로 열 수는 있지만 앱 하나의 하루 한도·검수를 나눠 쓰므로 기본은 주인만).
- 썸네일 지정(`thumbnails.set`), 고정 댓글 자동 달기, Instagram 체험용 릴스.
- 재생목록 만들기.

## 확인하지 못한 것

- 진짜 계정으로 올려 보지 않았다(시험은 가짜 서버). 주인이 [시험 올리기]로 처음 확인한다.
- Instagram Graph API 버전 기본값 `v24.0`(바꾸려면 `INSTAGRAM_API_VERSION`), 장기 토큰 교환·갱신 응답 모양은 2026-10 문서 기준이다.
- YouTube 재개 가능 업로드를 한 번에 PUT한다(20MB 이하). 중간에 끊기면 처음부터 다시 올린다. 업로드가 끝났는데 저장 전에 서버가 죽으면 같은 영상이 두 번 올라갈 수 있다.

## 근거

- YouTube Data API v3 videos.insert(할당량 "Video Uploads" 1단위·하루 100번, `status.publishAt`, `status.containsSyntheticMedia`), playlistItems.insert, OAuth 2.0 웹 서버 흐름(동의 화면 "테스트"면 갱신 토큰 7일 만료), 2026-10-28 확인
- Instagram API with Instagram Login: 콘텐츠 게시(REELS 컨테이너 → media_publish, 24시간 100개), 장기 토큰과 refresh_access_token, 2026-10-28 확인
