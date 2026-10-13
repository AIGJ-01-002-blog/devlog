-- 네이버 로그인 (spec 096): 로그인 수단에 NAVER, 블로그 주소에 nv- 접두어를 더한다.
-- 이메일 가입 주소가 nv_로 시작하지 못하게 하는 규칙은 카카오(V30)·Facebook(V31)처럼 애플리케이션(HandlePolicy)에서만 막는다.
ALTER TABLE auth_identity DROP CONSTRAINT ck_auth_provider;
ALTER TABLE auth_identity ADD CONSTRAINT ck_auth_provider CHECK (provider IN ('LOCAL', 'GITHUB', 'GOOGLE', 'KAKAO', 'FACEBOOK', 'NAVER'));

ALTER TABLE member DROP CONSTRAINT ck_member_handle;
ALTER TABLE member ADD CONSTRAINT ck_member_handle
    CHECK (handle ~ '^((go|gi|ka|fb|nv)-)?[a-z0-9][a-z0-9_]{1,18}[a-z0-9]$' AND handle !~ '^(go|gi)_');
