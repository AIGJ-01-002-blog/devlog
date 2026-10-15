# 쇼츠 캐릭터

캐릭터 하나가 하위 폴더 하나예요. 폴더 이름이 캐릭터 이름이고, 그 안의 Lottie 파일(.json) 이름이 동작 이름이에요.
대본(job)에 `"character": "<폴더 이름>"`을 주면 고양이 대신 그 캐릭터가 연기해요. 없거나 모르는 이름이면 기본 고양이예요.
규격은 [쇼츠 캐릭터 제작 도면](https://claude.ai/code/artifact/e4a20502-3552-4e83-a3d2-8abeb955c8b0): 512×512, 투명 배경, 30fps, 발끝이 아래 가운데(256, 500).

| 파일 | 동작 |
|---|---|
| idle.json | 가만히 서서 숨쉬기(없으면 그 캐릭터를 쓰지 않음) |
| talk.json | 말하기. 목소리 크기에 맞춰 입 모양 프레임(0 닫힘·4 조금·8 크게)에 세워 립싱크해요 |
| point.json | 오른쪽 위를 가리키며 설명 |
| happy.json | 두 팔 들고 뛰기 |
| shock.json | 깜짝 놀라기(한 번 재생) |
| sad.json | 슬퍼하기 |
| think.json | 생각하기 |
| typing.json | 키보드 치기 |
| idea.json | 아이디어(한 번 재생) |
| sleepy.json | 졸기 |
| panic.json | 허둥대기 |
| walk.json | 걷기 |

없는 동작은 idle(말할 때는 talk)로 대신해요.

## 출처와 라이선스

| 폴더 | 이름 | 만든 사람 | 라이선스 |
|---|---|---|---|
| lognyang/ | 로그냥(후드 입은 고양이) | 민서님이 도면대로 만들어 준 패키지 `devlog-lognyang-v1.0.0` | 패키지의 LICENSE.txt: 새로 만든 벡터 그림, 상업 사용·수정·배포 가능, 추가 표기 의무 없음 |
| logduck/ | 로그덕(스카프 맨 오리) | 민서님이 도면대로 만들어 준 패키지 `devlog-logduck-v1.0.0` | 같음 |

각 폴더의 LICENSE.txt가 패키지에 들어 있던 원문이에요. 미리보기용 lottie-web 플레이어는 가져오지 않았어요(재생은 @remotion/lottie).
