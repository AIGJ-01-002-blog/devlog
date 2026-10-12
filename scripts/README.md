# 설계 검증 스크립트

문서(문서 저장소 [AIGJ-01-002-blog/docs](https://github.com/AIGJ-01-002-blog/docs)의 `design/`)에 적힌 SQL·Lua·규칙이 **실제로 문서대로 동작하는지** 확인한다.
SQL과 Lua는 스크립트에 복사해 두지 않고 **문서에서 직접 꺼내 실행**하므로, 문서를 고치면 검증도 고친 내용으로 돈다.

```bash
git clone https://github.com/AIGJ-01-002-blog/docs ../docs   # 처음 한 번, 코드 저장소 옆에
scripts/check-all.sh        # 전부 실행하고 요약
```

| 스크립트 | 확인하는 것 | 읽는 문서 | 필요한 것 | 시간 |
|---|---|---|---|---|
| `check-ddl.sh` | 통합 ERD(V1) 적용, 제약조건이 문서 규칙대로 허용/거부, 06 친구 공개(FRIENDS) 규격 (모두 56개), 담당자 문서의 "ERD 변경 제안" 중 V1에 없는 SQL 적용 | erd/V1, 06, 20~49 | Docker | 약 10초 |
| `check-redis.sh` | 자동 저장 버전 확인 Lua, 발행 후 조건부 삭제 Lua, 조회수 중복 판정 Lua | 04, 05, 31 | Docker | 약 5초 |
| `check-concurrency.sh` | 동시 좋아요·취소에서 수 = 행, 동시 발행 1번 | erd/V1, 30 | Docker | 약 15초 |
| `sanitize/run.sh` | 본문 정화 파이프라인: XSS 32개, 정상 문법, 부하 제한, 제목 정리 (61개) | 12 (참고 구현) | Java 21 | 약 5초 |
| `proto/test_rules.py` | 블로그 주소 생성, 닉네임 검사, AI 캐시 판정 (57개) | 08, 09, 34 (참고 구현) | Python 3 | 1초 |
| `check-storage.sh` | 파일 저장소(MinIO): Presigned PUT 검증 7가지(23 §2-3) + 익명 쓰기·경로 제한·CORS 4가지 (11개) | 04, 23 | Docker | 약 30초 |
| `bench-search/run.sh` | 글 10만 개에서 단계별 관련도·2글자 규칙·이스케이프·속도 (p95 500ms) | erd/V1, 33 | Docker | 약 35초 |

## 규칙

- **해당 문서가 없으면 그 항목은 건너뛴다** (예: 31이 merge되기 전에는 조회수 Lua를 건너뜀).
- 임시 컨테이너 이름은 `teamblog-check-*`이고, 끝나면 **볼륨까지 함께 지운다.**
- 다른 문서 폴더로 돌리려면 `DOCS=/경로 scripts/check-ddl.sh`.
- `sanitize/Pipeline.java`, `proto/*.py`, `bench-search/search.py`는 문서를 읽지 않는 **참고 구현**이다. 12·08·09·33·34 문서의 규칙을 바꾸면 이 파일들도 함께 바꾼다.

## 담당자 문서에 SQL을 쓸 때

**기준 스키마는 `erd/V1__common_schema.sql`이다 (2026-10-07).** `check-ddl.sh`는 V1을 먼저 적용하고, 담당자 문서(20~49)의 **`## ERD 변경 제안` 절 안의 ` ```sql ` 블록**을 그 위에 적용한다. 그래서 새 제안은 V1 위에서 도는 `ALTER`·`CREATE`로 쓴다. V1에 이미 들어간 블록은 첫 줄을 `-- V1 반영됨`으로 시작하면 건너뛴다. 다른 절에 둔 SQL은 검증되지 않는다.

## 이미지

`postgres:18`, `redis:7-alpine`, 저장소 검증은 `pgsty/silo`·`pgsty/mc`(MinIO 커뮤니티 포크, 04 §6-1)·`python:3.12-slim`을 쓴다 (없으면 자동으로 받음). 정화 파이프라인의 라이브러리 jar 8개(약 1MB)는 `sanitize/lib/`에 받으며 커밋하지 않는다.

## README 검사

```bash
python3 scripts/check-readmes.py      # 언어별 README(한·영·일·중)가 서로, 그리고 저장소와 맞는지
python3 scripts/build-org-profile.py  # profile/README.md로 조직 프로필 README를 build/에 만든다
```

`check-readmes.py`는 README.md(한국어)를 기준으로 번역판의 제목·표·목록·그림 수, 릴리스 표 버전, CHANGELOG 맨 위 버전, Flyway 범위(V1~Vn), 기술 스택 버전, 상대 링크와 앵커를 대조한다. PR에서 `README 검사` 워크플로가 같은 검사를 돌린다. 그래서 CHANGELOG에 새 버전을 올리는 PR은 네 README의 릴리스 표에도 그 행을 함께 넣어야 통과한다.
