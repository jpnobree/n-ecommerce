-- Fase 5: carrinho (convidado e logado), cupons e favoritos (PRD, seções 7, 13 e 14).

CREATE TABLE coupon (
    id                    bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    code                  citext       NOT NULL UNIQUE,
    description           varchar(200),
    type                  varchar(15)  NOT NULL CHECK (type IN ('PERCENTAGE', 'FIXED_AMOUNT', 'FREE_SHIPPING')),
    value                 bigint       NOT NULL DEFAULT 0 CHECK (value >= 0),
    max_discount_amount   bigint CHECK (max_discount_amount > 0),
    max_shipping_discount bigint CHECK (max_shipping_discount > 0),
    min_order_amount      bigint CHECK (min_order_amount > 0),
    first_order_only      boolean      NOT NULL DEFAULT false,
    exclude_sale_items    boolean      NOT NULL DEFAULT false,
    starts_at             timestamptz,
    ends_at               timestamptz,
    usage_limit           int CHECK (usage_limit > 0),
    usage_limit_per_user  int CHECK (usage_limit_per_user > 0),
    active                boolean      NOT NULL DEFAULT true,
    version               int          NOT NULL DEFAULT 0,
    created_at            timestamptz  NOT NULL DEFAULT now(),
    updated_at            timestamptz  NOT NULL DEFAULT now(),
    CHECK (type <> 'PERCENTAGE' OR value BETWEEN 1 AND 100),
    CHECK (type <> 'FIXED_AMOUNT' OR value > 0),
    CHECK (ends_at IS NULL OR starts_at IS NULL OR ends_at > starts_at)
);

CREATE TABLE coupon_category (
    coupon_id   bigint NOT NULL REFERENCES coupon (id) ON DELETE CASCADE,
    category_id bigint NOT NULL REFERENCES category (id) ON DELETE CASCADE,
    PRIMARY KEY (coupon_id, category_id)
);

CREATE TABLE coupon_product (
    coupon_id  bigint NOT NULL REFERENCES coupon (id) ON DELETE CASCADE,
    product_id bigint NOT NULL REFERENCES product (id) ON DELETE CASCADE,
    PRIMARY KEY (coupon_id, product_id)
);

-- Usos reservados no checkout e confirmados no pagamento (preenchida a partir da Fase 6).
CREATE TABLE coupon_usage (
    id              bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    coupon_id       bigint      NOT NULL REFERENCES coupon (id),
    user_id         bigint REFERENCES app_user (id),
    order_id        bigint,
    status          varchar(10) NOT NULL CHECK (status IN ('RESERVED', 'CONFIRMED', 'RELEASED')),
    discount_amount bigint      NOT NULL,
    created_at      timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ix_coupon_usage_user ON coupon_usage (coupon_id, user_id) WHERE status <> 'RELEASED';

CREATE TABLE cart (
    id                   uuid PRIMARY KEY,
    user_id              bigint REFERENCES app_user (id) ON DELETE CASCADE,
    -- Carrinho de convidado: só o hash do token que fica no navegador
    token_hash           varchar(64) UNIQUE,
    coupon_id            bigint REFERENCES coupon (id) ON DELETE SET NULL,
    shipping_postal_code varchar(8),
    shipping_option      varchar(20),
    version              int         NOT NULL DEFAULT 0,
    created_at           timestamptz NOT NULL DEFAULT now(),
    updated_at           timestamptz NOT NULL DEFAULT now(),
    CHECK (user_id IS NOT NULL OR token_hash IS NOT NULL)
);
CREATE UNIQUE INDEX ux_cart_user ON cart (user_id) WHERE user_id IS NOT NULL;
CREATE INDEX ix_cart_guest_updated ON cart (updated_at) WHERE user_id IS NULL;

CREATE TABLE cart_item (
    id             bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    cart_id        uuid        NOT NULL REFERENCES cart (id) ON DELETE CASCADE,
    variant_id     bigint      NOT NULL REFERENCES product_variant (id),
    quantity       int         NOT NULL CHECK (quantity BETWEEN 1 AND 10),
    -- Preço visto pelo cliente (para avisar se mudou); o total usa sempre o preço atual
    price_snapshot bigint      NOT NULL,
    created_at     timestamptz NOT NULL DEFAULT now(),
    updated_at     timestamptz NOT NULL DEFAULT now(),
    UNIQUE (cart_id, variant_id)
);

CREATE TABLE wishlist_item (
    user_id    bigint      NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    product_id bigint      NOT NULL REFERENCES product (id) ON DELETE CASCADE,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, product_id)
);
