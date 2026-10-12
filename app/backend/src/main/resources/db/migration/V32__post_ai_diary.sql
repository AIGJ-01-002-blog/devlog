-- 088 AI 일기를 검색 수집에서 뺀다. 일기 글은 블로그에 그대로 보이고, 검색 엔진 수집(noindex)·사이트맵·광고에서만 빠진다.
ALTER TABLE post ADD COLUMN ai_diary boolean NOT NULL DEFAULT false;

COMMENT ON COLUMN post.ai_diary IS 'AI 일기로 만든 글 (true면 noindex, 사이트맵·광고 제외). 만들 때 정하고 바꾸지 않는다';

-- 이미 만든 일기: 자정 작업이 붙이는 제목("2026-10-11 개발 일기")으로 찾는다. 사람이 같은 제목을 썼어도 수집에서만 빠진다
UPDATE post SET ai_diary = true WHERE title ~ '^[0-9]{4}-[0-9]{2}-[0-9]{2} 개발 일기$';
