package com.atelier.order;

import com.atelier.catalog.service.InventoryService;
import com.atelier.catalog.service.ProductDenormalizer;
import com.atelier.notification.Outbox;
import com.atelier.shared.error.BusinessException;
import com.atelier.shared.error.ErrorCode;
import com.stripe.exception.SignatureVerificationException;
import com.stripe.net.Webhook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.*;

/**
 * Pagamento pela Stripe (PRD, seção 10). O status muda só por webhook com assinatura verificada (ou pela
 * reconciliação, que consulta a Stripe e passa pelo mesmo tratamento). Todo tratamento é idempotente:
 * o mesmo evento aplicado duas vezes não tem segundo efeito, e eventos fora de ordem são ignorados.
 */
@Service
class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);
    private static final Set<String> PAID = Set.of("PAID", "PROCESSING", "SHIPPED", "DELIVERED", "PARTIALLY_REFUNDED", "REFUNDED");

    record IntentResponse(String orderNumber, long amount, String clientSecret, String publishableKey) {}

    record PaymentRow(long id, String orderNumber, String paymentIntentId, String status, long amount, long amountReceived,
                      long amountRefunded, String methodType, String cardBrand, String cardLast4, String failureCode,
                      Instant createdAt) {}

    private record Pay(long id, long orderId, String status) {}

    private record Ord(long id, String number, String status, long total, String email, UUID cartId, Long couponId) {}

    private final OrderService orders;
    private final StripeGateway gateway;
    private final InventoryService inventory;
    private final ProductDenormalizer denormalizer;
    private final Outbox outbox;
    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final JsonMapper json;
    private final Clock clock;
    private final String publishableKey;
    private final String webhookSecret;
    private final boolean livemode;

    PaymentService(OrderService orders, StripeGateway gateway, InventoryService inventory, ProductDenormalizer denormalizer,
                   Outbox outbox, NamedParameterJdbcTemplate jdbc, TransactionTemplate tx, JsonMapper json, Clock clock,
                   @Value("${app.stripe.publishable-key:}") String publishableKey,
                   @Value("${app.stripe.webhook-secret:}") String webhookSecret,
                   @Value("${app.stripe.livemode:false}") boolean livemode) {
        this.orders = orders;
        this.gateway = gateway;
        this.inventory = inventory;
        this.denormalizer = denormalizer;
        this.outbox = outbox;
        this.jdbc = jdbc;
        this.tx = tx;
        this.json = json;
        this.clock = clock;
        this.publishableKey = publishableKey;
        this.webhookSecret = webhookSecret;
        this.livemode = livemode;
    }

    // ---- PaymentIntent ----

    /**
     * Intent do pedido pendente: devolve o que já existe ou cria (fora de transação; a chave de idempotência
     * da Stripe faz chamadas simultâneas receberem o mesmo intent). Stripe fora do ar: o pedido continua
     * reservado e o cliente tenta de novo até expirar.
     */
    IntentResponse ensureIntent(long userId, String number) {
        var o = jdbc.query("""
                SELECT id, status, total, expires_at, customer_email FROM orders WHERE order_number = :n AND user_id = :u
                """, new MapSqlParameterSource("n", number).addValue("u", userId), (rs, n) -> new Object[]{
                rs.getLong(1), rs.getString(2), rs.getLong(3), rs.getTimestamp(4).toInstant(), rs.getString(5)})
                .stream().findFirst().orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "Pedido não encontrado"));
        long orderId = (long) o[0];
        long total = (long) o[2];
        if (!"PENDING_PAYMENT".equals(o[1]) || ((Instant) o[3]).isBefore(clock.instant())) {
            throw new BusinessException(ErrorCode.INVALID_STATUS_TRANSITION, "Este pedido não aceita mais pagamento");
        }
        var params = new MapSqlParameterSource("order", orderId);
        String secret = jdbc.queryForList("SELECT client_secret FROM payment WHERE order_id = :order AND status <> 'CANCELED'",
                params, String.class).stream().findFirst().orElse(null);
        if (secret != null) return new IntentResponse(number, total, secret, publishableKey);
        if (!gateway.enabled()) throw new BusinessException(ErrorCode.PAYMENT_UNAVAILABLE);

        int attempt = 1 + jdbc.queryForObject("SELECT count(*) FROM payment WHERE order_id = :order", params, Integer.class);
        StripeGateway.Intent pi;
        try {
            pi = gateway.createIntent(orderId, attempt, number, total, (String) o[4]);
        } catch (Exception e) {
            log.error("Falha ao criar PaymentIntent do pedido {}", number, e);
            throw new BusinessException(ErrorCode.PAYMENT_UNAVAILABLE);
        }
        jdbc.update("""
                INSERT INTO payment (order_id, stripe_payment_intent_id, client_secret, status, amount, attempt)
                VALUES (:order, :pi, :secret, :status, :amount, :attempt) ON CONFLICT DO NOTHING
                """, params.addValue("pi", pi.id()).addValue("secret", pi.clientSecret()).addValue("status", status(pi.status()))
                .addValue("amount", total).addValue("attempt", attempt));
        return new IntentResponse(number, total, pi.clientSecret(), publishableKey);
    }

    private static String status(String stripe) {
        return switch (stripe) {
            case "requires_action" -> "REQUIRES_ACTION";
            case "processing", "requires_capture" -> "PROCESSING";
            case "succeeded" -> "SUCCEEDED";
            case "canceled" -> "CANCELED";
            default -> "REQUIRES_PAYMENT_METHOD";
        };
    }

    // ---- webhook ----

    /**
     * Corpo cru (qualquer alteração invalida a assinatura). Assinatura e janela de 5 min (anti-replay) verificadas
     * pela SDK. Erro no processamento: evento fica FAILED e a resposta 500 faz a Stripe reenviar com backoff.
     */
    void receive(String payload, String signature) {
        if (webhookSecret.isBlank()) throw new BusinessException(ErrorCode.PAYMENT_UNAVAILABLE);
        try {
            Webhook.Signature.verifyHeader(payload, signature, webhookSecret, 300);
        } catch (SignatureVerificationException e) {
            log.warn("Webhook da Stripe recusado: assinatura inválida ou vencida");
            throw new BusinessException(ErrorCode.INVALID_SIGNATURE);
        }
        JsonNode event = json.readTree(payload);
        String id = event.path("id").asString();
        String type = event.path("type").asString();
        if (event.path("livemode").asBoolean() != livemode) {
            log.warn("Evento Stripe {} com livemode diferente do ambiente; ignorado", id);
            return;
        }
        jdbc.update("""
                INSERT INTO stripe_event (id, type, livemode, payload, status) VALUES (:id, :type, :live, CAST(:payload AS jsonb), 'RECEIVED')
                ON CONFLICT (id) DO NOTHING
                """, new MapSqlParameterSource("id", id).addValue("type", type).addValue("live", livemode).addValue("payload", payload));

        List<Runnable> after = new ArrayList<>();
        try {
            tx.executeWithoutResult(s -> {
                // Trava o evento: entregas simultâneas do mesmo evento passam uma de cada vez
                String status = jdbc.queryForObject("SELECT status FROM stripe_event WHERE id = :id FOR UPDATE",
                        new MapSqlParameterSource("id", id), String.class);
                if ("PROCESSED".equals(status) || "IGNORED".equals(status)) return;
                boolean handled = apply(type, event.path("data").path("object"), after);
                jdbc.update("""
                        UPDATE stripe_event SET status = :status, attempts = attempts + 1, processed_at = now(), last_error = NULL
                         WHERE id = :id
                        """, new MapSqlParameterSource("id", id).addValue("status", handled ? "PROCESSED" : "IGNORED"));
            });
        } catch (RuntimeException e) {
            log.error("Falha ao processar evento Stripe {} ({})", id, type, e);
            jdbc.update("UPDATE stripe_event SET status = 'FAILED', attempts = attempts + 1, last_error = :error WHERE id = :id",
                    new MapSqlParameterSource("id", id).addValue("error", truncate(String.valueOf(e.getMessage()), 500)));
            throw e;
        }
        after.forEach(Runnable::run);
    }

    /** Aplica um evento dentro da transação. false = não diz respeito a nada nosso (IGNORED). */
    private boolean apply(String type, JsonNode object, List<Runnable> after) {
        return switch (type) {
            case "payment_intent.succeeded" -> succeeded(object, after);
            case "payment_intent.processing" -> intentStatus(object, "PROCESSING");
            case "payment_intent.requires_action" -> intentStatus(object, "REQUIRES_ACTION");
            case "payment_intent.payment_failed" -> failed(object);
            case "payment_intent.canceled" -> canceled(object);
            case "charge.succeeded" -> chargeDetails(object);
            case "refund.created", "refund.updated", "refund.failed", "charge.refund.updated" -> refund(object);
            case "charge.dispute.created", "charge.dispute.updated", "charge.dispute.closed",
                 "charge.dispute.funds_withdrawn", "charge.dispute.funds_reinstated" -> dispute(object, type);
            default -> false;
        };
    }

    // ---- PaymentIntent: transições ----

    private boolean succeeded(JsonNode intent, List<Runnable> after) {
        Pay p = payment(intent);
        if (p == null) return false;
        long received = intent.path("amount_received").asLong();
        jdbc.update("""
                UPDATE payment SET status = 'SUCCEEDED', amount_received = :received, failure_code = NULL, failure_message = NULL,
                                   updated_at = now() WHERE id = :id
                """, new MapSqlParameterSource("id", p.id()).addValue("received", received));
        Ord o = lockOrder(p.orderId());
        boolean mismatch = received != o.total() || !"brl".equalsIgnoreCase(intent.path("currency").asString());
        if (o.status().equals("PENDING_PAYMENT") || o.status().equals("PAYMENT_PROCESSING")) {
            markPaid(o, mismatch);
        } else if (o.status().equals("CANCELLED")) {
            latePayment(o, p, received, mismatch, after);
        } else if (PAID.contains(o.status()) && !p.status().equals("SUCCEEDED")) {
            // Outro intent já pagou este pedido: devolve o excedente inteiro
            log.error("ALERTA: pagamento em dobro no pedido {} (intent {}); reembolso automático", o.number(), intent.path("id").asString());
            automaticRefund(p, o.id(), received, "Pagamento em duplicidade", after);
        }
        return true;
    }

    /** PAID: reserva vira baixa, cupom confirmado, itens saem da sacola, e-mail pela outbox. */
    private void markPaid(Ord o, boolean mismatch) {
        var params = new MapSqlParameterSource("id", o.id()).addValue("review", mismatch)
                .addValue("cart", o.cartId()).addValue("coupon", o.couponId());
        jdbc.update("""
                UPDATE orders SET status = 'PAID', paid_at = now(), payment_review = :review, cancelled_at = NULL, cancel_reason = NULL,
                                  version = version + 1, updated_at = now()
                 WHERE id = :id
                """, params);
        inventory.commitSale(orders.itemQuantities(o.id()), o.id());
        jdbc.update("UPDATE coupon_usage SET status = 'CONFIRMED' WHERE order_id = :id", params);
        jdbc.update("""
                UPDATE product p SET sales_count = p.sales_count + x.q
                  FROM (SELECT product_id, sum(quantity) q FROM order_item WHERE order_id = :id AND product_id IS NOT NULL
                         GROUP BY product_id) x
                 WHERE p.id = x.product_id
                """, params);
        jdbc.update("DELETE FROM cart_item WHERE cart_id = :cart AND variant_id IN (SELECT variant_id FROM order_item WHERE order_id = :id)", params);
        if (o.couponId() != null) jdbc.update("UPDATE cart SET coupon_id = NULL WHERE id = :cart AND coupon_id = :coupon", params);
        if (mismatch) log.error("ALERTA: valor recebido diferente do total no pedido {}; marcado para revisão", o.number());
        outbox.email("ORDER", o.id(), o.email(), "Pedido " + o.number() + " confirmado",
                "Recebemos o pagamento do pedido " + o.number() + " (" + brl(o.total()) + ").\n"
                        + "Avisaremos quando ele for enviado.");
    }

    /** Pago depois de cancelado (expirou): re-reserva; sem estoque, reembolso integral automático (PRD 9.3). */
    private void latePayment(Ord o, Pay p, long received, boolean mismatch, List<Runnable> after) {
        Map<Long, Integer> items = orders.itemQuantities(o.id());
        List<Long> missing = inventory.reserve(items, o.id());
        if (missing.isEmpty()) {
            log.warn("Pagamento tardio do pedido {}: estoque re-reservado e pedido reativado", o.number());
            markPaid(o, mismatch);
        } else {
            Map<Long, Integer> reserved = new HashMap<>(items);
            missing.forEach(reserved::remove);
            inventory.release(reserved, o.id());
            log.error("ALERTA: pagamento tardio do pedido {} sem estoque; reembolso automático", o.number());
            automaticRefund(p, o.id(), received, "Pagamento após o cancelamento, sem estoque", after);
        }
        denormalizer.recompute(orders.productIds(o.id()));
    }

    private boolean intentStatus(JsonNode intent, String status) {
        Pay p = payment(intent);
        if (p == null) return false;
        if (p.status().equals("SUCCEEDED") || p.status().equals("CANCELED")) return true; // evento atrasado
        var params = new MapSqlParameterSource("id", p.id()).addValue("status", status).addValue("order", p.orderId());
        jdbc.update("UPDATE payment SET status = :status, updated_at = now() WHERE id = :id", params);
        if (status.equals("PROCESSING")) {
            jdbc.update("""
                    UPDATE orders SET status = 'PAYMENT_PROCESSING', version = version + 1, updated_at = now()
                     WHERE id = :order AND status = 'PENDING_PAYMENT'
                    """, params);
        }
        return true;
    }

    /** Recusa: o mesmo intent aceita nova tentativa até o pedido expirar. Depois de pago, é ignorada. */
    private boolean failed(JsonNode intent) {
        Pay p = payment(intent);
        if (p == null) return false;
        if (p.status().equals("SUCCEEDED") || p.status().equals("CANCELED")) return true;
        JsonNode error = intent.path("last_payment_error");
        String code = error.path("decline_code").asString(error.path("code").asString(null));
        var params = new MapSqlParameterSource("id", p.id()).addValue("order", p.orderId()).addValue("code", code)
                .addValue("message", truncate(error.path("message").asString(null), 300));
        jdbc.update("""
                UPDATE payment SET status = 'REQUIRES_PAYMENT_METHOD', failure_code = :code, failure_message = :message,
                                   updated_at = now() WHERE id = :id
                """, params);
        jdbc.update("""
                UPDATE orders SET status = 'PENDING_PAYMENT', version = version + 1, updated_at = now()
                 WHERE id = :order AND status = 'PAYMENT_PROCESSING'
                """, params);
        return true;
    }

    private boolean canceled(JsonNode intent) {
        Pay p = payment(intent);
        if (p == null) return false;
        if (p.status().equals("SUCCEEDED")) return true;
        jdbc.update("UPDATE payment SET status = 'CANCELED', updated_at = now() WHERE id = :id", new MapSqlParameterSource("id", p.id()));
        lockOrder(p.orderId());
        orders.cancelLocked(p.orderId(), "PAYMENT_CANCELED");
        return true;
    }

    /** Bandeira e final do cartão vêm na cobrança (não são dados sensíveis). */
    private boolean chargeDetails(JsonNode charge) {
        JsonNode details = charge.path("payment_method_details");
        return jdbc.update("""
                UPDATE payment SET payment_method_type = :type, card_brand = :brand, card_last4 = :last4, updated_at = now()
                 WHERE stripe_payment_intent_id = :pi
                """, new MapSqlParameterSource("pi", charge.path("payment_intent").asString())
                .addValue("type", details.path("type").asString(null))
                .addValue("brand", details.path("card").path("brand").asString(null))
                .addValue("last4", details.path("card").path("last4").asString(null))) > 0;
    }

    /** Intent → pagamento nosso (travado). Intent criado mas não gravado (queda no meio): recupera pelo metadata. */
    private Pay payment(JsonNode intent) {
        var params = new MapSqlParameterSource("pi", intent.path("id").asString());
        String sql = "SELECT id, order_id, status FROM payment WHERE stripe_payment_intent_id = :pi FOR UPDATE";
        Pay p = jdbc.query(sql, params, (rs, n) -> new Pay(rs.getLong(1), rs.getLong(2), rs.getString(3))).stream().findFirst().orElse(null);
        String orderId = intent.path("metadata").path("order_id").asString(null);
        if (p != null || orderId == null) return p;
        jdbc.update("""
                INSERT INTO payment (order_id, stripe_payment_intent_id, client_secret, status, amount, attempt)
                SELECT id, :pi, :secret, 'REQUIRES_PAYMENT_METHOD', total, 0 FROM orders WHERE id = :order
                ON CONFLICT DO NOTHING
                """, params.addValue("secret", intent.path("client_secret").asString("")).addValue("order", Long.parseLong(orderId)));
        return jdbc.query(sql, params, (rs, n) -> new Pay(rs.getLong(1), rs.getLong(2), rs.getString(3))).stream().findFirst().orElse(null);
    }

    private Ord lockOrder(long orderId) {
        return jdbc.queryForObject("""
                SELECT id, order_number, status, total, customer_email, cart_id, coupon_id FROM orders WHERE id = :id FOR UPDATE
                """, new MapSqlParameterSource("id", orderId), (rs, n) -> new Ord(rs.getLong(1), rs.getString(2), rs.getString(3),
                rs.getLong(4), rs.getString(5), rs.getObject(6, UUID.class), (Long) rs.getObject(7)));
    }

    // ---- reembolsos ----

    private void automaticRefund(Pay p, long orderId, long amount, String reason, List<Runnable> after) {
        Long refundId = jdbc.queryForObject("""
                INSERT INTO refund (payment_id, order_id, amount, reason, status) VALUES (:payment, :order, :amount, :reason, 'PENDING')
                RETURNING id
                """, new MapSqlParameterSource("payment", p.id()).addValue("order", orderId).addValue("amount", amount)
                .addValue("reason", reason), Long.class);
        after.add(() -> sendRefund(refundId));
    }

    /** Pede o reembolso à Stripe (fora de transação). Recusa: FAILED + alerta, pedido inalterado. */
    void sendRefund(long refundId) {
        var r = jdbc.queryForObject("""
                SELECT r.amount, p.stripe_payment_intent_id FROM refund r JOIN payment p ON p.id = r.payment_id WHERE r.id = :id
                """, new MapSqlParameterSource("id", refundId), (rs, n) -> new Object[]{rs.getLong(1), rs.getString(2)});
        try {
            JsonNode refund = gateway.createRefund((String) r[1], (long) r[0], refundId);
            tx.executeWithoutResult(s -> refund(refund));
        } catch (Exception e) {
            log.error("ALERTA: reembolso {} recusado pela Stripe", refundId, e);
            jdbc.update("UPDATE refund SET status = 'FAILED', failure_message = :message, updated_at = now() WHERE id = :id AND status = 'PENDING'",
                    new MapSqlParameterSource("id", refundId).addValue("message", truncate(String.valueOf(e.getMessage()), 300)));
        }
    }

    /** Efeitos do reembolso só quando a Stripe confirma: itens, estoque, saldo e status do pedido. */
    private boolean refund(JsonNode refund) {
        String metaId = refund.path("metadata").path("refund_id").asString(null);
        var params = new MapSqlParameterSource("sid", refund.path("id").asString())
                .addValue("meta", metaId == null ? null : Long.parseLong(metaId));
        var row = jdbc.query("""
                SELECT id, status, order_id, payment_id, amount, items::text, restock, cancel_order FROM refund
                 WHERE stripe_refund_id = :sid OR id = :meta FOR UPDATE
                """, params, (rs, n) -> new Object[]{rs.getLong(1), rs.getString(2), rs.getLong(3), rs.getLong(4), rs.getLong(5),
                rs.getString(6), rs.getBoolean(7), rs.getBoolean(8)}).stream().findFirst().orElse(null);
        if (row == null) return false; // ponytail: reembolso feito direto no Dashboard da Stripe não é importado
        long id = (long) row[0];
        String previous = (String) row[1];
        String next = switch (refund.path("status").asString()) {
            case "succeeded" -> "SUCCEEDED";
            case "failed", "canceled" -> "FAILED";
            default -> "PENDING";
        };
        jdbc.update("""
                UPDATE refund SET stripe_refund_id = :sid, status = :status, failure_message = :failure, updated_at = now() WHERE id = :id
                """, params.addValue("id", id).addValue("status", previous.equals("SUCCEEDED") ? previous : next)
                .addValue("failure", refund.path("failure_reason").asString(null)));
        if (previous.equals("PENDING") && next.equals("SUCCEEDED")) {
            applyRefund(id, (long) row[2], (long) row[3], (long) row[4], json.readTree((String) row[5]), (boolean) row[6], (boolean) row[7]);
        } else if (previous.equals("PENDING") && next.equals("FAILED")) {
            log.error("ALERTA: reembolso {} falhou na Stripe ({})", id, refund.path("failure_reason").asString(""));
        }
        return true;
    }

    private void applyRefund(long refundId, long orderId, long paymentId, long amount, JsonNode items, boolean restock, boolean cancel) {
        var params = new MapSqlParameterSource("payment", paymentId).addValue("amount", amount).addValue("order", orderId);
        jdbc.update("UPDATE payment SET amount_refunded = amount_refunded + :amount, updated_at = now() WHERE id = :payment", params);
        Map<Long, Integer> back = new HashMap<>();
        for (JsonNode item : items) {
            var p = new MapSqlParameterSource("item", item.path("orderItemId").asLong()).addValue("order", orderId)
                    .addValue("q", item.path("quantity").asInt());
            jdbc.update("UPDATE order_item SET quantity_refunded = quantity_refunded + :q WHERE id = :item AND order_id = :order", p);
            jdbc.queryForList("SELECT variant_id FROM order_item WHERE id = :item AND variant_id IS NOT NULL", p, Long.class)
                    .forEach(v -> back.merge(v, item.path("quantity").asInt(), Integer::sum));
        }
        if (restock && !back.isEmpty()) {
            inventory.restock(back, refundId);
            denormalizer.recompute(orders.productIds(orderId));
        }
        Ord o = lockOrder(orderId);
        if (o.status().equals("CANCELLED")) return; // reembolso automático de pedido já cancelado
        if (cancel) {
            jdbc.update("""
                    UPDATE orders SET status = 'CANCELLED', cancelled_at = now(), cancel_reason = 'ADMIN_REFUND',
                                      version = version + 1, updated_at = now() WHERE id = :order
                    """, params);
            return;
        }
        jdbc.update("""
                UPDATE orders o SET status = CASE WHEN p.amount_refunded >= p.amount_received THEN 'REFUNDED' ELSE 'PARTIALLY_REFUNDED' END,
                                    version = o.version + 1, updated_at = now()
                  FROM payment p WHERE p.id = :payment AND o.id = :order
                """, params);
    }

    // ---- disputas ----

    /** Chargeback: só por webhook. Pedido fica marcado (bloqueia envio na Fase 8) até a disputa ser ganha. */
    private boolean dispute(JsonNode d, String type) {
        var p = jdbc.query("SELECT id, order_id FROM payment WHERE stripe_payment_intent_id = :pi",
                new MapSqlParameterSource("pi", d.path("payment_intent").asString()), (rs, n) -> new long[]{rs.getLong(1), rs.getLong(2)})
                .stream().findFirst().orElse(null);
        if (p == null) return false;
        String status = d.path("status").asString();
        long due = d.path("evidence_details").path("due_by").asLong(0);
        var params = new MapSqlParameterSource("payment", p[0]).addValue("sid", d.path("id").asString())
                .addValue("amount", d.path("amount").asLong()).addValue("reason", d.path("reason").asString(null))
                .addValue("status", status).addValue("due", due == 0 ? null : Timestamp.from(Instant.ofEpochSecond(due)))
                .addValue("order", p[1]).addValue("open", !status.equals("won") && !status.equals("warning_closed"));
        jdbc.update("""
                INSERT INTO dispute (payment_id, stripe_dispute_id, amount, reason, status, evidence_due_by)
                VALUES (:payment, :sid, :amount, :reason, :status, :due)
                ON CONFLICT (stripe_dispute_id) DO UPDATE SET status = excluded.status, amount = excluded.amount,
                       reason = excluded.reason, evidence_due_by = excluded.evidence_due_by, updated_at = now()
                """, params);
        jdbc.update("UPDATE orders SET has_dispute = :open, updated_at = now() WHERE id = :order", params);
        if (type.equals("charge.dispute.created")) log.error("ALERTA: disputa aberta no pedido {} (prazo {})", p[1], params.getValue("due"));
        if (status.equals("lost")) log.error("ALERTA: disputa perdida no pedido {}", p[1]);
        return true;
    }

    // ---- reconciliação ----

    /**
     * Rede de segurança para webhook perdido (PRD 9.3): intents de pedidos ainda não pagos há mais de 10 min são
     * consultados na Stripe e passam pelo mesmo tratamento do webhook.
     */
    @Scheduled(fixedDelayString = "${app.stripe.reconcile-interval:15m}", initialDelayString = "${app.stripe.reconcile-interval:15m}")
    void reconcile() {
        if (!gateway.enabled()) return;
        List<String> intents = jdbc.queryForList("""
                SELECT p.stripe_payment_intent_id FROM payment p JOIN orders o ON o.id = p.order_id
                 WHERE o.status IN ('PENDING_PAYMENT', 'PAYMENT_PROCESSING') AND p.status <> 'CANCELED'
                   AND p.created_at < now() - interval '10 minutes'
                """, new MapSqlParameterSource(), String.class);
        for (String intent : intents) {
            try {
                syncIntent(intent);
            } catch (Exception e) {
                log.warn("Reconciliação do intent {} falhou", intent, e);
            }
        }
    }

    /** Admin: força a reconciliação de um pagamento. */
    PaymentRow sync(long paymentId) {
        String intent = jdbc.queryForList("SELECT stripe_payment_intent_id FROM payment WHERE id = :id",
                new MapSqlParameterSource("id", paymentId), String.class).stream().findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        try {
            syncIntent(intent);
        } catch (com.stripe.exception.StripeException e) {
            throw new BusinessException(ErrorCode.PAYMENT_UNAVAILABLE);
        }
        return list(null, paymentId).getFirst();
    }

    private void syncIntent(String intentId) throws com.stripe.exception.StripeException {
        JsonNode intent = gateway.retrieveIntent(intentId);
        String type = switch (intent.path("status").asString()) {
            case "succeeded" -> "payment_intent.succeeded";
            case "processing" -> "payment_intent.processing";
            case "canceled" -> "payment_intent.canceled";
            case "requires_payment_method" -> intent.path("last_payment_error").isMissingNode() || intent.path("last_payment_error").isNull()
                    ? null : "payment_intent.payment_failed";
            default -> null;
        };
        if (type == null) return;
        List<Runnable> after = new ArrayList<>();
        tx.executeWithoutResult(s -> apply(type, intent, after));
        if (!after.isEmpty()) log.warn("Reconciliação aplicou {} ao intent {}", type, intentId);
        after.forEach(Runnable::run);
    }

    List<PaymentRow> list(String status, Long paymentId) {
        return jdbc.query("""
                SELECT p.id, o.order_number, p.stripe_payment_intent_id, p.status, p.amount, p.amount_received, p.amount_refunded,
                       p.payment_method_type, p.card_brand, p.card_last4, p.failure_code, p.created_at
                  FROM payment p JOIN orders o ON o.id = p.order_id
                 WHERE (CAST(:status AS varchar) IS NULL OR p.status = :status) AND (CAST(:id AS bigint) IS NULL OR p.id = :id)
                 ORDER BY p.id DESC LIMIT 100
                """, new MapSqlParameterSource("status", status).addValue("id", paymentId), (rs, n) -> new PaymentRow(rs.getLong(1),
                rs.getString(2), rs.getString(3), rs.getString(4), rs.getLong(5), rs.getLong(6), rs.getLong(7), rs.getString(8),
                rs.getString(9), rs.getString(10), rs.getString(11), rs.getTimestamp(12).toInstant()));
    }

    private static String truncate(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }

    private static String brl(long cents) {
        return "R$ " + String.format(Locale.of("pt", "BR"), "%,.2f", cents / 100.0);
    }
}
