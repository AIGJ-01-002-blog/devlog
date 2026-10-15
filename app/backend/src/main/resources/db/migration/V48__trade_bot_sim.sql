-- 자동매매 모의 화면 /trade (spec 101).
-- 매매 봇(규칙형: 사용자 AI가 기준을 정하고 봇 코드가 5분마다 주문, 무료 Gemini는 진입 필터와 하루 요약)이
-- 모의(--dry-run) 계좌의 상태와 사건을 MCP 도구 report_trade_bot으로 보낸다.
-- 매매 일지(trade)와 섞지 않으려고 표를 따로 둔다. 공개 글·일지·포인트 정산은 이 표를 읽지 않는다. 실계좌는 받지 않는다.
CREATE TABLE trade_bot_status (
    member_id       bigint PRIMARY KEY,
    ai_enabled      boolean NOT NULL DEFAULT false,
    ai_model        varchar(60) NOT NULL DEFAULT '',
    ai_calls_today  integer NOT NULL DEFAULT 0,
    bot_version     varchar(30) NOT NULL DEFAULT '',
    reported_at     timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT fk_trade_bot_status_member FOREIGN KEY (member_id) REFERENCES member (id) ON DELETE CASCADE,
    CONSTRAINT ck_trade_bot_status_calls CHECK (ai_calls_today >= 0)
);

COMMENT ON TABLE trade_bot_status IS '자동매매 모의 화면: 봇이 마지막으로 알린 AI 필터 상태 (spec 101)';

CREATE TABLE trade_bot_account (
    member_id       bigint NOT NULL,
    account         varchar(80) NOT NULL,
    exchange        varchar(20) NOT NULL DEFAULT '',
    market          varchar(8) NOT NULL DEFAULT 'SPOT',
    currency        varchar(10) NOT NULL,
    cash            numeric(28, 10) NOT NULL,
    unrealized_pnl  numeric(28, 10),
    positions       jsonb NOT NULL DEFAULT '[]',
    rules           jsonb NOT NULL DEFAULT '[]',
    reported_at     timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (member_id, account),
    CONSTRAINT fk_trade_bot_account_member FOREIGN KEY (member_id) REFERENCES member (id) ON DELETE CASCADE,
    CONSTRAINT ck_trade_bot_account_market CHECK (market IN ('SPOT', 'FUTURES')),
    CONSTRAINT ck_trade_bot_account_currency CHECK (currency ~ '^[A-Z]{2,10}$')
);

COMMENT ON TABLE trade_bot_account IS '자동매매 모의 계좌의 현금·보유 포지션·적용 중인 규칙 (spec 101). 봇이 알릴 때마다 통째로 바꾼다';
COMMENT ON COLUMN trade_bot_account.positions IS '[{symbol, side, quantity, avgPrice, price, unrealizedPnl}] 서버가 검사해 다시 쓴 값';
COMMENT ON COLUMN trade_bot_account.rules IS '[{label, value}] 지금 적용 중인 매매 기준(전략·코인·봉·한도)';

CREATE TABLE trade_bot_event (
    id            bigserial PRIMARY KEY,
    member_id     bigint NOT NULL,
    external_id   varchar(120) NOT NULL,
    kind          varchar(8) NOT NULL,
    account       varchar(80) NOT NULL DEFAULT '',
    symbol        varchar(20) NOT NULL DEFAULT '',
    side          varchar(5) NOT NULL DEFAULT '',
    quantity      numeric(28, 10),
    price         numeric(28, 10),
    currency      varchar(10) NOT NULL DEFAULT '',
    realized_pnl  numeric(28, 10),
    verdict       varchar(5) NOT NULL DEFAULT '',
    text          varchar(1000) NOT NULL DEFAULT '',
    occurred_at   timestamptz NOT NULL,
    created_at    timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT fk_trade_bot_event_member FOREIGN KEY (member_id) REFERENCES member (id) ON DELETE CASCADE,
    CONSTRAINT uq_trade_bot_event_external UNIQUE (member_id, external_id),
    CONSTRAINT ck_trade_bot_event_kind CHECK (kind IN ('FILL', 'AI', 'SUMMARY')),
    CONSTRAINT ck_trade_bot_event_side CHECK (side IN ('', 'BUY', 'SELL', 'LONG', 'SHORT', 'CLOSE')),
    CONSTRAINT ck_trade_bot_event_verdict CHECK (verdict IN ('', 'ALLOW', 'BLOCK'))
);
CREATE INDEX ix_trade_bot_event_member_kind ON trade_bot_event (member_id, kind, occurred_at DESC, id DESC);

COMMENT ON TABLE trade_bot_event IS '자동매매 모의 사건: FILL 모의 체결(이유 포함), AI Gemini 진입 필터 판단, SUMMARY 하루 요약 (spec 101). 30일 지나면 지운다';
