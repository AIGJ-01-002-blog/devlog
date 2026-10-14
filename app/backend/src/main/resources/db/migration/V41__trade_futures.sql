-- 매매 일지 선물 기록 (#260, 목표 v2.1.0 #253).
-- 봇이 현물과 선물을 함께 돌린다. 선물은 포지션 방향(롱·숏)과 레버리지가 있어 손익을 매수·매도만으로 읽을 수 없다.
-- 방향 규칙: 롱 진입 BUY, 롱 청산 SELL, 숏 진입 SELL, 숏 청산 BUY. 실현 손익은 청산에만 붙는다.
-- 봉(1m·5m·15m·1h·4h·1d)은 봇이 판단한 봉이다. interval은 PostgreSQL 타입 이름이라 열 이름은 timeframe으로 쓴다.
ALTER TABLE trade ADD COLUMN market varchar(8) NOT NULL DEFAULT 'SPOT';
ALTER TABLE trade ADD COLUMN position_side varchar(5);
ALTER TABLE trade ADD COLUMN leverage smallint;
ALTER TABLE trade ADD COLUMN timeframe varchar(3) NOT NULL DEFAULT '';

-- #260 전에 봇이 선물 체결을 이유 앞머리로 알렸다: "[선물 숏 진입 3x] RSI 과열". 이 앞머리를 열로 옮기고 이유에서 지운다
UPDATE trade
SET market = 'FUTURES',
    position_side = CASE WHEN substring(reason FROM '^\[선물 (롱|숏)') = '롱' THEN 'LONG' ELSE 'SHORT' END,
    leverage = least(125, greatest(1, substring(reason FROM '^\[선물 (?:롱|숏) (?:진입|청산) (\d{1,3})(?:x|X|배)\]')::int)),
    reason = btrim(regexp_replace(reason, '^\[선물 [^]]*\]\s*', ''))
WHERE reason ~ '^\[선물 (롱|숏) (진입|청산) \d{1,3}(x|X|배)\]';

ALTER TABLE trade ADD CONSTRAINT ck_trade_market CHECK (market IN ('SPOT', 'FUTURES'));
-- 앱은 1~7배만 받는다(블로그 주인 결정). 옛 기록이 더 높을 수 있어 DB는 거래소 최대 125배까지 둔다
ALTER TABLE trade ADD CONSTRAINT ck_trade_position CHECK (
    (market = 'SPOT' AND position_side IS NULL AND leverage IS NULL)
    OR (market = 'FUTURES' AND position_side IN ('LONG', 'SHORT') AND leverage BETWEEN 1 AND 125));
ALTER TABLE trade ADD CONSTRAINT ck_trade_timeframe CHECK (timeframe IN ('', '1m', '5m', '15m', '1h', '4h', '1d'));

COMMENT ON COLUMN trade.market IS 'SPOT(현물) | FUTURES(선물)';
COMMENT ON COLUMN trade.position_side IS '선물 포지션 방향 LONG | SHORT. 현물은 비어 있다';
COMMENT ON COLUMN trade.leverage IS '선물 레버리지 배수. 현물은 비어 있다';
COMMENT ON COLUMN trade.timeframe IS '봇이 판단한 봉 (1m, 5m, 15m, 1h, 4h, 1d). 비어 있으면 알리지 않은 기록';
