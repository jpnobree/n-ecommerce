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

    private final NamedParameterJdbcTemplate jdbc;

    ProductDenormalizer(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void recompute(Collection<Long> productIds) {
        if (productIds.isEmpty()) return;
        jdbc.update(RECOMPUTE.formatted("p2.id IN (:ids)"), new MapSqlParameterSource("ids", productIds));
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
