-- 집 컴퓨터에서 주인 계정에 이메일·비밀번호 로그인을 만든다 (#241). 소셜 로그인(GitHub·Google)은 집 컴퓨터 주소로 돌아올 수 없어서다.
-- restore 스크립트가 psql 변수로 부른다: handle(주인 주소), email, hash(bcrypt). 회원 한 명은 로그인 수단이 하나뿐이라 그 수단을 비밀번호 로그인으로 바꾼다.
-- 운영 DB가 아니라 집 컴퓨터에 되살린 DB에서만 돌린다.
\set ON_ERROR_STOP on
UPDATE auth_identity a
SET provider = 'LOCAL', provider_user_id = lower(:'email'), email = lower(:'email'), password_hash = :'hash',
    email_verified_at = coalesce(a.email_verified_at, now())
FROM member m
WHERE m.id = a.member_id AND m.handle = :'handle';
SELECT CASE WHEN count(*) = 1 THEN '주인 계정 비밀번호 로그인을 만들었어요: ' || max(a.email) ELSE '그 주소의 회원을 찾지 못했어요: ' || :'handle' END AS result
FROM auth_identity a JOIN member m ON m.id = a.member_id WHERE m.handle = :'handle' AND a.provider = 'LOCAL';
