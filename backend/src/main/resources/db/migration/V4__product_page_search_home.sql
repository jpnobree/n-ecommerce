-- Fase 4: imagens, tabela de medidas, relacionados, histórico de slug, busca textual, banners, newsletter.

CREATE TABLE size_chart (
    id         bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name       varchar(80) NOT NULL UNIQUE,
    -- {"columns": ["Tamanho", "Busto (cm)", ...], "rows": [["P", "84-88", ...], ...]}
    content    jsonb       NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);

ALTER TABLE product
    ADD COLUMN size_chart_id bigint REFERENCES size_chart (id),
    ADD COLUMN tags          text[] NOT NULL DEFAULT '{}',
    -- Mantidos por ProductDenormalizer (busca)
    ADD COLUMN search_name   text,
    ADD COLUMN search_vector tsvector;

CREATE INDEX ix_product_search ON product USING gin (search_vector);
CREATE INDEX ix_product_search_name_trgm ON product USING gin (search_name gin_trgm_ops);

CREATE TABLE product_image (
    id          bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    product_id  bigint       NOT NULL REFERENCES product (id) ON DELETE CASCADE,
    color_id    bigint REFERENCES color (id),
    -- Chave no object storage; nula para imagens externas (seed de demonstração)
    storage_key varchar(300),
    url         varchar(500) NOT NULL,
    alt_text    varchar(200) NOT NULL,
    width       int,
    height      int,
    position    int          NOT NULL DEFAULT 0,
    is_main     boolean      NOT NULL DEFAULT false,
    created_at  timestamptz  NOT NULL DEFAULT now()
);
CREATE INDEX ix_image_product ON product_image (product_id, position);
CREATE UNIQUE INDEX ux_image_main ON product_image (product_id) WHERE is_main;

CREATE TABLE product_related (
    product_id bigint NOT NULL REFERENCES product (id) ON DELETE CASCADE,
    related_id bigint NOT NULL REFERENCES product (id) ON DELETE CASCADE,
    position   int    NOT NULL DEFAULT 0,
    PRIMARY KEY (product_id, related_id),
    CHECK (product_id <> related_id)
);

-- Slugs antigos continuam resolvendo para o produto (redirect 301 na loja)
CREATE TABLE product_slug_history (
    old_slug   varchar(170) PRIMARY KEY,
    product_id bigint       NOT NULL REFERENCES product (id) ON DELETE CASCADE,
    created_at timestamptz  NOT NULL DEFAULT now()
);

CREATE TABLE banner (
    id                bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    position          varchar(10)  NOT NULL CHECK (position IN ('HERO', 'CAMPAIGN', 'STRIP')),
    title             varchar(120) NOT NULL,
    subtitle          varchar(200),
    cta_label         varchar(40),
    link_url          varchar(300),
    image_desktop_url varchar(500),
    image_mobile_url  varchar(500),
    starts_at         timestamptz,
    ends_at           timestamptz,
    active            boolean      NOT NULL DEFAULT true,
    sort_order        int          NOT NULL DEFAULT 0,
    created_at        timestamptz  NOT NULL DEFAULT now(),
    updated_at        timestamptz  NOT NULL DEFAULT now(),
    CHECK (ends_at IS NULL OR starts_at IS NULL OR ends_at > starts_at)
);

CREATE TABLE newsletter_subscriber (
    id              bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    email           citext      NOT NULL UNIQUE,
    consent_at      timestamptz NOT NULL,
    source          varchar(30) NOT NULL DEFAULT 'HOME',
    unsubscribed_at timestamptz,
    created_at      timestamptz NOT NULL DEFAULT now()
);

-- Termos buscados (mais buscados, buscas sem resultado). Retenção: 90 dias.
CREATE TABLE search_log (
    id         bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    term       varchar(100) NOT NULL,
    results    int          NOT NULL,
    created_at timestamptz  NOT NULL DEFAULT now()
);
CREATE INDEX ix_search_log_created ON search_log (created_at);

-- Carga inicial do documento de busca (mesmo SQL de ProductDenormalizer.SEARCH).
UPDATE product p
   SET search_name = lower(unaccent(p.name)),
       search_vector =
           setweight(to_tsvector('portuguese', unaccent(p.name)), 'A')
        || setweight(to_tsvector('portuguese', unaccent(array_to_string(p.tags, ' ') || ' ' || coalesce(src.categories, ''))), 'B')
        || setweight(to_tsvector('portuguese', unaccent(coalesce(src.collections, '') || ' ' || coalesce(p.material, '') || ' ' || coalesce(src.colors, ''))), 'C')
        || setweight(to_tsvector('portuguese', unaccent(coalesce(p.description, ''))), 'D')
  FROM (SELECT p2.id,
               (SELECT string_agg(a.name, ' ') FROM category c
                  JOIN category a ON c.slug_path = a.slug_path OR c.slug_path LIKE a.slug_path || '/%'
                 WHERE c.id = p2.main_category_id) AS categories,
               (SELECT string_agg(co.name, ' ') FROM product_collection pc
                  JOIN collection co ON co.id = pc.collection_id WHERE pc.product_id = p2.id) AS collections,
               (SELECT string_agg(DISTINCT cl.name, ' ') FROM product_variant v
                  JOIN color cl ON cl.id = v.color_id WHERE v.product_id = p2.id AND v.active) AS colors
          FROM product p2) src
 WHERE p.id = src.id;
