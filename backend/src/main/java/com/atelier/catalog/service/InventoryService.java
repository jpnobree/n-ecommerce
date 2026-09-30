package com.atelier.catalog.service;

import com.atelier.catalog.api.dto.ProductAdminDtos.StockLevel;
import com.atelier.shared.error.BusinessException;
import com.atelier.shared.error.ErrorCode;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Estoque por variante. Toda alteração é um UPDATE condicional atômico (sem janela entre ler e escrever)
 * mais uma linha imutável em inventory_movement (PRD, seção 12). Reserva/devolução pelo checkout, baixa no pagamento, retorno no reembolso.
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

    /**
     * Reserva para um pedido (PRD 12.3): UPDATE condicional por variante em ordem crescente de id (sem deadlock entre
     * checkouts com os mesmos itens). Só roda dentro da transação do pedido: qualquer falta desfaz tudo.
     * @return variantes sem estoque suficiente (vazio = tudo reservado)
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public List<Long> reserve(Map<Long, Integer> quantities, long orderId) {
        List<Long> missing = new ArrayList<>();
        new TreeMap<>(quantities).forEach((variantId, qty) -> {
            if (!apply("RESERVE", variantId, 0, qty, qty, "ORDER", orderId, "i.on_hand - i.reserved >= :reserved")) missing.add(variantId);
        });
        return missing;
    }

    /** Devolve a reserva de um pedido cancelado ou expirado antes do pagamento. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void release(Map<Long, Integer> quantities, long orderId) {
        new TreeMap<>(quantities).forEach((variantId, qty) -> apply("RELEASE", variantId, 0, -qty, -qty, "ORDER", orderId, "true")); // CHECK (reserved >= 0) acusa inconsistência
    }

    /** Pagamento confirmado: a reserva vira baixa (sai do físico e do reservado). */
    @Transactional(propagation = Propagation.MANDATORY)
    public void commitSale(Map<Long, Integer> quantities, long orderId) {
        new TreeMap<>(quantities).forEach((variantId, qty) -> apply("SALE", variantId, -qty, -qty, -qty, "ORDER", orderId, "true"));
    }

    /** Devolução ao estoque (cancelamento pago ou reembolso com restock). */
    @Transactional(propagation = Propagation.MANDATORY)
    public void restock(Map<Long, Integer> quantities, long refundId) {
        new TreeMap<>(quantities).forEach((variantId, qty) -> apply("RETURN", variantId, qty, 0, qty, "REFUND", refundId, "true"));
    }

    /** quantity: o número com sinal que vai para o ledger. */
    private boolean apply(String type, long variantId, int onHandDelta, int reservedDelta, int quantity, String refType,
                          long refId, String condition) {
        var params = new MapSqlParameterSource().addValue("variantId", variantId).addValue("onHandDelta", onHandDelta)
                .addValue("reserved", reservedDelta).addValue("qty", quantity).addValue("type", type)
                .addValue("refType", refType).addValue("order", refId);
        List<StockLevel> updated = jdbc.query("""
                UPDATE inventory i SET on_hand = i.on_hand + :onHandDelta, reserved = i.reserved + :reserved,
                                       version = i.version + 1, updated_at = now()
                 WHERE i.variant_id = :variantId AND %s
                RETURNING i.on_hand, i.reserved
                """.formatted(condition), params, (rs, n) -> level(rs.getInt(1), rs.getInt(2)));
        if (updated.isEmpty()) return false;
        jdbc.update("""
                INSERT INTO inventory_movement (variant_id, type, quantity, on_hand_after, reserved_after, reference_type, reference_id)
                VALUES (:variantId, :type, :qty, :onHand, :reservedAfter, :refType, :order)
                """, params.addValue("onHand", updated.getFirst().onHand()).addValue("reservedAfter", updated.getFirst().reserved()));
        return true;
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
