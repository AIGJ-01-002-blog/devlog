-- 블로그 포인트 원장과 적립 규칙 (#266, 목표 #253).
-- 잔액은 따로 들고 있지 않고 원장 합계로 계산한다. 같은 요청 키(request_key)는 회원마다 한 번만 남는다.
-- 포인트는 현금·코인으로 바꿀 수 없고 회원끼리 주고받을 수 없다.
CREATE TABLE point_ledger (
    id           bigserial PRIMARY KEY,
    member_id    bigint NOT NULL,
    wallet       varchar(12) NOT NULL,
    amount       bigint NOT NULL,
    reason       varchar(20) NOT NULL,
    request_key  varchar(120) NOT NULL,
    ref_id       bigint,
    memo         varchar(200) NOT NULL DEFAULT '',
    created_at   timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT fk_point_ledger_member FOREIGN KEY (member_id) REFERENCES member (id) ON DELETE CASCADE,
    CONSTRAINT uq_point_ledger_request UNIQUE (member_id, request_key),
    CONSTRAINT ck_point_ledger_wallet CHECK (wallet IN ('INVEST_ONLY', 'SHOP')),
    CONSTRAINT ck_point_ledger_amount CHECK (amount <> 0)
);
CREATE INDEX ix_point_ledger_member ON point_ledger (member_id, id DESC);
CREATE INDEX ix_point_ledger_reason_day ON point_ledger (member_id, reason, created_at);

COMMENT ON TABLE point_ledger IS '포인트 적립(+)·사용(-) 내역. 잔액은 지갑별 합계 (#266)';
COMMENT ON COLUMN point_ledger.wallet IS 'INVEST_ONLY(가입 첫 지급, 모의투자에만) | SHOP(활동으로 번 포인트, 상점에서도)';
COMMENT ON COLUMN point_ledger.reason IS 'WELCOME, ATTENDANCE, POST, TRADE_PROFIT, SHOP, INVEST, ADMIN 등';
COMMENT ON COLUMN point_ledger.request_key IS '같은 일을 두 번 반영하지 않기 위한 키. 예: welcome, attend:2026-10-14, post:103';
COMMENT ON COLUMN point_ledger.ref_id IS '근거가 된 글·체결·아이템 번호 (없으면 비움)';

-- 출석: 회원·날짜(한국 시간)마다 한 줄. streak는 그날까지 이어진 연속 출석 일수
CREATE TABLE point_attendance (
    member_id  bigint NOT NULL,
    day        date NOT NULL,
    streak     integer NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (member_id, day),
    CONSTRAINT fk_point_attendance_member FOREIGN KEY (member_id) REFERENCES member (id) ON DELETE CASCADE,
    CONSTRAINT ck_point_attendance_streak CHECK (streak >= 1)
);

COMMENT ON TABLE point_attendance IS '하루 한 번 출석 (#266)';

-- 연속 출석 휘장: 20·50·100·150·200·300일, 365일(특별). 한 번 받으면 연속이 끊겨도 남는다
CREATE TABLE point_badge (
    member_id  bigint NOT NULL,
    days       integer NOT NULL,
    earned_at  timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (member_id, days),
    CONSTRAINT fk_point_badge_member FOREIGN KEY (member_id) REFERENCES member (id) ON DELETE CASCADE,
    CONSTRAINT ck_point_badge_days CHECK (days IN (20, 50, 100, 150, 200, 300, 365))
);

COMMENT ON TABLE point_badge IS '연속 출석 휘장 (#266). 블로그 이름 옆에 보인다';

-- 관리자가 바꾼 적립 규칙 값. 없는 규칙은 코드의 기본값을 쓴다
CREATE TABLE point_rule (
    code        varchar(30) PRIMARY KEY,
    value       bigint NOT NULL,
    updated_by  bigint,
    updated_at  timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT fk_point_rule_member FOREIGN KEY (updated_by) REFERENCES member (id) ON DELETE SET NULL,
    CONSTRAINT ck_point_rule_value CHECK (value >= 0)
);

COMMENT ON TABLE point_rule IS '관리자가 바꾼 포인트 적립 규칙 값 (#266)';
