-- MCP 관리자 도구는 관리자 권한을 따로 켠 토큰으로만 (보안 점검 2026-10-16, #378).
-- 전에는 관리자 회원의 토큰이면 어느 토큰이나 문의 관리 도구가 보였다. 옛 토큰은 모두 꺼진 채로 시작한다.
ALTER TABLE personal_access_token ADD COLUMN admin_tools boolean NOT NULL DEFAULT false;
