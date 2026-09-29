-- Dados de demonstração para desenvolvimento/homologação (perfil "seed"). Nunca roda em produção.
-- Idempotente: não faz nada se já houver produtos.
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM product) THEN
        RETURN;
    END IF;

    INSERT INTO color (name, slug, hex) VALUES
        ('Preto', 'preto', '#111111'), ('Branco', 'branco', '#FFFFFF'), ('Bege', 'bege', '#D8C7A6'),
        ('Azul-marinho', 'azul-marinho', '#1F2A44'), ('Cinza', 'cinza', '#8A8A8A'), ('Verde-oliva', 'verde-oliva', '#6B7045'),
        ('Vermelho', 'vermelho', '#A3232B'), ('Rosa', 'rosa', '#E8B4B8'), ('Marrom', 'marrom', '#6B4A2E'),
        ('Off-white', 'off-white', '#F5F0E6')
    ON CONFLICT DO NOTHING;

    INSERT INTO size (name, slug, size_group, sort_order) VALUES
        ('PP', 'pp', 'ROUPA', 1), ('P', 'p', 'ROUPA', 2), ('M', 'm', 'ROUPA', 3), ('G', 'g', 'ROUPA', 4), ('GG', 'gg', 'ROUPA', 5),
        ('36', '36', 'CALCA', 1), ('38', '38', 'CALCA', 2), ('40', '40', 'CALCA', 3), ('42', '42', 'CALCA', 4),
        ('44', '44', 'CALCA', 5), ('46', '46', 'CALCA', 6),
        ('Único', 'u', 'UNICO', 1)
    ON CONFLICT DO NOTHING;

    INSERT INTO category (name, slug, slug_path, depth, sort_order, featured) VALUES
        ('Feminino', 'feminino', 'feminino', 0, 1, true),
        ('Masculino', 'masculino', 'masculino', 0, 2, true),
        ('Acessórios', 'acessorios', 'acessorios', 0, 3, true)
    ON CONFLICT DO NOTHING;

    INSERT INTO category (parent_id, name, slug, slug_path, depth, sort_order)
    SELECT parent.id, t.name, t.slug, parent.slug_path || '/' || t.slug, 1, t.ord
      FROM (VALUES ('feminino', 'Vestidos', 'vestidos', 1), ('feminino', 'Blusas', 'blusas', 2),
                   ('feminino', 'Calças', 'calcas', 3), ('feminino', 'Saias', 'saias', 4),
                   ('masculino', 'Camisetas', 'camisetas', 1), ('masculino', 'Calças', 'calcas', 2),
                   ('masculino', 'Jaquetas', 'jaquetas', 3), ('masculino', 'Camisas', 'camisas', 4),
                   ('acessorios', 'Bolsas', 'bolsas', 1), ('acessorios', 'Bonés', 'bones', 2),
                   ('acessorios', 'Cintos', 'cintos', 3)) AS t(parent, name, slug, ord)
      JOIN category parent ON parent.slug_path = t.parent
    ON CONFLICT DO NOTHING;

    INSERT INTO collection (name, slug, description, sort_order) VALUES
        ('Verão 2027', 'verao-2027', 'Leveza, linho e cores da estação.', 1),
        ('Essenciais', 'essenciais', 'Peças-base para o dia a dia.', 2),
        ('Alfaiataria', 'alfaiataria', 'Cortes precisos e tecidos nobres.', 3)
    ON CONFLICT DO NOTHING;

    CREATE TEMP TABLE seed_leaf ON COMMIT DROP AS
    SELECT (row_number() OVER (ORDER BY t.ord) - 1)::int AS idx, c.id AS category_id, t.type_name, t.gender, t.size_group
      FROM (VALUES (1, 'feminino/vestidos', 'Vestido', 'FEMALE', 'ROUPA'), (2, 'feminino/blusas', 'Blusa', 'FEMALE', 'ROUPA'),
                   (3, 'feminino/calcas', 'Calça', 'FEMALE', 'CALCA'), (4, 'feminino/saias', 'Saia', 'FEMALE', 'ROUPA'),
                   (5, 'masculino/camisetas', 'Camiseta', 'MALE', 'ROUPA'), (6, 'masculino/calcas', 'Calça', 'MALE', 'CALCA'),
                   (7, 'masculino/jaquetas', 'Jaqueta', 'MALE', 'ROUPA'), (8, 'masculino/camisas', 'Camisa', 'MALE', 'ROUPA'),
                   (9, 'acessorios/bolsas', 'Bolsa', 'UNISEX', 'UNICO'), (10, 'acessorios/bones', 'Boné', 'UNISEX', 'UNICO'),
                   (11, 'acessorios/cintos', 'Cinto', 'UNISEX', 'UNICO')) AS t(ord, path, type_name, gender, size_group)
      JOIN category c ON c.slug_path = t.path;

    INSERT INTO product (name, slug, base_sku, description, material, care_instructions, gender, main_category_id,
                         base_price, status, featured, is_new, weight_grams, sales_count, published_at)
    SELECT n.name,
           regexp_replace(lower(unaccent(n.name)), '[^a-z0-9]+', '-', 'g') || '-' || g,
           'AT' || lpad(g::text, 5, '0'),
           'Peça ' || lower(n.adj) || ' em ' || lower(n.mat) || ', pensada para o dia a dia.',
           n.mat,
           'Lavar à mão ou na máquina a 30°C. Não usar alvejante.',
           l.gender, l.category_id,
           4990 + ((g * 7919) % 40) * 1000,
           'ACTIVE', g % 50 = 0, g % 17 = 0, 300,
           (g * 31) % 500,
           now() - make_interval(days => g % 365)
      FROM generate_series(1, 2000) AS g
      JOIN seed_leaf l ON l.idx = g % 11
      CROSS JOIN LATERAL (
          SELECT (ARRAY['Oversized','Slim','Midi','Básico','Estruturado','Fluido','Canelado','Cropped','Longo','Clássico'])[1 + g % 10] AS adj,
                 (ARRAY['Algodão','Linho','Viscose','Lã','Couro','Jeans','Seda','Tricô'])[1 + (g / 10) % 8] AS mat
      ) a
      CROSS JOIN LATERAL (SELECT l.type_name || ' ' || a.adj || ' ' || a.mat AS name, a.adj, a.mat) n;

    -- 1 a 3 cores por produto, todos os tamanhos da grade; 1 em cada 7 produtos em promoção de 30%.
    INSERT INTO product_variant (product_id, color_id, size_id, sku, sale_price)
    SELECT p.id, c.id, s.id, p.base_sku || '-' || upper(c.slug) || '-' || upper(s.slug),
           CASE WHEN p.id % 7 = 0 THEN round(p.base_price * 0.7) END
      FROM product p
      JOIN seed_leaf l ON l.category_id = p.main_category_id
      JOIN size s ON s.size_group = l.size_group
      JOIN (SELECT id, slug, row_number() OVER (ORDER BY id) AS rn FROM color) c ON (c.rn + p.id) % 10 < 1 + p.id % 3;

    -- Estoque variado: parte das variantes esgotada.
    INSERT INTO inventory (variant_id, on_hand)
    SELECT id, greatest(0, (id * 37) % 13 - 3) FROM product_variant;

    INSERT INTO inventory_movement (variant_id, type, quantity, on_hand_after, reserved_after, reason, reference_type)
    SELECT variant_id, 'PURCHASE', on_hand, on_hand, 0, 'Carga inicial (seed)', 'SEED'
      FROM inventory WHERE on_hand > 0;

    INSERT INTO product_collection (product_id, collection_id)
    SELECT p.id, c.id FROM product p JOIN collection c ON c.slug = 'verao-2027' WHERE p.id % 5 = 0
    UNION
    SELECT p.id, c.id FROM product p JOIN collection c ON c.slug = 'essenciais' WHERE p.id % 7 = 1
    UNION
    SELECT p.id, c.id FROM product p JOIN category cat ON cat.id = p.main_category_id
      JOIN collection c ON c.slug = 'alfaiataria' WHERE cat.slug IN ('calcas', 'camisas') AND p.id % 3 = 0;

    -- Mesmo cálculo de ProductDenormalizer.
    UPDATE product p
       SET min_base_price = agg.min_base, min_effective_price = agg.min_effective, has_stock = agg.has_stock
      FROM (SELECT p2.id,
                   min(coalesce(v.price, p2.base_price)) AS min_base,
                   min(coalesce(CASE WHEN v.sale_price IS NOT NULL
                                      AND (v.sale_starts_at IS NULL OR v.sale_starts_at <= now())
                                      AND (v.sale_ends_at IS NULL OR v.sale_ends_at > now())
                                     THEN v.sale_price END, v.price, p2.base_price)) AS min_effective,
                   coalesce(bool_or(i.on_hand - i.reserved > 0), false) AS has_stock
              FROM product p2
              LEFT JOIN product_variant v ON v.product_id = p2.id AND v.active
              LEFT JOIN inventory i ON i.variant_id = v.id
             GROUP BY p2.id) agg
     WHERE p.id = agg.id;
END
$$;
