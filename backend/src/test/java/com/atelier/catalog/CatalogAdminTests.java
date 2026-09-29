package com.atelier.catalog;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Regras do cadastro: árvore de categorias, produtos, variantes, preços e estoque. */
class CatalogAdminTests extends CatalogFixtures {

    @Autowired
    JdbcTemplate jdbc;

    private Map<String, Object> categoryBody(String name, String slug, Long parentId) {
        var body = new HashMap<String, Object>();
        body.put("name", name);
        body.put("slug", slug);
        body.put("parentId", parentId);
        return body;
    }

    @Test
    void categoryTreeLimitsDepthAndPreventsCycles() {
        var a = category("A", null);
        var b = category("B", a.path("id").asLong());
        var c = category("C", b.path("id").asLong());
        assertThat(c.path("path").asString()).isEqualTo("a-" + tag + "/b-" + tag + "/c-" + tag);

        var tooDeep = post("/api/admin/categories").bearer(admin).body(categoryBody("D", "d-" + tag, c.path("id").asLong())).send();
        assertThat(tooDeep.status()).isEqualTo(422);
        assertThat(tooDeep.text("code")).isEqualTo("CATEGORY_DEPTH_EXCEEDED");

        var cycle = put("/api/admin/categories/" + a.path("id").asLong()).bearer(admin)
                .body(categoryBody("A", "a-" + tag, c.path("id").asLong())).send();
        assertThat(cycle.text("code")).isEqualTo("CATEGORY_CYCLE");

        var inUse = delete("/api/admin/categories/" + a.path("id").asLong()).bearer(admin).send();
        assertThat(inUse.status()).isEqualTo(409);
        assertThat(inUse.text("code")).isEqualTo("CATEGORY_IN_USE");
    }

    @Test
    void renamingOrMovingRewritesTheWholeSubtree() {
        var a = category("A", null);
        var b = category("B", a.path("id").asLong());
        var c = category("C", b.path("id").asLong());
        var other = category("Outra", null);

        ok(put("/api/admin/categories/" + a.path("id").asLong()).bearer(admin).body(categoryBody("A", "novo-" + tag, null)).send());
        assertThat(pathOf(c.path("id").asLong())).isEqualTo("novo-" + tag + "/b-" + tag + "/c-" + tag);

        // Mover B (com filha) para dentro de "Outra": profundidades continuam válidas.
        ok(put("/api/admin/categories/" + b.path("id").asLong()).bearer(admin)
                .body(categoryBody("B", "b-" + tag, other.path("id").asLong())).send());
        assertThat(pathOf(c.path("id").asLong())).isEqualTo("outra-" + tag + "/b-" + tag + "/c-" + tag);

        // Mover "Outra" (que agora tem B e C abaixo) para dentro de A deixaria C no 4º nível.
        var tooDeep = put("/api/admin/categories/" + other.path("id").asLong()).bearer(admin)
                .body(categoryBody("Outra", "outra-" + tag, a.path("id").asLong())).send();
        assertThat(tooDeep.text("code")).isEqualTo("CATEGORY_DEPTH_EXCEEDED");
    }

    private String pathOf(long categoryId) {
        return jdbc.queryForObject("SELECT slug_path FROM category WHERE id = ?", String.class, categoryId);
    }

