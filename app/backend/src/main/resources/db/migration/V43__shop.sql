-- 크레딧 상점과 블로그 꾸미기 아이템 (#268, 목표 #253).
-- 활동으로 번 크레딧(SHOP 지갑)으로만 산다. 산 아이템은 보관함(member_item)에 남고, 종류마다 하나씩 내 블로그에 적용한다(member_decoration).
-- 아이템마다 티어(1 아이언 ~ 9 챌린저)가 있고, 가격은 100 × 10^(티어-1) 크레딧이 기본이다.
-- 윗 티어는 아래 티어를 가진 사람만 산다: 같은 종류에 바로 아래 티어 아이템이 있으면 그중 하나를, 없으면 어느 종류든 바로 아래 티어 아이템 하나를 가져야 한다.
CREATE TABLE shop_item (
    id           bigserial PRIMARY KEY,
    kind         varchar(10) NOT NULL,
    tier         smallint NOT NULL,
    name         varchar(40) NOT NULL,
    description  varchar(200) NOT NULL DEFAULT '',
    price        bigint NOT NULL,
    value        varchar(40) NOT NULL DEFAULT '',
    image_key    varchar(200),
    active       boolean NOT NULL DEFAULT true,
    sort         integer NOT NULL DEFAULT 0,
    created_at   timestamptz NOT NULL DEFAULT now(),
    updated_at   timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_shop_item_kind CHECK (kind IN ('THEME', 'FRAME', 'BANNER', 'EFFECT')),
    CONSTRAINT ck_shop_item_tier CHECK (tier BETWEEN 1 AND 9),
    CONSTRAINT ck_shop_item_price CHECK (price > 0),
    CONSTRAINT ck_shop_item_look CHECK (value <> '' OR image_key IS NOT NULL)
);
CREATE INDEX ix_shop_item_list ON shop_item (active, kind, tier, sort, id);

COMMENT ON TABLE shop_item IS '크레딧 상점의 블로그 꾸미기 아이템 (#268)';
COMMENT ON COLUMN shop_item.kind IS 'THEME(블로그 강조색) | FRAME(프로필 테두리) | BANNER(블로그 표지) | EFFECT(빛·반짝임 효과)';
COMMENT ON COLUMN shop_item.tier IS '1 아이언, 2 브론즈, 3 실버, 4 골드, 5 플래티넘, 6 다이아, 7 마스터, 8 그랜드마스터, 9 챌린저';
COMMENT ON COLUMN shop_item.value IS 'THEME: #RRGGBB, FRAME: #RRGGBB 또는 티어 모양 이름(iron~challenger), BANNER: 티어 표지 이름(iron~challenger), EFFECT: glow·stardust·radiance';
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
    CONSTRAINT ck_member_decoration_kind CHECK (kind IN ('THEME', 'FRAME', 'BANNER', 'EFFECT'))
);

COMMENT ON TABLE member_decoration IS '내 블로그에 적용한 꾸미기 (종류마다 하나, #268)';

INSERT INTO shop_item (kind, tier, name, description, price, value, sort) VALUES
    ('THEME', 1, '아이언', '버튼·탭·링크를 무쇠 회색으로', 100, '#5c5f66', 10),
    ('THEME', 2, '브론즈', '버튼·탭·링크를 구릿빛으로', 1000, '#8a5a33', 20),
    ('THEME', 3, '실버', '버튼·탭·링크를 은빛 청회색으로', 10000, '#5b6b7c', 30),
    ('THEME', 4, '골드', '버튼·탭·링크를 짙은 금색으로', 100000, '#946200', 40),
    ('THEME', 5, '플래티넘', '버튼·탭·링크를 백금빛 청록으로', 1000000, '#0b7a75', 50),
    ('THEME', 6, '다이아', '버튼·탭·링크를 다이아몬드 파랑으로', 10000000, '#3b5bdb', 60),
    ('THEME', 7, '마스터', '버튼·탭·링크를 마스터 보라로', 100000000, '#9c36b5', 70),
    ('THEME', 8, '그랜드마스터', '버튼·탭·링크를 그랜드마스터 진홍으로', 1000000000, '#c92a2a', 80),
    ('THEME', 9, '챌린저', '버튼·탭·링크를 챌린저 하늘빛 남색으로', 10000000000, '#1864ab', 90),
    ('FRAME', 1, '아이언 테두리', '무쇠빛 회색 테두리', 100, 'iron', 10),
    ('FRAME', 2, '브론즈 테두리', '구릿빛으로 빛나는 테두리', 1000, 'bronze', 20),
    ('FRAME', 3, '실버 테두리', '은빛으로 반사되는 테두리', 10000, 'silver', 30),
    ('FRAME', 4, '골드 테두리', '금빛 테두리', 100000, 'gold', 40),
    ('FRAME', 5, '플래티넘 테두리', '백금과 청록이 섞인 테두리', 1000000, 'platinum', 50),
    ('FRAME', 6, '다이아 테두리', '푸른 빛을 내는 다이아 테두리', 10000000, 'diamond', 60),
    ('FRAME', 7, '마스터 테두리', '보랏빛 후광이 도는 테두리', 100000000, 'master', 70),
    ('FRAME', 8, '그랜드마스터 테두리', '붉은 불꽃 후광이 도는 테두리', 1000000000, 'grandmaster', 80),
    ('FRAME', 9, '챌린저 테두리', '금빛·하늘빛이 돌며 반짝이는 최고 티어 테두리', 10000000000, 'challenger', 90),
    ('BANNER', 1, '아이언 표지', '무쇠빛 회색 표지', 100, 'iron', 10),
    ('BANNER', 2, '브론즈 표지', '구리에서 갈색으로 이어지는 표지', 1000, 'bronze', 20),
    ('BANNER', 3, '실버 표지', '은빛에서 청회색으로 이어지는 표지', 10000, 'silver', 30),
    ('BANNER', 4, '골드 표지', '금빛으로 물드는 표지', 100000, 'gold', 40),
    ('BANNER', 5, '플래티넘 표지', '백금빛 청록 표지', 1000000, 'platinum', 50),
    ('BANNER', 6, '다이아 표지', '푸른 빛이 번지는 다이아 표지', 10000000, 'diamond', 60),
    ('BANNER', 7, '마스터 표지', '보라 후광이 번지는 표지', 100000000, 'master', 70),
    ('BANNER', 8, '그랜드마스터 표지', '진홍 불꽃이 번지는 표지', 1000000000, 'grandmaster', 80),
    ('BANNER', 9, '챌린저 표지', '금빛·하늘빛이 반짝이는 최고 티어 표지', 10000000000, 'challenger', 90),
    ('EFFECT', 6, '빛무리', '프로필과 표지에 은은한 빛이 맴돌아요', 10000000, 'glow', 10),
    ('EFFECT', 8, '별가루', '표지 위로 별가루가 반짝여요', 1000000000, 'stardust', 20),
    ('EFFECT', 9, '광휘', '프로필에 빛 고리가 돌고 표지 전체가 반짝여요', 10000000000, 'radiance', 30);
