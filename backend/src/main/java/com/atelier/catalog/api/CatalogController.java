package com.atelier.catalog.api;

import com.atelier.catalog.api.dto.AttributeDtos.CollectionResponse;
import com.atelier.catalog.api.dto.CategoryDtos.CategoryNode;
import com.atelier.catalog.api.dto.CategoryDtos.CategoryPage;
import com.atelier.catalog.api.dto.ListingDtos.ListingFilter;
import com.atelier.catalog.api.dto.ListingDtos.ListingResponse;
import com.atelier.catalog.api.dto.ListingDtos.Sort;
import com.atelier.catalog.api.dto.ProductPageDtos.ProductPage;
import com.atelier.catalog.api.dto.ProductPageDtos.Related;
import com.atelier.catalog.api.dto.ProductPageDtos.VariantAvailability;
import com.atelier.catalog.domain.Gender;
import com.atelier.catalog.repository.CollectionRepository;
import com.atelier.catalog.service.CategoryService;
import com.atelier.catalog.service.ProductListing;
import com.atelier.catalog.service.ProductPageService;
import com.atelier.catalog.service.SearchService;
import com.atelier.catalog.service.SearchService.SearchResponse;
import com.atelier.catalog.service.SearchService.Suggestions;
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
    private final ProductPageService productPages;
    private final SearchService search;
    private final Clock clock;

    CatalogController(CategoryService categories, CollectionRepository collections, ProductListing listing,
                      ProductPageService productPages, SearchService search, Clock clock) {
        this.categories = categories;
        this.collections = collections;
        this.listing = listing;
        this.productPages = productPages;
        this.search = search;
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
    ResponseEntity<ListingResponse> products(Filters f) {
        return ResponseEntity.ok().cacheControl(SHORT).body(listing.list(f.toFilter(null, Sort.newest)));
    }

    /** Página de produto. `slug` na resposta é o atual: se diferente do pedido, a loja redireciona (301). */
    @GetMapping("/products/{slug}")
    ResponseEntity<ProductPage> product(@PathVariable String slug) {
        return ResponseEntity.ok().cacheControl(SHORT).body(productPages.page(slug));
    }

    /** Estoque muda a todo momento: nunca em cache. Só IN_STOCK / LOW (≤ 3) / OUT, nunca o número. */
    @GetMapping("/products/{slug}/availability")
    ResponseEntity<List<VariantAvailability>> availability(@PathVariable String slug) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(productPages.availability(slug));
    }

    @GetMapping("/products/{slug}/related")
    ResponseEntity<Related> related(@PathVariable String slug) {
        return ResponseEntity.ok().cacheControl(SHORT).body(productPages.related(slug));
    }

    /** Busca: q (2–100 caracteres) + os mesmos filtros da listagem; ordenação padrão por relevância. */
    @GetMapping("/search/products")
    ResponseEntity<SearchResponse> search(@RequestParam String q, Filters f) {
        return ResponseEntity.ok().cacheControl(SHORT).body(search.search(q, f.toFilter(null, Sort.relevance)));
    }

    @GetMapping("/search/suggest")
    ResponseEntity<Suggestions> suggest(@RequestParam String q) {
        return ResponseEntity.ok().cacheControl(SHORT).body(search.suggest(q));
    }

    /** Parâmetros de filtro comuns a listagem e busca (bind por nome do query param). */
    public record Filters(String category, List<String> collection, List<String> sizes, List<String> colors,
                          Long minPrice, Long maxPrice, Boolean inStock, Boolean onSale, List<Gender> gender,
                          Sort sort, Integer page, Integer pageSize) {

        ListingFilter toFilter(String query, Sort defaultSort) {
            return new ListingFilter(category == null || category.isBlank() ? null : category, orEmpty(collection),
                    orEmpty(sizes), orEmpty(colors), minPrice, maxPrice, Boolean.TRUE.equals(inStock),
                    Boolean.TRUE.equals(onSale), orEmpty(gender), sort != null ? sort : defaultSort,
                    page != null ? page : 0, pageSize != null ? pageSize : 24, query);
        }

        private static <T> List<T> orEmpty(List<T> list) {
            return list == null ? List.of() : list.stream().filter(v -> v != null && !v.toString().isBlank()).distinct().toList();
        }
    }
}
