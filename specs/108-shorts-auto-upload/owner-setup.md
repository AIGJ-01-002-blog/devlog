# 쇼츠 자동 올리기 설정 안내 (블로그 주인용)

스튜디오에서 만든 쇼츠를 YouTube와 Instagram에 자동으로 올리려면 아래를 한 번 해 두면 돼요. 처음에는 꺼져 있고, 아래를 다 해도 스튜디오에서 직접 켜기 전에는 아무것도 올라가지 않아요. 기능 설명은 [spec.md](./spec.md)에 있어요.

## 1. 토큰 열쇠 만들기

플랫폼 토큰을 DB에 암호화해 두는 열쇠예요. 한 번 만들고 바꾸지 않아요(바꾸면 계정을 다시 연결해야 해요).

```bash
openssl rand -base64 32
```

나온 44자를 `SHORTS_TOKEN_KEY`로 넣어요(아래 4번).

## 2. YouTube (Google Cloud 콘솔)

로그인용 Google 프로젝트와 따로 새 프로젝트를 만드는 것을 권해요. 로그인 동의 화면에 YouTube 권한이 섞이지 않아요.

1. [Google Cloud 콘솔](https://console.cloud.google.com/)에서 프로젝트를 만들어요(예: `devlog-shorts`).
2. **API 및 서비스 → 라이브러리**에서 **YouTube Data API v3**를 찾아 **사용**을 눌러요.
3. **API 및 서비스 → OAuth 동의 화면(Google 인증 플랫폼)**에서
   - 사용자 유형은 **외부**, 앱 이름·지원 이메일·개발자 연락처를 넣어요.
   - **데이터 액세스(범위)**에 `.../auth/youtube.upload`와 `.../auth/youtube`를 더해요.
   - **대상**에서 게시 상태를 **프로덕션으로 푸시**해요. "테스트"로 두면 갱신 토큰이 7일 뒤 끊겨 자동 올리기가 멈춰요. 검수(인증)를 받지 않아도 내 계정은 "확인되지 않은 앱" 경고에서 **고급 → 이동**을 눌러 이을 수 있어요.
4. **API 및 서비스 → 사용자 인증 정보 → 사용자 인증 정보 만들기 → OAuth 클라이언트 ID**
   - 애플리케이션 유형: **웹 애플리케이션**
   - 승인된 리디렉션 URI: `https://devlog.life/api/me/shorts/auto-upload/youtube/callback`
   - 만들면 나오는 클라이언트 ID·보안 비밀을 `YOUTUBE_CLIENT_ID`·`YOUTUBE_CLIENT_SECRET`으로 넣어요.
5. 하루 올리기 한도는 100편(할당량 "Video Uploads")이에요. 하루 한 편이라 넉넉해요.

## 3. Instagram (Meta 개발자 대시보드)

Instagram 계정은 **프로페셔널 계정**(비즈니스 또는 크리에이터)이어야 해요. Instagram 앱 → 설정 → 계정 유형 및 도구에서 바꿀 수 있어요.

1. [Meta for Developers](https://developers.facebook.com/apps/)에서 **앱 만들기** → 사용 사례 **"Instagram에서 메시지와 콘텐츠 관리"**를 골라요.
2. 앱 대시보드 → **Instagram → API setup with Instagram login**으로 가요.
   - **필요한 메시지 권한·콘텐츠 권한**에서 `instagram_business_basic`, `instagram_business_content_publish`가 있는지 봐요.
   - **Instagram 비즈니스 로그인 설정**의 **리디렉션 URL**에 `https://devlog.life/api/me/shorts/auto-upload/instagram/callback`을 넣어요.
   - 같은 화면 위쪽의 **Instagram 앱 ID**·**Instagram 앱 시크릿 코드**를 `INSTAGRAM_APP_ID`·`INSTAGRAM_APP_SECRET`으로 넣어요. (Facebook 앱 ID와 다른 값이에요.)
3. **앱 역할 → 역할**에서 올릴 Instagram 계정을 **Instagram 테스터**로 더하고, Instagram 앱(웹) → 설정 → 앱 및 웹사이트 → 테스터 초대에서 수락해요. 내 계정만 쓰면 앱 검수(App Review)는 필요 없어요.
4. 영상 주소가 바깥에서 열려야 Instagram이 영상을 가져가요. 스튜디오의 영상 링크를 휴대폰 데이터(학교·집 망 밖)로 열어 보세요.
5. 한도는 24시간에 API 게시 100개예요. 장기 토큰(약 60일)은 서버가 만료 전에 늘려요.

## 4. 값 넣기

GitHub 저장소 **Settings → Secrets and variables → Actions**에 Secret으로 넣으면 다음 배포 때 앱에 들어가요(또는 `BLOG_SECRET_ENV`에 같은 이름으로).

| 이름 | 값 |
|---|---|
| `SHORTS_TOKEN_KEY` | 1번에서 만든 44자 |
| `YOUTUBE_CLIENT_ID`, `YOUTUBE_CLIENT_SECRET` | 2번 4단계 |
| `INSTAGRAM_APP_ID`, `INSTAGRAM_APP_SECRET` | 3번 2단계 |

선택: `SHORTS_AUTO_UPLOAD_AUDIENCE`(기본 `ADMINS`, 관리자만), `INSTAGRAM_API_VERSION`(기본 `v24.0`).

## 5. 스튜디오에서 켜기

1. 관리자 계정으로 **쇼츠 스튜디오 → 자동 올리기**를 열어요.
2. 플랫폼마다 **[계정 연결]** → 동의 화면에서 모든 권한을 허용해요. 카드 아래 **앱에 등록할 콜백 주소**가 2·3번에 넣은 주소와 같은지 봐요.
3. 다 만든 쇼츠 하나를 골라 **[시험 올리기]**를 눌러요.
   - YouTube: 공개 예약 없이 **비공개**로 올라가요. YouTube 스튜디오에서 확인한 뒤 지워도 돼요.
   - Instagram: 게시하지 않고 토큰과 영상 주소만 확인해요(비공개 게시가 없어서요).
4. 시험이 통과하면 **"렌더가 끝나면 …에 자동으로 올리기"**를 켜요. 이때부터 영상 만들기가 끝난 쇼츠가 하루 한 편씩 저녁 8시(한국 시간)에 공개돼요. YouTube는 비공개로 미리 올라가 그 시각에 공개되고, Instagram은 서버가 그 시각에 게시해요.
5. 쇼츠마다 올리기 안내 위에 상태(예약 시각·올린 주소·실패 이유)가 보여요. 실패하면 **[다시 올리기]**를 눌러요. 고정 댓글은 지금처럼 직접 달아요.

## 멈추고 싶을 때

- 체크를 끄면 새 영상은 올리지 않아요. 이미 YouTube에 예약된 영상은 YouTube 스튜디오에서 지우거나 비공개로 바꿔요.
- **[연결 끊기]**는 저장한 토큰을 지우고 아직 안 올린 작업을 멈춰요. Google 계정 → 보안 → 서드 파티 액세스에서 앱 권한도 지울 수 있어요.
