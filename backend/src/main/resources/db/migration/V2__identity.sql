-- Identidade: usuários, papéis, sessões (refresh tokens), tokens de e-mail e endereços (PRD, seções 8 e 16).

CREATE TABLE app_user (
    id                 bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    email              citext       NOT NULL UNIQUE,
    password_hash      varchar(255) NOT NULL,
    full_name          varchar(120) NOT NULL,
    phone              varchar(20),
    cpf                varchar(11) CHECK (cpf ~ '^[0-9]{11}$'),
    birth_date         date,
    email_verified_at  timestamptz,
    status             varchar(20)  NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'BLOCKED', 'DELETED')),
    token_version      int          NOT NULL DEFAULT 0,
    failed_login_count int          NOT NULL DEFAULT 0,
    locked_until       timestamptz,
    marketing_opt_in   boolean      NOT NULL DEFAULT false,
    terms_version      varchar(20),
    terms_accepted_at  timestamptz,
    last_login_at      timestamptz,
    version            int          NOT NULL DEFAULT 0,
    created_at         timestamptz  NOT NULL DEFAULT now(),
    updated_at         timestamptz  NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX ux_user_cpf ON app_user (cpf) WHERE cpf IS NOT NULL;

CREATE TABLE user_role (
    user_id bigint      NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    role    varchar(20) NOT NULL CHECK (role IN ('CUSTOMER', 'OPERATOR', 'ADMIN')),
    PRIMARY KEY (user_id, role)
);

CREATE TABLE refresh_token (
    id          uuid PRIMARY KEY,
    user_id     bigint      NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    token_hash  varchar(64) NOT NULL UNIQUE,
    family_id   uuid        NOT NULL,
    expires_at  timestamptz NOT NULL,
    revoked_at  timestamptz,
    replaced_by uuid,
    user_agent  varchar(255),
    ip          varchar(45),
    created_at  timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ix_refresh_token_user ON refresh_token (user_id);
CREATE INDEX ix_refresh_token_family ON refresh_token (family_id);

CREATE TABLE user_token (
    id         uuid PRIMARY KEY,
    user_id    bigint      NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    type       varchar(20) NOT NULL CHECK (type IN ('EMAIL_VERIFY', 'PASSWORD_RESET')),
    token_hash varchar(64) NOT NULL UNIQUE,
    expires_at timestamptz NOT NULL,
    used_at    timestamptz,
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ix_user_token_user ON user_token (user_id, type);

CREATE TABLE address (
    id             bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id        bigint       NOT NULL REFERENCES app_user (id),
    label          varchar(40),
    recipient_name varchar(120) NOT NULL,
    phone          varchar(20)  NOT NULL,
    postal_code    varchar(8)   NOT NULL CHECK (postal_code ~ '^[0-9]{8}$'),
    state          varchar(2)   NOT NULL,
    city           varchar(100) NOT NULL,
    district       varchar(100) NOT NULL,
    street         varchar(200) NOT NULL,
    number         varchar(20)  NOT NULL,
    complement     varchar(100),
    reference      varchar(200),
    is_default     boolean      NOT NULL DEFAULT false,
    deleted_at     timestamptz,
    created_at     timestamptz  NOT NULL DEFAULT now(),
    updated_at     timestamptz  NOT NULL DEFAULT now()
);
CREATE INDEX ix_address_user ON address (user_id) WHERE deleted_at IS NULL;
CREATE UNIQUE INDEX ux_address_default ON address (user_id) WHERE is_default AND deleted_at IS NULL;
