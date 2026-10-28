-- 쇼츠 자동 올리기 (#501): YouTube를 공개 예약 없이 비공개로만 올리면 작업은 UPLOADED(비공개로 올림)로 끝난다.
-- 공개는 블로그 주인이 YouTube 스튜디오에서 직접 한다
ALTER TABLE shorts_auto_upload DROP CONSTRAINT ck_shorts_auto_upload_status;
ALTER TABLE shorts_auto_upload ADD CONSTRAINT ck_shorts_auto_upload_status
    CHECK (status IN ('QUEUED', 'UPLOADING', 'SCHEDULED', 'UPLOADED', 'PUBLISHED', 'FAILED'));
COMMENT ON COLUMN shorts_auto_upload.publish_at IS '공개 시각(하루 한 편, 한국 시간 20:00). YouTube 비공개로만 올리면 올린 시각';
