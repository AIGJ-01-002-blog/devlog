-- 쇼츠 캐릭터 고르기 (#304, 목표 v2.2.0 #282).
-- 대본마다 연기할 캐릭터를 고른다: 로그냥(lognyang)·로그덕(logduck)·두부(dubu)·Noto 고양이(cat). 비우면 렌더 워커 기본값(로그냥).
-- character는 SQL 예약어라 칸 이름은 mascot으로 둔다(API와 워커 작업에서는 character).
ALTER TABLE shorts ADD COLUMN mascot varchar(20);

COMMENT ON COLUMN shorts.mascot IS '연기할 캐릭터 이름(lognyang·logduck·dubu·cat). 비면 워커 기본 캐릭터';
