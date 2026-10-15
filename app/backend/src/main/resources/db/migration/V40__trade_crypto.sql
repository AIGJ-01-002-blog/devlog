-- 매매 일지 코인 지원 (#254, 목표 v2.1.0 #253).
-- 봇이 증권사가 아니라 코인 거래소(바이낸스·업비트, 나중에 바이비트)로 돈다.
-- 거래소와 통화(KRW·USDT 등)를 남기고, 0.00012345 BTC 같은 소수 수량·가격을 담도록 자릿수를 넓힌다.
ALTER TABLE trade ADD COLUMN exchange varchar(20) NOT NULL DEFAULT '';
ALTER TABLE trade ADD COLUMN currency varchar(10) NOT NULL DEFAULT 'KRW';
ALTER TABLE trade
    ALTER COLUMN quantity TYPE numeric(28, 10),
    ALTER COLUMN price TYPE numeric(28, 10),
    ALTER COLUMN fee TYPE numeric(28, 10),
    ALTER COLUMN realized_pnl TYPE numeric(28, 10);
ALTER TABLE trade ADD CONSTRAINT ck_trade_currency CHECK (currency ~ '^[A-Z]{2,10}$');
ALTER TABLE trade ADD CONSTRAINT ck_trade_exchange CHECK (exchange ~ '^[A-Z0-9_]{0,20}$');

COMMENT ON COLUMN trade.exchange IS '거래소 코드(BINANCE, UPBIT, BYBIT 등). 비어 있으면 거래소를 알리지 않은 기록';
COMMENT ON COLUMN trade.currency IS '가격·수수료·손익의 통화(KRW, USDT 등). 합계는 통화별로 따로 낸다';
