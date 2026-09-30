package com.atelier.order;

import com.atelier.order.OrderService.OrderView;
import com.atelier.order.OrderStateMachine.Actor;
import com.atelier.order.OrderStateMachine.ActorType;
import com.atelier.order.PaymentService.PaymentRow;
import com.atelier.order.RefundService.RefundRequest;
import com.atelier.shared.error.BusinessException;
import com.atelier.shared.error.ErrorCode;
import com.atelier.shared.web.PageResponse;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Operação de pedidos (PRD 15.3): busca, detalhe com linha do tempo, envio, notas internas e cancelamento.
 * Operador avança só o status logístico; cancelar e reembolsar é de ADMIN. Alterações exigem a versão lida
 * (dois operadores no mesmo pedido: o segundo recebe 409 CONCURRENT_MODIFICATION).
 */
@Service
class OrderAdminService {

    record AdminOrderSummary(String orderNumber, String status, String fulfillmentStatus, Instant placedAt, String customerName,
                             String customerEmail, long total, int itemCount, boolean paymentReview, boolean hasDispute) {}

    record HistoryEntry(String kind, String from, String to, String actorType, String actorName, String reason, Instant at) {}

    record NoteView(long id, String author, String text, Instant at) {}

    record RefundRow(long id, String status, long amount, String reason, String failureMessage, Instant createdAt) {}

    record DisputeRow(String status, long amount, String reason, Instant evidenceDueBy) {}

    record AdminOrderView(OrderView order, long customerId, String customerName, String customerEmail, String customerPhone,
                          boolean paymentReview, boolean hasDispute, List<PaymentRow> payments,
                          List<RefundRow> refunds, List<DisputeRow> disputes, List<HistoryEntry> history, List<NoteView> internalNotes) {}

    private static final List<String> STEPS = List.of("UNFULFILLED", "PROCESSING", "SHIPPED", "DELIVERED");
    /** Pagos e não encerrados: podem seguir no fluxo logístico. */
    private static final Set<String> SHIPPABLE = Set.of("PAID", "PROCESSING", "SHIPPED", "DELIVERED", "PARTIALLY_REFUNDED");
    /** Status que acompanham o logístico; em PARTIALLY_REFUNDED só o fulfillment_status anda. */
    private static final Set<String> LOGISTIC = Set.of("PAID", "PROCESSING", "SHIPPED");

    private final OrderService orders;
    private final PaymentService payments;
    private final RefundService refunds;
    private final OrderStateMachine states;
    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate tx;

    OrderAdminService(OrderService orders, PaymentService payments, RefundService refunds, OrderStateMachine states,
                      NamedParameterJdbcTemplate jdbc, TransactionTemplate tx) {
        this.orders = orders;
        this.payments = payments;
        this.refunds = refunds;
        this.states = states;
        this.jdbc = jdbc;
        this.tx = tx;
    }

    /** q: número, e-mail, nome, CPF ou SKU. ponytail: ILIKE com % dos dois lados; índice trigram se a base crescer. */
    PageResponse<AdminOrderSummary> search(String q, String status, String fulfillment, Instant from, Instant to,
                                           Boolean hasDispute, int page, int size) {
        String term = q == null || q.isBlank() ? null : q.trim();
        var params = new MapSqlParameterSource("q", term == null ? null : "%" + term.replace("%", "\\%") + "%")
                .addValue("digits", term == null ? null : term.replaceAll("\\D", ""))
                .addValue("status", status).addValue("fulfillment", fulfillment)
                .addValue("from", from == null ? null : Timestamp.from(from)).addValue("to", to == null ? null : Timestamp.from(to))
                .addValue("dispute", hasDispute).addValue("limit", size).addValue("offset", (long) page * size);
        String where = """
                 WHERE (CAST(:q AS varchar) IS NULL OR o.order_number ILIKE :q OR o.customer_email ILIKE :q OR o.customer_name ILIKE :q
                        OR (length(CAST(:digits AS varchar)) = 11 AND o.customer_document = :digits)
                        OR EXISTS (SELECT 1 FROM order_item oi WHERE oi.order_id = o.id AND oi.sku ILIKE :q))
                   AND (CAST(:status AS varchar) IS NULL OR o.status = :status)
                   AND (CAST(:fulfillment AS varchar) IS NULL OR o.fulfillment_status = :fulfillment)
                   AND (CAST(:from AS timestamptz) IS NULL OR o.placed_at >= :from)
                   AND (CAST(:to AS timestamptz) IS NULL OR o.placed_at < :to)
                   AND (CAST(:dispute AS boolean) IS NULL OR o.has_dispute = :dispute)
                """;
        List<AdminOrderSummary> content = jdbc.query("""
                SELECT o.order_number, o.status, o.fulfillment_status, o.placed_at, o.customer_name, o.customer_email, o.total,
                       (SELECT sum(quantity) FROM order_item WHERE order_id = o.id), o.payment_review, o.has_dispute
                  FROM orders o
                """ + where + " ORDER BY o.placed_at DESC, o.id DESC LIMIT :limit OFFSET :offset", params,
                (rs, n) -> new AdminOrderSummary(rs.getString(1), rs.getString(2), rs.getString(3), rs.getTimestamp(4).toInstant(),
                        rs.getString(5), rs.getString(6), rs.getLong(7), rs.getInt(8), rs.getBoolean(9), rs.getBoolean(10)));
        long total = jdbc.queryForObject("SELECT count(*) FROM orders o " + where, params, Long.class);
        return new PageResponse<>(content, page, size, total, (int) ((total + size - 1) / size));
    }

