-- 서버가 돌리는 모의 봇 (spec 102). /trade에서 [모의 봇 켜기]를 누른 회원마다 devlog 서버가 5분마다
-- 공개 캔들(키 없음)로 이동평균 교차 모의 매매를 하고, 결과를 spec 101 표(trade_bot_account·trade_bot_event)에
-- 계좌 이름 server-paper-…로 남긴다. 실거래는 하지 않고 거래소 키도 받지 않는다.
CREATE TABLE trade_paper_bot (
    member_id   bigint PRIMARY KEY,
    enabled     boolean NOT NULL DEFAULT true,
    rules       jsonb NOT NULL DEFAULT '{}',
    state       jsonb NOT NULL DEFAULT '{}',
    created_at  timestamptz NOT NULL DEFAULT now(),
    updated_at  timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT fk_trade_paper_bot_member FOREIGN KEY (member_id) REFERENCES member (id) ON DELETE CASCADE
);
CREATE INDEX ix_trade_paper_bot_enabled ON trade_paper_bot (member_id) WHERE enabled;

COMMENT ON TABLE trade_paper_bot IS '서버가 돌리는 모의 봇 (spec 102): 켜짐, 회원 AI가 정한 기준, 계좌별 현금·보유·처리한 봉';
COMMENT ON COLUMN trade_paper_bot.rules IS '{coins, window, stop_loss, take_profit, daily_profit_stop, paused, reason, changed_at}';
COMMENT ON COLUMN trade_paper_bot.state IS '{BINANCE|UPBIT: {cash, positions:{symbol:{quantity, avg_price}}, seen:{symbol: 마지막 처리 봉(밀리초)}, day, day_pnl}}';
