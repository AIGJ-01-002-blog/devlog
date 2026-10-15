-- 글 발행 크레딧 미지급 목록 (#266, 블로그 주인 결정 "outbox 방식"). 글이 처음 전체 공개되는 트랜잭션 안에서 한 줄을 넣고,
-- 커밋 뒤 바로 지급하면서 지운다. 서버가 그 사이에 멈추면 1분마다 도는 작업이 남은 줄을 다시 지급한다.
CREATE TABLE point_outbox (
    id          bigserial PRIMARY KEY,
    member_id   bigint NOT NULL,
    post_id     bigint NOT NULL,
    attempts    int NOT NULL DEFAULT 0,
    next_at     timestamptz NOT NULL DEFAULT now(),
    last_error  varchar(200),
    created_at  timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_point_outbox_post UNIQUE (post_id),
    CONSTRAINT fk_point_outbox_member FOREIGN KEY (member_id) REFERENCES member (id) ON DELETE CASCADE
);

CREATE INDEX ix_point_outbox_next ON point_outbox (next_at);

COMMENT ON TABLE point_outbox IS '아직 주지 못한 글 발행 크레딧 (#266). 지급하면 지운다';
COMMENT ON COLUMN point_outbox.next_at IS '다음에 지급을 시도할 시각. 실패하면 점점 늘린다(최대 1시간)';
COMMENT ON COLUMN point_outbox.last_error IS '마지막 실패 까닭(예외 이름)';
