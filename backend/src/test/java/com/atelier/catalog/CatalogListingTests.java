package com.atelier.catalog;

import com.atelier.catalog.service.ProductDenormalizer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Filtros, ordenação e facetas da listagem pública (PRD, seção 4.2). */
class CatalogListingTests extends CatalogFixtures {

    @Autowired
    ProductDenormalizer denormalizer;

    long preto, branco, p, m, colecao;
    String root;
    JsonNode camiseta, promo;

    /**
     * Árvore: raiz → filha. Produtos:
     * - Camiseta (filha, 10000): Preto/P e Branco/M com estoque; Preto/M e Branco/P esgotados; coleção.
     * - Promo (raiz, 12000 → 7000): Preto/M com estoque.
     * - Esgotado (filha, 5000): nada em estoque.
     * - Rascunho (filha): nunca aparece.
     */
    @BeforeEach
    void catalog() {
        var rootCat = category("Moda", null);
        root = rootCat.path("path").asString();
        long child = category("Vestidos", rootCat.path("id").asLong()).path("id").asLong();
        preto = color("Preto", "#111111");
        branco = color("Branco", "#FFFFFF");
        p = size("P", 1);
        m = size("M", 2);
        colecao = collection("Verao");

        camiseta = product("Camiseta", child, 10000, "FEMALE", List.of(colecao), List.of(preto, branco), List.of(p, m));
        long camisetaId = camiseta.path("id").asLong();
        stock(camisetaId, variantId(camiseta, preto, p), 5);
        stock(camisetaId, variantId(camiseta, branco, m), 5);
        publish(camisetaId);

        promo = product("Promo", rootCat.path("id").asLong(), 12000, "MALE", List.of(), List.of(preto), List.of(m));
        long promoId = promo.path("id").asLong();
        long promoVariant = variantId(promo, preto, m);
        ok(put("/api/admin/products/" + promoId + "/variants/" + promoVariant).bearer(admin).body(Map.of("salePrice", 7000)).send());
        stock(promoId, promoVariant, 2);
        publish(promoId);

        var esgotado = product("Esgotado", child, 5000, "FEMALE", List.of(), List.of(branco), List.of(p));
        publish(esgotado.path("id").asLong());

        product("Rascunho", child, 3000, "FEMALE", List.of(), List.of(preto), List.of(p));
    }

    @Test
    void categoryIncludesSubtreeAndHidesDrafts() {
        var all = list("category=" + root);
        assertThat(names(all)).containsExactlyInAnyOrder("Camiseta", "Promo", "Esgotado");
        assertThat(all.path("totalElements").asLong()).isEqualTo(3);
    }

    @Test
    void sizeAndColorMustMatchTheSameVariantWithStock() {
        assertThat(names(list("category=" + root + "&colors=preto-" + tag + "&sizes=p-" + tag))).containsExactly("Camiseta");
        // Preto/M da camiseta existe mas está esgotado: só a Promo tem Preto/M disponível.
        assertThat(names(list("category=" + root + "&colors=preto-" + tag + "&sizes=m-" + tag))).containsExactly("Promo");
        // Branco/P só existe esgotado (camiseta e "Esgotado").
        assertThat(names(list("category=" + root + "&colors=branco-" + tag + "&sizes=p-" + tag))).isEmpty();
    }

    @Test
    void facetsCountWithOtherFiltersButNotTheirOwn() {
        var byPreto = list("category=" + root + "&colors=preto-" + tag);
        // Tamanhos disponíveis em preto: P (camiseta) e M (promo).
        assertThat(facetCount(byPreto, "sizes", "p-" + tag)).isEqualTo(1);
        assertThat(facetCount(byPreto, "sizes", "m-" + tag)).isEqualTo(1);
        // A faceta de cor ignora o próprio filtro: branco continua listado.
        assertThat(facetCount(byPreto, "colors", "branco-" + tag)).isEqualTo(2);
        assertThat(facetCount(byPreto, "genders", "FEMALE")).isEqualTo(1);
        assertThat(facetCount(byPreto, "collections", "verao-" + tag)).isEqualTo(1);

        var bySizeM = list("category=" + root + "&sizes=m-" + tag);
        assertThat(facetCount(bySizeM, "colors", "branco-" + tag)).isEqualTo(1);
        assertThat(facetCount(bySizeM, "colors", "preto-" + tag)).isEqualTo(1);
    }

