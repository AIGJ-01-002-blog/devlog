-- 107 AI 발행 투명성과 주간 회고.
-- ai_assisted: MCP(AI 도구)로 만들거나 고치거나 발행한 글. 글 아래에 "AI 보조 작성"을 보여 준다. 수집 여부와는 상관없다.
ALTER TABLE post ADD COLUMN ai_assisted boolean NOT NULL DEFAULT false;
-- retro_week: 매주 일요일에 그 주 AI 일기를 묶어 만든 주간 회고 초안이면 그 주 월요일(KST). 사람이 도입·배운 점을 써야 발행된다.
ALTER TABLE post ADD COLUMN retro_week date NULL;

COMMENT ON COLUMN post.ai_assisted IS 'AI 도구(MCP)로 만들거나 고치거나 발행한 글 (spec 107). 글 아래 "AI 보조 작성" 표기';
COMMENT ON COLUMN post.retro_week IS '주간 회고 초안이면 그 주 월요일 (spec 107). 한 사람에 한 주 하나';

CREATE UNIQUE INDEX uq_post_retro_week ON post (author_id, retro_week) WHERE retro_week IS NOT NULL AND deleted_at IS NULL;

-- 이미 있는 글: 기록으로 확실히 알 수 있는 것만 표시한다. AI 일기, AI 글감 제안으로 만든 글,
-- MCP가 본문 끝에 붙이는 "## 근거" 목록이 있는 글(#240). 그 밖의 MCP 글은 표시가 빠질 수 있다(운영자가 확인).
UPDATE post p SET ai_assisted = true
WHERE p.ai_diary
   OR EXISTS (SELECT 1 FROM ai_post_proposal pr WHERE pr.post_id = p.id)
   OR p.content_md ~ E'\\n## 근거\\n';
