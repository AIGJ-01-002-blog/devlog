-- 쇼츠 주제 묶음 (#276, 목표 v2.1.0 #253).
-- 글 하나나 MCP로 들어온 맥락 하나를 작은 주제 여러 개로 나눠, 주제마다 쇼츠 한 편씩 만든다.
-- 근거 본문(source_text)은 양산형 검사가 "원문을 그대로 읽는지" 비교할 대상이다.
CREATE TABLE shorts_batch (
    id           bigserial PRIMARY KEY,
    member_id    bigint NOT NULL,
    post_id      bigint,
    title        varchar(60) NOT NULL,
    source_text  text NOT NULL DEFAULT '',
    topics       jsonb NOT NULL DEFAULT '[]'::jsonb,
    created_at   timestamptz NOT NULL DEFAULT now(),
    updated_at   timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT fk_shorts_batch_member FOREIGN KEY (member_id) REFERENCES member (id) ON DELETE CASCADE,
    CONSTRAINT fk_shorts_batch_post FOREIGN KEY (post_id) REFERENCES post (id) ON DELETE SET NULL
);
CREATE INDEX ix_shorts_batch_member ON shorts_batch (member_id, updated_at DESC, id DESC);

COMMENT ON TABLE shorts_batch IS '쇼츠 주제 묶음: 근거 하나에서 나눈 작은 주제들 (#276)';
COMMENT ON COLUMN shorts_batch.source_text IS 'MCP로 받은 맥락 본문. 글이 근거면 비어 있을 수 있다';
COMMENT ON COLUMN shorts_batch.topics IS '주제 배열 [{title, hook, scope}], 2~5개';

ALTER TABLE shorts
    ADD COLUMN batch_id    bigint,
    ADD COLUMN topic       smallint,
    ADD COLUMN source_text text NOT NULL DEFAULT '',
    ADD CONSTRAINT fk_shorts_batch FOREIGN KEY (batch_id) REFERENCES shorts_batch (id) ON DELETE SET NULL,
    ADD CONSTRAINT ck_shorts_topic CHECK (topic IS NULL OR topic BETWEEN 1 AND 5);
CREATE UNIQUE INDEX ux_shorts_batch_topic ON shorts (batch_id, topic) WHERE batch_id IS NOT NULL;

COMMENT ON COLUMN shorts.batch_id IS '주제 묶음. 묶음을 지우면 쇼츠는 남고 묶음만 풀린다';
COMMENT ON COLUMN shorts.topic IS '묶음 안 주제 번호 (1부터)';
COMMENT ON COLUMN shorts.source_text IS 'MCP로 받은 맥락 근거 (묶음에서 만들면 묶음의 근거를 복사)';
