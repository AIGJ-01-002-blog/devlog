-- 백업한 DB를 집 컴퓨터 주소로 옮긴다 (#241). restore 스크립트가 psql 변수로 부른다:
--   old_media  옛 사진 공개 주소 (예: https://devlog.life/blog-images)
--   new_media  새 사진 주소       (예: http://localhost:8080/media)
--   old_site   옛 사이트 주소     (예: https://devlog.life)
--   new_site   새 사이트 주소     (예: http://localhost:8080)
-- 글 본문·렌더링 결과·썸네일·프로필 사진처럼 주소가 들어간 모든 글자 열(text·varchar·jsonb)에서 앞부분만 바꾼다.
-- 사진 주소를 먼저 바꾸고 나서 사이트 주소를 바꾼다(사진 주소가 사이트 주소로 시작하므로). 몇 번 돌려도 결과가 같다.
\set ON_ERROR_STOP on
SELECT set_config('devlog.old_media', :'old_media' || '/', false),
       set_config('devlog.new_media', :'new_media' || '/', false),
       set_config('devlog.old_site', :'old_site' || '/', false),
       set_config('devlog.new_site', :'new_site' || '/', false);

DO $$
DECLARE
    c record;
    n bigint;
    total bigint := 0;
    pairs text[][] := ARRAY[
        ARRAY[current_setting('devlog.old_media'), current_setting('devlog.new_media')],
        ARRAY[current_setting('devlog.old_site'), current_setting('devlog.new_site')]];
    i int;
BEGIN
    FOR i IN 1..array_length(pairs, 1) LOOP
        IF pairs[i][1] = pairs[i][2] OR pairs[i][1] = '/' THEN CONTINUE; END IF;
        FOR c IN
            SELECT table_name, column_name, data_type FROM information_schema.columns
            WHERE table_schema = 'public' AND data_type IN ('text', 'character varying', 'jsonb')
              AND table_name IN (SELECT table_name FROM information_schema.tables WHERE table_schema = 'public' AND table_type = 'BASE TABLE')
              AND table_name <> 'flyway_schema_history'
        LOOP
            IF c.data_type = 'jsonb' THEN
                EXECUTE format('UPDATE %I SET %I = replace(%I::text, $1, $2)::jsonb WHERE %I::text LIKE $3',
                               c.table_name, c.column_name, c.column_name, c.column_name)
                    USING pairs[i][1], pairs[i][2], '%' || pairs[i][1] || '%';
            ELSE
                EXECUTE format('UPDATE %I SET %I = replace(%I, $1, $2) WHERE %I LIKE $3',
                               c.table_name, c.column_name, c.column_name, c.column_name)
                    USING pairs[i][1], pairs[i][2], '%' || pairs[i][1] || '%';
            END IF;
            GET DIAGNOSTICS n = ROW_COUNT;
            IF n > 0 THEN
                RAISE NOTICE '%.%: %행 (% → %)', c.table_name, c.column_name, n, pairs[i][1], pairs[i][2];
                total := total + n;
            END IF;
        END LOOP;
    END LOOP;
    RAISE NOTICE '주소를 바꾼 행: 모두 %', total;
END $$;
