package com.atelier.cart;

import com.atelier.catalog.CatalogFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Carrinho (PRD, seção 7), cupons (13) e favoritos (14.1) de ponta a ponta. */
class CartTests extends CatalogFixtures {

    long camiseta, promo, cat, pretoP, pretoM, brancoP, promoVar;

    /**
     * Camiseta (R$ 100): Preto/P estoque 5, Preto/M estoque 1, Branco/P esgotado.
     * Promo (R$ 80 → R$ 50): estoque 5, outra categoria.
     */
    @BeforeEach
    void products() {
        cat = category("Roupas", null).path("id").asLong();
        long outra = category("Outra", null).path("id").asLong();
        long preto = color("Preto", "#111111");
        long branco = color("Branco", "#FFFFFF");
        long p = size("P", 1);
        long m = size("M", 2);

        var c = product("Camiseta", cat, 10_000, "UNISEX", List.of(), List.of(preto, branco), List.of(p, m));
        camiseta = c.path("id").asLong();
        pretoP = variantId(c, preto, p);
        pretoM = variantId(c, preto, m);
        brancoP = variantId(c, branco, p);
        stock(camiseta, pretoP, 5);
        stock(camiseta, pretoM, 1);
        publish(camiseta);

        var pr = product("Promo", outra, 8_000, "UNISEX", List.of(), List.of(preto), List.of(p));
        promo = pr.path("id").asLong();
        promoVar = variantId(pr, preto, p);
        ok(put("/api/admin/products/" + promo + "/variants/" + promoVar).bearer(admin).body(Map.of("salePrice", 5_000)).send());
        stock(promo, promoVar, 5);
        publish(promo);
    }

    // ---- helpers ----

    private Req withToken(Req req, String token) {
        return token == null ? req : req.header("X-Cart-Token", token);
    }

    private Res addItem(String token, long variant, int qty) {
        return withToken(post("/api/cart/items"), token).body(Map.of("variantId", variant, "quantity", qty)).send();
    }

    private JsonNode cart(String token) {
        return ok(withToken(get("/api/cart"), token).send());
    }

    private String coupon(Map<String, Object> overrides) {
        String code = ("C" + tag + overrides.hashCode()).replace("-", "").toUpperCase();
        var body = new HashMap<String, Object>(Map.of("code", code, "type", "PERCENTAGE", "value", 10));
        body.putAll(overrides);
        ok(post("/api/admin/coupons").bearer(admin).body(body).send());
        return code;
    }

    private Res applyCoupon(String token, String code) {
        return withToken(put("/api/cart/coupon"), token).body(Map.of("code", code)).send();
    }

    // ---- testes ----

    @Test
    void guestCartIsCreatedOnFirstAddAndIdentifiedByToken() {
        var created = addItem(null, pretoP, 2);
        assertThat(created.status()).isEqualTo(201);
        String token = created.raw().headers().firstValue("X-Cart-Token").orElseThrow();
        assertThat(created.text("cartToken")).isEqualTo(token);

        var same = cart(token);
        assertThat(same.path("count").asInt()).isEqualTo(2);
        assertThat(same.path("totals").path("subtotal").asLong()).isEqualTo(20_000);
        assertThat(same.path("cartToken").isNull()).isTrue();
        assertThat(same.path("canCheckout").asBoolean()).isTrue();

        assertThat(cart(null).path("items").size()).isZero();
        assertThat(cart("token-que-nao-existe").path("items").size()).isZero();
        assertThat(withToken(get("/api/cart"), token).send().raw().headers().firstValue("Cache-Control"))
                .hasValueSatisfying(v -> assertThat(v).contains("no-store"));
    }

    @Test
    void stockAndQuantityLimitsAreEnforced() {
        var over = addItem(null, pretoP, 6);
        assertThat(over.status()).isEqualTo(409);
        assertThat(over.text("detail")).contains("5");
        assertThat(addItem(null, pretoP, 11).status()).isEqualTo(400);
        assertThat(addItem(null, brancoP, 1).text("code")).isEqualTo("INSUFFICIENT_STOCK");

        String token = addItem(null, pretoP, 5).text("cartToken");
        assertThat(addItem(token, pretoP, 1).status()).isEqualTo(409); // 5 + 1 > estoque
    }

