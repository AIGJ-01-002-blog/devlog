-- 크레딧 모의투자 지정가 주문 (#332). 살 때는 낼 크레딧(수수료 포함)을, 팔 때는 팔 수량을 묶어 두고
-- 시세가 지정가에 닿으면 체결한다. 취소하면 묶은 크레딧을 낸 지갑으로 돌려준다.
CREATE TABLE paper_limit_order (
    id               bigserial PRIMARY KEY,
    member_id        bigint NOT NULL,
    client_order_id  varchar(64) NOT NULL,
    symbol           varchar(20) NOT NULL,
    side             varchar(4) NOT NULL,
    price            numeric(28, 10) NOT NULL,
    quantity         numeric(28, 10) NOT NULL,
    locked_invest    bigint NOT NULL DEFAULT 0,
    locked_shop      bigint NOT NULL DEFAULT 0,
    reason           varchar(300) NOT NULL DEFAULT '',
    status           varchar(10) NOT NULL DEFAULT 'OPEN',
    trade_id         bigint,
    created_at       timestamptz NOT NULL DEFAULT now(),
    closed_at        timestamptz,
    CONSTRAINT uq_paper_limit_order_client UNIQUE (member_id, client_order_id),
    CONSTRAINT fk_paper_limit_order_member FOREIGN KEY (member_id) REFERENCES member (id) ON DELETE CASCADE,
    CONSTRAINT fk_paper_limit_order_trade FOREIGN KEY (trade_id) REFERENCES trade (id) ON DELETE SET NULL,
    CONSTRAINT ck_paper_limit_order_side CHECK (side IN ('BUY', 'SELL')),
    CONSTRAINT ck_paper_limit_order_status CHECK (status IN ('OPEN', 'FILLED', 'CANCELED')),
    CONSTRAINT ck_paper_limit_order_amount CHECK (price > 0 AND quantity > 0 AND locked_invest >= 0 AND locked_shop >= 0)
);

CREATE INDEX ix_paper_limit_order_open ON paper_limit_order (symbol) WHERE status = 'OPEN';
CREATE INDEX ix_paper_limit_order_member ON paper_limit_order (member_id, id DESC);

COMMENT ON TABLE paper_limit_order IS '크레딧 모의투자 지정가 주문 (#332). 체결·취소 전까지 크레딧이나 수량을 묶어 둔다';
COMMENT ON COLUMN paper_limit_order.locked_invest IS '살 때 묶은 기본 크레딧. 체결하면 원금이 되고 남은 몫·취소분은 돌려준다';
COMMENT ON COLUMN paper_limit_order.locked_shop IS '살 때 묶은 번 크레딧(상점 지갑)';
