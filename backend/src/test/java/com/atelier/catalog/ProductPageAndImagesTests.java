package com.atelier.catalog;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.CRC32;

import static org.assertj.core.api.Assertions.assertThat;

/** Imagens (upload seguro, principal, remoção) e página de produto da loja. */
class ProductPageAndImagesTests extends CatalogFixtures {

    private long draft(String name) {
        long cat = category("Rascunhos", null).path("id").asLong();
        return ok(post("/api/admin/products").bearer(admin).body(productBody(name, cat, 10000, "FEMALE", List.of())).send())
                .path("id").asLong();
    }

    @Test
    void uploadAcceptsOnlyRealImagesOfMinimumSize() {
        long id = draft("Blusa");
        var fakeJpeg = multipart("/api/admin/products/" + id + "/images", admin, "<script>alert(1)</script>".getBytes(),
                "foto.jpg", Map.of("alt", "x"));
        assertThat(fakeJpeg.status()).isEqualTo(422);
        assertThat(fakeJpeg.text("code")).isEqualTo("INVALID_IMAGE");

        var tooSmall = multipart("/api/admin/products/" + id + "/images", admin, jpeg(600, 800), "foto.jpg", Map.of("alt", "x"));
        assertThat(tooSmall.text("detail")).contains("1200");

        // Cabeçalho PNG declarando 30000×30000: recusado antes de decodificar (não aloca memória).
        var bomb = multipart("/api/admin/products/" + id + "/images", admin, pngHeader(30_000, 30_000), "bomba.png", Map.of("alt", "x"));
        assertThat(bomb.status()).isEqualTo(422);
        assertThat(bomb.text("detail")).contains("8000");

        var customer = register(uniqueEmail(), "senha-forte-1").text("accessToken");
        assertThat(multipart("/api/admin/products/" + id + "/images", customer, jpeg(1200, 1600), "f.jpg", Map.of("alt", "x")).status())
                .isEqualTo(403);
    }

    @Test
    void firstImageIsMainAndDeletingItPromotesTheNext() {
        long id = draft("Vestido");
        var first = uploadImage(id, null);
        var second = uploadImage(id, null);
        assertThat(first.path("main").asBoolean()).isTrue();
        assertThat(second.path("main").asBoolean()).isFalse();
        assertThat(first.path("url").asString()).startsWith("http://localhost:9000/atelier-media/products/" + id + "/");
        assertThat(first.path("width").asInt()).isEqualTo(1200);

        long secondId = second.path("id").asLong();
        ok(put("/api/admin/products/" + id + "/images/" + secondId).bearer(admin).body(Map.of("alt", "Costas", "main", true)).send());
        var images = ok(get("/api/admin/products/" + id).bearer(admin).send()).path("images");
        assertThat(images.get(0).path("id").asLong()).isEqualTo(secondId);
        assertThat(images.get(1).path("main").asBoolean()).isFalse();

        String key = second.path("url").asString().replace("http://localhost:9000/atelier-media/", "");
        assertThat(delete("/api/admin/products/" + id + "/images/" + secondId).bearer(admin).send().status()).isEqualTo(204);
        var after = ok(get("/api/admin/products/" + id).bearer(admin).send()).path("images");
        assertThat(after.size()).isEqualTo(1);
        assertThat(after.get(0).path("main").asBoolean()).isTrue();
        assertThat(storage.deleted).contains(key);
    }

    @Test
    void publishingRequiresAnImage() {
        long id = draft("Saia");
        long preto = color("Preto", "#111111");
        long p = size("P", 1);
        ok(post("/api/admin/products/" + id + "/variants/generate").bearer(admin)
                .body(Map.of("colorIds", List.of(preto), "sizeIds", List.of(p))).send());
        var res = post("/api/admin/products/" + id + "/publish").bearer(admin).send();
        assertThat(res.status()).isEqualTo(422);
        assertThat(res.text("detail")).contains("imagem");
    }