    @Test
    void changingSizeMergesAndRespectsStockAndProduct() {
        String token = addItem(null, pretoP, 1).text("cartToken");
        addItem(token, pretoM, 1);
        long itemP = cart(token).path("items").get(0).path("id").asLong();

        // Trocar P por M juntaria 2 unidades de M, que só tem 1
        var merged = withToken(patch("/api/cart/items/" + itemP), token).body(Map.of("variantId", pretoM)).send();
        assertThat(merged.text("code")).isEqualTo("INSUFFICIENT_STOCK");

        var otherProduct = withToken(patch("/api/cart/items/" + itemP), token).body(Map.of("variantId", promoVar)).send();
        assertThat(otherProduct.text("code")).isEqualTo("VARIANT_OF_OTHER_PRODUCT");

        var qty = ok(withToken(patch("/api/cart/items/" + itemP), token).body(Map.of("quantity", 3)).send());
        assertThat(qty.path("count").asInt()).isEqualTo(4);
        // Opções de tamanho da mesma cor, com disponibilidade
        assertThat(qty.path("items").get(0).path("sizes").toString()).contains("\"size\":\"P\"", "\"size\":\"M\"");
    }

    @Test
    void priceChangeAndStockOutAreWarnedOnRead() {
        String token = addItem(null, pretoP, 2).text("cartToken");
        ok(put("/api/admin/products/" + camiseta + "/variants/" + pretoP).bearer(admin).body(Map.of("price", 12_000)).send());

        var first = cart(token);
        assertThat(first.path("warnings").toString()).contains("PRICE_CHANGED");
        assertThat(first.path("totals").path("subtotal").asLong()).isEqualTo(24_000);
        assertThat(cart(token).path("warnings").toString()).doesNotContain("PRICE_CHANGED"); // avisa uma vez

        stock(camiseta, pretoP, -5);
        var out = cart(token);
        assertThat(out.path("items").get(0).path("status").asString()).isEqualTo("OUT_OF_STOCK");
        assertThat(out.path("totals").path("total").asLong()).isZero();
        assertThat(out.path("canCheckout").asBoolean()).isFalse();
    }

    @Test
    void couponRejectionsAreSpecific() {
        String token = addItem(null, pretoP, 1).text("cartToken"); // R$ 100 na categoria "Roupas"

        assertThat(applyCoupon(token, "NAOEXISTE").text("code")).isEqualTo("COUPON_NOT_FOUND");
        var min = applyCoupon(token, coupon(Map.of("minOrderAmount", 15_000)));
        assertThat(min.text("code")).isEqualTo("COUPON_MIN_AMOUNT_NOT_REACHED");
        assertThat(min.text("detail")).contains("Faltam R$ 50,00");
        assertThat(applyCoupon(token, coupon(Map.of("startsAt", "2020-01-01T00:00:00Z", "endsAt", "2020-02-01T00:00:00Z"))).text("code"))
                .isEqualTo("COUPON_EXPIRED");
        assertThat(applyCoupon(token, coupon(Map.of("usageLimitPerUser", 1))).text("code")).isEqualTo("COUPON_REQUIRES_LOGIN");
        long outra = category("Acessorios", null).path("id").asLong();
        assertThat(applyCoupon(token, coupon(Map.of("categoryIds", List.of(outra)))).text("code")).isEqualTo("COUPON_NO_ELIGIBLE_ITEMS");

        String saleToken = addItem(null, promoVar, 1).text("cartToken");
        assertThat(applyCoupon(saleToken, coupon(Map.of("excludeSaleItems", true))).text("code")).isEqualTo("COUPON_NO_ELIGIBLE_ITEMS");
    }

    @Test
    void validCouponDiscountsAndIsRemovedWhenItStopsApplying() {
        String token = addItem(null, pretoP, 2).text("cartToken");   // R$ 200 elegível
        addItem(token, promoVar, 1);                                    // R$ 50, outra categoria
        String code = coupon(Map.of("value", 10, "categoryIds", List.of(cat), "minOrderAmount", 15_000));

        var applied = ok(applyCoupon(token, code.toLowerCase())); // cliente digita em minúsculas
        assertThat(applied.path("coupon").path("code").asString()).isEqualTo(code);
        assertThat(applied.path("totals").path("discount").asLong()).isEqualTo(2_000); // 10% só de R$ 200
        assertThat(applied.path("totals").path("total").asLong()).isEqualTo(23_000);

        // Tirando 1 camiseta o elegível cai para R$ 100 < mínimo: cupom sai com aviso.
        long item = applied.path("items").get(0).path("id").asLong();
        var after = ok(withToken(patch("/api/cart/items/" + item), token).body(Map.of("quantity", 1)).send());
        assertThat(after.path("coupon").isNull()).isTrue();
        assertThat(after.path("warnings").toString()).contains("COUPON_REMOVED");
        assertThat(after.path("totals").path("discount").asLong()).isZero();
    }

