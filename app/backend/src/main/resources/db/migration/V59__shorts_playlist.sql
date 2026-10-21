-- 쇼츠 묶음별 재생목록 (2026-10-21 블로그 주인 결정 "하루 1개, 7주"): 면접 준비생이 몰아보게 배포·DB·보안·성능·AI협업 다섯 묶음으로 나눈다.
-- 재생목록은 회원이 YouTube에서 직접 만들고, 여기에는 이 쇼츠를 넣을 묶음만 남긴다. 비우면 아직 안 고른 것.
ALTER TABLE shorts ADD COLUMN playlist varchar(12)
    CONSTRAINT ck_shorts_playlist CHECK (playlist IN ('DEPLOY', 'DB', 'SECURITY', 'PERFORMANCE', 'AI'));
COMMENT ON COLUMN shorts.playlist IS '넣을 재생목록 묶음 (DEPLOY 배포, DB, SECURITY 보안, PERFORMANCE 성능, AI AI협업)';
