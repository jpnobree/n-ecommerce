package com.atelier.order;

import com.atelier.catalog.CatalogFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.util.*;
import java.util.concurrent.*;

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

}
