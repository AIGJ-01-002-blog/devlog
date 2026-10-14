-- 쇼츠를 직접 올린 기록 (spec 099 2단계, #238). devlog는 SNS에 올리지 않고, 회원이 올린 뒤 그 영상 주소를 붙여 넣는다.
-- 플랫폼마다 하나씩. 대본을 고쳐 영상을 다시 만들어도 이미 올린 영상은 남으므로 기록도 남긴다. 계정별 대시보드(#239)가 읽는다.
CREATE TABLE shorts_upload (
    shorts_id    bigint NOT NULL,
    platform     varchar(16) NOT NULL,
    url          varchar(300) NOT NULL,
    uploaded_at  timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (shorts_id, platform),
    CONSTRAINT fk_shorts_upload_shorts FOREIGN KEY (shorts_id) REFERENCES shorts (id) ON DELETE CASCADE,
    CONSTRAINT ck_shorts_upload_platform CHECK (platform IN ('YOUTUBE', 'INSTAGRAM', 'TIKTOK'))
);

COMMENT ON TABLE shorts_upload IS '쇼츠를 SNS에 직접 올린 기록 (spec 099)';
COMMENT ON COLUMN shorts_upload.url IS '회원이 붙여 넣은 그 플랫폼의 영상 주소 (https, 플랫폼 도메인만)';
