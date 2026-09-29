package com.atelier.catalog.api;

import com.atelier.catalog.api.dto.ProductAdminDtos.*;
import com.atelier.catalog.domain.ProductStatus;
import com.atelier.catalog.service.InventoryService;
import com.atelier.catalog.service.ProductImageService;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import com.atelier.catalog.service.ProductAdminService;
import com.atelier.shared.web.PageResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

/** Produtos, variantes e estoque. Escrita de cadastro: ADMIN; movimentação de estoque: ADMIN e OPERATOR. */
@RestController
@Validated
@RequestMapping("/api/admin/products")
public class AdminProductController {

    private final ProductAdminService products;
    private final InventoryService inventory;
    private final ProductImageService images;

    AdminProductController(ProductAdminService products, InventoryService inventory, ProductImageService images) {
        this.products = products;
        this.inventory = inventory;
        this.images = images;
    }

    @GetMapping
    PageResponse<ProductSummary> search(@RequestParam(required = false) String q,
                                        @RequestParam(required = false) ProductStatus status,
                                        @RequestParam(defaultValue = "0") int page,
                                        @RequestParam(defaultValue = "20") int size) {
        return PageResponse.of(products.search(q, status, page, size).map(ProductSummary::of));
    }

    @GetMapping("/{id}")
    ProductDetail detail(@PathVariable Long id) {
        return products.detail(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('ADMIN')")
    ProductDetail create(@Valid @RequestBody ProductRequest req) {
        return products.detail(products.create(req));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    ProductDetail update(@PathVariable Long id, @Valid @RequestBody ProductRequest req) {
        products.update(id, req);
        return products.detail(id);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('ADMIN')")
    void delete(@PathVariable Long id) {
        products.delete(id);
    }

    @PostMapping("/{id}/publish")
    @PreAuthorize("hasRole('ADMIN')")
    ProductDetail publish(@PathVariable Long id) {
        products.publish(id);
        return products.detail(id);
    }

    @PostMapping("/{id}/archive")
    @PreAuthorize("hasRole('ADMIN')")
    ProductDetail archive(@PathVariable Long id) {
        products.archive(id);
        return products.detail(id);
    }

    @PostMapping("/{id}/variants/generate")
    @PreAuthorize("hasRole('ADMIN')")
    ProductDetail generateVariants(@PathVariable Long id, @Valid @RequestBody GenerateVariantsRequest req) {
        products.generateVariants(id, req);
        return products.detail(id);
    }

    @PutMapping("/{id}/variants/{variantId}")
    @PreAuthorize("hasRole('ADMIN')")
    ProductDetail updateVariant(@PathVariable Long id, @PathVariable Long variantId, @Valid @RequestBody VariantRequest req) {
        products.updateVariant(id, variantId, req);
        return products.detail(id);
    }

    @PostMapping("/{id}/variants/{variantId}/stock-movements")
    StockLevel moveStock(@PathVariable Long id, @PathVariable Long variantId, @Valid @RequestBody StockMovementRequest req,
                         @AuthenticationPrincipal Jwt jwt) {
        return inventory.move(id, variantId, req.type(), req.quantity(), req.reason(), Long.valueOf(jwt.getSubject()));
    }

    // ---- imagens ----

    /** multipart/form-data: file (JPEG/PNG, até 10 MB, largura ≥ 1200 px), alt (obrigatório), colorId (opcional). */
    @PostMapping(value = "/{id}/images", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('ADMIN')")
    ImageResponse uploadImage(@PathVariable Long id, @RequestParam("file") MultipartFile file,
                              @RequestParam("alt") @NotBlank @Size(max = 200) String alt,
                              @RequestParam(value = "colorId", required = false) Long colorId) throws IOException {
        return ImageResponse.of(images.upload(id, file.getBytes(), alt, colorId));
    }

    @PutMapping("/{id}/images/{imageId}")
    @PreAuthorize("hasRole('ADMIN')")
    ImageResponse updateImage(@PathVariable Long id, @PathVariable Long imageId, @Valid @RequestBody ImageUpdateRequest req) {
        return ImageResponse.of(images.update(id, imageId, req));
    }

    @PutMapping("/{id}/images/order")
    @PreAuthorize("hasRole('ADMIN')")
    ProductDetail reorderImages(@PathVariable Long id, @Valid @RequestBody ImageOrderRequest req) {
        images.reorder(id, req.imageIds());
        return products.detail(id);
    }

    @DeleteMapping("/{id}/images/{imageId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('ADMIN')")
    void deleteImage(@PathVariable Long id, @PathVariable Long imageId) {
        images.delete(id, imageId);
    }

    // ---- relacionados ("complete o look") ----

    @PutMapping("/{id}/related")
    @PreAuthorize("hasRole('ADMIN')")
    ProductDetail setRelated(@PathVariable Long id, @Valid @RequestBody RelatedRequest req) {
        products.setRelated(id, req.productIds());
        return products.detail(id);
    }
}
