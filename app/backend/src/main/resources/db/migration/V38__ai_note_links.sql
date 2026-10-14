-- #240: AI가 메모와 함께 남기는 근거 링크(커밋·PR·이슈 주소). 일기로 묶을 때 주제 문단 아래 "근거:" 줄로 붙인다.
ALTER TABLE ai_note ADD COLUMN links varchar(1000) NOT NULL DEFAULT '';

COMMENT ON COLUMN ai_note.links IS '근거 링크 (https 주소를 줄바꿈으로 이은 값, 3개까지)';
