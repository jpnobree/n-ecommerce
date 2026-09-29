package com.atelier.catalog.api.dto;

import com.atelier.catalog.api.dto.CategoryDtos.Crumb;
import com.atelier.catalog.api.dto.ListingDtos.ProductCard;
import com.atelier.catalog.domain.Gender;

import java.util.List;

/** Página de produto na loja. Valores em centavos (BRL). */
public final class ProductPageDtos {

    private ProductPageDtos() {}

    public record ColorOption(Long id, String slug, String name, String hex) {
    }

    public record SizeOption(Long id, String slug, String name) {
    }

    /** price = preço cheio da variante; effectivePrice = o que será cobrado agora. */
    public record VariantOption(Long id, String sku, Long colorId, Long sizeId, long price, long effectivePrice) {
    }

    public record Image(String url, String alt, Long colorId, Integer width, Integer height) {
    }

    public record SizeChart(String name, List<String> columns, List<List<String>> rows) {
    }

    public record Seo(String title, String description) {
    }

    /**
     * slug = slug atual (canônico). Se a loja pediu por um slug antigo, compara e redireciona (301).
     * Disponibilidade por variante vem de /availability (não cacheado).
     */
    public record ProductPage(Long id, String slug, String sku, String name, String description, String material,
                              String careInstructions, Gender gender, List<Crumb> breadcrumb, long price,
                              Long salePrice, boolean priceVaries, boolean inStock, List<String> badges,
                              List<ColorOption> colors, List<SizeOption> sizes, List<VariantOption> variants,
                              List<Image> images, SizeChart sizeChart, Seo seo) {
    }

    public enum Availability { IN_STOCK, LOW, OUT }

    public record VariantAvailability(Long variantId, Availability status) {
    }

    public record Related(List<ProductCard> completeTheLook, List<ProductCard> similar) {
    }
}
