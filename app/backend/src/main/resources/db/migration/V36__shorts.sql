-- 쇼츠 스튜디오 (spec 099, 목표 v2.0.0 #236).
-- 대본은 사용자 쪽 AI가 MCP로 쓰거나 회원이 화면에서 쓰고, 영상은 따로 도는 렌더 워커가 만들어 사진과 같은 저장소(shorts/ 키)에 올린다.
-- 상태: DRAFT(대본) → QUEUED(영상 만들기 대기) → RENDERING(워커가 받음) → READY(완성) | FAILED(3번 실패).
CREATE TABLE shorts (
    id              bigserial PRIMARY KEY,
    member_id       bigint NOT NULL,
    post_id         bigint,
    title           varchar(60) NOT NULL,
    scenes          jsonb NOT NULL DEFAULT '[]'::jsonb,
    status          varchar(16) NOT NULL DEFAULT 'DRAFT',
    attempts        integer NOT NULL DEFAULT 0,
    claim_token     varchar(36),
    queued_at       timestamptz,
    claimed_at      timestamptz,
    video_key       varchar(200),
    video_bytes     bigint,
    duration_ms     integer,
    rendered_at     timestamptz,
    error           varchar(500),
    version         integer NOT NULL DEFAULT 0,
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT fk_shorts_member FOREIGN KEY (member_id) REFERENCES member (id) ON DELETE CASCADE,
    CONSTRAINT fk_shorts_post FOREIGN KEY (post_id) REFERENCES post (id) ON DELETE SET NULL,
    CONSTRAINT ck_shorts_status CHECK (status IN ('DRAFT', 'QUEUED', 'RENDERING', 'READY', 'FAILED')),
    CONSTRAINT ck_shorts_ready CHECK (status <> 'READY' OR video_key IS NOT NULL),
    CONSTRAINT ck_shorts_claim CHECK (status <> 'RENDERING' OR claim_token IS NOT NULL)
);
CREATE INDEX ix_shorts_member ON shorts (member_id, updated_at DESC, id DESC);
CREATE INDEX ix_shorts_queue ON shorts (queued_at, id) WHERE status = 'QUEUED';
CREATE INDEX ix_shorts_rendering ON shorts (claimed_at) WHERE status = 'RENDERING';

COMMENT ON TABLE shorts IS '쇼츠 대본과 만든 영상 (spec 099)';
COMMENT ON COLUMN shorts.scenes IS '장면 배열 [{caption, narration, code}]';
COMMENT ON COLUMN shorts.claim_token IS '워커가 작업을 받을 때마다 새로 주는 일회용 값. 결과는 이 값이 맞을 때만 받는다';
COMMENT ON COLUMN shorts.video_key IS '저장소 키 shorts/yyyy/mm/{uuid}.mp4';

-- 영상 만들기 요청 기록 (하루 한도 FR-005). 같은 쇼츠를 다시 만들어도 한 번씩 센다.
CREATE TABLE shorts_render_request (
    id            bigserial PRIMARY KEY,
    member_id     bigint NOT NULL,
    shorts_id     bigint,
    requested_at  timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT fk_shorts_render_request_member FOREIGN KEY (member_id) REFERENCES member (id) ON DELETE CASCADE,
    CONSTRAINT fk_shorts_render_request_shorts FOREIGN KEY (shorts_id) REFERENCES shorts (id) ON DELETE SET NULL
);
CREATE INDEX ix_shorts_render_request_member ON shorts_render_request (member_id, requested_at DESC);

COMMENT ON TABLE shorts_render_request IS '쇼츠 영상 만들기 요청 (회원별 하루 한도용, spec 099)';