    @Test
    void salePriceDrivesPriceFiltersSortingAndBadges() {
        var sale = list("category=" + root + "&onSale=true");
        assertThat(names(sale)).containsExactly("Promo");
        JsonNode card = sale.path("content").get(0);
        assertThat(card.path("price").asLong()).isEqualTo(12000);
        assertThat(card.path("salePrice").asLong()).isEqualTo(7000);
        assertThat(card.path("badges").toString()).contains("SALE");

        // Esgotados vão para o fim mesmo sendo os mais baratos.
        assertThat(names(list("category=" + root + "&sort=price_asc"))).containsExactly("Promo", "Camiseta", "Esgotado");
        assertThat(names(list("category=" + root + "&sort=price_desc"))).containsExactly("Camiseta", "Promo", "Esgotado");
        assertThat(names(list("category=" + root + "&minPrice=8000"))).containsExactly("Camiseta");

        var range = list("category=" + root).path("facets").path("price");
        assertThat(range.path("min").asLong()).isEqualTo(5000);
        assertThat(range.path("max").asLong()).isEqualTo(10000);
    }

    @Test
    void inStockCollectionAndGenderFilters() {
        assertThat(names(list("category=" + root + "&inStock=true"))).containsExactlyInAnyOrder("Camiseta", "Promo");
        assertThat(names(list("category=" + root + "&collection=verao-" + tag))).containsExactly("Camiseta");
        assertThat(names(list("category=" + root + "&gender=MALE"))).containsExactly("Promo");
        assertThat(names(list("category=" + root + "&gender=FEMALE,MALE&inStock=true"))).hasSize(2);
    }

    @Test
    void cardsCarryColorsAndPagination() {
        var page1 = list("category=" + root + "&pageSize=2&page=0");
        var page2 = list("category=" + root + "&pageSize=2&page=1");
        assertThat(page1.path("content").size()).isEqualTo(2);
        assertThat(page2.path("content").size()).isEqualTo(1);
        assertThat(page1.path("totalPages").asInt()).isEqualTo(2);

        var camisetaCard = list("category=" + root + "&collection=verao-" + tag).path("content").get(0);
        assertThat(camisetaCard.path("colors").size()).isEqualTo(2);
        assertThat(camisetaCard.path("inStock").asBoolean()).isTrue();
    }

    @Test
    void invalidFiltersAreRejected() {
        assertThat(get("/api/products?pageSize=500").send().status()).isEqualTo(400);
        assertThat(get("/api/products?sort=hack").send().status()).isEqualTo(400);
        assertThat(get("/api/products?sizes=p';drop").send().status()).isEqualTo(400);
        assertThat(get("/api/products?category=../x").send().status()).isEqualTo(400);
        assertThat(get("/api/products?minPrice=9&maxPrice=1").send().status()).isEqualTo(400);
    }

    @Test
    void scheduledSaleStartsWithoutAnyWrite() throws Exception {
        long promoId = promo.path("id").asLong();
        long variant = variantId(promo, preto, m);
        var body = new HashMap<String, Object>();
        body.put("salePrice", 6000);
        body.put("saleStartsAt", Instant.now().plusMillis(1500).toString());
        ok(put("/api/admin/products/" + promoId + "/variants/" + variant).bearer(admin).body(body).send());
        assertThat(names(list("category=" + root + "&onSale=true"))).isEmpty();

        Thread.sleep(1700);
        denormalizer.refreshSaleBoundaries();
        var card = list("category=" + root + "&onSale=true").path("content").get(0);
        assertThat(card.path("salePrice").asLong()).isEqualTo(6000);
    }

    @Test
    void publicCategoryTreeAndPage() {
        var tree = ok(get("/api/categories").send());
        assertThat(tree.toString()).contains(root + "/vestidos-" + tag);
        var page = ok(get("/api/categories/page?path=" + root + "/vestidos-" + tag).send());
        assertThat(page.path("breadcrumb").size()).isEqualTo(2);
        assertThat(page.path("breadcrumb").get(0).path("path").asString()).isEqualTo(root);
        assertThat(get("/api/categories/page?path=nao-existe-" + tag).send().status()).isEqualTo(404);
    }
}