    @Test
    void productPageShowsVariantsPricesGalleryAndAvailabilityWithoutExactStock() {
        var root = category("Moda", null);
        long cat = category("Vestidos", root.path("id").asLong()).path("id").asLong();
        long preto = color("Preto", "#111111");
        long branco = color("Branco", "#FFFFFF");
        long p = size("P", 1);
        long m = size("M", 2);
        var product = product("Vestido Midi", cat, 20000, "FEMALE", List.of(), List.of(preto, branco), List.of(p, m));
        long id = product.path("id").asLong();
        stock(id, variantId(product, preto, p), 10);
        stock(id, variantId(product, preto, m), 2);
        long promo = variantId(product, branco, m);
        ok(put("/api/admin/products/" + id + "/variants/" + promo).bearer(admin).body(Map.of("salePrice", 15000)).send());
        uploadImage(id, branco);
        publish(id);
        String slug = product.path("slug").asString();

        JsonNode page = ok(get("/api/products/" + slug).send());
        assertThat(page.path("name").asString()).isEqualTo("Vestido Midi");
        assertThat(page.path("price").asLong()).isEqualTo(20000);
        assertThat(page.path("salePrice").asLong()).isEqualTo(15000);
        assertThat(page.path("priceVaries").asBoolean()).isTrue();
        assertThat(page.path("variants").size()).isEqualTo(4);
        assertThat(page.path("colors").size()).isEqualTo(2);
        assertThat(page.path("sizes").get(0).path("name").asString()).isEqualTo("P");
        assertThat(page.path("images").size()).isEqualTo(2);
        assertThat(page.path("breadcrumb").size()).isEqualTo(2);
        assertThat(page.path("seo").path("title").asString()).isEqualTo("Vestido Midi");

        var availability = ok(get("/api/products/" + slug + "/availability").send());
        Map<Long, String> status = new HashMap<>();
        availability.forEach(a -> status.put(a.path("variantId").asLong(), a.path("status").asString()));
        assertThat(status.get(variantId(product, preto, p))).isEqualTo("IN_STOCK");
        assertThat(status.get(variantId(product, preto, m))).isEqualTo("LOW");
        assertThat(status.get(variantId(product, branco, p))).isEqualTo("OUT");
        assertThat(availability.toString()).doesNotContain("onHand", "available\":");
        assertThat(get("/api/products/" + slug + "/availability").send().raw().headers().firstValue("Cache-Control"))
                .hasValueSatisfying(v -> assertThat(v).contains("no-store"));
    }

    @Test
    void draftsAreNotFoundAndOldSlugsResolveToTheCurrentOne() {
        long cat = category("Cat", null).path("id").asLong();
        long draftId = draft("Rascunho");
        String draftSlug = ok(get("/api/admin/products/" + draftId).bearer(admin).send()).path("slug").asString();
        assertThat(get("/api/products/" + draftSlug).send().status()).isEqualTo(404);

        long preto = color("Preto", "#111111");
        long p = size("P", 1);
        var product = product("Camisa Linho", cat, 9000, "MALE", List.of(), List.of(preto), List.of(p));
        long id = product.path("id").asLong();
        publish(id);
        String oldSlug = product.path("slug").asString();

        var body = productBody("Camisa Linho", cat, 9000, "MALE", List.of());
        body.put("slug", "camisa-linho-nova-" + tag);
        body.put("version", ok(get("/api/admin/products/" + id).bearer(admin).send()).path("version").asInt());
        ok(put("/api/admin/products/" + id).bearer(admin).body(body).send());

        var viaOld = ok(get("/api/products/" + oldSlug).send());
        assertThat(viaOld.path("slug").asString()).isEqualTo("camisa-linho-nova-" + tag);
    }

    @Test
    void relatedCombinesCuratedAndSimilar() {
        long cat = category("Cat", null).path("id").asLong();
        long preto = color("Preto", "#111111");
        long p = size("P", 1);
        long[] ids = new long[4];
        String[] names = {"Principal", "Look Um", "Look Dois", "Parecido"};
        for (int i = 0; i < 4; i++) {
            var prod = product(names[i], cat, 5000, "UNISEX", List.of(), List.of(preto), List.of(p));
            ids[i] = prod.path("id").asLong();
            stock(ids[i], variantId(prod, preto, p), 5);
            publish(ids[i]);
        }
        ok(put("/api/admin/products/" + ids[0] + "/related").bearer(admin).body(Map.of("productIds", List.of(ids[2], ids[1]))).send());
        String slug = ok(get("/api/admin/products/" + ids[0]).bearer(admin).send()).path("slug").asString();

        var related = ok(get("/api/products/" + slug + "/related").send());
        assertThat(cardNames(related.path("completeTheLook"))).containsExactly("Look Dois", "Look Um");
        assertThat(cardNames(related.path("similar"))).containsExactly("Parecido");
    }

    private static List<String> cardNames(JsonNode cards) {
        var list = new java.util.ArrayList<String>();
        cards.forEach(c -> list.add(c.path("name").asString()));
        return list;
    }

    /** Assinatura PNG + chunk IHDR válido com as dimensões dadas (sem dados de imagem). */
    private static byte[] pngHeader(int width, int height) {
        ByteBuffer ihdr = ByteBuffer.allocate(17);
        ihdr.put("IHDR".getBytes()).putInt(width).putInt(height).put((byte) 8).put((byte) 2).put((byte) 0).put((byte) 0).put((byte) 0);
        CRC32 crc = new CRC32();
        crc.update(ihdr.array());
        ByteBuffer png = ByteBuffer.allocate(8 + 4 + 17 + 4);
        png.put(new byte[]{(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'}).putInt(13).put(ihdr.array()).putInt((int) crc.getValue());
        return png.array();
    }
}
