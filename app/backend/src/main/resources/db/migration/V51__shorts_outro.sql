-- 쇼츠 끝 블로그 광고 켜고 끄기 (블로그 주인 요청 2026-10-16).
-- 렌더 워커가 모든 영상 끝에 3초 devlog 광고를 붙인다. 대본마다 끌 수 있고, 기존 대본과 새 대본은 켜진 채로 시작한다.
ALTER TABLE shorts ADD COLUMN outro boolean NOT NULL DEFAULT true;

COMMENT ON COLUMN shorts.outro IS '영상 끝 3초 블로그 광고를 붙일지. 기본 true';