    @Test
    void shippingIsQuotedByPostalCodeAndFreeAboveThreshold() {
        String token = addItem(null, pretoP, 1).text("cartToken");
        var quoted = ok(withToken(put("/api/cart/shipping"), token).body(Map.of("postalCode", "01310100")).send());
        assertThat(quoted.path("shipping").path("selected").asString()).isEqualTo("pac");
        assertThat(quoted.path("shipping").path("options").size()).isEqualTo(2);
        assertThat(quoted.path("totals").path("shipping").asLong()).isEqualTo(1_890);
        assertThat(quoted.path("totals").path("total").asLong()).isEqualTo(11_890);

        long item = quoted.path("items").get(0).path("id").asLong();
        var free = ok(withToken(patch("/api/cart/items/" + item), token).body(Map.of("quantity", 3)).send());
        assertThat(free.path("totals").path("shippingDiscount").asLong()).isEqualTo(1_890); // R$ 300 >= R$ 299
        assertThat(free.path("totals").path("total").asLong()).isEqualTo(30_000);

        var sedex = ok(withToken(put("/api/cart/shipping"), token).body(Map.of("postalCode", "01310100", "option", "sedex")).send());
        assertThat(sedex.path("totals").path("shippingDiscount").asLong()).isZero(); // expresso não entra no grátis

        assertThat(withToken(put("/api/cart/shipping"), token).body(Map.of("postalCode", "0131")).send().status()).isEqualTo(400);
    }

    @Test
    void guestCartMergesIntoAccountOnLogin() {
        String token = addItem(null, pretoP, 2).text("cartToken");
        String user = register(uniqueEmail(), "senha-forte-1").text("accessToken");
        ok(post("/api/cart/items").bearer(user).body(Map.of("variantId", pretoP, "quantity", 1)).send());

        var merged = ok(post("/api/cart/merge").bearer(user).header("X-Cart-Token", token).send());
        assertThat(merged.path("items").size()).isEqualTo(1);
        assertThat(merged.path("count").asInt()).isEqualTo(3);
        assertThat(cart(token).path("items").size()).isZero(); // carrinho do convidado não existe mais
        assertThat(ok(get("/api/cart").bearer(user).send()).path("count").asInt()).isEqualTo(3);

        assertThat(post("/api/cart/merge").header("X-Cart-Token", token).send().status()).isEqualTo(401);
    }

    @Test
    void couponLimitedPerUserWorksWhenLoggedIn() {
        String user = register(uniqueEmail(), "senha-forte-1").text("accessToken");
        ok(post("/api/cart/items").bearer(user).body(Map.of("variantId", pretoP, "quantity", 1)).send());
        var res = ok(put("/api/cart/coupon").bearer(user).body(Map.of("code", coupon(Map.of("usageLimitPerUser", 1)))).send());
        assertThat(res.path("totals").path("discount").asLong()).isEqualTo(1_000);
    }

    @Test
    void wishlistIsIdempotentAndPrivate() {
        String user = register(uniqueEmail(), "senha-forte-1").text("accessToken");
        assertThat(put("/api/me/wishlist/" + camiseta).bearer(user).send().status()).isEqualTo(204);
        assertThat(put("/api/me/wishlist/" + camiseta).bearer(user).send().status()).isEqualTo(204);
        put("/api/me/wishlist/" + promo).bearer(user).send();

        assertThat(ok(get("/api/me/wishlist/ids").bearer(user).send()).toString()).isEqualTo("[" + promo + "," + camiseta + "]");
        assertThat(ok(get("/api/me/wishlist").bearer(user).send()).get(0).path("name").asString()).isEqualTo("Promo");

        assertThat(delete("/api/me/wishlist/" + promo).bearer(user).send().status()).isEqualTo(204);
        assertThat(ok(get("/api/me/wishlist/ids").bearer(user).send()).size()).isEqualTo(1);
        assertThat(put("/api/me/wishlist/999999999").bearer(user).send().status()).isEqualTo(404);
        assertThat(get("/api/me/wishlist").send().status()).isEqualTo(401);
    }

    @Test
    void onlyAdminsManageCoupons() {
        String user = register(uniqueEmail(), "senha-forte-1").text("accessToken");
        assertThat(get("/api/admin/coupons").bearer(user).send().status()).isEqualTo(403);
        String code = coupon(Map.of());
        var dup = post("/api/admin/coupons").bearer(admin).body(Map.of("code", code, "type", "PERCENTAGE", "value", 10)).send();
        assertThat(dup.text("code")).isEqualTo("COUPON_CODE_EXISTS");
        assertThat(post("/api/admin/coupons").bearer(admin).body(Map.of("code", "X" + tag, "type", "PERCENTAGE", "value", 150)).send().status())
                .isEqualTo(400);
    }
}
