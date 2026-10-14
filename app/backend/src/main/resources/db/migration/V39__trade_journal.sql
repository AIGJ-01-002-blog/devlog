-- 매매 일지 (#243, 목표 v2.0.0 #236).
-- 매매 봇은 devlog 밖(집 컴퓨터)에서 돌고, 체결할 때마다 MCP 도구 record_trade로 체결 기록만 보낸다.
-- 증권사 접속 정보는 봇의 환경 변수에만 있고 devlog에는 저장하지 않는다.
CREATE TABLE trade (
    id            bigserial PRIMARY KEY,
    member_id     bigint NOT NULL,
    external_id   varchar(80) NOT NULL,
    mode          varchar(8) NOT NULL,
    symbol        varchar(20) NOT NULL,
    symbol_name   varchar(60) NOT NULL DEFAULT '',
    side          varchar(4) NOT NULL,
    quantity      numeric(18, 4) NOT NULL,
    price         numeric(18, 4) NOT NULL,
    fee           numeric(18, 4) NOT NULL DEFAULT 0,
    realized_pnl  numeric(18, 4),
    strategy      varchar(60) NOT NULL DEFAULT '',
    reason        varchar(300) NOT NULL DEFAULT '',
    executed_at   timestamptz NOT NULL,
    created_at    timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT fk_trade_member FOREIGN KEY (member_id) REFERENCES member (id) ON DELETE CASCADE,
    CONSTRAINT uq_trade_external UNIQUE (member_id, external_id),
    CONSTRAINT ck_trade_mode CHECK (mode IN ('PAPER', 'LIVE')),
    CONSTRAINT ck_trade_side CHECK (side IN ('BUY', 'SELL')),
    CONSTRAINT ck_trade_quantity CHECK (quantity > 0),
    CONSTRAINT ck_trade_price CHECK (price >= 0),
    CONSTRAINT ck_trade_fee CHECK (fee >= 0)
);
CREATE INDEX ix_trade_member_time ON trade (member_id, executed_at DESC, id DESC);

COMMENT ON TABLE trade IS '매매 봇이 보낸 체결 기록 (#243)';
COMMENT ON COLUMN trade.external_id IS '봇 쪽 주문·체결 번호. 같은 기록을 다시 보내도 한 번만 남는다';
COMMENT ON COLUMN trade.mode IS 'PAPER(모의투자) | LIVE(실계좌)';
COMMENT ON COLUMN trade.realized_pnl IS '매도로 확정된 손익(수수료·세금 뺀 값). 봇이 계산해 보낸다. 매수는 비어 있다';

-- 하루(한국 시간) 단위 메모와, 그날로 만든 요약 임시글·쇼츠 대본
CREATE TABLE trade_day (
    member_id   bigint NOT NULL,
    day         date NOT NULL,
    memo        varchar(2000) NOT NULL DEFAULT '',
    post_id     bigint,
    shorts_id   bigint,
    updated_at  timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (member_id, day),
    CONSTRAINT fk_trade_day_member FOREIGN KEY (member_id) REFERENCES member (id) ON DELETE CASCADE,
    CONSTRAINT fk_trade_day_post FOREIGN KEY (post_id) REFERENCES post (id) ON DELETE SET NULL,
    CONSTRAINT fk_trade_day_shorts FOREIGN KEY (shorts_id) REFERENCES shorts (id) ON DELETE SET NULL
);

COMMENT ON TABLE trade_day IS '매매 일지의 하루 메모와 그날로 만든 글·쇼츠 (#243)';
