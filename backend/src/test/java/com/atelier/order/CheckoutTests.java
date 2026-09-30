package com.atelier.order;

import com.atelier.catalog.CatalogFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import tools.jackson.databind.JsonNode;

import java.util.*;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;

/** Checkout (PRD 9.2 e 9.3): pedido com snapshot, reserva atômica, idempotência, expiração e cancelamento. */
class CheckoutTests extends CatalogFixtures {

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

    record Buyer(String token, long addressId) {}

    private Buyer buyer() {
        rateLimit.reset(); // cadastro tem limite por IP
        String token = register(uniqueEmail(), "senha-forte-1").text("accessToken");
        var a = new HashMap<String, Object>(Map.of("recipientName", "Ana Souza", "phone", "11987654321", "postalCode", "01310100",
                "state", "SP", "city", "São Paulo", "district", "Bela Vista", "street", "Avenida Paulista", "number", "1000"));
        return new Buyer(token, ok(post("/api/me/addresses").bearer(token).body(a).send()).path("id").asLong());
    }

    private void add(Buyer b, long variant, int qty) {
        ok(post("/api/cart/items").bearer(b.token()).body(Map.of("variantId", variant, "quantity", qty)).send());
    }

    private Res checkout(Buyer b, String key, String option) {
        var body = new HashMap<String, Object>(Map.of("addressId", b.addressId()));
        if (option != null) body.put("shippingOption", option);
        return post("/api/checkout").bearer(b.token()).header("Idempotency-Key", key).body(body).send();
    }

    private Res checkout(Buyer b) {
        return checkout(b, UUID.randomUUID().toString(), null);
    }

    private int reserved(long variant) {
        return jdbc.queryForObject("SELECT reserved FROM inventory WHERE variant_id = :v", new MapSqlParameterSource("v", variant), Integer.class);
    }

    private String coupon(Map<String, Object> overrides) {
        String code = ("K" + tag + overrides.hashCode()).replace("-", "").toUpperCase();
        var body = new HashMap<String, Object>(Map.of("code", code, "type", "PERCENTAGE", "value", 10));
        body.putAll(overrides);
        ok(post("/api/admin/coupons").bearer(admin).body(body).send());
        return code;
    }

    private String couponUsageStatus(String orderNumber) {
        return jdbc.queryForObject("""
                SELECT cu.status FROM coupon_usage cu JOIN orders o ON o.id = cu.order_id WHERE o.order_number = :n
                """, new MapSqlParameterSource("n", orderNumber), String.class);
    }

