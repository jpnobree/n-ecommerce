package com.atelier.catalog.api;

import com.atelier.catalog.api.dto.AttributeDtos.CollectionResponse;
import com.atelier.catalog.api.dto.CategoryDtos.CategoryNode;
import com.atelier.catalog.api.dto.CategoryDtos.CategoryPage;
import com.atelier.catalog.api.dto.ListingDtos.ListingFilter;
import com.atelier.catalog.api.dto.ListingDtos.ListingResponse;
import com.atelier.catalog.api.dto.ListingDtos.Sort;
import com.atelier.catalog.domain.Gender;
import com.atelier.catalog.repository.CollectionRepository;
import com.atelier.catalog.service.CategoryService;
import com.atelier.catalog.service.ProductListing;
import com.atelier.shared.error.BusinessException;
import com.atelier.shared.error.ErrorCode;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Clock;
import java.time.Duration;
import java.util.List;

/** Catálogo público (sem autenticação, cacheável na CDN por pouco tempo). */
@RestController
@RequestMapping("/api")
public class CatalogController {

    private static final CacheControl SHORT = CacheControl.maxAge(Duration.ofSeconds(60)).cachePublic();

    private final CategoryService categories;
    private final CollectionRepository collections;
    private final ProductListing listing;
    private final Clock clock;

    CatalogController(CategoryService categories, CollectionRepository collections, ProductListing listing, Clock clock) {
        this.categories = categories;
        this.collections = collections;
        this.listing = listing;
        this.clock = clock;
    }

    @GetMapping("/categories")
    ResponseEntity<List<CategoryNode>> tree() {
        return ResponseEntity.ok().cacheControl(SHORT).body(categories.publicTree());
    }

    /** Página de categoria por caminho: /api/categories/page?path=feminino/vestidos */
    @GetMapping("/categories/page")
    ResponseEntity<CategoryPage> categoryPage(@RequestParam String path) {
        return ResponseEntity.ok().cacheControl(SHORT).body(categories.page(path));
    }

    @GetMapping("/collections")
    ResponseEntity<List<CollectionResponse>> collections() {
        return ResponseEntity.ok().cacheControl(SHORT)
                .body(collections.findVisible(clock.instant()).stream().map(CollectionResponse::of).toList());
    }

    @GetMapping("/collections/{slug}")
    ResponseEntity<CollectionResponse> collection(@PathVariable String slug) {
        var c = collections.findVisibleBySlug(slug, clock.instant())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        return ResponseEntity.ok().cacheControl(SHORT).body(CollectionResponse.of(c));
    }

    /**
     * Listagem com filtros. Listas aceitam vírgula: sizes=p,m&colors=preto.
     * Ordenação: newest (padrão), best_sellers, price_asc, price_desc.
     */
    @GetMapping("/products")
    ResponseEntity<ListingResponse> products(
            @RequestParam(required = false) String category,
            @RequestParam(required = false, name = "collection") List<String> collections,
            @RequestParam(required = false) List<String> sizes,
            @RequestParam(required = false) List<String> colors,
            @RequestParam(required = false) Long minPrice,
            @RequestParam(required = false) Long maxPrice,
            @RequestParam(defaultValue = "false") boolean inStock,
            @RequestParam(defaultValue = "false") boolean onSale,
            @RequestParam(required = false, name = "gender") List<Gender> genders,
            @RequestParam(defaultValue = "newest") Sort sort,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "24") int pageSize) {
        var filter = new ListingFilter(blankToNull(category), orEmpty(collections), orEmpty(sizes), orEmpty(colors),
                minPrice, maxPrice, inStock, onSale, orEmpty(genders), sort, page, pageSize);
        return ResponseEntity.ok().cacheControl(SHORT).body(listing.list(filter));
    }

    private static <T> List<T> orEmpty(List<T> list) {
        return list == null ? List.of() : list.stream().filter(v -> v != null && !v.toString().isBlank()).distinct().toList();
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
