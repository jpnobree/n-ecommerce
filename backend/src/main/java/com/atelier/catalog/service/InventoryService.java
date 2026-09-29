package com.atelier.catalog.service;

import com.atelier.catalog.api.dto.ProductAdminDtos.StockLevel;
import com.atelier.shared.error.BusinessException;
import com.atelier.shared.error.ErrorCode;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Estoque por variante. Toda alteração é um UPDATE condicional atômico (sem janela entre ler e escrever)
 * mais uma linha imutável em inventory_movement (PRD, seção 12). Reserva/baixa entram na Fase 6.
 */
@Service
public class InventoryService {

    public enum MovementType { PURCHASE, ADJUSTMENT }

    private final NamedParameterJdbcTemplate jdbc;
    private final ProductDenormalizer denormalizer;

    InventoryService(NamedParameterJdbcTemplate jdbc, ProductDenormalizer denormalizer) {
        this.jdbc = jdbc;
        this.denormalizer = denormalizer;
    }

    /** Toda variante nasce com estoque zerado. */
    void createFor(Long variantId) {
        jdbc.update("INSERT INTO inventory (variant_id) VALUES (:id) ON CONFLICT DO NOTHING", new MapSqlParameterSource("id", variantId));
    }

    /**
     * Entrada (quantidade positiva) ou ajuste (±). Recusa deixar on_hand negativo ou abaixo do reservado.
     */
    @Transactional
    public StockLevel move(Long productId, Long variantId, MovementType type, int quantity, String reason, Long actorId) {
        if (quantity == 0 || (type == MovementType.PURCHASE && quantity < 0)) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "Quantidade inválida para " + type);
        }
        var params = new MapSqlParameterSource()
                .addValue("variantId", variantId).addValue("productId", productId).addValue("qty", quantity)
                .addValue("type", type.name()).addValue("reason", reason).addValue("actor", actorId);

        List<StockLevel> updated = jdbc.query("""
                UPDATE inventory i SET on_hand = i.on_hand + :qty, version = i.version + 1, updated_at = now()
                  FROM product_variant v
                 WHERE i.variant_id = :variantId AND v.id = i.variant_id AND v.product_id = :productId
                   AND i.on_hand + :qty >= i.reserved
                RETURNING i.on_hand, i.reserved
                """, params, (rs, n) -> level(rs.getInt(1), rs.getInt(2)));
        if (updated.isEmpty()) {
            boolean exists = Boolean.TRUE.equals(jdbc.queryForObject(
                    "SELECT EXISTS (SELECT 1 FROM product_variant WHERE id = :variantId AND product_id = :productId)",
                    params, Boolean.class));
            throw new BusinessException(exists ? ErrorCode.STOCK_BELOW_RESERVED : ErrorCode.NOT_FOUND);
        }
        StockLevel level = updated.getFirst();
        jdbc.update("""
                INSERT INTO inventory_movement (variant_id, type, quantity, on_hand_after, reserved_after, reason, reference_type, actor_id)
                VALUES (:variantId, :type, :qty, :onHand, :reserved, :reason, 'MANUAL', :actor)
                """, params.addValue("onHand", level.onHand()).addValue("reserved", level.reserved()));
        denormalizer.recompute(List.of(productId));
        return level;
    }

    @Transactional(readOnly = true)
    public Map<Long, StockLevel> levels(Collection<Long> variantIds) {
        Map<Long, StockLevel> result = new HashMap<>();
        if (variantIds.isEmpty()) return result;
        jdbc.query("SELECT variant_id, on_hand, reserved FROM inventory WHERE variant_id IN (:ids)",
                new MapSqlParameterSource("ids", variantIds),
                rs -> { result.put(rs.getLong(1), level(rs.getInt(2), rs.getInt(3))); });
        return result;
    }

    private static StockLevel level(int onHand, int reserved) {
        return new StockLevel(onHand, reserved, onHand - reserved);
    }
}
