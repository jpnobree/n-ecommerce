-- Fase 6: checkout cria o pedido com snapshot e reserva o estoque por 30 min (PRD, seções 9, 11 e 12).

CREATE SEQUENCE order_number_seq;

CREATE TABLE orders (
    id                bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    order_number      varchar(20)  NOT NULL UNIQUE,
    user_id           bigint       NOT NULL REFERENCES app_user (id),
    -- Carrinho de origem e sua versão: reaproveita o pedido pendente se nada mudou (duplo clique, nova aba)
    cart_id           uuid         NOT NULL,
    cart_version      int          NOT NULL,
    idempotency_key   uuid         NOT NULL,
    request_hash      varchar(64)  NOT NULL,
    status            varchar(20)  NOT NULL CHECK (status IN ('PENDING_PAYMENT', 'PAYMENT_PROCESSING', 'PAID', 'PROCESSING',
                                                              'SHIPPED', 'DELIVERED', 'CANCELLED', 'REFUNDED', 'PARTIALLY_REFUNDED')),
    customer_name     varchar(120) NOT NULL,
    customer_email    varchar(254) NOT NULL,
    customer_phone    varchar(20),
    customer_document varchar(11),
    subtotal          bigint       NOT NULL,
    discount_total    bigint       NOT NULL,
    shipping_total    bigint       NOT NULL,
    shipping_discount bigint       NOT NULL,
    total             bigint       NOT NULL CHECK (total >= 0),
    currency          char(3)      NOT NULL DEFAULT 'BRL',
    coupon_id         bigint REFERENCES coupon (id),
    coupon_code       varchar(40),
    coupon_snapshot   jsonb,
    shipping_address  jsonb        NOT NULL,
    shipping_method   jsonb        NOT NULL,
    ip                varchar(45),
    user_agent        varchar(300),
    placed_at         timestamptz  NOT NULL DEFAULT now(),
    expires_at        timestamptz  NOT NULL,
    paid_at           timestamptz,
    cancelled_at      timestamptz,
    cancel_reason     varchar(30),
    version           int          NOT NULL DEFAULT 0,
    updated_at        timestamptz  NOT NULL DEFAULT now(),
    UNIQUE (user_id, idempotency_key),
    CHECK (total = subtotal - discount_total + shipping_total - shipping_discount)
);
-- Um pedido aguardando pagamento por carrinho: segunda barreira contra pedido duplicado
CREATE UNIQUE INDEX ux_orders_pending_cart ON orders (cart_id) WHERE status = 'PENDING_PAYMENT';
CREATE INDEX ix_orders_expiring ON orders (expires_at) WHERE status = 'PENDING_PAYMENT';
CREATE INDEX ix_orders_user ON orders (user_id, placed_at DESC);

CREATE TABLE order_item (
    id                 bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    order_id           bigint       NOT NULL REFERENCES orders (id),
    product_id         bigint REFERENCES product (id) ON DELETE SET NULL,
    variant_id         bigint REFERENCES product_variant (id) ON DELETE SET NULL,
    sku                varchar(60)  NOT NULL,
    product_name       varchar(200) NOT NULL,
    product_slug       varchar(200) NOT NULL,
    color_name         varchar(60)  NOT NULL,
    size_name          varchar(20)  NOT NULL,
    image_url          text,
    unit_price         bigint       NOT NULL,
    list_price         bigint       NOT NULL,
    quantity           int          NOT NULL CHECK (quantity BETWEEN 1 AND 10),
    discount_allocated bigint       NOT NULL DEFAULT 0,
    line_total         bigint       NOT NULL,
    weight_grams       int          NOT NULL,
    quantity_refunded  int          NOT NULL DEFAULT 0
);
CREATE INDEX ix_order_item_order ON order_item (order_id);

ALTER TABLE coupon_usage ADD CONSTRAINT fk_coupon_usage_order FOREIGN KEY (order_id) REFERENCES orders (id);
CREATE INDEX ix_coupon_usage_order ON coupon_usage (order_id);
