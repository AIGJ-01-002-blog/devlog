# Feature Specification: develop 시험 환경 자동 배포

**Feature Branch**: `feature/092-staging`
**Created**: 2026-10-14
**Status**: Implemented (develop), 켜기는 민서님 Cloudflare 설정 뒤
**근거**: 목표 v1.68.0(#197)의 할 일 #170. 2026-10-12 강사님 피드백 "develop에 합치고 정상 동작을 확인한 뒤 배포"의 나머지 절반.

## 사용자 시나리오

1. **Given** 저장소 변수 `STAGING_ENABLED=true`, **When** PR이 develop에 머지되면, **Then** 백엔드·화면 테스트와 이미지 빌드가 통과한 그 커밋이 시험 환경(dev.devlog.life)에 배포되고, 운영(devlog.life)은 바뀌지 않는다.
2. **Given** 시험 환경, **When** 글을 쓰거나 회원을 만들면, **Then** 운영 DB·Redis·사진 저장소에는 아무것도 남지 않는다.
3. **Given** Cloudflare Access 이메일 정책, **When** 허용되지 않은 사람이 dev.devlog.life를 열면, **Then** Cloudflare 로그인 화면에서 막힌다.
4. **Given** 새 이미지가 준비(readiness)되지 않으면, **Then** 시험 환경은 직전 버전으로 돌아가고 운영 배포 줄도 막히지 않는다.

## Requirements

- **FR-001**: 블로그 배포 워크플로는 develop 푸시에서 백엔드·화면 테스트와 이미지 빌드를 거친 뒤 `deploy-staging` 잡으로 네임스페이스 `blog-dev`에 배포한다. 변수 `STAGING_ENABLED`가 `true`일 때만 돈다. 수동 실행의 "시험 환경에 배포"도 같은 변수가 `true`일 때만 돈다(Access 없이 개발용 로그인이 열리지 않게).
- **FR-002**: 운영 배포 잡(main 푸시, 수동 실행 `deploy`)의 조건과 단계는 바꾸지 않는다. 학교 서버 SSH 터널은 두 잡이 같은 composite action(`.github/actions/school-tunnel`)을 쓴다.
- **FR-003**: 시험 환경은 `deploy/k8s/overlays/staging`으로 PostgreSQL·Redis·MinIO를 `blog-dev`에 따로 띄운다. 앱 1개(메모리 요청 384Mi), 디스크 2Gi, 검색 임베딩 모델·매일 DB 백업·PDB는 뺀다.
- **FR-004**: 시험 환경 비밀값은 운영 값을 쓰지 않는다. 첫 배포 때 `gen-secret-env.sh staging`으로 임의 값을 만들어 클러스터 Secret `blog-dev-seed`에 두고, 다음 배포부터 그 값을 다시 쓴다.
- **FR-005**: 이미지 태그 `latest`는 main만 가리킨다. develop 빌드는 `develop`과 커밋 SHA 태그를 단다.
- **FR-006**: 운영 배포와 시험 환경 배포는 같은 동시성 그룹(`deploy-cluster`)으로 차례로 돈다.
- **FR-007**: 매니페스트 검증 잡이 staging overlay도 빌드·스키마 검사한다.
- **FR-008**: 시험 환경은 개발용 로그인(`DEV_LOGIN_ENABLED`)을 켜지 않는다. 로그인은 이메일 가입·로그인으로 한다. 매니페스트 검증 잡이 local 밖 overlay에서 이 값이 켜져 있으면 실패한다(2026-10-15 보안 점검, 민서님 결정).
- **FR-009**: 시험 환경은 관리자 승격(`OWNER_HANDLE`)을 비워 둔다. 인증번호 없는 이메일 가입이라 블로그 주소로는 운영자를 가려낼 수 없기 때문이다.

## 고른 것과 이유 (질문 없이 진행)

- **같은 클러스터, 다른 네임스페이스**: 학교 서버 하나뿐이라 클러스터를 더 띄우면 k3d 자체 메모리(약 1.6GB)가 또 든다. 네임스페이스로 나누면 앱·DB 몫(약 0.9GB)만 더 든다. 검색 임베딩 모델을 쓸 때만 올리게 바꾸며(#198) 돌려받은 약 3GB 안에 들어간다.
- **운영 열쇠를 넣지 않음**: OAuth 앱은 운영 주소로만 등록돼 있고, 메일·AI·텔레그램 열쇠가 시험 환경에서 쓰이면 운영 한도와 알림이 섞인다. 로그인은 이메일 가입으로 하고, Access로 막은 뒤에만 연다. 처음에는 개발용 로그인도 켰지만, 비밀번호 없이 관리자까지 들어갈 수 있고 막는 것이 Access 하나뿐이라 2026-10-15에 껐다(민서님 결정).
- **변수로 켜기**: Access와 Tunnel 주소는 Cloudflare 화면에서 민서님만 만들 수 있다. 그 전에 develop 머지가 시험 환경을 띄우면 막지 않은 개발용 로그인이 열린다. 그래서 기본은 꺼짐이다.