    AdminOrderView detail(String number) {
        long id = idOf(number);
        var p = new MapSqlParameterSource("id", id);
        var head = jdbc.queryForObject("""
                SELECT user_id, customer_name, customer_email, customer_phone, payment_review, has_dispute FROM orders WHERE id = :id
                """, p, (rs, n) -> new Object[]{rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4),
                rs.getBoolean(5), rs.getBoolean(6)});
        return new AdminOrderView(orders.view(id), (long) head[0], (String) head[1], (String) head[2], (String) head[3],
                (boolean) head[4], (boolean) head[5], payments.list(null, null, id),
                jdbc.query("SELECT id, status, amount, reason, failure_message, created_at FROM refund WHERE order_id = :id ORDER BY id", p,
                        (rs, n) -> new RefundRow(rs.getLong(1), rs.getString(2), rs.getLong(3), rs.getString(4), rs.getString(5),
                                rs.getTimestamp(6).toInstant())),
                jdbc.query("""
                        SELECT d.status, d.amount, d.reason, d.evidence_due_by FROM dispute d JOIN payment pa ON pa.id = d.payment_id
                         WHERE pa.order_id = :id ORDER BY d.id
                        """, p, (rs, n) -> new DisputeRow(rs.getString(1), rs.getLong(2), rs.getString(3),
                        rs.getTimestamp(4) == null ? null : rs.getTimestamp(4).toInstant())),
                jdbc.query("""
                        SELECT h.kind, h.from_status, h.to_status, h.actor_type, u.full_name, h.reason, h.created_at
                          FROM order_status_history h LEFT JOIN app_user u ON u.id = h.actor_id
                         WHERE h.order_id = :id ORDER BY h.id
                        """, p, (rs, n) -> new HistoryEntry(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getString(5), rs.getString(6), rs.getTimestamp(7).toInstant())),
                jdbc.query("""
                        SELECT n.id, u.full_name, n.text, n.created_at FROM order_note n JOIN app_user u ON u.id = n.author_id
                         WHERE n.order_id = :id ORDER BY n.id
                        """, p, (rs, n) -> new NoteView(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getTimestamp(4).toInstant())));
    }

    /** Avança o envio um passo: PROCESSING (separação) → SHIPPED (com transportadora e rastreio) → DELIVERED. */
    AdminOrderView transition(String number, String to, long version, Actor actor, String reason, String carrier, String tracking) {
        tx.executeWithoutResult(s -> {
            var o = jdbc.query("""
                    SELECT id, status, fulfillment_status, version, payment_review, has_dispute FROM orders WHERE order_number = :n FOR UPDATE
                    """, new MapSqlParameterSource("n", number), (rs, n) -> new Object[]{rs.getLong(1), rs.getString(2), rs.getString(3),
                    rs.getLong(4), rs.getBoolean(5), rs.getBoolean(6)}).stream().findFirst()
                    .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "Pedido não encontrado"));
            long id = (long) o[0];
            String status = (String) o[1];
            String current = (String) o[2];
            if ((long) o[3] != version) throw new BusinessException(ErrorCode.CONCURRENT_MODIFICATION);
            if (!SHIPPABLE.contains(status) || STEPS.indexOf(to) < 1 || STEPS.indexOf(to) != STEPS.indexOf(current) + 1) {
                throw new BusinessException(ErrorCode.INVALID_STATUS_TRANSITION,
                        "Pedido " + status + " / envio " + current + " não pode ir para " + to);
            }
            if (to.equals("SHIPPED")) {
                if (isBlank(carrier) || isBlank(tracking)) {
                    throw new BusinessException(ErrorCode.VALIDATION_ERROR, "Informe transportadora e código de rastreio");
                }
                if ((boolean) o[4] || (boolean) o[5]) throw new BusinessException(ErrorCode.FULFILLMENT_BLOCKED);
            }
            jdbc.update("""
                    UPDATE orders SET fulfillment_status = :to, carrier = coalesce(:carrier, carrier),
                           tracking_code = coalesce(:tracking, tracking_code),
                           shipped_at = CASE WHEN :to = 'SHIPPED' THEN now() ELSE shipped_at END,
                           delivered_at = CASE WHEN :to = 'DELIVERED' THEN now() ELSE delivered_at END,
                           version = version + 1, updated_at = now()
                     WHERE id = :id
                    """, new MapSqlParameterSource("id", id).addValue("to", to).addValue("carrier", trim(carrier))
                    .addValue("tracking", trim(tracking)));
            boolean moved = LOGISTIC.contains(status) && states.move(id, to, actor, reason);
            if (!moved) {
                states.history(id, "FULFILLMENT", current, to, actor, reason);
                states.email(id, to);
            }
        });
        return detail(number);
    }

    AdminOrderView addNote(String number, long authorId, String text) {
        jdbc.update("INSERT INTO order_note (order_id, author_id, text) VALUES (:order, :author, :text)",
                new MapSqlParameterSource("order", idOf(number)).addValue("author", authorId).addValue("text", text.trim()));
        return detail(number);
    }

    /** Não pago: cancela o intent e libera a reserva. Pago e não enviado: reembolso integral com devolução ao estoque. */
    AdminOrderView cancel(String number, long adminId, String reason) {
        long id = idOf(number);
        String status = jdbc.queryForObject("SELECT status FROM orders WHERE id = :id", new MapSqlParameterSource("id", id), String.class);
        if (status.equals("PENDING_PAYMENT") || status.equals("PAYMENT_PROCESSING")) {
            if (!orders.cancelIntents(id)) {
                throw new BusinessException(ErrorCode.INVALID_STATUS_TRANSITION, "O pagamento já foi feito ou está em processamento");
            }
            tx.executeWithoutResult(s -> {
                if (!orders.cancelLocked(id, "ADMIN", new Actor(ActorType.ADMIN, adminId))) {
                    throw new BusinessException(ErrorCode.INVALID_STATUS_TRANSITION);
                }
            });
        } else {
            // Chave derivada do pedido: repetir o cancelamento não gera um segundo reembolso
            UUID key = UUID.nameUUIDFromBytes(("cancel-" + id).getBytes(StandardCharsets.UTF_8));
            refunds.create(adminId, number, key, new RefundRequest(null, null, true, true, reason));
        }
        return detail(number);
    }

    /** Reenvia o e-mail de confirmação (cliente não recebeu). */
    AdminOrderView resendConfirmation(String number) {
        long id = idOf(number);
        tx.executeWithoutResult(s -> {
            String status = jdbc.queryForObject("SELECT status FROM orders WHERE id = :id", new MapSqlParameterSource("id", id), String.class);
            if (!SHIPPABLE.contains(status)) throw new BusinessException(ErrorCode.INVALID_STATUS_TRANSITION, "Pedido sem pagamento confirmado");
            states.email(id, "PAID");
        });
        return detail(number);
    }

    private long idOf(String number) {
        return jdbc.queryForList("SELECT id FROM orders WHERE order_number = :n", new MapSqlParameterSource("n", number), Long.class)
                .stream().findFirst().orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "Pedido não encontrado"));
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static String trim(String s) {
        return isBlank(s) ? null : s.trim();
    }
}
