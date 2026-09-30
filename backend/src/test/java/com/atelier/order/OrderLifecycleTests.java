package com.atelier.order;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import tools.jackson.databind.JsonNode;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

/** Ciclo de vida do pedido (PRD 11 e 15.3): máquina de estados, histórico, envio, "Meus pedidos", admin e e-mails. */
class OrderLifecycleTests extends OrderFixtures {

    private String operator() {
        rateLimit.reset();
        String email = uniqueEmail();
        register(email, "senha-forte-1");
        jdbc.update("INSERT INTO user_role (user_id, role) SELECT id, 'OPERATOR' FROM app_user WHERE email = :e",
                new MapSqlParameterSource("e", email));
        return login(email, "senha-forte-1").text("accessToken");
    }

    private JsonNode adminDetail(String token, Order o) {
        return ok(get("/api/admin/orders/" + o.number()).bearer(token).send());
    }

    private Res move(String token, Order o, String to, long version, String carrier, String tracking) {
        var body = new HashMap<String, Object>(Map.of("to", to, "version", version));
        if (carrier != null) body.put("carrier", carrier);
        if (tracking != null) body.put("trackingCode", tracking);
        return post("/api/admin/orders/" + o.number() + "/transitions").bearer(token).body(body).send();
    }

    private long version(String token, Order o) {
        return adminDetail(token, o).path("order").path("version").asLong();
    }

    private List<String> emails(Order o) {
        return jdbc.queryForList("SELECT payload->>'subject' FROM outbox_event WHERE aggregate_type = 'ORDER' AND aggregate_id = :id ORDER BY id",
                new MapSqlParameterSource("id", o.id()), String.class);
    }

    @Test
    void orderGoesThroughTheWholeCycle() {
        Order o = order(buyer(), pretoP, 1);
        paid(o);
        String op = operator();

        long v = version(op, o);
        assertThat(ok(move(op, o, "PROCESSING", v, null, null)).path("order").path("status").asString()).isEqualTo("PROCESSING");
        // Dois operadores com a mesma versão: o segundo perde
        assertThat(move(op, o, "SHIPPED", v, "Correios", "AA123456789BR").text("code")).isEqualTo("CONCURRENT_MODIFICATION");
        v = version(op, o);
        assertThat(move(op, o, "SHIPPED", v, null, null).text("code")).isEqualTo("VALIDATION_ERROR");
        var shipped = ok(move(op, o, "SHIPPED", v, "Correios", "AA123456789BR"));
        assertThat(shipped.path("order").path("status").asString()).isEqualTo("SHIPPED");
        assertThat(shipped.path("order").path("trackingCode").asString()).isEqualTo("AA123456789BR");
        v = shipped.path("order").path("version").asLong();
        v = ok(move(op, o, "DELIVERED", v, null, null)).path("order").path("version").asLong();
        assertThat(move(op, o, "PROCESSING", v, null, null).text("code")).isEqualTo("INVALID_STATUS_TRANSITION");

        // Cliente vê a linha do tempo e o rastreio
        JsonNode mine = ok(get("/api/orders/" + o.number()).bearer(o.buyer().token()).send());
        List<String> timeline = new ArrayList<>();
        mine.path("timeline").forEach(t -> timeline.add(t.path("status").asString()));
        assertThat(timeline).containsExactly("PENDING_PAYMENT", "PAID", "PROCESSING", "SHIPPED", "DELIVERED");
        assertThat(mine.path("fulfillmentStatus").asString()).isEqualTo("DELIVERED");
        assertThat(mine.path("carrier").asString()).isEqualTo("Correios");

        // Admin vê quem fez cada mudança
        List<String> actors = new ArrayList<>();
        adminDetail(admin, o).path("history").forEach(h -> actors.add(h.path("actorType").asString()));
        assertThat(actors).containsExactly("CUSTOMER", "STRIPE", "OPERATOR", "OPERATOR", "OPERATOR");

        assertThat(emails(o)).containsExactly("Pedido " + o.number() + " confirmado", "Pedido " + o.number() + " enviado",
                "Pedido " + o.number() + " entregue");
        assertThat(jdbc.queryForObject("SELECT payload->>'body' FROM outbox_event WHERE aggregate_id = :id AND payload->>'subject' LIKE '%enviado'",
                new MapSqlParameterSource("id", o.id()), String.class)).contains("AA123456789BR");

        // Operador não reembolsa nem cancela
        assertThat(refund(o, Map.of("amount", 100, "reason", "x")).status()).isEqualTo(201); // admin pode
        assertThat(post("/api/admin/orders/" + o.number() + "/refunds").bearer(op).header("Idempotency-Key", UUID.randomUUID().toString())
                .body(Map.of("amount", 100, "reason", "x")).send().status()).isEqualTo(403);
        assertThat(post("/api/admin/orders/" + o.number() + "/cancel").bearer(op).body(Map.of("reason", "x")).send().status()).isEqualTo(403);
    }

    @Test
    void disputeOrReviewBlocksShipping() {
        Order o = order(buyer(), pretoP, 1);
        paid(o);
        webhook(event("evt_dp_ship_" + o.id(), "charge.dispute.created", Map.of("id", "dp_ship_" + o.id(), "payment_intent", o.intent(),
                "amount", o.total(), "reason", "fraudulent", "status", "needs_response")));
        long v = ok(move(admin, o, "PROCESSING", version(admin, o), null, null)).path("order").path("version").asLong();
        assertThat(move(admin, o, "SHIPPED", v, "Correios", "AA1BR").text("code")).isEqualTo("FULFILLMENT_BLOCKED");
    }

