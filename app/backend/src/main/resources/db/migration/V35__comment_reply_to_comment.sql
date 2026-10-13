-- 대댓글을 Git 브랜치처럼 잇기 (spec 011 FR-011a, 민서님 요청 2026-10-13).
-- 저장 구조는 그대로 깊이 1이다(parent_id는 늘 최상위). 화면에서 답글이 어느 답글에서 갈라졌는지 선으로 잇도록
-- 답한 답글 번호만 따로 둔다. 그 답글 행이 지워지면 비워지고, 화면은 최상위에서 갈라진 것으로 그린다.
ALTER TABLE comment
    ADD COLUMN reply_to_comment_id bigint NULL,
    ADD CONSTRAINT fk_comment_reply_to_comment FOREIGN KEY (reply_to_comment_id) REFERENCES comment (id) ON DELETE SET NULL,
    ADD CONSTRAINT ck_comment_reply_to_comment CHECK (reply_to_comment_id IS NULL OR parent_id IS NOT NULL);

CREATE INDEX ix_comment_reply_to_comment ON comment (reply_to_comment_id) WHERE reply_to_comment_id IS NOT NULL;

COMMENT ON COLUMN comment.reply_to_comment_id IS '답한 답글 번호 (답글에 답했을 때만, 화면의 가지 선용)';