    /** Dispara as requisições ao mesmo tempo e devolve os status HTTP. */
    private List<Res> concurrently(List<Callable<Res>> calls) throws Exception {
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

    // ---- testes ----

    @Test
    void checkoutCreatesOrderSnapshotAndReservesStock() {
        Buyer b = buyer();
        add(b, pretoP, 2);
        ok(put("/api/cart/coupon").bearer(b.token()).body(Map.of("code", coupon(Map.of()))).send());

        var res = checkout(b);
        assertThat(res.status()).as(res.body()).isEqualTo(201);
        String number = res.text("orderNumber");
        assertThat(number).matches("AT-\\d{4}-\\d{6}");
        assertThat(res.text("status")).isEqualTo("PENDING_PAYMENT");
        // R$ 200 − 10% + PAC Grande SP R$ 18,90 (não chega aos R$ 299 do frete grátis)
        assertThat(res.json().path("total").asLong()).isEqualTo(20_000 - 2_000 + 1_890);
        assertThat(reserved(pretoP)).isEqualTo(2);
        assertThat(couponUsageStatus(number)).isEqualTo("RESERVED");

        // Mudar o produto depois não altera o pedido
        ok(put("/api/admin/products/" + camiseta + "/variants/" + pretoP).bearer(admin).body(Map.of("price", 15_000)).send());
        JsonNode order = ok(get("/api/orders/" + number).bearer(b.token()).send());
        JsonNode item = order.path("items").get(0);
        assertThat(item.path("unitPrice").asLong()).isEqualTo(10_000);
        assertThat(item.path("discount").asLong()).isEqualTo(2_000);
        assertThat(item.path("lineTotal").asLong()).isEqualTo(18_000);
        assertThat(item.path("size").asString()).isEqualTo("P");
        assertThat(order.path("shippingAddress").path("street").asString()).isEqualTo("Avenida Paulista");
        assertThat(order.path("shippingMethod").path("service").asString()).isEqualTo("PAC");

        // Pedido de outro cliente não existe para ele
        assertThat(get("/api/orders/" + number).bearer(buyer().token()).send().status()).isEqualTo(404);
    }

    @Test
    void sameKeyOrSameCartNeverCreatesASecondOrder() {
        Buyer b = buyer();
        add(b, pretoP, 1);
        String key = UUID.randomUUID().toString();

        var first = checkout(b, key, null);
        assertThat(first.status()).as(first.body()).isEqualTo(201);
        var again = checkout(b, key, null);
        assertThat(again.status()).isEqualTo(200);
        assertThat(again.text("orderNumber")).isEqualTo(first.text("orderNumber"));
        // Mesma chave com outro pedido de dados é erro do cliente
        assertThat(checkout(b, key, "sedex").text("code")).isEqualTo("IDEMPOTENCY_KEY_REUSED");
        // Outra aba (outra chave), mesmo carrinho: mesmo pedido
        assertThat(checkout(b).text("orderNumber")).isEqualTo(first.text("orderNumber"));
        assertThat(reserved(pretoP)).isEqualTo(1);

        // Sacola mudou: novo pedido substitui o pendente e a reserva antiga volta
        add(b, pretoM, 1);
        var replaced = checkout(b);
        assertThat(replaced.status()).isEqualTo(201);
        assertThat(replaced.text("orderNumber")).isNotEqualTo(first.text("orderNumber"));
        assertThat(ok(get("/api/orders/" + first.text("orderNumber")).bearer(b.token()).send()).path("status").asString())
                .isEqualTo("CANCELLED");
        assertThat(reserved(pretoP)).isEqualTo(1);
        assertThat(reserved(pretoM)).isEqualTo(1);
    }

    @Test
    void pendingOrderDoesNotHideItsOwnStockFromTheCart() {
        Buyer b = buyer();
        add(b, pretoM, 3); // todo o estoque
        String number = checkout(b).text("orderNumber");
        assertThat(reserved(pretoM)).isEqualTo(3);

        // A sacola continua vendo as 3 unidades (estão reservadas para ela mesma) e volta ao mesmo pedido
        // Reabrir o checkout reenvia o mesmo CEP: não conta como mudança na sacola
        ok(put("/api/cart/shipping").bearer(b.token()).body(Map.of("postalCode", "01310100")).send());
        JsonNode cart = ok(get("/api/cart").bearer(b.token()).send());
        assertThat(cart.path("warnings").size()).isZero();
        assertThat(cart.path("canCheckout").asBoolean()).isTrue();
        var again = checkout(b);
        assertThat(again.status()).as(again.body()).isEqualTo(200);
        assertThat(again.text("orderNumber")).isEqualTo(number);

        // Mudando a sacola, o pedido novo troca a reserva antiga pela nova sem acusar falta
        add(b, pretoP, 1);
        assertThat(checkout(b).status()).isEqualTo(201);
        assertThat(reserved(pretoM)).isEqualTo(3);
        assertThat(reserved(pretoP)).isEqualTo(1);
        // Para os outros clientes, continua esgotado
        Buyer other = buyer();
        assertThat(post("/api/cart/items").bearer(other.token()).body(Map.of("variantId", pretoM, "quantity", 1)).send().status())
                .isEqualTo(409);
    }

    @Test
    void doubleClickCreatesOneOrder() throws Exception {
        Buyer b = buyer();
        add(b, pretoP, 1);
        String key = UUID.randomUUID().toString();
        var results = concurrently(List.of(() -> checkout(b, key, null), () -> checkout(b, key, null), () -> checkout(b, key, null)));
        assertThat(results).allSatisfy(r -> assertThat(r.status()).as(r.body()).isIn(200, 201));
        assertThat(results.stream().map(r -> r.text("orderNumber")).distinct()).hasSize(1);
        assertThat(reserved(pretoP)).isEqualTo(1);
    }

    /** Saída da Fase 6: muitos clientes disputando as últimas unidades, exatamente o estoque é reservado. */
    @Test
    void concurrentCheckoutsNeverOversell() throws Exception {
        List<Buyer> buyers = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            Buyer b = buyer();
            add(b, pretoM, 1); // estoque 3
            buyers.add(b);
        }
        var results = concurrently(buyers.stream().<Callable<Res>>map(b -> () -> checkout(b)).toList());

        assertThat(results.stream().filter(r -> r.status() == 201)).hasSize(3);
        assertThat(results.stream().filter(r -> r.status() == 409)).hasSize(9);
        assertThat(reserved(pretoM)).isEqualTo(3);
        long movements = jdbc.queryForObject("SELECT coalesce(sum(quantity), 0) FROM inventory_movement WHERE variant_id = :v AND type = 'RESERVE'",
                new MapSqlParameterSource("v", pretoM), Long.class);
        assertThat(movements).isEqualTo(3);
    }

