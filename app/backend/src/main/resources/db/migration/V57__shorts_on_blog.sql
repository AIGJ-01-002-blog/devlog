-- 103 쇼츠 채널을 블로그에 잇기: 직접 올린 영상 주소를 남긴 쇼츠를 블로그·글 화면에 보일지 회원이 쇼츠마다 고른다.
-- 남긴 주소는 공개로 올린 영상이라(099 FR-013 순서 5) 기본은 보인다.
ALTER TABLE shorts ADD COLUMN on_blog boolean NOT NULL DEFAULT true;
COMMENT ON COLUMN shorts.on_blog IS '남긴 영상을 블로그·글 화면에 보일지 (spec 103)';
-- 글 상세가 글마다 "이 글의 쇼츠"를 찾는다
CREATE INDEX ix_shorts_post ON shorts (post_id) WHERE post_id IS NOT NULL;
