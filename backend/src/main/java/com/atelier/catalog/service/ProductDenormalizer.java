package com.atelier.catalog.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Collection;

/**
 * Mantém as colunas de listagem do produto (menor preço cheio, menor preço efetivo, tem estoque).
 * O preço efetivo segue a mesma regra de {@link com.atelier.catalog.domain.ProductVariant#effectivePrice}.
 */
@Component
public class ProductDenormalizer {

    private static final Logger log = LoggerFactory.getLogger(ProductDenormalizer.class);

    private static final String RECOMPUTE = """
            UPDATE product p
               SET min_base_price = agg.min_base,
                   min_effective_price = agg.min_effective,
                   has_stock = agg.has_stock
              FROM (SELECT p2.id,
                           min(coalesce(v.price, p2.base_price)) AS min_base,
                           min(coalesce(CASE WHEN v.sale_price IS NOT NULL
                                              AND (v.sale_starts_at IS NULL OR v.sale_starts_at <= now())
                                              AND (v.sale_ends_at IS NULL OR v.sale_ends_at > now())
                                             THEN v.sale_price END,
                                        v.price, p2.base_price)) AS min_effective,
                           coalesce(bool_or(i.on_hand - i.reserved > 0), false) AS has_stock
                      FROM product p2
                      LEFT JOIN product_variant v ON v.product_id = p2.id AND v.active
                      LEFT JOIN inventory i ON i.variant_id = v.id
                     WHERE %s
                     GROUP BY p2.id) agg
             WHERE p.id = agg.id
            """;

    /**
     * Documento de busca (PRD 5.1): nome (A), tags + categorias com ancestrais (B), coleções, material e cores (C),
     * descrição (D). Sem acento e com stemming em português ("camisetas" encontra "Camiseta").
     * Mesmo SQL da migration V4 (carga inicial).
     */
    private static final String SEARCH = """
            UPDATE product p
               SET search_name = lower(unaccent(p.name)),
                   search_vector =
                       setweight(to_tsvector('portuguese', unaccent(p.name)), 'A')
                    || setweight(to_tsvector('portuguese', unaccent(array_to_string(p.tags, ' ') || ' ' || coalesce(src.categories, ''))), 'B')
                    || setweight(to_tsvector('portuguese', unaccent(coalesce(src.collections, '') || ' ' || coalesce(p.material, '') || ' ' || coalesce(src.colors, ''))), 'C')
                    || setweight(to_tsvector('portuguese', unaccent(coalesce(p.description, ''))), 'D')
              FROM (SELECT p2.id,
                           (SELECT string_agg(a.name, ' ') FROM category c
                              JOIN category a ON c.slug_path = a.slug_path OR c.slug_path LIKE a.slug_path || '/%%'
                             WHERE c.id = p2.main_category_id) AS categories,
                           (SELECT string_agg(co.name, ' ') FROM product_collection pc
                              JOIN collection co ON co.id = pc.collection_id WHERE pc.product_id = p2.id) AS collections,
                           (SELECT string_agg(DISTINCT cl.name, ' ') FROM product_variant v
                              JOIN color cl ON cl.id = v.color_id WHERE v.product_id = p2.id AND v.active) AS colors
                      FROM product p2
                     WHERE %s) src
             WHERE p.id = src.id
            """;

    private final NamedParameterJdbcTemplate jdbc;

    ProductDenormalizer(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void recompute(Collection<Long> productIds) {
        if (productIds.isEmpty()) return;
        var params = new MapSqlParameterSource("ids", productIds);
        jdbc.update(RECOMPUTE.formatted("p2.id IN (:ids)"), params);
        jdbc.update(SEARCH.formatted("p2.id IN (:ids)"), params);
    }

    /**
     * Renomear categoria, coleção ou cor muda o documento de busca de muitos produtos.
     * ponytail: reindexa tudo (alguns segundos com 50 mil produtos); filtrar pelos afetados se ficar lento.
     */
    public void recomputeAllSearch() {
        jdbc.update(SEARCH.formatted("true"), new MapSqlParameterSource());
    }

    /**
     * Promoções que começaram ou terminaram nos últimos 2 min mudam o preço efetivo sem nenhuma escrita:
     * recalcula esses produtos. Idempotente, então rodar em várias instâncias não causa dano.
     */
    @Scheduled(fixedDelayString = "${app.catalog.sale-refresh-interval:60s}")
    public void refreshSaleBoundaries() {
        int updated = jdbc.update(RECOMPUTE.formatted("""
                p2.id IN (SELECT product_id FROM product_variant
                           WHERE sale_price IS NOT NULL
                             AND (sale_starts_at BETWEEN now() - interval '2 minutes' AND now()
                                  OR sale_ends_at BETWEEN now() - interval '2 minutes' AND now()))
                """), new MapSqlParameterSource());
        if (updated > 0) log.info("Preços promocionais recalculados em {} produto(s)", updated);
    }
}
