package com.atelier.catalog.api.dto;

import com.atelier.catalog.domain.Gender;

import java.util.List;

/** Listagem pública do catálogo. Valores em centavos (BRL). */
public final class ListingDtos {

    private ListingDtos() {}

    public enum Sort { newest, best_sellers, price_asc, price_desc }

    public record ListingFilter(String category, List<String> collections, List<String> sizes, List<String> colors,
                                Long minPrice, Long maxPrice, boolean inStock, boolean onSale, List<Gender> genders,
                                Sort sort, int page, int pageSize) {
    }

    public record ColorChip(String slug, String name, String hex) {
    }

    /** price = menor preço cheio; salePrice = menor preço efetivo quando há promoção (senão null). */
    public record ProductCard(Long id, String slug, String name, long price, Long salePrice, String currency,
                              String imageUrl, List<ColorChip> colors, boolean inStock, List<String> badges) {
    }

    public record FacetValue(String value, String label, String hex, long count) {
    }

    public record PriceRange(Long min, Long max) {
    }

    public record Facets(List<FacetValue> sizes, List<FacetValue> colors, List<FacetValue> genders,
                         List<FacetValue> collections, PriceRange price) {
    }

    public record ListingResponse(List<ProductCard> content, int page, int pageSize, long totalElements,
                                  int totalPages, Facets facets) {
    }
}
