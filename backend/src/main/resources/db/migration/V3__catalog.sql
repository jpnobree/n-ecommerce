-- Catálogo: categorias (árvore), coleções, cores, tamanhos, produtos, variantes e estoque (PRD, seções 6, 12 e 16).

CREATE TABLE category (
    id               bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    parent_id        bigint REFERENCES category (id),
    name             varchar(80)  NOT NULL,
    slug             varchar(100) NOT NULL CHECK (slug ~ '^[a-z0-9]+(-[a-z0-9]+)*$'),
    -- Caminho de slugs materializado ("feminino/vestidos"): URL da categoria e filtro de subárvore por prefixo
    slug_path        varchar(255) NOT NULL UNIQUE,
    depth            smallint     NOT NULL CHECK (depth BETWEEN 0 AND 2),
    description      text,
    sort_order       int          NOT NULL DEFAULT 0,
    active           boolean      NOT NULL DEFAULT true,
    featured         boolean      NOT NULL DEFAULT false,
    meta_title       varchar(70),
    meta_description varchar(170),
    version          int          NOT NULL DEFAULT 0,
    created_at       timestamptz  NOT NULL DEFAULT now(),
    updated_at       timestamptz  NOT NULL DEFAULT now()
);
CREATE INDEX ix_category_parent ON category (parent_id);
CREATE INDEX ix_category_slug_path ON category (slug_path varchar_pattern_ops);

CREATE TABLE collection (
    id               bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name             varchar(100) NOT NULL,
    slug             varchar(120) NOT NULL UNIQUE CHECK (slug ~ '^[a-z0-9]+(-[a-z0-9]+)*$'),
    description      text,
    starts_at        timestamptz,
    ends_at          timestamptz,
    active           boolean      NOT NULL DEFAULT true,
    sort_order       int          NOT NULL DEFAULT 0,
    meta_title       varchar(70),
    meta_description varchar(170),
    version          int          NOT NULL DEFAULT 0,
    created_at       timestamptz  NOT NULL DEFAULT now(),
    updated_at       timestamptz  NOT NULL DEFAULT now(),
    CHECK (ends_at IS NULL OR starts_at IS NULL OR ends_at > starts_at)
);

CREATE TABLE color (
    id   bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name varchar(40) NOT NULL UNIQUE,
    slug varchar(50) NOT NULL UNIQUE CHECK (slug ~ '^[a-z0-9]+(-[a-z0-9]+)*$'),
    hex  varchar(7) CHECK (hex ~ '^#[0-9A-Fa-f]{6}$')
);

CREATE TABLE size (
    id         bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name       varchar(20) NOT NULL,
    slug       varchar(30) NOT NULL UNIQUE CHECK (slug ~ '^[a-z0-9]+(-[a-z0-9]+)*$'),
    size_group varchar(20) NOT NULL,
    sort_order int         NOT NULL DEFAULT 0,
    UNIQUE (size_group, name)
);

CREATE TABLE product (
    id                  bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name                varchar(150) NOT NULL,
    slug                varchar(170) NOT NULL UNIQUE CHECK (slug ~ '^[a-z0-9]+(-[a-z0-9]+)*$'),
    base_sku            varchar(40)  NOT NULL UNIQUE,
    description         text,
    material            varchar(200),
    care_instructions   text,
    gender              varchar(10)  NOT NULL CHECK (gender IN ('FEMALE', 'MALE', 'UNISEX')),
    main_category_id    bigint       NOT NULL REFERENCES category (id),
    base_price          bigint       NOT NULL CHECK (base_price > 0),
    status              varchar(10)  NOT NULL DEFAULT 'DRAFT' CHECK (status IN ('DRAFT', 'ACTIVE', 'ARCHIVED')),
    featured            boolean      NOT NULL DEFAULT false,
    is_new              boolean      NOT NULL DEFAULT false,
    weight_grams        int          NOT NULL CHECK (weight_grams > 0),
    meta_title          varchar(70),
    meta_description    varchar(170),
    -- Desnormalizados para listagem; recalculados pelo serviço a cada mudança de variante/estoque
    -- e por job quando uma promoção começa ou termina.
    min_base_price      bigint,
    min_effective_price bigint,
    has_stock           boolean      NOT NULL DEFAULT false,
    sales_count         int          NOT NULL DEFAULT 0,
    published_at        timestamptz,
    version             int          NOT NULL DEFAULT 0,
    created_at          timestamptz  NOT NULL DEFAULT now(),
    updated_at          timestamptz  NOT NULL DEFAULT now()
);
CREATE INDEX ix_product_category ON product (main_category_id);
CREATE INDEX ix_product_listing_new ON product (status, has_stock, published_at DESC, id);
CREATE INDEX ix_product_listing_price ON product (status, has_stock, min_effective_price, id);
CREATE INDEX ix_product_listing_sales ON product (status, has_stock, sales_count DESC, id);

