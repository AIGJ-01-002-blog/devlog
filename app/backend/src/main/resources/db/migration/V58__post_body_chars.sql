-- 105 애드센스 재심사: 본문이 짧은 글은 검색 수집·사이트맵·광고에서 뺀다. 글은 그대로 공개된다.
-- 본문 글자 수는 코드 블록(```), 그림(![..](..)), 링크 주소((..))를 빼고 공백을 하나로 줄여 센다.
-- 발행본(content_md)에서 DB가 계산해 두므로 글을 쓰고 고치는 코드는 바꾸지 않는다.
ALTER TABLE post ADD COLUMN body_chars integer GENERATED ALWAYS AS (
    char_length(btrim(regexp_replace(regexp_replace(regexp_replace(regexp_replace(content_md,
        '```.*?```', ' ', 'g'),
        '!\[[^]]*\]\([^)]*\)', ' ', 'g'),
        '\]\([^)]*\)', ']', 'g'),
        '\s+', ' ', 'g')))
) STORED;

COMMENT ON COLUMN post.body_chars IS '코드 블록·그림·링크 주소를 뺀 발행 본문 글자 수 (spec 105, 짧은 글 수집 제외 기준)';
