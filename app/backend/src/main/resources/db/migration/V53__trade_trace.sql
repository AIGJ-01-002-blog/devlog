-- 매매 일지에 봇 판단 흔적 남기기 (#283 진입, #284 정리, 목표 v2.3.0 #344).
-- 체결 기록에 기법 버전·판단 키·바구니·세 겹 거름망 결과·비중 계산 흔적·손절익절을 붙이고,
-- 포지션을 닫는 체결에는 정리 이유·R배수·펀딩비를 붙인다. 모두 선택 값이라 옛 기록은 비어 있다.
-- 관망을 포함한 판단 원본(뉴스 원문, AI 입력)은 봇 쪽 로컬 DB에만 두고 여기에는 남기지 않는다.
ALTER TABLE trade ALTER COLUMN reason TYPE varchar(2000);

ALTER TABLE trade
    ADD COLUMN strategy_version  varchar(40) NOT NULL DEFAULT '',
    ADD COLUMN decision_id       varchar(80) NOT NULL DEFAULT '',
    ADD COLUMN basket            varchar(8),
    ADD COLUMN regime            varchar(30) NOT NULL DEFAULT '',
    ADD COLUMN setup             varchar(30) NOT NULL DEFAULT '',
    ADD COLUMN trigger_name      varchar(30) NOT NULL DEFAULT '',
    ADD COLUMN news_score        smallint,
    ADD COLUMN news_flags        varchar(120) NOT NULL DEFAULT '',
    ADD COLUMN risk_pct          numeric(9, 8),
    ADD COLUMN kelly_raw         numeric(9, 8),
    ADD COLUMN kelly_used        numeric(9, 8),
    ADD COLUMN ai_conf           numeric(9, 8),
    ADD COLUMN equity_at_entry   numeric(28, 10),
    ADD COLUMN stop_price        numeric(28, 10),
    ADD COLUMN tp1_price         numeric(28, 10),
    ADD COLUMN tp2_price         numeric(28, 10),
    ADD COLUMN liquidation_price numeric(28, 10),
    ADD COLUMN ai_model          varchar(60) NOT NULL DEFAULT '',
    ADD COLUMN ai_cost           numeric(18, 8),
    ADD COLUMN exit_reason       varchar(8),
    ADD COLUMN r_multiple        numeric(12, 4),
    ADD COLUMN funding           numeric(28, 10);

ALTER TABLE trade ADD CONSTRAINT ck_trade_basket CHECK (basket IS NULL OR basket IN ('CORE', 'TRADING'));
ALTER TABLE trade ADD CONSTRAINT ck_trade_exit_reason CHECK (
    exit_reason IS NULL OR exit_reason IN ('STOP', 'TP1', 'TP2', 'TRAIL', 'TIME', 'NEWS', 'MANUAL'));
ALTER TABLE trade ADD CONSTRAINT ck_trade_fractions CHECK (
    (risk_pct IS NULL OR risk_pct BETWEEN 0 AND 1) AND (kelly_raw IS NULL OR kelly_raw BETWEEN 0 AND 1)
    AND (kelly_used IS NULL OR kelly_used BETWEEN 0 AND 1) AND (ai_conf IS NULL OR ai_conf BETWEEN 0 AND 1));
ALTER TABLE trade ADD CONSTRAINT ck_trade_futures_only CHECK (
    market = 'FUTURES' OR (liquidation_price IS NULL AND funding IS NULL));

-- 같은 판단 키의 진입·분할 익절·정리 체결을 한 포지션으로 묶어 본다
CREATE INDEX ix_trade_decision ON trade (member_id, decision_id) WHERE decision_id <> '';

COMMENT ON COLUMN trade.strategy_version IS '기법 버전 (예: ladder-v0.1). 버전별 성적 비교에 쓴다';
COMMENT ON COLUMN trade.decision_id IS '봇 로컬 판단 기록과 잇는 키. 같은 키의 진입·정리 체결이 한 포지션이다';
COMMENT ON COLUMN trade.basket IS 'CORE(코어 적립) | TRADING(트레이딩)';
COMMENT ON COLUMN trade.regime IS '세 겹 거름망 1단: 장세 (예: UP)';
COMMENT ON COLUMN trade.setup IS '세 겹 거름망 2단: 셋업 (예: PULLBACK)';
COMMENT ON COLUMN trade.trigger_name IS '세 겹 거름망 3단: 방아쇠 (예: BREAKOUT10)';
COMMENT ON COLUMN trade.risk_pct IS '이번 거래에 건 위험 (잔고 대비 0~1)';
COMMENT ON COLUMN trade.equity_at_entry IS '크기 계산에 쓴 아침 잔고 (currency 통화)';
COMMENT ON COLUMN trade.exit_reason IS '정리 이유 STOP | TP1 | TP2 | TRAIL | TIME | NEWS | MANUAL. 포지션을 닫는 체결에만';
COMMENT ON COLUMN trade.r_multiple IS '정리 손익 ÷ 처음 위험. 포지션을 닫는 체결에만';
COMMENT ON COLUMN trade.funding IS '선물 펀딩비 (currency 통화, 낸 돈은 음수). 수수료는 fee에 적는다';
