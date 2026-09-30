package com.atelier.order;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import tools.jackson.databind.JsonNode;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Stripe (PRD 10 e 22.3): intent, webhook assinado e idempotente, reconciliação, reembolsos e disputas. */
class PaymentTests extends OrderFixtures {

    static final String SECRET = "whsec_test_segredo";

    @Autowired
    PaymentService payments;

    @Autowired
    com.atelier.notification.Outbox outbox;

    /** Pedido com o id e o intent que a "Stripe" criou para ele. */
    record Order(Buyer buyer, long id, String number, long total, String intent) {}

    @BeforeEach
    void stripeUp() throws Exception {
        reset(stripe);
        when(stripe.enabled()).thenReturn(true);
        when(stripe.createIntent(anyLong(), anyInt(), anyString(), anyLong(), anyString()))
                .thenAnswer(i -> new StripeGateway.Intent("pi_" + i.getArgument(0) + "_" + i.getArgument(1),
                        "secret_" + i.getArgument(0), "requires_payment_method"));
        when(stripe.cancelIntent(anyString())).thenReturn("canceled");
        // Reembolso aceito na hora, com o refund_id no metadata como a Stripe devolve
        when(stripe.createRefund(anyString(), anyLong(), anyLong())).thenAnswer(i -> node(Map.of(
                "id", "re_" + i.getArgument(2), "object", "refund", "amount", i.getArgument(1), "status", "succeeded",
                "payment_intent", i.getArgument(0), "metadata", Map.of("refund_id", String.valueOf((long) i.getArgument(2))))));
    }

    // ---- helpers ----

    private JsonNode node(Object value) {
        return json.readTree(json.writeValueAsString(value));
    }

    private Order order(Buyer b, long variant, int qty) {
        add(b, variant, qty);
        var placed = checkout(b);
        assertThat(placed.status()).as(placed.body()).isEqualTo(201);
        var intent = ok(post("/api/orders/" + placed.text("orderNumber") + "/payment-intent").bearer(b.token()).send());
        long id = placed.json().path("orderId").asLong();
        return new Order(b, id, placed.text("orderNumber"), placed.json().path("total").asLong(), "pi_" + id + "_1");
    }

    private Map<String, Object> intent(Order o, String status, long received) {
        return Map.of("id", o.intent(), "object", "payment_intent", "status", status, "amount", o.total(),
                "amount_received", received, "currency", "brl", "metadata", Map.of("order_id", String.valueOf(o.id())));
    }

    private String event(String id, String type, Map<String, Object> object) {
        return json.writeValueAsString(Map.of("id", id, "object", "event", "type", type, "livemode", false,
                "data", Map.of("object", object)));
    }

