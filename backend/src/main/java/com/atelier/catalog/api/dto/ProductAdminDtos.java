package com.atelier.catalog.api.dto;

import com.atelier.catalog.domain.Gender;
import com.atelier.catalog.domain.Product;
import com.atelier.catalog.domain.ProductStatus;
import com.atelier.catalog.domain.ProductImage;
import com.atelier.catalog.domain.ProductVariant;
import com.atelier.catalog.service.InventoryService;
import jakarta.validation.constraints.*;

import java.time.Instant;
import java.util.List;
import java.util.Set;

/** DTOs do cadastro de produtos no admin. Valores monetários em centavos. */
public final class ProductAdminDtos {

    private ProductAdminDtos() {}

    public record ProductRequest(
            @NotBlank @jakarta.validation.constraints.Size(max = 150) String name,
            @Pattern(regexp = "^[a-z0-9]+(-[a-z0-9]+)*$") @jakarta.validation.constraints.Size(max = 170) String slug,
            @NotBlank @Pattern(regexp = "^[A-Z0-9]+(-[A-Z0-9]+)*$", message = "use letras maiúsculas, números e hífen")
            @jakarta.validation.constraints.Size(max = 40) String baseSku,
            String description,
            @jakarta.validation.constraints.Size(max = 200) String material,
            String careInstructions,
            @NotNull Gender gender,
            @NotNull Long mainCategoryId,
            @NotNull @Positive Long basePrice,
            @NotNull @Positive Integer weightGrams,
            Boolean featured,
            Boolean isNew,
            Set<Long> collectionIds,
            @jakarta.validation.constraints.Size(max = 70) String metaTitle,
            @jakarta.validation.constraints.Size(max = 170) String metaDescription,
            @jakarta.validation.constraints.Size(max = 20) List<@NotBlank @jakarta.validation.constraints.Size(max = 40) String> tags,
            Long sizeChartId,
            /* Obrigatório na edição: versão lida pelo cliente (lock otimista). */
            Integer version) {
    }

    public record GenerateVariantsRequest(@NotEmpty Set<Long> colorIds, @NotEmpty Set<Long> sizeIds) {
    }

    public record VariantRequest(
            @Positive Long price,
            @Positive Long salePrice,
            Instant saleStartsAt,
            Instant saleEndsAt,
            @jakarta.validation.constraints.Size(max = 60) String supplierRef,
            Boolean active) {
    }

    public record StockMovementRequest(@NotNull InventoryService.MovementType type, @NotNull Integer quantity,
                                       @NotBlank @jakarta.validation.constraints.Size(max = 200) String reason) {
    }

    public record StockLevel(int onHand, int reserved, int available) {
    }

    public record VariantResponse(Long id, Long colorId, Long sizeId, String sku, String supplierRef, Long price,
                                  Long salePrice, Instant saleStartsAt, Instant saleEndsAt, boolean active,
                                  long effectivePrice, StockLevel stock) {

        public static VariantResponse of(ProductVariant v, long basePrice, StockLevel stock, Instant now) {
            return new VariantResponse(v.id, v.colorId, v.sizeId, v.sku, v.supplierRef, v.price, v.salePrice,
                    v.saleStartsAt, v.saleEndsAt, v.active, v.effectivePrice(basePrice, now), stock);
        }
    }

    public record ProductSummary(Long id, String name, String slug, String baseSku, ProductStatus status,
                                 Long minEffectivePrice, boolean hasStock, Instant publishedAt) {

        public static ProductSummary of(Product p) {
            return new ProductSummary(p.id, p.name, p.slug, p.baseSku, p.status, p.minEffectivePrice, p.hasStock, p.publishedAt);
        }
    }

    public record ProductDetail(Long id, String name, String slug, String baseSku, String description, String material,
                                String careInstructions, Gender gender, Long mainCategoryId, long basePrice,
                                int weightGrams, boolean featured, boolean isNew, Set<Long> collectionIds,
                                String metaTitle, String metaDescription, ProductStatus status, Instant publishedAt,
                                Long minBasePrice, Long minEffectivePrice, boolean hasStock, int version,
                                List<String> tags, Long sizeChartId, List<VariantResponse> variants,
                                List<ImageResponse> images, List<Long> relatedIds) {

        public static ProductDetail of(Product p, List<VariantResponse> variants, List<ImageResponse> images, List<Long> relatedIds) {
            return new ProductDetail(p.id, p.name, p.slug, p.baseSku, p.description, p.material, p.careInstructions,
                    p.gender, p.mainCategoryId, p.basePrice, p.weightGrams, p.featured, p.isNew, p.collectionIds,
                    p.metaTitle, p.metaDescription, p.status, p.publishedAt, p.minBasePrice, p.minEffectivePrice,
                    p.hasStock, p.version, List.of(p.tags), p.sizeChartId, variants, images, relatedIds);
        }
    }

    public record ImageResponse(Long id, String url, String alt, Long colorId, Integer width, Integer height,
                                int position, boolean main) {

        public static ImageResponse of(ProductImage i) {
            return new ImageResponse(i.id, i.url, i.altText, i.colorId, i.width, i.height, i.position, i.isMain);
        }
    }

    public record ImageUpdateRequest(@NotBlank @jakarta.validation.constraints.Size(max = 200) String alt, Long colorId, Boolean main) {
    }

    public record ImageOrderRequest(@NotEmpty List<Long> imageIds) {
    }

    public record RelatedRequest(@NotNull @jakarta.validation.constraints.Size(max = 12) List<Long> productIds) {
    }

    public record SizeChartRequest(
            @NotBlank @jakarta.validation.constraints.Size(max = 80) String name,
            @NotEmpty @jakarta.validation.constraints.Size(max = 10) List<@NotBlank String> columns,
            @NotEmpty @jakarta.validation.constraints.Size(max = 20) List<@NotEmpty List<String>> rows) {
    }

    public record SizeChartResponse(Long id, String name, List<String> columns, List<List<String>> rows) {
    }
}
