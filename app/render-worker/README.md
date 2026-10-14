# devlog 쇼츠 렌더 워커

devlog 쇼츠 스튜디오(spec 099)에서 [영상 만들기]를 누른 대본을 받아 **9:16 MP4(1080×1920)** 로 만들어 다시 올리는 작은 프로그램이에요.
맥·Windows·리눅스 어디서든 돌고, 서버로 **나가는 요청만** 하니 집 공유기 뒤에서도 동작해요. 영상을 SNS에 자동으로 올리지는 않아요.

- 음성: 무료 신경망 한국어 음성(edge-tts `ko-KR-SunHiNeural`)이 기본이에요. `--voice ko-KR-InJoonNeural`처럼 바꿀 수 있어요.
  인터넷이 막혔거나 실패하면 영상 전체를 기기 음성으로 다시 만들어요: 맥 `say`(Yuna), Windows 기본 한국어 음성(Heami), 리눅스 `espeak-ng`, 없으면 무음
- 자막: 내레이션을 1~2줄 구절로 나눠 음성에 맞춰 한 구절씩 띄우고, 지금 읽는 단어를 노랗게 키워요(신경망 음성은 단어마다 소리 나는 시각을 받아 맞춰요). 요점 자막은 단어마다 튀어 오르고 형광펜이 그어져요
- 화면 도구: [Remotion](https://www.remotion.dev)(React로 영상을 만드는 도구)으로 그려요. `remotion/` 폴더가 화면(캐릭터·자막·전환)을, 파이썬 워커가 음성·시간·소리 섞기를 맡아요.
  Node가 없으면 예전처럼 Pillow로 그린 무대로, Pillow도 없으면 글자 화면으로 내려가요
- 캐릭터: 크림색 2D 고양이(SVG)가 장면마다 상황을 연기해요. 말할 때 소리 크기에 맞춰 입을 움직이고, 숨 쉬고, 눈을 깜빡여요.
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
- 배경음: 워커가 직접 합성한 잔잔한 반복 음악(저작권 걱정 없음)을 깔고 말할 때 소리를 낮춰요. 다른 음악은 `--bgm 파일`, 끄려면 `--bgm none`
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
차트는 Remotion 맞춤 장면 `replay`(`remotion/src/Replay.tsx`)로 그리고, 끝 고지는 `replay-card` 장면이에요. 그래서 매매 재현 영상은 Remotion(Node 18 이상)이 있어야 만들어요. 1번 장면은 체결 전 흐름, 2번 장면부터 체결 하나에 장면 하나씩 표시가 찍히고, 남은 장면에서 마지막 캔들까지 흘러가요. 체결 순간에는 번쩍임·고리·밀려 들어오는 띠·흔들림과 매수·매도 효과음이, 결과 장면에는 손익 합계와 익절·손절음이 나와요. 효과음은 `trade_sfx.py`가 직접 합성해요. 영상 맨 끝에는 투자 권유 고지 카드 한 장(1.3초)이 늘 붙어요. 캔들을 받지 못하면 서버에 실패를 알려 다시 맡겨요.
서버 없이 해 보려면 `script.json`의 `replay`에 `candles`(`[[시작 epoch초, 시가, 고가, 저가, 종가], …]`)를 넣으면 시세를 받지 않고 그대로 그려요.
`effects`는 장면이 화면에 나온 뒤 `at`초에 내장 효과음(pop·ding·whoosh·boing·tick·typing)을 얹어요. `samples/`의 견본 대본 3개로 품질을 비교해요. PR을 올리면 CI(`render-worker-ci`의 samples)가 신경망 음성으로 만든 견본 영상을 `shorts-samples`로 올려요.

## 테스트

```bash
python3 -m unittest discover -s tests -t . -v
```

ffmpeg가 있으면 실제로 영상을 만들고 가짜 서버와 주고받는 흐름까지 확인해요. 테스트는 기본으로 Pillow 무대로 그리고,
Remotion으로 실제 렌더까지 보려면 `DEVLOG_REMOTION_TEST=1`을 주세요. 화면 코드 타입 검사는 `cd remotion && npm run typecheck`.
