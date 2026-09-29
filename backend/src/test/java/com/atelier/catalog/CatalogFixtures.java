package com.atelier.catalog;

import com.atelier.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import tools.jackson.databind.JsonNode;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Cria dados de catálogo pelo próprio admin da API. Slugs com sufixo único isolam cada teste. */
abstract class CatalogFixtures extends IntegrationTest {

    protected String admin;
    protected String tag;

    @BeforeEach
    void loginAdmin() {
        admin = login(ADMIN_EMAIL, ADMIN_PASSWORD).text("accessToken");
        tag = UUID.randomUUID().toString().substring(0, 6);
    }

    protected JsonNode ok(Res res) {
        assertThat(res.status()).as(res.body()).isBetween(200, 299);
        return res.json();
    }

    protected JsonNode category(String name, Long parentId) {
        var body = new HashMap<String, Object>();
        body.put("name", name);
        body.put("slug", (name + "-" + tag).toLowerCase());
        body.put("parentId", parentId);
        return ok(post("/api/admin/categories").bearer(admin).body(body).send());
    }

    protected long color(String name, String hex) {
        return ok(post("/api/admin/colors").bearer(admin)
                .body(Map.of("name", name + " " + tag, "slug", name.toLowerCase() + "-" + tag, "hex", hex)).send())
                .path("id").asLong();
    }

    protected long size(String name, int order) {
        return ok(post("/api/admin/sizes").bearer(admin)
                .body(Map.of("name", name, "slug", name.toLowerCase() + "-" + tag, "sizeGroup", "T" + tag, "sortOrder", order)).send())
                .path("id").asLong();
    }

    protected long collection(String name) {
        return ok(post("/api/admin/collections").bearer(admin)
                .body(Map.of("name", name, "slug", name.toLowerCase() + "-" + tag)).send()).path("id").asLong();
    }

    protected Map<String, Object> productBody(String name, long categoryId, long basePrice, String gender, List<Long> collections) {
        var body = new HashMap<String, Object>();
        body.put("name", name);
        body.put("baseSku", (name.replaceAll("[^A-Za-z0-9]", "") + tag).toUpperCase());
        body.put("gender", gender);
        body.put("mainCategoryId", categoryId);
        body.put("basePrice", basePrice);
        body.put("weightGrams", 300);
        body.put("collectionIds", collections);
        return body;
    }

    protected JsonNode product(String name, long categoryId, long basePrice, String gender, List<Long> collections,
                               List<Long> colorIds, List<Long> sizeIds) {
        long id = ok(post("/api/admin/products").bearer(admin)
                .body(productBody(name, categoryId, basePrice, gender, collections)).send()).path("id").asLong();
        return ok(post("/api/admin/products/" + id + "/variants/generate").bearer(admin)
                .body(Map.of("colorIds", colorIds, "sizeIds", sizeIds)).send());
    }

    protected long variantId(JsonNode product, long colorId, long sizeId) {
        for (JsonNode v : product.path("variants")) {
            if (v.path("colorId").asLong() == colorId && v.path("sizeId").asLong() == sizeId) return v.path("id").asLong();
        }
        throw new AssertionError("variante não encontrada");
    }

    protected Res stock(long productId, long variantId, int qty) {
        return post("/api/admin/products/" + productId + "/variants/" + variantId + "/stock-movements").bearer(admin)
                .body(Map.of("type", qty > 0 ? "PURCHASE" : "ADJUSTMENT", "quantity", qty, "reason", "teste")).send();
    }

    /** Publicar exige imagem (RN-56): envia uma antes. */
    protected void publish(long productId) {
        uploadImage(productId, null);
        ok(post("/api/admin/products/" + productId + "/publish").bearer(admin).send());
    }

    protected JsonNode uploadImage(long productId, Long colorId) {
        var fields = new HashMap<String, String>();
        fields.put("alt", "Foto do produto");
        if (colorId != null) fields.put("colorId", colorId.toString());
        return ok(multipart("/api/admin/products/" + productId + "/images", admin, jpeg(1200, 1600), "foto.jpg", fields));
    }

    protected JsonNode list(String query) {
        return ok(get("/api/products?" + query).send());
    }

    protected static List<String> names(JsonNode listing) {
        var names = new java.util.ArrayList<String>();
        listing.path("content").forEach(p -> names.add(p.path("name").asString()));
        return names;
    }

    protected static long facetCount(JsonNode listing, String facet, String value) {
        for (JsonNode f : listing.path("facets").path(facet)) {
            if (f.path("value").asString().equals(value)) return f.path("count").asLong();
        }
        return 0;
    }
}
