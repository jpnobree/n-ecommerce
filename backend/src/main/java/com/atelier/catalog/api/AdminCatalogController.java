package com.atelier.catalog.api;

import com.atelier.catalog.api.dto.AttributeDtos.*;
import com.atelier.catalog.api.dto.CategoryDtos.CategoryRequest;
import com.atelier.catalog.api.dto.CategoryDtos.CategoryResponse;
import com.atelier.catalog.repository.CollectionRepository;
import com.atelier.catalog.repository.ColorRepository;
import com.atelier.catalog.repository.SizeRepository;
import com.atelier.catalog.repository.SizeChartRepository;
import com.atelier.catalog.api.dto.ProductAdminDtos.SizeChartRequest;
import com.atelier.catalog.api.dto.ProductAdminDtos.SizeChartResponse;
import com.atelier.catalog.service.AttributeService;
import com.atelier.catalog.service.CategoryService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Categorias, coleções, cores e tamanhos. Leitura: ADMIN e OPERATOR (regra de /api/admin/**);
 * escrita: só ADMIN.
 */
@RestController
@RequestMapping("/api/admin")
public class AdminCatalogController {

    private final CategoryService categories;
    private final AttributeService attributes;
    private final CollectionRepository collections;
    private final ColorRepository colors;
    private final SizeRepository sizes;
    private final SizeChartRepository sizeCharts;

    AdminCatalogController(CategoryService categories, AttributeService attributes, CollectionRepository collections,
                           ColorRepository colors, SizeRepository sizes, SizeChartRepository sizeCharts) {
        this.sizeCharts = sizeCharts;
        this.categories = categories;
        this.attributes = attributes;
        this.collections = collections;
        this.colors = colors;
        this.sizes = sizes;
    }

    // ---- categorias ----

    @GetMapping("/categories")
    List<CategoryResponse> categories() {
        return categories.all().stream().map(CategoryResponse::of).toList();
    }

    @PostMapping("/categories")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('ADMIN')")
    CategoryResponse createCategory(@Valid @RequestBody CategoryRequest req) {
        return CategoryResponse.of(categories.create(req));
    }

    @PutMapping("/categories/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    CategoryResponse updateCategory(@PathVariable Long id, @Valid @RequestBody CategoryRequest req) {
        return CategoryResponse.of(categories.update(id, req));
    }

    @DeleteMapping("/categories/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('ADMIN')")
    void deleteCategory(@PathVariable Long id) {
        categories.delete(id);
    }

    // ---- coleções ----

    @GetMapping("/collections")
    List<CollectionResponse> collections() {
        return collections.findAllByOrderBySortOrderAscNameAsc().stream().map(CollectionResponse::of).toList();
    }

    @PostMapping("/collections")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('ADMIN')")
    CollectionResponse createCollection(@Valid @RequestBody CollectionRequest req) {
        return CollectionResponse.of(attributes.createCollection(req));
    }

    @PutMapping("/collections/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    CollectionResponse updateCollection(@PathVariable Long id, @Valid @RequestBody CollectionRequest req) {
        return CollectionResponse.of(attributes.updateCollection(id, req));
    }

    @DeleteMapping("/collections/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('ADMIN')")
    void deleteCollection(@PathVariable Long id) {
        attributes.deleteCollection(id);
    }

    // ---- cores ----

    @GetMapping("/colors")
    List<ColorResponse> colors() {
        return colors.findAllByOrderByNameAsc().stream().map(ColorResponse::of).toList();
    }

    @PostMapping("/colors")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('ADMIN')")
    ColorResponse createColor(@Valid @RequestBody ColorRequest req) {
        return ColorResponse.of(attributes.saveColor(null, req));
    }

    @PutMapping("/colors/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    ColorResponse updateColor(@PathVariable Long id, @Valid @RequestBody ColorRequest req) {
        return ColorResponse.of(attributes.saveColor(id, req));
    }

    @DeleteMapping("/colors/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('ADMIN')")
    void deleteColor(@PathVariable Long id) {
        attributes.deleteColor(id);
    }

    // ---- tamanhos ----

    @GetMapping("/sizes")
    List<SizeResponse> sizes() {
        return sizes.findAllByOrderBySizeGroupAscSortOrderAsc().stream().map(SizeResponse::of).toList();
    }

    @PostMapping("/sizes")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('ADMIN')")
    SizeResponse createSize(@Valid @RequestBody SizeRequest req) {
        return SizeResponse.of(attributes.saveSize(null, req));
    }

    @PutMapping("/sizes/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    SizeResponse updateSize(@PathVariable Long id, @Valid @RequestBody SizeRequest req) {
        return SizeResponse.of(attributes.saveSize(id, req));
    }

    @DeleteMapping("/sizes/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('ADMIN')")
    void deleteSize(@PathVariable Long id) {
        attributes.deleteSize(id);
    }

    // ---- tabelas de medidas ----

    @GetMapping("/size-charts")
    List<SizeChartResponse> sizeCharts() {
        return sizeCharts.findAllByOrderByNameAsc().stream().map(attributes::toResponse).toList();
    }

    @PostMapping("/size-charts")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('ADMIN')")
    SizeChartResponse createSizeChart(@Valid @RequestBody SizeChartRequest req) {
        return attributes.toResponse(attributes.saveSizeChart(null, req));
    }

    @PutMapping("/size-charts/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    SizeChartResponse updateSizeChart(@PathVariable Long id, @Valid @RequestBody SizeChartRequest req) {
        return attributes.toResponse(attributes.saveSizeChart(id, req));
    }

    @DeleteMapping("/size-charts/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('ADMIN')")
    void deleteSizeChart(@PathVariable Long id) {
        attributes.deleteSizeChart(id);
    }
}