    private Res webhook(String payload, long timestamp, String secret) {
        try {
            var mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            String sig = HexFormat.of().formatHex(mac.doFinal((timestamp + "." + payload).getBytes(StandardCharsets.UTF_8)));
            return post("/api/payments/webhook").header("Stripe-Signature", "t=" + timestamp + ",v1=" + sig).body(payload).send();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private Res webhook(String payload) {
        return webhook(payload, Instant.now().getEpochSecond(), SECRET);
    }

    private void paid(Order o) {
        assertThat(webhook(event("evt_ok_" + o.id(), "payment_intent.succeeded", intent(o, "succeeded", o.total()))).status()).isEqualTo(200);
        assertThat(status(o)).isEqualTo("PAID");
    }

    private String status(Order o) {
        return ok(get("/api/orders/" + o.number()).bearer(o.buyer().token()).send()).path("status").asString();
    }

    private int onHand(long variant) {
        return jdbc.queryForObject("SELECT on_hand FROM inventory WHERE variant_id = :v", new MapSqlParameterSource("v", variant), Integer.class);
    }

    private Object column(String sql, Order o) {
        return jdbc.queryForObject(sql, new MapSqlParameterSource("id", o.id()), Object.class);
    }

    private Res refund(Order o, Map<String, Object> body) {
        return post("/api/admin/orders/" + o.number() + "/refunds").bearer(admin)
                .header("Idempotency-Key", UUID.randomUUID().toString()).body(body).send();
    }

    // ---- intent ----

    @Test
    void intentIsCreatedOnceForTheOrderTotal() throws Exception {
        Order o = order(buyer(), pretoP, 2);
        var again = ok(post("/api/orders/" + o.number() + "/payment-intent").bearer(o.buyer().token()).send());
        assertThat(again.path("clientSecret").asString()).isEqualTo("secret_" + o.id());
        assertThat(again.path("amount").asLong()).isEqualTo(o.total()).isEqualTo(20_000 + 1_890);
        assertThat(again.path("publishableKey").asString()).isEqualTo("pk_test_atelier");
        verify(stripe, times(1)).createIntent(eq(o.id()), eq(1), eq(o.number()), eq(o.total()), anyString());

        // Pedido de outro cliente
        assertThat(post("/api/orders/" + o.number() + "/payment-intent").bearer(buyer().token()).send().status()).isEqualTo(404);
    }

    @Test
    void stripeDownKeepsTheOrderAndAllowsRetry() throws Exception {
        Buyer b = buyer();
        add(b, pretoP, 1);
        var placed = checkout(b);
        String number = placed.text("orderNumber");
        when(stripe.createIntent(anyLong(), anyInt(), anyString(), anyLong(), anyString())).thenThrow(new RuntimeException("timeout"));
        var down = post("/api/orders/" + number + "/payment-intent").bearer(b.token()).send();
        assertThat(down.status()).isEqualTo(503);
        assertThat(down.text("code")).isEqualTo("PAYMENT_UNAVAILABLE");
        assertThat(reserved(pretoP)).isEqualTo(1);

        stripeUp();
        assertThat(ok(post("/api/orders/" + number + "/payment-intent").bearer(b.token()).send()).path("clientSecret").asString())
                .startsWith("secret_");
    }

    // ---- webhook ----

    @Test
    void succeededPaysOnceEvenWhenDeliveredThreeTimes() {
        Buyer b = buyer();
        add(b, pretoP, 2);
        ok(put("/api/cart/coupon").bearer(b.token()).body(Map.of("code", coupon(Map.of()))).send());
        var placed = checkout(b);
        long id = placed.json().path("orderId").asLong();
        ok(post("/api/orders/" + placed.text("orderNumber") + "/payment-intent").bearer(b.token()).send());
        Order o = new Order(b, id, placed.text("orderNumber"), placed.json().path("total").asLong(), "pi_" + id + "_1");

        String payload = event("evt_triplo_" + id, "payment_intent.succeeded", intent(o, "succeeded", o.total()));
        for (int i = 0; i < 3; i++) assertThat(webhook(payload).status()).isEqualTo(200);

        assertThat(status(o)).isEqualTo("PAID");
        assertThat(onHand(pretoP)).isEqualTo(3);
        assertThat(reserved(pretoP)).isZero();
        assertThat(couponUsageStatus(o.number())).isEqualTo("CONFIRMED");
        assertThat(column("SELECT sales_count FROM product p JOIN order_item oi ON oi.product_id = p.id WHERE oi.order_id = :id", o)).isEqualTo(2);
        assertThat(ok(get("/api/cart").bearer(b.token()).send()).path("items").size()).isZero();
        assertThat(column("SELECT count(*) FROM outbox_event WHERE aggregate_type = 'ORDER' AND aggregate_id = :id", o)).isEqualTo(1L);

        outbox.publish();
        assertThat(column("SELECT status FROM outbox_event WHERE aggregate_type = 'ORDER' AND aggregate_id = :id", o)).isEqualTo("PUBLISHED");

        // Recusa que chega depois do pagamento é ignorada
        var failed = new HashMap<>(intent(o, "requires_payment_method", 0));
        failed.put("last_payment_error", Map.of("code", "card_declined", "decline_code", "insufficient_funds"));
        assertThat(webhook(event("evt_late_fail_" + id, "payment_intent.payment_failed", failed)).status()).isEqualTo(200);
        assertThat(status(o)).isEqualTo("PAID");
    }

    @Test
    void invalidOrOldSignatureIsRejectedAndChangesNothing() {
        Order o = order(buyer(), pretoP, 1);
        String payload = event("evt_forjado_" + o.id(), "payment_intent.succeeded", intent(o, "succeeded", o.total()));

        assertThat(webhook(payload, Instant.now().getEpochSecond(), "whsec_outro").status()).isEqualTo(400);
        assertThat(webhook(payload, Instant.now().getEpochSecond() - 600, SECRET).status()).isEqualTo(400);
        assertThat(post("/api/payments/webhook").body(payload).send().status()).isEqualTo(400);
        assertThat(status(o)).isEqualTo("PENDING_PAYMENT");
        assertThat(column("SELECT count(*) FROM stripe_event WHERE id = 'evt_forjado_' || :id", o)).isEqualTo(0L);
    }

    @Test
    void failedPaymentAllowsRetryAndAmountMismatchIsFlagged() {
        Order o = order(buyer(), pretoP, 1);
        var failed = new HashMap<>(intent(o, "requires_payment_method", 0));
        failed.put("last_payment_error", Map.of("code", "card_declined", "decline_code", "generic_decline", "message", "Seu cartão foi recusado."));
        webhook(event("evt_fail_" + o.id(), "payment_intent.payment_failed", failed));
        assertThat(status(o)).isEqualTo("PENDING_PAYMENT");
        assertThat(column("SELECT failure_code FROM payment WHERE order_id = :id", o)).isEqualTo("generic_decline");

        webhook(event("evt_menos_" + o.id(), "payment_intent.succeeded", intent(o, "succeeded", o.total() - 100)));
        assertThat(status(o)).isEqualTo("PAID");
        assertThat(column("SELECT payment_review FROM orders WHERE id = :id", o)).isEqualTo(true);
    }

    @Test
    void latePaymentReactivatesWithStockOrRefundsWithout() throws Exception {
        Order withStock = order(buyer(), pretoP, 1);
        Order withoutStock = order(buyer(), pretoM, 3);
        jdbc.update("UPDATE orders SET expires_at = now() - interval '1 minute' WHERE id IN (:a, :b)",
                new MapSqlParameterSource("a", withStock.id()).addValue("b", withoutStock.id()));
        orders.expireOverdue();
        assertThat(status(withStock)).isEqualTo("CANCELLED");
        assertThat(status(withoutStock)).isEqualTo("CANCELLED");

        // Enquanto isso, outro cliente leva as 3 unidades de M
        Order other = order(buyer(), pretoM, 3);
        paid(other);

        webhook(event("evt_tarde1_" + withStock.id(), "payment_intent.succeeded", intent(withStock, "succeeded", withStock.total())));
        assertThat(status(withStock)).isEqualTo("PAID");
        assertThat(reserved(pretoP)).isZero();

        webhook(event("evt_tarde2_" + withoutStock.id(), "payment_intent.succeeded", intent(withoutStock, "succeeded", withoutStock.total())));
        assertThat(status(withoutStock)).isEqualTo("CANCELLED");
        assertThat(onHand(pretoM)).isZero();
        assertThat(reserved(pretoM)).isZero();
        assertThat(column("SELECT status FROM refund WHERE order_id = :id", withoutStock)).isEqualTo("SUCCEEDED");
        verify(stripe).createRefund(withoutStock.intent(), withoutStock.total(),
                (long) column("SELECT id FROM refund WHERE order_id = :id", withoutStock));
    }

    @Test
    void reconciliationFixesALostWebhook() throws Exception {
        Order o = order(buyer(), pretoP, 1);
        jdbc.update("UPDATE payment SET created_at = now() - interval '20 minutes' WHERE order_id = :id", new MapSqlParameterSource("id", o.id()));
        when(stripe.retrieveIntent(o.intent())).thenReturn(node(intent(o, "succeeded", o.total())));
        payments.reconcile();
        assertThat(status(o)).isEqualTo("PAID");
    }

    @Test
    void customerCancelCancelsTheIntentFirst() throws Exception {
        Order o = order(buyer(), pretoP, 2);
        assertThat(ok(post("/api/orders/" + o.number() + "/cancel").bearer(o.buyer().token()).send()).path("status").asString())
                .isEqualTo("CANCELLED");
        verify(stripe).cancelIntent(o.intent());
        assertThat(reserved(pretoP)).isZero();

        // Já pago na Stripe: não cancela
        Order paying = order(buyer(), pretoP, 1);
        when(stripe.cancelIntent(paying.intent())).thenReturn("succeeded");
        assertThat(post("/api/orders/" + paying.number() + "/cancel").bearer(paying.buyer().token()).send().status()).isEqualTo(409);
        assertThat(status(paying)).isEqualTo("PENDING_PAYMENT");
    }

    // ---- reembolsos e disputas ----

    @Test
    void partialRefundOfOneItemWithRestock() throws Exception {
        Order o = order(buyer(), pretoP, 2);
        paid(o);
        long item = (long) column("SELECT id FROM order_item WHERE order_id = :id", o);
        when(stripe.createRefund(anyString(), anyLong(), anyLong())).thenAnswer(i -> node(Map.of("id", "re_p" + i.getArgument(2),
                "status", "pending", "amount", i.getArgument(1), "metadata", Map.of("refund_id", String.valueOf((long) i.getArgument(2))))));

        String key = UUID.randomUUID().toString();
        var body = Map.<String, Object>of("items", List.of(Map.of("orderItemId", item, "quantity", 1)), "restock", true, "reason", "Devolução");
        var created = post("/api/admin/orders/" + o.number() + "/refunds").bearer(admin).header("Idempotency-Key", key).body(body).send();
        assertThat(created.status()).as(created.body()).isEqualTo(201);
        assertThat(created.json().path("amount").asLong()).isEqualTo(10_000);
        assertThat(created.text("status")).isEqualTo("PENDING");
        // Mesmo clique de novo: mesmo reembolso, Stripe chamada uma vez
        var same = post("/api/admin/orders/" + o.number() + "/refunds").bearer(admin).header("Idempotency-Key", key).body(body).send();
        assertThat(same.json().path("id").asLong()).isEqualTo(created.json().path("id").asLong());
        verify(stripe, times(1)).createRefund(anyString(), anyLong(), anyLong());
        assertThat(status(o)).isEqualTo("PAID"); // nada muda até a Stripe confirmar

        long refundId = created.json().path("id").asLong();
        webhook(event("evt_ref_" + refundId, "refund.updated", Map.of("id", "re_p" + refundId, "object", "refund", "status", "succeeded",
                "amount", 10_000, "metadata", Map.of("refund_id", String.valueOf(refundId)))));
        assertThat(status(o)).isEqualTo("PARTIALLY_REFUNDED");
        assertThat(column("SELECT quantity_refunded FROM order_item WHERE order_id = :id", o)).isEqualTo(1);
        assertThat(onHand(pretoP)).isEqualTo(4); // 5 − 2 vendidas + 1 devolvida

        assertThat(refund(o, Map.of("amount", o.total(), "reason", "Excede")).text("code")).isEqualTo("REFUND_EXCEEDS_BALANCE");
        assertThat(post("/api/admin/orders/" + o.number() + "/refunds").bearer(o.buyer().token())
                .header("Idempotency-Key", UUID.randomUUID().toString()).body(Map.of("amount", 1, "reason", "x")).send().status()).isEqualTo(403);
    }

    @Test
    void failedRefundLeavesTheOrderUntouchedAndAdminCancelRestocks() throws Exception {
        Order o = order(buyer(), pretoP, 2);
        paid(o);
        when(stripe.createRefund(anyString(), anyLong(), anyLong())).thenThrow(new RuntimeException("insufficient balance"));
        var failed = refund(o, Map.of("amount", 500, "reason", "Cortesia"));
        assertThat(failed.text("status")).isEqualTo("FAILED");
        assertThat(status(o)).isEqualTo("PAID");

        stripeUp();
        var cancel = refund(o, Map.of("cancelOrder", true, "reason", "Cliente desistiu"));
        assertThat(cancel.text("status")).as(cancel.body()).isEqualTo("SUCCEEDED");
        assertThat(cancel.json().path("amount").asLong()).isEqualTo(o.total()); // o reembolso falho não conta no saldo
        assertThat(status(o)).isEqualTo("CANCELLED");
        assertThat(onHand(pretoP)).isEqualTo(5);
    }

    @Test
    void disputeFlagsTheOrderUntilWon() {
        Order o = order(buyer(), pretoP, 1);
        paid(o);
        var dispute = new HashMap<String, Object>(Map.of("id", "dp_" + o.id(), "object", "dispute", "payment_intent", o.intent(),
                "amount", o.total(), "reason", "fraudulent", "status", "needs_response",
                "evidence_details", Map.of("due_by", Instant.now().plusSeconds(7 * 86400).getEpochSecond())));
        webhook(event("evt_dp1_" + o.id(), "charge.dispute.created", dispute));
        assertThat(column("SELECT has_dispute FROM orders WHERE id = :id", o)).isEqualTo(true);

        dispute.put("status", "won");
        webhook(event("evt_dp2_" + o.id(), "charge.dispute.closed", dispute));
        assertThat(column("SELECT has_dispute FROM orders WHERE id = :id", o)).isEqualTo(false);
        assertThat(column("SELECT status FROM dispute WHERE payment_id = (SELECT id FROM payment WHERE order_id = :id)", o)).isEqualTo("won");
    }
}
