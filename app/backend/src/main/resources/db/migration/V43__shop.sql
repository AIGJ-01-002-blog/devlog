-- 포인트 상점과 블로그 꾸미기 아이템 (#268, 목표 #253).
-- 상점 포인트(SHOP 지갑)로만 산다. 산 아이템은 보관함(member_item)에 남고, 종류마다 하나씩 내 블로그에 적용한다(member_decoration).
CREATE TABLE shop_item (
    id           bigserial PRIMARY KEY,
    kind         varchar(10) NOT NULL,
    name         varchar(40) NOT NULL,
    description  varchar(200) NOT NULL DEFAULT '',
    price        bigint NOT NULL,
    value        varchar(40) NOT NULL DEFAULT '',
    image_key    varchar(200),
    active       boolean NOT NULL DEFAULT true,
    sort         integer NOT NULL DEFAULT 0,
    created_at   timestamptz NOT NULL DEFAULT now(),
    updated_at   timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_shop_item_kind CHECK (kind IN ('THEME', 'FRAME', 'BANNER')),
    CONSTRAINT ck_shop_item_price CHECK (price > 0),
    CONSTRAINT ck_shop_item_look CHECK (value <> '' OR image_key IS NOT NULL)
);
CREATE INDEX ix_shop_item_list ON shop_item (active, kind, sort, id);

COMMENT ON TABLE shop_item IS '포인트 상점의 블로그 꾸미기 아이템 (#268)';
COMMENT ON COLUMN shop_item.kind IS 'THEME(블로그 강조색) | FRAME(프로필 테두리) | BANNER(블로그 표지)';
COMMENT ON COLUMN shop_item.value IS 'THEME·FRAME: #RRGGBB 또는 정해 둔 모양 이름(gold·rainbow·aurora), BANNER: 정해 둔 그러데이션 이름. 사진 표지면 비울 수 있다';
COMMENT ON COLUMN shop_item.image_key IS 'BANNER 사진의 저장소 키 (기존 사진 저장소)';
COMMENT ON COLUMN shop_item.active IS 'false면 상점에서 내려간다. 이미 산 사람은 계속 쓴다';

CREATE TABLE member_item (
    member_id     bigint NOT NULL,
    item_id       bigint NOT NULL,
    price         bigint NOT NULL,
    purchased_at  timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (member_id, item_id),
    CONSTRAINT fk_member_item_member FOREIGN KEY (member_id) REFERENCES member (id) ON DELETE CASCADE,
    CONSTRAINT fk_member_item_item FOREIGN KEY (item_id) REFERENCES shop_item (id)
);

COMMENT ON TABLE member_item IS '회원이 산 꾸미기 아이템 보관함 (#268). price는 산 때 가격';

CREATE TABLE member_decoration (
    member_id   bigint NOT NULL,
    kind        varchar(10) NOT NULL,
    item_id     bigint NOT NULL,
    updated_at  timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (member_id, kind),
    CONSTRAINT fk_member_decoration_owned FOREIGN KEY (member_id, item_id) REFERENCES member_item (member_id, item_id) ON DELETE CASCADE,
    CONSTRAINT ck_member_decoration_kind CHECK (kind IN ('THEME', 'FRAME', 'BANNER'))
);

COMMENT ON TABLE member_decoration IS '내 블로그에 적용한 꾸미기 (종류마다 하나, #268)';

INSERT INTO shop_item (kind, name, description, price, value, sort) VALUES
    ('THEME', '숲', '버튼·탭·링크를 짙은 초록으로', 3000, '#2f9e44', 10),
    ('THEME', '바다', '버튼·탭·링크를 맑은 파랑으로', 3000, '#1c7ed6', 20),
    ('THEME', '노을', '버튼·탭·링크를 따뜻한 주황으로', 3000, '#e8590c', 30),
    ('THEME', '자두', '버튼·탭·링크를 보라로', 3000, '#ae3ec9', 40),
    ('FRAME', '금테', '프로필 사진에 금색 테두리', 2000, 'gold', 10),
    ('FRAME', '무지개', '프로필 사진에 무지개 테두리', 4000, 'rainbow', 20),
    ('FRAME', '오로라', '초록·보라로 번지는 테두리', 4000, 'aurora', 30),
    ('BANNER', '노을 하늘', '주황에서 분홍으로 물드는 표지', 5000, 'sunset', 10),
    ('BANNER', '깊은 바다', '남색에서 청록으로 내려가는 표지', 5000, 'ocean', 20),
    ('BANNER', '밤하늘', '별이 박힌 어두운 표지', 6000, 'night', 30),
    ('BANNER', '숲길', '연두에서 짙은 초록으로 이어지는 표지', 5000, 'forest', 40);
