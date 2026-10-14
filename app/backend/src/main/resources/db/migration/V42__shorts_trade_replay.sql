-- 매매 재현 쇼츠 (#265, 목표 v2.1.0 #253).
-- 회원이 매매 일지에서 고른 체결(최대 6개)과 차트 구간을 쇼츠에 복사해 둔다. 렌더 워커가 그 구간의 공개 캔들을 받아
-- 캔들이 흘러가며 체결 시점에 매수·매도가 찍히는 영상을 만든다. 체결을 나중에 지워도 대본·영상은 어긋나지 않는다.
ALTER TABLE shorts ADD COLUMN replay jsonb;

COMMENT ON COLUMN shorts.replay IS '매매 재현 영상이면 {exchange, symbol, symbolName, currency, mode, interval, from, to, fills[]}. 글 쇼츠는 비어 있다';
