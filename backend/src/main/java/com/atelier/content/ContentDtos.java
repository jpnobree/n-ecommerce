package com.atelier.content;

import com.atelier.catalog.api.dto.AttributeDtos.CollectionResponse;
import com.atelier.catalog.api.dto.CategoryDtos.CategoryNode;
import com.atelier.catalog.api.dto.ListingDtos.ProductCard;
import jakarta.validation.constraints.*;

import java.time.Instant;
import java.util.List;

public final class ContentDtos {

    private ContentDtos() {}

    public record BannerRequest(
            @NotNull Banner.Position position,
            @NotBlank @Size(max = 120) String title,
            @Size(max = 200) String subtitle,
            @Size(max = 40) String ctaLabel,
            /* Só caminhos internos da loja ("/c/feminino") */
            @Pattern(regexp = "^/[A-Za-z0-9/_?=&.,%-]*$", message = "use um caminho interno, ex.: /c/feminino") @Size(max = 300) String linkUrl,
            @Size(max = 500) String imageDesktopUrl,
            @Size(max = 500) String imageMobileUrl,
            Instant startsAt,
            Instant endsAt,
            Boolean active,
            Integer sortOrder) {
    }

    public record BannerResponse(Long id, Banner.Position position, String title, String subtitle, String ctaLabel,
                                 String linkUrl, String imageDesktopUrl, String imageMobileUrl, Instant startsAt,
                                 Instant endsAt, boolean active, int sortOrder) {

        public static BannerResponse of(Banner b) {
            return new BannerResponse(b.id, b.position, b.title, b.subtitle, b.ctaLabel, b.linkUrl, b.imageDesktopUrl,
                    b.imageMobileUrl, b.startsAt, b.endsAt, b.active, b.sortOrder);
        }
    }

    public record UploadResponse(String url, int width, int height) {
    }

    public record Home(List<BannerResponse> hero, List<BannerResponse> campaigns, BannerResponse strip,
                       List<CategoryNode> categories, List<CollectionResponse> collections,
                       List<ProductCard> featured, List<ProductCard> newArrivals, List<ProductCard> bestSellers) {
    }

    public record NewsletterRequest(@NotBlank @Email @Size(max = 254) String email,
                                    @NotNull @AssertTrue(message = "é preciso autorizar o envio") Boolean consent) {
    }

    public record SitemapEntry(String path, Instant lastModified) {
    }
}