    @Test
    void productLifecycleAndValidation() {
        long cat = category("Cat", null).path("id").asLong();
        long preto = color("Preto", "#111111");
        long p = size("P", 1);
        long m = size("M", 2);

        var body = productBody("Camiseta Básica", cat, 10000, "UNISEX", List.of());
        var created = ok(post("/api/admin/products").bearer(admin).body(body).send());
        long id = created.path("id").asLong();
        assertThat(created.path("status").asString()).isEqualTo("DRAFT");
        assertThat(created.path("slug").asString()).startsWith("camiseta-basica");

        var dupSku = post("/api/admin/products").bearer(admin).body(body).send();
        assertThat(dupSku.text("code")).isEqualTo("SKU_TAKEN");

        var noVariant = post("/api/admin/products/" + id + "/publish").bearer(admin).send();
        assertThat(noVariant.status()).isEqualTo(422);
        assertThat(noVariant.text("detail")).contains("variante");

        // Geração de grade é idempotente e o SKU segue BASE-COR-TAMANHO.
        var gen = Map.of("colorIds", List.of(preto), "sizeIds", List.of(p, m));
        ok(post("/api/admin/products/" + id + "/variants/generate").bearer(admin).body(gen).send());
        var again = ok(post("/api/admin/products/" + id + "/variants/generate").bearer(admin).body(gen).send());
        assertThat(again.path("variants").size()).isEqualTo(2);
        assertThat(again.path("variants").get(0).path("sku").asString()).isEqualTo(body.get("baseSku") + "-PRETO-" + tag.toUpperCase() + "-P-" + tag.toUpperCase());

        long variant = again.path("variants").get(0).path("id").asLong();
        var badSale = put("/api/admin/products/" + id + "/variants/" + variant).bearer(admin).body(Map.of("salePrice", 10000)).send();
        assertThat(badSale.text("code")).isEqualTo("INVALID_SALE_PRICE");

        // Rascunho com variantes não é excluído: arquiva-se.
        assertThat(delete("/api/admin/products/" + id).bearer(admin).send().text("code")).isEqualTo("PRODUCT_HAS_VARIANTS");

        publish(id);
        var published = ok(get("/api/admin/products/" + id).bearer(admin).send());
        assertThat(published.path("status").asString()).isEqualTo("ACTIVE");
        assertThat(published.path("publishedAt").isNull()).isFalse();
    }

    @Test
    void staleVersionIsRejected() {
        long cat = category("Cat", null).path("id").asLong();
        var created = ok(post("/api/admin/products").bearer(admin).body(productBody("Saia", cat, 8000, "FEMALE", List.of())).send());
        long id = created.path("id").asLong();

        var body = productBody("Saia Midi", cat, 8000, "FEMALE", List.of());
        body.put("version", created.path("version").asInt());
        ok(put("/api/admin/products/" + id).bearer(admin).body(body).send());

        var stale = put("/api/admin/products/" + id).bearer(admin).body(body).send();
        assertThat(stale.status()).isEqualTo(409);
        assertThat(stale.text("code")).isEqualTo("CONCURRENT_MODIFICATION");
    }

    @Test
    void stockMovementsAreAtomicAndLedgered() {
        long cat = category("Cat", null).path("id").asLong();
        long preto = color("Preto", "#111111");
        long p = size("P", 1);
        var product = product("Bolsa", cat, 20000, "UNISEX", List.of(), List.of(preto), List.of(p));
        long id = product.path("id").asLong();
        long variant = variantId(product, preto, p);

        assertThat(ok(stock(id, variant, 10)).path("onHand").asInt()).isEqualTo(10);
        assertThat(ok(stock(id, variant, -4)).path("available").asInt()).isEqualTo(6);

        var negative = stock(id, variant, -7);
        assertThat(negative.status()).isEqualTo(409);
        assertThat(negative.text("code")).isEqualTo("STOCK_BELOW_RESERVED");

        // Reservado (checkout, Fase 6) protege contra ajuste para baixo.
        jdbc.update("UPDATE inventory SET reserved = 5 WHERE variant_id = ?", variant);
        assertThat(stock(id, variant, -2).status()).isEqualTo(409);

        Integer ledger = jdbc.queryForObject("SELECT sum(quantity) FROM inventory_movement WHERE variant_id = ?", Integer.class, variant);
        assertThat(ledger).isEqualTo(6);
        assertThat(ok(get("/api/admin/products/" + id).bearer(admin).send()).path("hasStock").asBoolean()).isTrue();

        // Histórico imutável.
        assertThat(org.assertj.core.api.Assertions.catchThrowable(
                () -> jdbc.update("DELETE FROM inventory_movement WHERE variant_id = ?", variant))).isNotNull();

        // Variante de outro produto não pode ser movimentada por este caminho.
        assertThat(stock(id + 999_999, variant, 1).status()).isEqualTo(404);
    }

    @Test
    void onlyAdminsWriteTheCatalog() {
        String customer = register(uniqueEmail(), "senha-forte-1").text("accessToken");
        assertThat(get("/api/admin/categories").bearer(customer).send().status()).isEqualTo(403);
        assertThat(post("/api/admin/categories").bearer(customer).body(categoryBody("X", "x-" + tag, null)).send().status()).isEqualTo(403);
        assertThat(post("/api/admin/categories").body(categoryBody("X", "x-" + tag, null)).send().status()).isEqualTo(401);
        // Loja: leitura pública
        assertThat(get("/api/products").send().status()).isEqualTo(200);
        assertThat(get("/api/categories").send().status()).isEqualTo(200);
    }
}
