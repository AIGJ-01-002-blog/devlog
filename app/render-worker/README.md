# devlog 쇼츠 렌더 워커

devlog 쇼츠 스튜디오(spec 099)에서 [영상 만들기]를 누른 대본을 받아 **9:16 MP4(1080×1920)** 로 만들어 다시 올리는 작은 프로그램이에요.
맥·Windows·리눅스 어디서든 돌고, 서버로 **나가는 요청만** 하니 집 공유기 뒤에서도 동작해요. 영상을 SNS에 자동으로 올리지는 않아요.

- 음성: 무료 신경망 한국어 음성(edge-tts `ko-KR-SunHiNeural`)이 기본이에요. `--voice ko-KR-InJoonNeural`처럼 바꿀 수 있어요.
  인터넷이 막혔거나 실패하면 영상 전체를 컴퓨터 안에서 도는 신경망 음성 [Supertonic-3](https://github.com/supertone-inc/supertonic)(코드 MIT, 모델 OpenRAIL-M, 처음 한 번 모델을 내려받아요)으로 다시 만들어요.
  그것도 안 되면 기기 음성으로 내려가요: 맥 `say`(Yuna), Windows 기본 한국어 음성(Heami), 리눅스 `espeak-ng`, 없으면 무음
- 자막: 내레이션을 1~2줄 구절로 나눠 음성에 맞춰 한 구절씩 띄우고, 지금 읽는 단어를 노랗게 키워요(신경망 음성은 단어마다 소리 나는 시각을 받아 맞춰요). 요점 자막은 단어마다 튀어 오르고 형광펜이 그어져요
- 화면 도구: [Remotion](https://www.remotion.dev)(React로 영상을 만드는 도구)으로 그려요. `remotion/` 폴더가 화면(캐릭터·자막·전환)을, 파이썬 워커가 음성·시간·소리 섞기를 맡아요.
  Node가 없으면 예전처럼 Pillow로 그린 무대로, Pillow도 없으면 글자 화면으로 내려가요
- 캐릭터: Google Noto 움직이는 고양이 얼굴(Lottie, CC BY 4.0)에 같은 색으로 그린 몸·팔·다리·꼬리를 달아, 표정과 몸짓으로 상황을 연기해요. 전구·로켓·폭죽·모래시계 같은 소품도 움직이는 이모지예요. 장면마다 옆에서 뛰어 들어오고, 구절이 바뀔 때마다 콩 뛰며 손가락으로 가리켜요.
- 스타일: 밝은 바탕·진한 글자·흰 카드의 깔끔한 화면. 글꼴·색·여백·자막 모양·움직임 속도는 `remotion/src/theme.ts` 한 곳에 있고, 매매 재현 같은 다른 장면도 같은 값을 써요.
- 영상마다 다른 연출: 유튜브·인스타그램은 비슷한 틀을 반복한 영상을 수익화·추천에서 빼므로, 영상 한 편마다 룩 하나(색·배경 무늬·구절 자막 모양·요점 자막 등장 방식, 7가지)와 배경음 하나(4곡)를 고르고, 장면마다 전환(밀기·지우기·뒤집기·시계·조리개·밀어 끊기·점프 컷 등 14가지)·무대 카메라(당기기·확대·축소·좌우 이동·위로 이동·고정)·배치(가운데 무대 또는 위 그림·아래 캐릭터)를 앞 장면과 겹치지 않게 골라요. 참고 영상 분석(2026-10-17)에 따라 장면마다 바탕색이 강조색 쪽으로 조금씩 바뀌고, 한 편에 한두 장면은 목소리가 시작할 때 사건(흔들림·섬광·'중요' 도장, 소리와 함께)이 일어나요(놀람·당황 연기 장면 먼저). 카메라는 장면 성격을 따라 코드는 확대, 도식은 거의 고정이에요. 작업 번호·제목·내레이션으로 정한 씨앗으로 고르므로 같은 대본은 다시 그려도 같은 영상이고, 대본을 고치면 달라져요. 고르는 규칙은 `devlog_render/look.py`, 룩 모양은 `remotion/src/looks.ts`. 서버 작업에 `"look": "night"`처럼 룩을 정해 줄 수도 있어요. 매매 재현은 차트가 주인공이라 기본 룩(paper)이에요. 끝 광고는 룩과 상관없이 늘 같아요.
- 점프 컷·짧은 자막(참고 영상 분석 2026-10-17): 6초보다 긴 가운데 무대 장면(캐릭터·코드)은 카메라가 `jump`가 되어 구절 자막이 바뀔 때마다 확대를 1배↔1.1배로 번갈아 끊어요(`look.hold_camera`). 같은 화면이 6초 넘게 이어지지 않게 하려는 거예요. 도식 장면은 칸이 차례로 나타나 화면이 스스로 바뀌므로 그대로 둬요. grape 룩의 구절 자막(`chunk`)은 지금 읽는 1~2어절(10자 이내)만 크게, 움직임 없이 바로 바꿔 띄워요.
- CS 설명 도식: 대본 장면에 `diagram`을 주면 고양이 연기 대신 도식(흐름 `flow`·층 `stack`·순서 `steps`·비교 `compare`·연표 `timeline`·정의 `define`·숫자 변화 `number`)을 그리고, 칸은 내레이션이 그 이름을 말할 때 나타나요. 아이콘은 [Lucide](https://lucide.dev)(ISC). 모양은 `devlog_render/diagram.py` 맨 위, 예시는 `samples/cs-cache.json`.
- 빠른 견본 스타일(시험 중): 대본에 `"style": "fast"`를 주면 위에 그림·아래에 캐릭터를 두고, 그 경계에 흰 글씨·검은 테두리 한 줄 자막(강조 낱말은 한 편에 3번까지 빨강·노랑)을 띄워요. 장면 안에서도 약 2초마다 구도를 끊어 바꾸고 목소리는 1.25배 빨라요. 없으면 지금의 깔끔한 스타일이에요. 견본은 `samples/docker-volume-fast.json`, 화면은 `remotion/src/FastScene.tsx`.
  출처 표시가 필요해요: 쇼츠 설명란에 "Animated emoji by Google, Noto Emoji Animation, CC BY 4.0"을 넣어 주세요(`remotion/src/emoji/README.md`)
  연기는 대본 장면의 `visual`(아래 표)을 따르고, 없으면 장면 글에서 상황을 읽어 골라요. 연기마다 효과음(뿅·띵·휙)이 붙어요

  | visual | 연기 | 글에서 고르는 단서 |
  |---|---|---|
  | `explain` | 손 흔들며 설명 | (기본) |
  | `shock` | 자료가 휴지통으로 빨려 들어가고 깜짝 놀라 울먹임 | 사라졌·지웠·삭제·날렸 |
  | `typing` | 모니터에 코드가 써지고 문제 줄은 빨강, 고친 줄은 초록 | 장면에 코드가 있으면 |
  | `panic` | 요청이 서버로 쏟아지고 숫자가 올라가며 머리를 감쌈 | 요청·트래픽·폭주·무한 |
  | `restore` | 백업 상자에서 DB가 차오르고 만세 | 복원·되살·백업 |
  | `wait` | 로딩 화면 앞에서 시간이 올라가며 졸음 | 걸렸·느려·로딩 |
  | `fast` | 전·후 막대와 "N배 빨라짐" 환호 | 빨라·떨어졌 + 시간 두 개 |
  | `idea` | 머리 위에 전구 | ~하세요·~보세요·팁·켜 두 |
  | `protect` | 방패를 들고 당당하게 | 막았·지켜·보안 |
  | `think` | 턱을 괴고 물음표 | 원인·왜·범인·이유 |
  | `celebrate` | 별 속에서 폴짝 | 성공·해결·완성 |
- 화면: 장면마다 천천히 흐르는 그라데이션 배경, 글 이미지(`image`·`coverImage`)가 있으면 흐린 배경으로 써요(캐릭터가 없을 때는 가운데 카드로도)
- 배경음: 워커가 직접 합성한 잔잔한 반복 음악(저작권 걱정 없음, 빠르기·조·진행이 다른 4곡 중 영상마다 하나)을 깔고 말할 때 소리를 낮춰요. 다른 음악은 `--bgm 파일`, 끄려면 `--bgm none`
- 전환: 장면 사이 0.3초 밀기(Remotion이 없으면 페이드), 장면 끝 쉼 0.15초. 소리는 쇼츠 기준 -14 LUFS로 맞춰요
- 글꼴: 맥 Apple SD Gothic Neo, Windows 맑은 고딕, 리눅스 Noto Sans CJK (바꾸려면 `--font`)
- 필요한 것: Python 3.10 이상, ffmpeg, Node 18 이상(Remotion 화면). 신경망 음성은 `pip install -r requirements.txt`(edge-tts, Pillow)
- 맞춤 장면: 대본 장면에 `custom: {"kind": "replay", "data": {...}}`를 주면 고양이 대신 `remotion/src/custom.tsx`의 `CUSTOM_SCENES`에 등록한 화면을 그려요(매매 재현 같은 다른 연출이 얹는 자리)

## 설치

| 운영체제 | ffmpeg 설치 |
|---|---|
| macOS | `brew install ffmpeg` |
| Windows | `winget install Gyan.FFmpeg` 후 새 터미널 열기 |
| Ubuntu | `sudo apt install ffmpeg fonts-noto-cjk` |

```bash
cd app/render-worker
python3 -m pip install -r requirements.txt   # 신경망 음성 (선택)
(cd remotion && npm ci)                       # Remotion 화면 (처음 한 번, 안 해도 첫 렌더 때 알아서 해요)
python3 -m devlog_render check      # ffmpeg·글꼴·음성 확인 (Windows는 python)
```

Remotion은 처음 렌더할 때 헤드리스 크롬을 내려받아요. 이미 있는 크롬을 쓰려면 `DEVLOG_BROWSER`에 실행 파일 경로를, Remotion을 끄려면 `DEVLOG_REMOTION=0`을 주세요.
화면만 고쳐 보려면 `cd remotion && npm run studio`로 견본 장면(`src/sample.ts`)을 브라우저에서 볼 수 있어요.
Remotion은 개인과 직원 3명 이하 회사는 무료이고, 그보다 큰 회사가 쓰면 회사 라이선스가 필요해요([라이선스](https://www.remotion.dev/license)). 글꼴은 Pretendard·JetBrains Mono(둘 다 OFL)를 함께 넣어요.

Windows 한국어 음성이 없다면 설정 → 시간 및 언어 → 언어 및 지역 → 한국어 → 언어 옵션 → 음성을 설치해 주세요.

## 서버 대기열 처리

토큰은 devlog 서버의 `SHORTS_WORKER_TOKEN`과 같은 값을 **환경 변수로만** 넘겨요. 파일이나 저장소에 적지 마세요.

```bash
# macOS / Linux
export DEVLOG_RENDER_TOKEN='…'
python3 -m devlog_render work --server https://devlog.life

# Windows PowerShell
$env:DEVLOG_RENDER_TOKEN = '…'
python -m devlog_render work --server https://devlog.life
```

- 대기열이 비면 15초마다 다시 봐요(`--interval`). 쌓인 것만 처리하고 끝내려면 `--once`.
- 렌더가 실패하면 서버에 알리고, 서버가 3번까지 다시 맡겨요. 워커가 꺼지면 15분 뒤 서버가 작업을 되돌려요.
- 토큰이 틀리거나 서버에서 쇼츠가 꺼져 있으면 워커가 바로 멈춰요.

로컬로 띄운 devlog(`app/compose.yaml`)에 붙이려면 `--server http://localhost:8080`.

### 토큰 바꾸기

워커를 멈추지 않고 토큰을 바꾸려면, 서버의 `SHORTS_WORKER_TOKEN`에 새 값을, `SHORTS_WORKER_TOKEN_PREVIOUS`에 옛 값을 넣고 서버를 다시 띄워요. 그다음 새 터미널에서 `DEVLOG_RENDER_TOKEN`을 새 값으로 넣은 워커를 띄워 대기열을 처리하는 것을 본 뒤 옛 워커를 끄세요. 이미 떠 있는 워커는 처음 받은 토큰을 계속 쓰기 때문에, 환경 변수만 바꾸고 워커를 다시 띄우지 않으면 옛 토큰으로 계속 와요. 옛 워커를 끄고 하루가 지나 관리자 대시보드의 "쇼츠 렌더 워커" 칸에서 옛 토큰 안내가 사라지면(옛 토큰 사용 기록은 마지막 사용 뒤 하루 동안 남아요) `SHORTS_WORKER_TOKEN_PREVIOUS`를 비워요. 워커가 5분 넘게 대기열을 보러 오지 않는데 기다리는 영상이 있으면 대시보드 "처리할 일"에 뜹니다.

## 서버 없이 만들어 보기

```bash
python3 -m devlog_render render script.json out.mp4
```

`script.json` 모양 (`image`·`coverImage`는 없어도 돼요. `/`로 시작하면 `--site` 주소에서 받아요):

```json
{"title": "도커 볼륨 날린 날", "handle": "minseo", "coverImage": "/uploads/cover.png",
 "scenes": [{"caption": "볼륨이 사라졌어요", "narration": "어제 도커 볼륨을 지웠더니 글이 전부 사라졌어요.", "code": null, "image": null, "visual": "shock"}],
 "effects": [{"scene": 0, "at": 1.2, "sound": "ding", "volume": 0.5}]}
```

## 매매 재현 영상 (#265)

서버가 준 작업에 `replay`가 있으면 그 구간의 공개 캔들을 받아(바이낸스 `api.binance.com`, 막히면 `data-api.binance.vision` / 업비트 `api.upbit.com`, API 키 없음) 캔들이 흘러가며 체결 시각에 매수·매도가 찍히는 차트를 그려요.
차트는 Remotion 맞춤 장면 `replay`(`remotion/src/Replay.tsx`)로 그리고, 끝 고지는 `replay-card` 장면이에요. 그래서 매매 재현 영상은 Remotion(Node 18 이상)이 있어야 만들어요. 서버가 정한 구간은 가능한 한 1분봉(480개까지, 넘으면 5분봉…)으로, 화면 왼쪽을 채울 앞 240개와 함께 받아요. 영상 첫 1.6초는 검은 연기 바탕의 제목 카드이고, 걷히면 거래소 화면을 녹화해 빨리감기한 것처럼 차트 한 화면(첫 체결~마지막 체결 폭의 1.1배, 80~300개 캔들)이 끝까지 끊김 없이 흘러요. 캔들 폭과 세로 배율은 영상 내내 고정이고, 지금 캔들은 화면 가운데에서 자라며 카메라가 지금 가격을 0.6초 늦게 부드럽게 따라가요(지금 가격은 늘 화면 위아래 15% 안쪽). 2번 장면부터 체결 하나에 장면 하나(말이 짧아도 4초 이상)이고, 장면이 나오고 1초 뒤에 그 체결이 찍혀요. 그 순간에만 아래 가운데에 작은 자막(진입 흰색 한 줄, 익절 초록·손절 빨강 두 줄 "( ±손익 | 누적 손익 )")과 오른쪽 위 체결 알림이 떠요. 마지막 체결 다음 장면부터는 차트가 멈추고 화면이 어둡게 눌리며 결산(손익 합계)이 떠요. 배경음은 `trade_bgm.py`가 합성한 136bpm 다크 트랩(두꺼운 808, 또렷한 박수, 셋잇단 하이햇, 중간 고조)이고, 제목 카드가 걷히면 들어와 결산 화면에서 작아져요(--bgm 파일이 있으면 그 파일). 효과음은 체결 때 작은 소리와 결산 때 익절·손절음뿐이고 `trade_sfx.py`가 직접 합성해요. 영상 맨 끝에는 투자 권유 고지 카드 한 장(1.3초)이 늘 붙어요. 캔들을 받지 못하면 서버에 실패를 알려 다시 맡겨요.
서버 없이 해 보려면 `script.json`의 `replay`에 `candles`(`[[시작 epoch초, 시가, 고가, 저가, 종가], …]`)를 넣으면 시세를 받지 않고 그대로 그려요.
`effects`는 장면이 화면에 나온 뒤 `at`초에 내장 효과음(pop·ding·whoosh·boing·tick·typing)을 얹어요. `samples/`의 견본 대본 3개로 품질을 비교해요. PR을 올리면 CI(`render-worker-ci`의 samples)가 신경망 음성으로 만든 견본 영상을 `shorts-samples`로 올려요.

## 테스트

```bash
python3 -m unittest discover -s tests -t . -v
```

ffmpeg가 있으면 실제로 영상을 만들고 가짜 서버와 주고받는 흐름까지 확인해요. 테스트는 기본으로 Pillow 무대로 그리고,
Remotion으로 실제 렌더까지 보려면 `DEVLOG_REMOTION_TEST=1`을 주세요. 화면 코드 타입 검사는 `cd remotion && npm run typecheck`.
