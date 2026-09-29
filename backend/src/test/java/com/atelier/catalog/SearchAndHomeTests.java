package com.atelier.catalog;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Busca textual, sugestões, home, newsletter e sitemap. */
class SearchAndHomeTests extends CatalogFixtures {

    private JsonNode published(String name, long cat) {
        long preto = color("Preto" + name.length(), "#111111");
        long p = size("P" + name.length(), 1);
        var prod = product(name, cat, 7000, "UNISEX", List.of(), List.of(preto), List.of(p));
        stock(prod.path("id").asLong(), variantId(prod, preto, p), 5);
        publish(prod.path("id").asLong());
        return prod;
    }

    private JsonNode search(String q) {
        return ok(get("/api/search/products?q=" + URLEncoder.encode(q, StandardCharsets.UTF_8)).send());
    }

    @Test
    void searchHandlesPluralsAccentsAndSku() {
        long cat = category("Cat", null).path("id").asLong();
        var camiseta = published("Camiseta Listrada " + tag, cat);
        published("Calça Pantalona " + tag, cat);

        assertThat(names(search("camisetas " + tag).path("result"))).containsExactly("Camiseta Listrada " + tag);
        assertThat(names(search("calca pantalona " + tag).path("result"))).containsExactly("Calça Pantalona " + tag);
        // Busca vale com filtros e traz facetas.
        var result = search("listrada " + tag).path("result");
        assertThat(result.path("facets").path("colors").size()).isEqualTo(1);

        var bySku = search(camiseta.path("baseSku").asString().toLowerCase());
        assertThat(bySku.path("exactMatch").asString()).isEqualTo(camiseta.path("slug").asString());
    }

    @Test
    void typoIsCorrectedAndEmptySearchSuggestsBestSellers() {
        long cat = category("Cat", null).path("id").asLong();
        published("Quimono Estampado", cat);

        var typo = search("quimonu");
        assertThat(typo.path("correctedQuery").asString()).isEqualTo("quimono");
        assertThat(names(typo.path("result"))).contains("Quimono Estampado");

        var nothing = search("xyzqwk wvutsr");
        assertThat(nothing.path("result").path("totalElements").asLong()).isZero();
        assertThat(nothing.path("suggestions").size()).isPositive();

        assertThat(get("/api/search/products?q=a").send().status()).isEqualTo(400);
    }

    @Test
    void suggestMatchesByPrefix() {
        var root = category("Macacoes", null);
        published("Macaquinho Floral " + tag, root.path("id").asLong());
        var s = ok(get("/api/search/suggest?q=macaqui").send());
        assertThat(s.path("products").toString()).contains("Macaquinho Floral " + tag);
        assertThat(ok(get("/api/search/suggest?q=macac").send()).path("categories").toString()).contains("Macacoes");
    }

    @Test
    void homeShowsOnlyVisibleBannersFromOurStorage() {
        var upload = ok(multipart("/api/admin/uploads", admin, jpeg(1600, 900), "hero.jpg", Map.of()));
        String url = upload.path("url").asString();

        var visible = new HashMap<String, Object>();
        visible.put("position", "HERO");
        visible.put("title", "Hero " + tag);
        visible.put("linkUrl", "/colecao/verao");
        visible.put("imageDesktopUrl", url);
        ok(post("/api/admin/banners").bearer(admin).body(visible).send());

        var future = new HashMap<>(visible);
        future.put("title", "Futuro " + tag);
        future.put("startsAt", Instant.now().plusSeconds(3600).toString());
        ok(post("/api/admin/banners").bearer(admin).body(future).send());

        var external = new HashMap<>(visible);
        external.put("imageDesktopUrl", "https://site-qualquer.com/x.jpg");
        assertThat(post("/api/admin/banners").bearer(admin).body(external).send().text("code")).isEqualTo("INVALID_URL");

        var badLink = new HashMap<>(visible);
        badLink.put("linkUrl", "javascript:alert(1)");
        assertThat(post("/api/admin/banners").bearer(admin).body(badLink).send().status()).isEqualTo(400);

        var home = ok(get("/api/home").send());
        assertThat(home.path("hero").toString()).contains("Hero " + tag).doesNotContain("Futuro " + tag);
        assertThat(home.has("bestSellers")).isTrue();
    }

    @Test
    void newsletterIsIdempotentAndRequiresConsent() {
        String email = uniqueEmail();
        assertThat(post("/api/newsletter").body(Map.of("email", email, "consent", true)).send().status()).isEqualTo(202);
        assertThat(post("/api/newsletter").body(Map.of("email", email.toUpperCase(), "consent", true)).send().status()).isEqualTo(202);
        assertThat(post("/api/newsletter").body(Map.of("email", email, "consent", false)).send().status()).isEqualTo(400);
    }

    @Test
    void sitemapListsPublishedProductsAndCategories() {
        var root = category("Mapa", null);
        var prod = published("Produto Mapa " + tag, root.path("id").asLong());
        var sitemap = ok(get("/api/seo/sitemap").send()).toString();
        assertThat(sitemap).contains("/p/" + prod.path("slug").asString(), "/c/" + root.path("path").asString());
    }

}
