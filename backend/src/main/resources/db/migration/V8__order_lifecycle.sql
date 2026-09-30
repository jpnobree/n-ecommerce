-- Fase 8: máquina de estados do pedido, histórico com ator, envio (status logístico separado) e notas internas (PRD, seção 11).

-- Status logístico separado do financeiro: pedido parcialmente reembolsado antes do envio continua sendo enviado
ALTER TABLE orders
    ADD COLUMN fulfillment_status varchar(12) NOT NULL DEFAULT 'UNFULFILLED'
        CHECK (fulfillment_status IN ('UNFULFILLED', 'PROCESSING', 'SHIPPED', 'DELIVERED')),
    ADD COLUMN carrier       varchar(40),
    ADD COLUMN tracking_code varchar(40),
    ADD COLUMN shipped_at    timestamptz,
    ADD COLUMN delivered_at  timestamptz,
    ADD CONSTRAINT ck_orders_shipped_tracking
        CHECK (fulfillment_status IN ('UNFULFILLED', 'PROCESSING') OR (carrier IS NOT NULL AND tracking_code IS NOT NULL));
CREATE INDEX ix_orders_status ON orders (status, placed_at DESC);

CREATE TABLE order_status_history (
    id          bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    order_id    bigint      NOT NULL REFERENCES orders (id),
    -- STATUS: financeiro/geral; FULFILLMENT: só o logístico mudou (ex.: pedido parcialmente reembolsado sendo enviado)
    kind        varchar(12) NOT NULL DEFAULT 'STATUS' CHECK (kind IN ('STATUS', 'FULFILLMENT')),
    from_status varchar(20),
    to_status   varchar(20) NOT NULL,
    actor_type  varchar(10) NOT NULL CHECK (actor_type IN ('SYSTEM', 'CUSTOMER', 'OPERATOR', 'ADMIN', 'STRIPE')),
    actor_id    bigint REFERENCES app_user (id),
    reason      varchar(200),
    created_at  timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ix_order_history_order ON order_status_history (order_id, id);

-- Pedidos já existentes (dev): o estado atual vira a primeira linha do histórico
INSERT INTO order_status_history (order_id, to_status, actor_type, reason, created_at)
SELECT id, status, 'SYSTEM', 'Histórico iniciado na migração', placed_at FROM orders;

CREATE TABLE order_note (
    id         bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    order_id   bigint        NOT NULL REFERENCES orders (id),
    author_id  bigint        NOT NULL REFERENCES app_user (id),
    text       varchar(2000) NOT NULL,
    created_at timestamptz   NOT NULL DEFAULT now()
);
CREATE INDEX ix_order_note_order ON order_note (order_id);
