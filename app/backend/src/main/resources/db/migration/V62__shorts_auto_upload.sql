-- 쇼츠 자동 올리기 (spec 108, #498). 렌더가 끝난 영상을 블로그 주인의 YouTube·Instagram에 올린다.
-- 처음에는 모두 꺼져 있다: 계정을 잇고 시험 올리기를 통과한 플랫폼만 주인이 켠다.

-- 회원별 플랫폼 연결. 토큰은 앱 설정 열쇠(SHORTS_TOKEN_KEY)로 AES-GCM 암호화한 값만 둔다
CREATE TABLE shorts_platform_connection (
    member_id          bigint NOT NULL,
    platform           varchar(16) NOT NULL,
    account_id         varchar(64) NOT NULL,
    account_name       varchar(120),
    access_token_enc   text,
    refresh_token_enc  text,
    token_expires_at   timestamptz,
    token_issued_at    timestamptz,
    enabled            boolean NOT NULL DEFAULT false,
    enabled_at         timestamptz,
    tested_at          timestamptz,
    test_url           varchar(300),
    test_error         varchar(500),
    created_at         timestamptz NOT NULL DEFAULT now(),
    updated_at         timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (member_id, platform),
    CONSTRAINT fk_shorts_platform_connection_member FOREIGN KEY (member_id) REFERENCES member (id) ON DELETE CASCADE,
    CONSTRAINT ck_shorts_platform_connection_platform CHECK (platform IN ('YOUTUBE', 'INSTAGRAM'))
);

COMMENT ON TABLE shorts_platform_connection IS '쇼츠 자동 올리기용 플랫폼 계정 연결 (spec 108)';
COMMENT ON COLUMN shorts_platform_connection.access_token_enc IS '접근 토큰 (AES-GCM 암호문, 평문을 두지 않는다)';
COMMENT ON COLUMN shorts_platform_connection.refresh_token_enc IS '갱신 토큰 (AES-GCM 암호문). Instagram은 쓰지 않는다';
COMMENT ON COLUMN shorts_platform_connection.token_issued_at IS '토큰을 받거나 갱신한 시각. Instagram 장기 토큰은 발급 24시간 뒤부터 늘린다';
COMMENT ON COLUMN shorts_platform_connection.enabled IS '자동 올리기 켜짐. 시험 올리기(tested_at)를 통과해야 켤 수 있다';
COMMENT ON COLUMN shorts_platform_connection.enabled_at IS '켠 시각. 이 뒤에 렌더가 끝난 영상만 올린다';

-- 쇼츠 × 플랫폼 올리기 작업. 쇼츠를 지우면 함께 지운다(이미 올린 영상은 플랫폼에 남는다)
CREATE TABLE shorts_auto_upload (
    id               bigserial PRIMARY KEY,
    shorts_id        bigint NOT NULL,
    member_id        bigint NOT NULL,
    platform         varchar(16) NOT NULL,
    status           varchar(16) NOT NULL DEFAULT 'QUEUED',
    attempts         int NOT NULL DEFAULT 0,
    next_attempt_at  timestamptz NOT NULL DEFAULT now(),
    publish_at       timestamptz NOT NULL,
    external_id      varchar(64),
    container_id     varchar(64),
    url              varchar(300),
    error            varchar(500),
    note             varchar(300),
    created_at       timestamptz NOT NULL DEFAULT now(),
    updated_at       timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_shorts_auto_upload UNIQUE (shorts_id, platform),
    CONSTRAINT fk_shorts_auto_upload_shorts FOREIGN KEY (shorts_id) REFERENCES shorts (id) ON DELETE CASCADE,
    CONSTRAINT ck_shorts_auto_upload_platform CHECK (platform IN ('YOUTUBE', 'INSTAGRAM')),
    CONSTRAINT ck_shorts_auto_upload_status CHECK (status IN ('QUEUED', 'UPLOADING', 'SCHEDULED', 'PUBLISHED', 'FAILED'))
);

CREATE INDEX ix_shorts_auto_upload_due ON shorts_auto_upload (status, next_attempt_at);
CREATE INDEX ix_shorts_auto_upload_member_slot ON shorts_auto_upload (member_id, platform, publish_at);

COMMENT ON TABLE shorts_auto_upload IS '쇼츠 자동 올리기 작업 (spec 108)';
COMMENT ON COLUMN shorts_auto_upload.publish_at IS '공개 시각. 하루 한 편, 한국 시간 20:00';
COMMENT ON COLUMN shorts_auto_upload.container_id IS 'Instagram 미디어 컨테이너 ID (게시 전)';
COMMENT ON COLUMN shorts_auto_upload.note IS '실패는 아니지만 알릴 것 (예: 재생목록을 못 찾음)';