    @Test
    void couponUsageLimitHoldsUnderConcurrency() throws Exception {
        String code = coupon(Map.of("usageLimit", 1));
        List<Buyer> buyers = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            Buyer b = buyer();
            add(b, pretoP, 1);
            ok(put("/api/cart/coupon").bearer(b.token()).body(Map.of("code", code)).send());
            buyers.add(b);
        }
        var results = concurrently(buyers.stream().<Callable<Res>>map(b -> () -> checkout(b)).toList());

        assertThat(results.stream().filter(r -> r.status() == 201)).hasSize(1);
        long used = jdbc.queryForObject("SELECT count(*) FROM coupon_usage cu JOIN coupon c ON c.id = cu.coupon_id WHERE c.code = :c AND cu.status <> 'RELEASED'",
                new MapSqlParameterSource("c", code), Long.class);
        assertThat(used).isEqualTo(1);
    }

    @Test
    void unpaidOrdersExpireAndCustomerCanCancel() {
        Buyer b = buyer();
        add(b, pretoP, 2);
        ok(put("/api/cart/coupon").bearer(b.token()).body(Map.of("code", coupon(Map.of("usageLimitPerUser", 1)))).send());
        String number = checkout(b).text("orderNumber");
        assertThat(reserved(pretoP)).isEqualTo(2);

        jdbc.update("UPDATE orders SET expires_at = now() - interval '1 minute' WHERE order_number = :n", new MapSqlParameterSource("n", number));
        orders.expireOverdue();
        JsonNode expired = ok(get("/api/orders/" + number).bearer(b.token()).send());
        assertThat(expired.path("status").asString()).isEqualTo("CANCELLED");
        assertThat(expired.path("cancelReason").asString()).isEqualTo("PAYMENT_TIMEOUT");
        assertThat(reserved(pretoP)).isZero();
        assertThat(couponUsageStatus(number)).isEqualTo("RELEASED");

        // O cupom de uso único volta a valer e o cliente cancela o novo pedido
        var second = checkout(b);
        assertThat(second.status()).as(second.body()).isEqualTo(201);
        var cancelled = ok(post("/api/orders/" + second.text("orderNumber") + "/cancel").bearer(b.token()).send());
        assertThat(cancelled.path("cancelReason").asString()).isEqualTo("CUSTOMER");
        assertThat(reserved(pretoP)).isZero();
        assertThat(post("/api/orders/" + second.text("orderNumber") + "/cancel").bearer(b.token()).send().text("code"))
                .isEqualTo("INVALID_STATUS_TRANSITION");
    }

    @Test
    void checkoutRejectsWhatTheCustomerDidNotSee() {
        Buyer b = buyer();
        Buyer other = buyer();
        assertThat(checkout(b).text("code")).isEqualTo("CART_EMPTY");
        add(b, pretoP, 1);

        assertThat(post("/api/checkout").bearer(b.token()).body(Map.of("addressId", b.addressId())).send().status()).isEqualTo(400);
        assertThat(post("/api/checkout").header("Idempotency-Key", UUID.randomUUID().toString())
                .body(Map.of("addressId", b.addressId())).send().status()).isEqualTo(401);
        assertThat(checkout(new Buyer(b.token(), other.addressId())).status()).isEqualTo(404);

        // Preço mudou depois da última leitura: recusa com o aviso, e a próxima tentativa já usa o preço novo
        ok(put("/api/admin/products/" + camiseta + "/variants/" + pretoP).bearer(admin).body(Map.of("price", 12_000)).send());
        var changed = checkout(b);
        assertThat(changed.status()).isEqualTo(409);
        assertThat(changed.text("code")).isEqualTo("CART_CHANGED");
        assertThat(changed.text("detail")).contains("o preço mudou");
        // Campos de valor no corpo são ignorados: o total vem do banco
        var retry = post("/api/checkout").bearer(b.token()).header("Idempotency-Key", UUID.randomUUID().toString())
                .body(Map.of("addressId", b.addressId(), "total", 1)).send();
        assertThat(retry.status()).as(retry.body()).isEqualTo(201);
        assertThat(retry.json().path("total").asLong()).isEqualTo(12_000 + 1_890);
    }
}
