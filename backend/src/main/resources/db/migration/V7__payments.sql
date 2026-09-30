-- Fase 7: Stripe (PaymentIntent por pedido, webhooks idempotentes, reembolsos, disputas) e outbox (PRD, seção 10).

ALTER TABLE orders
    ADD COLUMN payment_review boolean NOT NULL DEFAULT false, -- valor recebido ≠ total: não enviar antes de revisar
    ADD COLUMN has_dispute    boolean NOT NULL DEFAULT false;

CREATE TABLE payment (
    id                       bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    order_id                 bigint       NOT NULL REFERENCES orders (id),
    provider                 varchar(10)  NOT NULL DEFAULT 'STRIPE',
    stripe_payment_intent_id varchar(64)  NOT NULL UNIQUE,
    client_secret            varchar(200) NOT NULL,
    status                   varchar(25)  NOT NULL CHECK (status IN ('REQUIRES_PAYMENT_METHOD', 'REQUIRES_ACTION', 'PROCESSING',
                                                                     'SUCCEEDED', 'CANCELED')),
    amount                   bigint       NOT NULL,
    amount_received          bigint       NOT NULL DEFAULT 0,
    amount_refunded          bigint       NOT NULL DEFAULT 0,
    currency                 char(3)      NOT NULL DEFAULT 'BRL',
    payment_method_type      varchar(20),
    card_brand               varchar(20),
    card_last4               varchar(4),
    failure_code             varchar(60),
    failure_message          varchar(300),
    attempt                  int          NOT NULL,
    created_at               timestamptz  NOT NULL DEFAULT now(),
    updated_at               timestamptz  NOT NULL DEFAULT now(),
    CHECK (amount_refunded <= amount_received)
);
-- Um intent vivo por pedido (os cancelados ficam como histórico)
CREATE UNIQUE INDEX ux_payment_active_order ON payment (order_id) WHERE status <> 'CANCELED';

CREATE TABLE refund (
    id               bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    payment_id       bigint      NOT NULL REFERENCES payment (id),
    order_id         bigint      NOT NULL REFERENCES orders (id),
    stripe_refund_id varchar(64) UNIQUE,
    idempotency_key  uuid UNIQUE,
    amount           bigint      NOT NULL CHECK (amount > 0),
    reason           varchar(200) NOT NULL,
    status           varchar(10) NOT NULL CHECK (status IN ('PENDING', 'SUCCEEDED', 'FAILED')),
    -- Efeitos aplicados só quando a Stripe confirma: itens (order_item_id, quantity), devolver ao estoque, cancelar pedido
    items            jsonb       NOT NULL DEFAULT '[]',
    restock          boolean     NOT NULL DEFAULT false,
    cancel_order     boolean     NOT NULL DEFAULT false,
    requested_by     bigint REFERENCES app_user (id),
    failure_message  varchar(300),
    created_at       timestamptz NOT NULL DEFAULT now(),
    updated_at       timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ix_refund_order ON refund (order_id);

CREATE TABLE dispute (
    id                bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    payment_id        bigint      NOT NULL REFERENCES payment (id),
    stripe_dispute_id varchar(64) NOT NULL UNIQUE,
    amount            bigint      NOT NULL,
    reason            varchar(60),
    status            varchar(30) NOT NULL,
    evidence_due_by   timestamptz,
    created_at        timestamptz NOT NULL DEFAULT now(),
    updated_at        timestamptz NOT NULL DEFAULT now()
);

-- Deduplicação por event.id: a mesma entrega repetida tem um único efeito
CREATE TABLE stripe_event (
    id           varchar(64) PRIMARY KEY,
    type         varchar(60) NOT NULL,
    livemode     boolean     NOT NULL,
    payload      jsonb       NOT NULL,
    status       varchar(10) NOT NULL CHECK (status IN ('RECEIVED', 'PROCESSED', 'FAILED', 'IGNORED')),
    attempts     int         NOT NULL DEFAULT 0,
    last_error   varchar(500),
    received_at  timestamptz NOT NULL DEFAULT now(),
    processed_at timestamptz
);

-- Efeitos colaterais gravados na mesma transação da mudança (e-mail de pedido pago não se perde se o processo cair)
CREATE TABLE outbox_event (
    id             bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    aggregate_type varchar(30) NOT NULL,
    aggregate_id   bigint      NOT NULL,
    event_type     varchar(40) NOT NULL,
    payload        jsonb       NOT NULL,
    status         varchar(10) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'PUBLISHED', 'FAILED')),
    attempts       int         NOT NULL DEFAULT 0,
    created_at     timestamptz NOT NULL DEFAULT now(),
    published_at   timestamptz
);
CREATE INDEX ix_outbox_pending ON outbox_event (id) WHERE status = 'PENDING';
