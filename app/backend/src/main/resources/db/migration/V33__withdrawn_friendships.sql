-- 탈퇴 신청한 회원의 친구 관계는 신청 즉시 지운다(처리방침과 맞춤). 이전에는 30일 정리 때 지웠으므로, 이미 탈퇴 신청한 회원의 남은 관계를 한 번 지운다.
DELETE FROM friendship f
WHERE EXISTS (SELECT 1 FROM member m WHERE m.status = 'WITHDRAWN' AND m.id IN (f.member_a_id, f.member_b_id));
