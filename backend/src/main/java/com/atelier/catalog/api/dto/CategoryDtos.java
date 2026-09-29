package com.atelier.catalog.api.dto;

import com.atelier.catalog.domain.Category;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

/** DTOs de categoria (loja e admin). */
public final class CategoryDtos {

    private CategoryDtos() {}

    public record CategoryRequest(
            @NotBlank @Size(max = 80) String name,
            @Pattern(regexp = "^[a-z0-9]+(-[a-z0-9]+)*$", message = "use letras minúsculas, números e hífen") @Size(max = 100) String slug,
            Long parentId,
            String description,
            Integer sortOrder,
            Boolean active,
            Boolean featured,
            @Size(max = 70) String metaTitle,
            @Size(max = 170) String metaDescription) {
    }

    public record CategoryResponse(Long id, Long parentId, String name, String slug, String path, int depth,
                                   String description, int sortOrder, boolean active, boolean featured,
                                   String metaTitle, String metaDescription) {

        public static CategoryResponse of(Category c) {
            return new CategoryResponse(c.id, c.parentId, c.name, c.slug, c.slugPath, c.depth, c.description,
                    c.sortOrder, c.active, c.featured, c.metaTitle, c.metaDescription);
        }
    }

    /** Nó da árvore pública (menu da loja). */
    public record CategoryNode(Long id, String name, String slug, String path, boolean featured, List<CategoryNode> children) {
    }

    /** Página de categoria: dados + migalhas (raiz → atual) + filhas diretas. */
    public record CategoryPage(CategoryResponse category, List<Crumb> breadcrumb, List<Crumb> children) {
    }

    public record Crumb(String name, String path) {
    }
}
