package com.atelier.catalog.api.dto;

import com.atelier.catalog.domain.Collection;
import com.atelier.catalog.domain.Color;
import com.atelier.catalog.domain.Size;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

import java.time.Instant;

/** DTOs de coleções, cores e tamanhos. */
public final class AttributeDtos {

    private static final String SLUG = "^[a-z0-9]+(-[a-z0-9]+)*$";

    private AttributeDtos() {}

    public record CollectionRequest(
            @NotBlank @jakarta.validation.constraints.Size(max = 100) String name,
            @Pattern(regexp = SLUG) @jakarta.validation.constraints.Size(max = 120) String slug,
            String description,
            Instant startsAt,
            Instant endsAt,
            Boolean active,
            Integer sortOrder,
            @jakarta.validation.constraints.Size(max = 70) String metaTitle,
            @jakarta.validation.constraints.Size(max = 170) String metaDescription) {
    }

    public record CollectionResponse(Long id, String name, String slug, String description, Instant startsAt,
                                     Instant endsAt, boolean active, int sortOrder, String metaTitle, String metaDescription) {

        public static CollectionResponse of(Collection c) {
            return new CollectionResponse(c.id, c.name, c.slug, c.description, c.startsAt, c.endsAt, c.active,
                    c.sortOrder, c.metaTitle, c.metaDescription);
        }
    }

    public record ColorRequest(
            @NotBlank @jakarta.validation.constraints.Size(max = 40) String name,
            @Pattern(regexp = SLUG) @jakarta.validation.constraints.Size(max = 50) String slug,
            @Pattern(regexp = "^#[0-9A-Fa-f]{6}$", message = "use o formato #RRGGBB") String hex) {
    }

    public record ColorResponse(Long id, String name, String slug, String hex) {
        public static ColorResponse of(Color c) {
            return new ColorResponse(c.id, c.name, c.slug, c.hex);
        }
    }

    public record SizeRequest(
            @NotBlank @jakarta.validation.constraints.Size(max = 20) String name,
            @Pattern(regexp = SLUG) @jakarta.validation.constraints.Size(max = 30) String slug,
            @NotBlank @jakarta.validation.constraints.Size(max = 20) String sizeGroup,
            Integer sortOrder) {
    }

    public record SizeResponse(Long id, String name, String slug, String sizeGroup, int sortOrder) {
        public static SizeResponse of(Size s) {
            return new SizeResponse(s.id, s.name, s.slug, s.sizeGroup, s.sortOrder);
        }
    }
}
