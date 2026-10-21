-- 107 외부 회원 글 검수: 외부 회원 가입이 열려 있어, 운영진(관리자·매니저)이 아닌 회원의 글은 운영진이 검수해 허락한 뒤에만
-- 검색 수집·사이트맵에 넣는다. 글은 그대로 공개된다. 운영진 글은 이 열과 상관없이 지금처럼 수집 기준(일기 아님·본문 1,000자 이상)만 본다.
ALTER TABLE post ADD COLUMN index_approved_at timestamptz NULL;

COMMENT ON COLUMN post.index_approved_at IS '운영진이 검색 수집을 허락한 시각 (spec 107). NULL이면 외부 회원 글은 noindex, 사이트맵 제외';
