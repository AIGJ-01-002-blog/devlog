# 집 컴퓨터로 옮기기 (백업과 되살리기)

학교 서버와 지금 쓰는 맥북을 반납하기 전에, devlog의 DB와 파일 저장소(사진·첨부·쇼츠 영상)를 폴더 하나로 받아 외장하드에 두고, 나중에 집 PC(Windows)나 맥에서 Docker로 다시 띄우는 순서예요 (#241).

되살린 블로그는 그 컴퓨터에서만 열리는 개인용이에요(`http://localhost:8080`). 글·사진·쇼츠·대시보드·MCP 기록을 그대로 쓰고 새로 쓸 수 있지만, 메일 보내기·소셜 로그인·다른 사람의 접속은 없어요.

| 파일 | 하는 일 |
|---|---|
| `fetch-backup.sh` | 집 맥에서 실행. 학교 서버에 SSH로 들어가 `backup.sh`를 돌리고 폴더를 내려받아 확인한 뒤 서버 사본을 지운다 |
| `setup-backup.ps1` | 집 PC(Windows)에서 한 번만 실행. 백업 전용 SSH 키를 만들어 학교 서버에 묶고, 작업 스케줄러에 매일 받기를 건다 |
| `pull-backup.ps1` | 작업 스케줄러가 켤 때와 매일 한 번 실행. 백업을 받아 확인하고 최근 7개만 남긴다 |
| `backup-stream.sh` | 학교 서버에서 백업 전용 키로만 돈다. `backup.sh`로 만든 폴더를 tar로 보내고 서버 사본을 지운다 |
| `backup.sh` | 클러스터에 `kubectl`이 닿는 곳에서 실행. `db.dump`(pg_dump), `media/`(MinIO 버킷 전체), `MANIFEST.txt`(크기·개수·SHA-256)를 만든다 |
| `restore.sh` / `restore.ps1` | 맥·리눅스 / Windows에서 실행. DB를 넣고, 옛 주소를 이 컴퓨터 주소로 바꾸고, 주인 계정 비밀번호 로그인을 만들고, 앱을 띄운다 |
| `compose.yaml` | 집에서 띄우는 구성: PostgreSQL 17(pgvector)·Redis·앱. 사진은 백업 폴더의 `media/`를 그대로 쓴다 |
| `rebase-urls.sql` | `https://devlog.life/blog-images/…` → `http://localhost:8080/media/…`, `https://devlog.life/…` → `http://localhost:8080/…` |
| `owner-login.sql` | 주인 계정의 로그인 수단을 이메일·비밀번호로 바꾼다(되살린 DB에서만) |

## 0. 매일 집 PC로 백업 받기 (#356)

학교 서버에 있는 DB와 사진·첨부(MinIO)를 집 PC가 매일 한 번 받아 와요. 집 PC가 학교 서버에 접속해서 가져오는 방식이라 집 공유기에 포트를 열 필요가 없어요. PC가 꺼져 있던 날은 켜고 5분 뒤에 따라잡아요.

필요한 것: 집 PC의 `D:\devlog`(이 저장소, `git pull`로 최신), 학교 서버 SSH 주소·포트·아이디·비밀번호(설정할 때 한 번만 물어요).

```powershell
cd D:\devlog
git pull
powershell -ExecutionPolicy Bypass -File deploy\home\setup-backup.ps1 -Target <아이디>@<학교 서버 주소> -Port <SSH 포트>
```

- 처음 붙는 서버라 SSH 키 지문을 저장할지 묻지 않고 저장해요. 화면에 찍힌 지문을 한 번 봐 두세요.
- 비밀번호를 한 번 묻고, 시험으로 한 번 받아요. 끝나면 `D:\devlog-backups\devlog-backup-날짜-시각\`이 생겨요.
- 작업 스케줄러에 "devlog 백업 받기"가 생겨요(로그인하고 5분 뒤, 매일 21:00). 같은 날 이미 받았으면 그냥 넘어가요.
- 받은 백업은 `db.dump`의 SHA-256과 사진 수를 서버 목록과 맞춰 본 뒤에만 남기고, 최근 7개만 둬요. 기록은 `D:\devlog-backups\backup.log`예요.
- 이틀 넘게 못 받으면 창으로 알려 줘요.
- 다른 곳·개수·시각: `-Dest E:\devlog-backups -Keep 14 -Time 13:00`. 지금 바로 받기: `powershell -ExecutionPolicy Bypass -File deploy\home\pull-backup.ps1 -Now`.
- 이 키로는 서버에서 백업 말고 아무것도 할 수 없어요(`restrict,command=`). 키는 `%USERPROFILE%\.devlog-backup`에만 있어요. 그만두려면 학교 서버 `~/.ssh/authorized_keys`에서 `devlog-home-backup` 줄을 지우고 작업 스케줄러에서 작업을 지워요.
- 받은 폴더는 아래 "2. 집 컴퓨터에서 되살리기"에 그대로 넣을 수 있어요.

## 1. 백업 받기 (반납 전, 11월 마지막 주 권장)

필요한 것: 학교 서버 SSH 주소·포트·아이디·비밀번호, 외장하드(백업 크기는 `MANIFEST.txt`에 나와요).

```bash
git clone https://github.com/AIGJ-01-002-blog/devlog.git && cd devlog
deploy/home/fetch-backup.sh <아이디>@<학교 서버 주소> <SSH 포트> /Volumes/<외장하드>/devlog
```

- SSH 비밀번호를 서너 번 물어요(백업 만들기, 내려받기, 서버 사본 지우기). 스크립트는 비밀번호를 받거나 저장하지 않아요.
- 끝나면 `devlog-backup-날짜-시각/` 폴더가 생기고, `db.dump`의 SHA-256과 파일 개수가 서버 것과 같은지 확인한 뒤 끝나요.
- 블로그를 계속 쓰는 동안이라면 마지막 날에 한 번 더 받으세요. 폴더가 날짜별로 따로 생겨요.
- 서버에서 직접 돌릴 때: `KUBECONFIG=~/devlog/kubeconfig.yaml deploy/home/backup.sh ~/devlog-backup` 후 `rsync`나 `scp`로 가져와요.

## 2. 집 컴퓨터에서 되살리기

필요한 것: [Docker Desktop](https://www.docker.com/products/docker-desktop/), 이 저장소(git clone), 외장하드의 백업 폴더. 처음 한 번은 앱을 빌드하느라 인터넷이 필요하고 몇 분 걸려요.

맥:

```bash
deploy/home/restore.sh /Volumes/<외장하드>/devlog/devlog-backup-20261130-2100
```

Windows (PowerShell):

```powershell
powershell -ExecutionPolicy Bypass -File deploy\home\restore.ps1 -Backup E:\devlog\devlog-backup-20261130-2100
```

중간에 주인 계정을 물어요. 내 블로그 주소(`@` 뒤, 예: `kim755030`), 로그인할 이메일, 새 비밀번호(8자 이상)를 넣으면 집에서는 그 이메일·비밀번호로 로그인해요. GitHub·Google 로그인은 집 주소로 돌아올 수 없어서 바꿔 두는 거예요. 운영 서버의 계정은 건드리지 않아요.

끝나면 `http://localhost:8080`을 열어요.

| 할 일 | 명령 |
|---|---|
| 끄기 (데이터는 남음) | `docker compose -f deploy/home/compose.yaml down` |
| 다시 켜기 | `docker compose -f deploy/home/compose.yaml --env-file deploy/home/.env up -d` |
| 처음부터 다시 넣기 | restore 명령 끝에 `--force`(Windows는 `-Force`) |
| 새 버전으로 올리기 | `git pull` 뒤 `docker compose -f deploy/home/compose.yaml --env-file deploy/home/.env up -d --build` |

- 새로 올린 사진·영상은 외장하드의 `media/`에 바로 저장돼요. 외장하드를 빼기 전에 끄세요.
- 리눅스에서 띄우면 앱이 사용자 번호 10001로 돌아서 `sudo chown -R 10001 <백업 폴더>/media`가 필요해요. 맥·Windows의 Docker Desktop은 필요 없어요.
- DB는 Docker 볼륨(`devlog-home_pgdata`)에 있어요. 집에서 쓴 내용까지 외장하드에 남기려면 가끔 `docker compose -f deploy/home/compose.yaml exec postgres pg_dump -U blog -d blog -Fc > <백업 폴더>/db-home.dump`로 받아 두세요.
- 쇼츠 영상 만들기를 집에서도 쓰려면 `deploy/home/.env`에 `SHORTS_ENABLED=true`와 `SHORTS_WORKER_TOKEN=<직접 정한 긴 값>`을 더하고, 렌더 워커(`app/render-worker`)를 `http://localhost:8080`과 같은 값으로 띄워요.
- 인터넷 없이 다시 설치해야 할 때를 대비하려면 앱 이미지를 파일로 남겨 두세요: `docker save devlog-home:latest -o <외장하드>/devlog-app.tar` (불러오기: `docker load -i …`).

## 무엇이 바뀌어 들어가나

- 글 본문·사진 주소·프로필 사진처럼 주소가 들어간 모든 글자 열에서 `https://devlog.life/blog-images/`를 `http://localhost:8080/media/`로, 그다음 `https://devlog.life/`를 `http://localhost:8080/`로 바꿔요. 바꾼 행 수를 표와 열별로 보여 줘요.
- 앱은 옛 사진 주소(`https://devlog.life/blog-images`)도 우리 사진으로 알아봐요(`BLOG_IMAGE_LEGACY_BASE_URLS`). 주소가 다르면 restore를 실행하기 전에 `OLD_SITE_URL`·`OLD_IMAGE_BASE_URL`(Windows는 `-OldSite`·`-OldMedia`)로 알려 주세요.
- 의미 검색용 임베딩 모델은 띄우지 않아 키워드 검색만 써요.
