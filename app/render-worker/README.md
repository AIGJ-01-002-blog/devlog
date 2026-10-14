# devlog 쇼츠 렌더 워커

devlog 쇼츠 스튜디오(spec 099)에서 [영상 만들기]를 누른 대본을 받아 **9:16 MP4(1080×1920)** 로 만들어 다시 올리는 작은 프로그램이에요.
맥·Windows·리눅스 어디서든 돌고, 서버로 **나가는 요청만** 하니 집 공유기 뒤에서도 동작해요. 영상을 SNS에 자동으로 올리지는 않아요.

- 음성: 비용 없는 기기 안 음성만 써요. 맥은 `say`(Yuna), Windows는 기본 한국어 음성(Heami), 리눅스는 `espeak-ng`, 없으면 무음
- 글꼴: 맥 Apple SD Gothic Neo, Windows 맑은 고딕, 리눅스 Noto Sans CJK (바꾸려면 `--font`)
- 필요한 것: Python 3.10 이상, ffmpeg (추가 패키지 없음)

## 설치

| 운영체제 | ffmpeg 설치 |
|---|---|
| macOS | `brew install ffmpeg` |
| Windows | `winget install Gyan.FFmpeg` 후 새 터미널 열기 |
| Ubuntu | `sudo apt install ffmpeg fonts-noto-cjk` |

```bash
cd app/render-worker
python3 -m devlog_render check      # ffmpeg·글꼴·음성 확인 (Windows는 python)
```

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

`script.json` 모양:

```json
{"title": "도커 볼륨 날린 날", "handle": "minseo",
 "scenes": [{"caption": "볼륨이 사라졌어요", "narration": "어제 도커 볼륨을 지웠더니 글이 전부 사라졌어요.", "code": null}]}
```

## 테스트

```bash
python3 -m unittest discover -s tests -t . -v
```

ffmpeg가 있으면 실제로 영상을 만들고 가짜 서버와 주고받는 흐름까지 확인해요.