    @Test
    void partiallyRefundedOrderStillShips() {
        Order o = order(buyer(), pretoP, 2);
        paid(o);
        long item = (long) column("SELECT id FROM order_item WHERE order_id = :id", o);
        ok(refund(o, Map.of("items", List.of(Map.of("orderItemId", item, "quantity", 1)), "restock", true, "reason", "Desistiu de uma")));
        assertThat(status(o)).isEqualTo("PARTIALLY_REFUNDED");

        var processing = ok(move(admin, o, "PROCESSING", version(admin, o), null, null)).path("order");
        assertThat(processing.path("status").asString()).isEqualTo("PARTIALLY_REFUNDED");
        assertThat(processing.path("fulfillmentStatus").asString()).isEqualTo("PROCESSING");
        var shipped = ok(move(admin, o, "SHIPPED", processing.path("version").asLong(), "Jadlog", "JD1")).path("order");
        assertThat(shipped.path("fulfillmentStatus").asString()).isEqualTo("SHIPPED");
        assertThat(emails(o)).contains("Pedido " + o.number() + " enviado");
        assertThat(column("SELECT count(*) FROM order_status_history WHERE order_id = :id AND kind = 'FULFILLMENT'", o)).isEqualTo(2L);
    }

    @Test
    void adminCancelsUnpaidAndPaidOrdersButNotShippedOnes() {
        Order unpaid = order(buyer(), pretoP, 1);
        var c1 = ok(post("/api/admin/orders/" + unpaid.number() + "/cancel").bearer(admin).body(Map.of("reason", "Fraude")).send());
        assertThat(c1.path("order").path("status").asString()).isEqualTo("CANCELLED");
        assertThat(emails(unpaid)).containsExactly("Pedido " + unpaid.number() + " cancelado");

        Order paidOrder = order(buyer(), pretoP, 2);
        paid(paidOrder);
        assertThat(onHand(pretoP)).isEqualTo(3);
        var c2 = ok(post("/api/admin/orders/" + paidOrder.number() + "/cancel").bearer(admin).body(Map.of("reason", "Sem entrega na região")).send());
        assertThat(c2.path("order").path("status").asString()).isEqualTo("CANCELLED");
        assertThat(c2.path("refunds").get(0).path("amount").asLong()).isEqualTo(paidOrder.total());
        assertThat(onHand(pretoP)).isEqualTo(5);
        // Repetir o cancelamento não reembolsa de novo
        assertThat(post("/api/admin/orders/" + paidOrder.number() + "/cancel").bearer(admin).body(Map.of("reason", "de novo")).send().status())
                .isEqualTo(200);
        assertThat(column("SELECT count(*) FROM refund WHERE order_id = :id", paidOrder)).isEqualTo(1L);

        Order shipped = order(buyer(), pretoM, 1);
        paid(shipped);
        long v = ok(move(admin, shipped, "PROCESSING", version(admin, shipped), null, null)).path("order").path("version").asLong();
        ok(move(admin, shipped, "SHIPPED", v, "Correios", "AA2BR"));
        assertThat(post("/api/admin/orders/" + shipped.number() + "/cancel").bearer(admin).body(Map.of("reason", "x")).send().text("code"))
                .isEqualTo("INVALID_STATUS_TRANSITION");
    }

    @Test
    void customerListsOnlyOwnOrdersAndAdminSearches() {
        Buyer b = buyer();
        Order first = order(b, pretoP, 1);
        paid(first);
        Order second = order(b, pretoM, 1);

        JsonNode mine = ok(get("/api/orders").bearer(b.token()).send());
        assertThat(mine.path("totalElements").asLong()).isEqualTo(2);
        assertThat(mine.path("content").get(0).path("orderNumber").asString()).isEqualTo(second.number());
        assertThat(ok(get("/api/orders").bearer(buyer().token()).send()).path("totalElements").asLong()).isZero();

        String email = jdbc.queryForObject("SELECT customer_email FROM orders WHERE id = :id", new MapSqlParameterSource("id", first.id()), String.class);
        assertThat(ok(get("/api/admin/orders?q=" + first.number()).bearer(admin).send()).path("totalElements").asLong()).isEqualTo(1);
        assertThat(ok(get("/api/admin/orders?q=" + email).bearer(admin).send()).path("totalElements").asLong()).isEqualTo(2);
        assertThat(ok(get("/api/admin/orders?q=" + email + "&status=PAID").bearer(admin).send()).path("totalElements").asLong()).isEqualTo(1);
        String sku = jdbc.queryForObject("SELECT sku FROM order_item WHERE order_id = :id", new MapSqlParameterSource("id", second.id()), String.class);
        assertThat(ok(get("/api/admin/orders?q=" + sku).bearer(admin).send()).path("content").get(0).path("orderNumber").asString())
                .isEqualTo(second.number());
        assertThat(get("/api/admin/orders").bearer(b.token()).send().status()).isEqualTo(403);

        var noted = ok(post("/api/admin/orders/" + first.number() + "/notes").bearer(operator()).body(Map.of("text", "Cliente pediu embrulho")).send());
        assertThat(noted.path("internalNotes").get(0).path("text").asString()).isEqualTo("Cliente pediu embrulho");
        // Nota interna nunca aparece para o cliente
        assertThat(get("/api/orders/" + first.number()).bearer(b.token()).send().body()).doesNotContain("embrulho");
    }
}
