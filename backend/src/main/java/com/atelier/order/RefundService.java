package com.atelier.order;

import com.atelier.shared.error.BusinessException;
import com.atelier.shared.error.ErrorCode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.util.*;

/**
 * Reembolso total, parcial por itens ou por valor, e cancelamento de pedido pago ainda não enviado (PRD 10.7).
 * O pedido fica travado enquanto o saldo é calculado: dois admins ao mesmo tempo nunca passam do valor pago.
 * Itens, estoque e status mudam só quando a Stripe confirma (webhook), no {@link PaymentService}.
 */
@Service
class RefundService {

    record RefundItem(@NotNull Long orderItemId, @Min(1) @Max(10) int quantity) {}

    /** amount em centavos (sem itens); cancelOrder = reembolso do saldo inteiro + devolução de tudo ao estoque. */
    record RefundRequest(@Positive Long amount, @Valid List<RefundItem> items, Boolean restock, Boolean cancelOrder,
                         @NotBlank @Size(max = 200) String reason) {}

    record RefundView(long id, String status, long amount, String reason, String failureMessage) {}

    private static final Set<String> REFUNDABLE = Set.of("PAID", "PROCESSING", "SHIPPED", "DELIVERED", "PARTIALLY_REFUNDED");

    private final PaymentService payments;
    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final JsonMapper json;

    RefundService(PaymentService payments, NamedParameterJdbcTemplate jdbc, TransactionTemplate tx, JsonMapper json) {
        this.payments = payments;
        this.jdbc = jdbc;
        this.tx = tx;
        this.json = json;
    }

    RefundView create(long adminId, String orderNumber, UUID key, RefundRequest req) {
        boolean[] created = {false};
        long id = tx.execute(s -> {
            var existing = jdbc.queryForList("SELECT id FROM refund WHERE idempotency_key = :key",
                    new MapSqlParameterSource("key", key), Long.class);
            if (!existing.isEmpty()) return existing.getFirst();

            var order = jdbc.query("SELECT id, status FROM orders WHERE order_number = :n FOR UPDATE",
                            new MapSqlParameterSource("n", orderNumber), (rs, n) -> new Object[]{rs.getLong(1), rs.getString(2)})
                    .stream().findFirst().orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "Pedido não encontrado"));
            long orderId = (long) order[0];
            String status = (String) order[1];
            boolean cancel = Boolean.TRUE.equals(req.cancelOrder());
            if (!REFUNDABLE.contains(status)) throw new BusinessException(ErrorCode.INVALID_STATUS_TRANSITION, "Pedido sem pagamento a reembolsar");
            if (cancel && !(status.equals("PAID") || status.equals("PROCESSING"))) {
                throw new BusinessException(ErrorCode.INVALID_STATUS_TRANSITION, "Pedido já enviado: use reembolso por itens");
            }
            var params = new MapSqlParameterSource("order", orderId);
            long[] payment = jdbc.queryForObject("SELECT id, amount_received FROM payment WHERE order_id = :order AND status = 'SUCCEEDED'",
                    params, (rs, n) -> new long[]{rs.getLong(1), rs.getLong(2)});
            long committed = jdbc.queryForObject("SELECT coalesce(sum(amount), 0) FROM refund WHERE order_id = :order AND status <> 'FAILED'",
                    params, Long.class);
            long balance = payment[1] - committed;

            // Por item: valor pago proporcional (já com o desconto rateado)
            Map<Long, long[]> lines = new HashMap<>(); // id -> [line_total, quantity, quantity_refunded]
            jdbc.query("SELECT id, line_total, quantity, quantity_refunded FROM order_item WHERE order_id = :order", params,
                    rs -> { lines.put(rs.getLong(1), new long[]{rs.getLong(2), rs.getInt(3), rs.getInt(4)}); });
            List<Map<String, Object>> items = new ArrayList<>();
            long amount;
            if (cancel) {
                lines.forEach((itemId, l) -> {
                    if (l[1] > l[2]) items.add(Map.of("orderItemId", itemId, "quantity", l[1] - l[2]));
                });
                amount = balance;
            } else if (req.items() != null && !req.items().isEmpty()) {
                amount = 0;
                for (RefundItem i : req.items()) {
                    long[] l = lines.get(i.orderItemId());
                    if (l == null || i.quantity() > l[1] - l[2]) {
                        throw new BusinessException(ErrorCode.VALIDATION_ERROR, "Item " + i.orderItemId() + " inválido ou já reembolsado");
                    }
                    amount += l[0] * i.quantity() / l[1];
                    items.add(Map.of("orderItemId", i.orderItemId(), "quantity", i.quantity()));
                }
            } else {
                amount = req.amount() == null ? balance : req.amount();
            }
            if (amount <= 0 || amount > balance) throw new BusinessException(ErrorCode.REFUND_EXCEEDS_BALANCE);

            created[0] = true;
            return jdbc.queryForObject("""
                    INSERT INTO refund (payment_id, order_id, idempotency_key, amount, reason, status, items, restock, cancel_order, requested_by)
                    VALUES (:payment, :order, :key, :amount, :reason, 'PENDING', CAST(:items AS jsonb), :restock, :cancel, :admin)
                    RETURNING id
                    """, params.addValue("payment", payment[0]).addValue("key", key).addValue("amount", amount)
                    .addValue("reason", req.reason()).addValue("items", json.writeValueAsString(items))
                    .addValue("restock", cancel || Boolean.TRUE.equals(req.restock())).addValue("cancel", cancel)
                    .addValue("admin", adminId), Long.class);
        });
        if (created[0]) payments.sendRefund(id);
        return jdbc.queryForObject("SELECT id, status, amount, reason, failure_message FROM refund WHERE id = :id",
                new MapSqlParameterSource("id", id), (rs, n) -> new RefundView(rs.getLong(1), rs.getString(2), rs.getLong(3),
                        rs.getString(4), rs.getString(5)));
    }
}
