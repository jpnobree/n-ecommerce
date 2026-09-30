package com.atelier.order;

import com.atelier.notification.Outbox;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Único lugar que muda o status do pedido (PRD 11.2): valida a transição, grava o histórico com o ator e
 * põe na outbox o e-mail ao cliente. Quem chama já travou a linha do pedido (FOR UPDATE).
 */
@Component
class OrderStateMachine {

    enum ActorType { SYSTEM, CUSTOMER, OPERATOR, ADMIN, STRIPE }

    record Actor(ActorType type, Long id) {
        static final Actor SYSTEM = new Actor(ActorType.SYSTEM, null);
        static final Actor STRIPE = new Actor(ActorType.STRIPE, null);
    }

    static final Map<String, Set<String>> ALLOWED = Map.of(
            "PENDING_PAYMENT", Set.of("PAYMENT_PROCESSING", "PAID", "CANCELLED"),
            "PAYMENT_PROCESSING", Set.of("PAID", "PENDING_PAYMENT", "CANCELLED"),
            "PAID", Set.of("PROCESSING", "CANCELLED", "PARTIALLY_REFUNDED"),
            "PROCESSING", Set.of("SHIPPED", "CANCELLED", "PARTIALLY_REFUNDED"),
            "SHIPPED", Set.of("DELIVERED", "PARTIALLY_REFUNDED", "REFUNDED"),
            "DELIVERED", Set.of("PARTIALLY_REFUNDED", "REFUNDED"),
            "PARTIALLY_REFUNDED", Set.of("PARTIALLY_REFUNDED", "REFUNDED", "CANCELLED"),
            // Exceção automática: pagamento que chega depois do cancelamento, com estoque re-reservado
            "CANCELLED", Set.of("PAID"));

    /** Motivos de cancelamento que não geram e-mail (o próprio cliente agiu). */
    private static final Set<String> SILENT_CANCEL = Set.of("CUSTOMER", "REPLACED");

    private final NamedParameterJdbcTemplate jdbc;
    private final Outbox outbox;

    OrderStateMachine(NamedParameterJdbcTemplate jdbc, Outbox outbox) {
        this.jdbc = jdbc;
        this.outbox = outbox;
    }

    private record Current(String status, String number, String email, long total, String carrier, String tracking) {}

    /**
     * Aplica a transição se ela é permitida a partir do status atual. false = não permitida (quem chama decide
     * se é erro 409 ou evento repetido/fora de ordem a ignorar). reason: motivo livre; em CANCELLED, o código.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    boolean move(long orderId, String to, Actor actor, String reason) {
        Current c = current(orderId);
        if (!ALLOWED.getOrDefault(c.status(), Set.of()).contains(to)) return false;
        jdbc.update("""
                UPDATE orders SET status = :to, version = version + 1, updated_at = now(),
                       paid_at = CASE WHEN :to = 'PAID' THEN now() ELSE paid_at END,
                       cancelled_at = CASE WHEN :to = 'CANCELLED' THEN now() WHEN :to = 'PAID' THEN NULL ELSE cancelled_at END,
                       cancel_reason = CASE WHEN :to = 'CANCELLED' THEN :reason WHEN :to = 'PAID' THEN NULL ELSE cancel_reason END
                 WHERE id = :id
                """, new MapSqlParameterSource("id", orderId).addValue("to", to).addValue("reason", reason));
        history(orderId, "STATUS", c.status(), to, actor, reason);
        if (!(to.equals("CANCELLED") && SILENT_CANCEL.contains(reason))) email(orderId, to);
        return true;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    void history(long orderId, String kind, String from, String to, Actor actor, String reason) {
        jdbc.update("""
                INSERT INTO order_status_history (order_id, kind, from_status, to_status, actor_type, actor_id, reason)
                VALUES (:order, :kind, :from, :to, :actorType, :actorId, :reason)
                """, new MapSqlParameterSource("order", orderId).addValue("kind", kind).addValue("from", from).addValue("to", to)
                .addValue("actorType", actor.type().name()).addValue("actorId", actor.id())
                .addValue("reason", reason == null || reason.length() <= 200 ? reason : reason.substring(0, 200)));
    }

    /** E-mail ao cliente nas mudanças que ele precisa saber (PRD 11.2). Lido depois do UPDATE: já tem o rastreio. */
    @Transactional(propagation = Propagation.MANDATORY)
    void email(long orderId, String status) {
        Current c = current(orderId);
        String n = c.number();
        String[] mail = switch (status) {
            case "PAID" -> new String[]{"Pedido " + n + " confirmado",
                    "Recebemos o pagamento do pedido " + n + " (" + brl(c.total()) + ").\nAvisaremos quando ele for enviado."};
            case "SHIPPED" -> new String[]{"Pedido " + n + " enviado",
                    "Seu pedido " + n + " foi enviado.\nTransportadora: " + c.carrier() + "\nCódigo de rastreio: " + c.tracking()};
            case "DELIVERED" -> new String[]{"Pedido " + n + " entregue", "Seu pedido " + n + " foi entregue. Esperamos que goste!"};
            case "CANCELLED" -> new String[]{"Pedido " + n + " cancelado",
                    "O pedido " + n + " foi cancelado. Se houve pagamento, o valor é devolvido pelo mesmo meio."};
            case "REFUNDED", "PARTIALLY_REFUNDED" -> new String[]{"Reembolso do pedido " + n,
                    "Fizemos um reembolso no pedido " + n + ". O prazo para aparecer depende do seu banco ou cartão."};
            default -> null;
        };
        if (mail != null) outbox.email("ORDER", orderId, c.email(), mail[0], mail[1]);
    }

    private Current current(long orderId) {
        return jdbc.queryForObject("SELECT status, order_number, customer_email, total, carrier, tracking_code FROM orders WHERE id = :id",
                new MapSqlParameterSource("id", orderId), (rs, n) -> new Current(rs.getString(1), rs.getString(2), rs.getString(3),
                        rs.getLong(4), rs.getString(5), rs.getString(6)));
    }

    private static String brl(long cents) {
        return "R$ " + String.format(Locale.of("pt", "BR"), "%,.2f", cents / 100.0);
    }
}
