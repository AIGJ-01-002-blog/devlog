-- 회원 포인트 모의투자 (#267, 목표 #253). 업비트 공개 시세로 BTC·ETH·XRP·SOL·DOGE를 포인트로 사고판다.
-- 체결 자체는 매매 일지(trade)에 PAPER로 남고, 여기에는 보유 수량·원금과 주문 번호만 둔다.
CREATE TABLE paper_position (
    member_id    bigint NOT NULL,
    symbol       varchar(20) NOT NULL,
    quantity     numeric(28, 10) NOT NULL,
    cost_invest  bigint NOT NULL DEFAULT 0,
    cost_shop    bigint NOT NULL DEFAULT 0,
    updated_at   timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (member_id, symbol),
    CONSTRAINT fk_paper_position_member FOREIGN KEY (member_id) REFERENCES member (id) ON DELETE CASCADE,
    CONSTRAINT ck_paper_position_quantity CHECK (quantity > 0),
    CONSTRAINT ck_paper_position_cost CHECK (cost_invest >= 0 AND cost_shop >= 0)
);

COMMENT ON TABLE paper_position IS '포인트 모의투자 보유분 (#267)';
COMMENT ON COLUMN paper_position.cost_invest IS '남은 원금 중 투자 전용 포인트로 낸 몫. 팔면 이 지갑으로 돌아간다';
COMMENT ON COLUMN paper_position.cost_shop IS '남은 원금 중 상점 포인트로 낸 몫';

-- 같은 주문 번호(화면이 만든 clientOrderId)는 한 번만 체결한다
CREATE TABLE paper_order (
    member_id        bigint NOT NULL,
    client_order_id  varchar(64) NOT NULL,
    trade_id         bigint,
    created_at       timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (member_id, client_order_id),
    CONSTRAINT fk_paper_order_member FOREIGN KEY (member_id) REFERENCES member (id) ON DELETE CASCADE,
    CONSTRAINT fk_paper_order_trade FOREIGN KEY (trade_id) REFERENCES trade (id) ON DELETE SET NULL
);

COMMENT ON TABLE paper_order IS '포인트 모의투자 주문 번호. 두 번 누르거나 다시 보내도 한 번만 체결 (#267)';
