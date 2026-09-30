package com.atelier.order;

import com.atelier.catalog.CatalogFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import tools.jackson.databind.JsonNode;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Produto com estoque, cliente com endereço e sacola, checkout: base dos testes de pedido e pagamento. */
abstract class OrderFixtures extends CatalogFixtures {

    @Autowired
    NamedParameterJdbcTemplate jdbc;

    @Autowired
    OrderService orders;

    long camiseta, pretoP, pretoM;

    /** Camiseta R$ 100 (300 g): Preto/P estoque 5, Preto/M estoque 3. */
    @BeforeEach
    void products() {
        long cat = category("Roupas", null).path("id").asLong();
        long preto = color("Preto", "#111111");
        long p = size("P", 1);
        long m = size("M", 2);
        var c = product("Camiseta", cat, 10_000, "UNISEX", List.of(), List.of(preto), List.of(p, m));
        camiseta = c.path("id").asLong();
        pretoP = variantId(c, preto, p);
        pretoM = variantId(c, preto, m);
        stock(camiseta, pretoP, 5);
        stock(camiseta, pretoM, 3);
        publish(camiseta);
    }

    // ---- helpers ----

    protected record Buyer(String token, long addressId) {}

    protected Buyer buyer() {
        rateLimit.reset(); // cadastro tem limite por IP
        String token = register(uniqueEmail(), "senha-forte-1").text("accessToken");
        var a = new HashMap<String, Object>(Map.of("recipientName", "Ana Souza", "phone", "11987654321", "postalCode", "01310100",
                "state", "SP", "city", "São Paulo", "district", "Bela Vista", "street", "Avenida Paulista", "number", "1000"));
        return new Buyer(token, ok(post("/api/me/addresses").bearer(token).body(a).send()).path("id").asLong());
    }

    protected void add(Buyer b, long variant, int qty) {
        ok(post("/api/cart/items").bearer(b.token()).body(Map.of("variantId", variant, "quantity", qty)).send());
    }

    protected Res checkout(Buyer b, String key, String option) {
        var body = new HashMap<String, Object>(Map.of("addressId", b.addressId()));
        if (option != null) body.put("shippingOption", option);
        return post("/api/checkout").bearer(b.token()).header("Idempotency-Key", key).body(body).send();
    }

    protected Res checkout(Buyer b) {
        return checkout(b, UUID.randomUUID().toString(), null);
    }

    protected int reserved(long variant) {
        return jdbc.queryForObject("SELECT reserved FROM inventory WHERE variant_id = :v", new MapSqlParameterSource("v", variant), Integer.class);
    }

    protected String coupon(Map<String, Object> overrides) {
        String code = ("K" + tag + overrides.hashCode()).replace("-", "").toUpperCase();
        var body = new HashMap<String, Object>(Map.of("code", code, "type", "PERCENTAGE", "value", 10));
        body.putAll(overrides);
        ok(post("/api/admin/coupons").bearer(admin).body(body).send());
        return code;
    }

    protected String couponUsageStatus(String orderNumber) {
        return jdbc.queryForObject("""
                SELECT cu.status FROM coupon_usage cu JOIN orders o ON o.id = cu.order_id WHERE o.order_number = :n
                """, new MapSqlParameterSource("n", orderNumber), String.class);
    }

    /** Dispara as requisições ao mesmo tempo e devolve os status HTTP. */
    protected List<Res> concurrently(List<Callable<Res>> calls) throws Exception {
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(calls.size())) {
            List<Future<Res>> futures = new ArrayList<>();
            for (var call : calls) futures.add(pool.submit(() -> { start.await(); return call.call(); }));
            start.countDown();
            List<Res> results = new ArrayList<>();
            for (var f : futures) results.add(f.get(60, TimeUnit.SECONDS));
            return results;
        }
    }


    // ---- pagamento (Stripe simulada) ----

    static final String SECRET = "whsec_test_segredo";

    /** Pedido com o id e o intent que a "Stripe" criou para ele. */
    protected record Order(Buyer buyer, long id, String number, long total, String intent) {}

    @BeforeEach
    protected void stripeUp() throws Exception {
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

    protected JsonNode node(Object value) {
        return json.readTree(json.writeValueAsString(value));
    }

    protected Order order(Buyer b, long variant, int qty) {
        add(b, variant, qty);
        var placed = checkout(b);
        assertThat(placed.status()).as(placed.body()).isEqualTo(201);
        var intent = ok(post("/api/orders/" + placed.text("orderNumber") + "/payment-intent").bearer(b.token()).send());
        long id = placed.json().path("orderId").asLong();
        return new Order(b, id, placed.text("orderNumber"), placed.json().path("total").asLong(), "pi_" + id + "_1");
    }

    protected Map<String, Object> intent(Order o, String status, long received) {
        return Map.of("id", o.intent(), "object", "payment_intent", "status", status, "amount", o.total(),
                "amount_received", received, "currency", "brl", "metadata", Map.of("order_id", String.valueOf(o.id())));
    }

    protected String event(String id, String type, Map<String, Object> object) {
        return json.writeValueAsString(Map.of("id", id, "object", "event", "type", type, "livemode", false,
                "data", Map.of("object", object)));
    }

    protected Res webhook(String payload, long timestamp, String secret) {
        try {
            var mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            String sig = HexFormat.of().formatHex(mac.doFinal((timestamp + "." + payload).getBytes(StandardCharsets.UTF_8)));
            return post("/api/payments/webhook").header("Stripe-Signature", "t=" + timestamp + ",v1=" + sig).body(payload).send();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    protected Res webhook(String payload) {
        return webhook(payload, Instant.now().getEpochSecond(), SECRET);
    }

    protected void paid(Order o) {
        assertThat(webhook(event("evt_ok_" + o.id(), "payment_intent.succeeded", intent(o, "succeeded", o.total()))).status()).isEqualTo(200);
        assertThat(status(o)).isEqualTo("PAID");
    }

    protected String status(Order o) {
        return ok(get("/api/orders/" + o.number()).bearer(o.buyer().token()).send()).path("status").asString();
    }

    protected int onHand(long variant) {
        return jdbc.queryForObject("SELECT on_hand FROM inventory WHERE variant_id = :v", new MapSqlParameterSource("v", variant), Integer.class);
    }

    protected Object column(String sql, Order o) {
        return jdbc.queryForObject(sql, new MapSqlParameterSource("id", o.id()), Object.class);
    }

    protected Res refund(Order o, Map<String, Object> body) {
        return post("/api/admin/orders/" + o.number() + "/refunds").bearer(admin)
                .header("Idempotency-Key", UUID.randomUUID().toString()).body(body).send();
    }

}
