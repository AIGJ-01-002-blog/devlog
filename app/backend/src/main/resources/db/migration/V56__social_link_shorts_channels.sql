-- 103 쇼츠 채널을 블로그에 잇기: 소셜 정보 종류에 YouTube·Instagram·TikTok 채널을 더한다.
-- 값은 정리한 형태로 둔다: YouTube는 youtube.com/ 뒤 경로(@핸들 또는 channel/UC…), Instagram·TikTok은 아이디만.
ALTER TABLE member_social_link DROP CONSTRAINT ck_member_social_link_kind;
ALTER TABLE member_social_link ADD CONSTRAINT ck_member_social_link_kind
    CHECK (kind IN ('EMAIL', 'GITHUB', 'X', 'FACEBOOK', 'HOMEPAGE', 'YOUTUBE', 'INSTAGRAM', 'TIKTOK'));
COMMENT ON COLUMN member_social_link.kind IS '종류 (EMAIL, GITHUB, X, FACEBOOK, HOMEPAGE, YOUTUBE, INSTAGRAM, TIKTOK)';
COMMENT ON COLUMN member_social_link.value IS '정리한 값 (이메일 주소, 아이디, 홈페이지 주소, YouTube는 @핸들 또는 channel/채널ID)';