CREATE TABLE product_collection (
    product_id    bigint NOT NULL REFERENCES product (id) ON DELETE CASCADE,
    collection_id bigint NOT NULL REFERENCES collection (id) ON DELETE CASCADE,
    PRIMARY KEY (collection_id, product_id)
);
CREATE INDEX ix_product_collection_product ON product_collection (product_id);

CREATE TABLE product_variant (
    id             bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    product_id     bigint      NOT NULL REFERENCES product (id),
    color_id       bigint      NOT NULL REFERENCES color (id),
    size_id        bigint      NOT NULL REFERENCES size (id),
    sku            varchar(60) NOT NULL UNIQUE,
    supplier_ref   varchar(60),
    price          bigint CHECK (price > 0),
    sale_price     bigint CHECK (sale_price > 0),
    sale_starts_at timestamptz,
    sale_ends_at   timestamptz,
    active         boolean     NOT NULL DEFAULT true,
    version        int         NOT NULL DEFAULT 0,
    created_at     timestamptz NOT NULL DEFAULT now(),
    updated_at     timestamptz NOT NULL DEFAULT now(),
    UNIQUE (product_id, color_id, size_id),
    CHECK (sale_ends_at IS NULL OR sale_starts_at IS NULL OR sale_ends_at > sale_starts_at)
);
CREATE INDEX ix_variant_filter ON product_variant (product_id, size_id, color_id) WHERE active;
CREATE INDEX ix_variant_sale_start ON product_variant (sale_starts_at) WHERE sale_price IS NOT NULL;
CREATE INDEX ix_variant_sale_end ON product_variant (sale_ends_at) WHERE sale_price IS NOT NULL;

CREATE TABLE inventory (
    variant_id          bigint PRIMARY KEY REFERENCES product_variant (id),
    on_hand             int         NOT NULL DEFAULT 0,
    reserved            int         NOT NULL DEFAULT 0,
    low_stock_threshold int         NOT NULL DEFAULT 3,
    version             int         NOT NULL DEFAULT 0,
    updated_at          timestamptz NOT NULL DEFAULT now(),
    CHECK (on_hand >= 0 AND reserved >= 0 AND reserved <= on_hand)
);

CREATE TABLE inventory_movement (
    id             bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    variant_id     bigint      NOT NULL REFERENCES product_variant (id),
    type           varchar(12) NOT NULL CHECK (type IN ('RESERVE', 'RELEASE', 'SALE', 'RETURN', 'PURCHASE', 'ADJUSTMENT')),
    quantity       int         NOT NULL CHECK (quantity <> 0),
    on_hand_after  int         NOT NULL,
    reserved_after int         NOT NULL,
    reason         varchar(200),
    reference_type varchar(20),
    reference_id   bigint,
    actor_id       bigint REFERENCES app_user (id),
    created_at     timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ix_movement_variant ON inventory_movement (variant_id, created_at DESC);

-- O histórico de estoque é imutável.
CREATE FUNCTION forbid_movement_change() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'inventory_movement é imutável';
END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER trg_inventory_movement_immutable
    BEFORE UPDATE OR DELETE ON inventory_movement
    FOR EACH ROW EXECUTE FUNCTION forbid_movement_change();
