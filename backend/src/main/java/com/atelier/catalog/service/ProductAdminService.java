package com.atelier.catalog.service;

import com.atelier.catalog.api.dto.ProductAdminDtos.*;
import com.atelier.catalog.domain.*;
import com.atelier.catalog.repository.*;
import com.atelier.shared.error.BusinessException;
import com.atelier.shared.error.ErrorCode;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.*;

/** Cadastro de produtos e variantes (admin). */
@Service
public class ProductAdminService {

    private final ProductRepository products;
    private final ProductVariantRepository variants;
    private final CategoryRepository categories;
    private final CollectionRepository collections;
    private final ColorRepository colors;
    private final SizeRepository sizes;
    private final InventoryService inventory;
    private final ProductDenormalizer denormalizer;
    private final ProductImageRepository images;
    private final SizeChartRepository sizeCharts;
    private final NamedParameterJdbcTemplate jdbc;
    private final Clock clock;

    ProductAdminService(ProductRepository products, ProductVariantRepository variants, CategoryRepository categories,
                        CollectionRepository collections, ColorRepository colors, SizeRepository sizes,
                        InventoryService inventory, ProductDenormalizer denormalizer, ProductImageRepository images,
                        SizeChartRepository sizeCharts, NamedParameterJdbcTemplate jdbc, Clock clock) {
        this.images = images;
        this.sizeCharts = sizeCharts;
        this.jdbc = jdbc;
        this.products = products;
        this.variants = variants;
        this.categories = categories;
        this.collections = collections;
        this.colors = colors;
        this.sizes = sizes;
        this.inventory = inventory;
        this.denormalizer = denormalizer;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public Page<Product> search(String q, ProductStatus status, int page, int size) {
        return products.findAll(ProductRepository.adminSearch(q, status),
                PageRequest.of(page, Math.min(size, 100), Sort.by(Sort.Direction.DESC, "id")));
    }

    @Transactional(readOnly = true)
    public ProductDetail detail(Long id) {
        Product p = get(id);
        List<ProductVariant> list = variants.findByProductIdOrderByColorIdAscSizeIdAsc(id);
        Map<Long, StockLevel> stock = inventory.levels(list.stream().map(v -> v.id).toList());
        Instant now = clock.instant();
        List<Long> related = jdbc.queryForList(
                "SELECT related_id FROM product_related WHERE product_id = :id ORDER BY position",
                new MapSqlParameterSource("id", id), Long.class);
        return ProductDetail.of(p, list.stream()
                        .map(v -> VariantResponse.of(v, p.basePrice, stock.getOrDefault(v.id, new StockLevel(0, 0, 0)), now))
                        .toList(),
                images.findByProductIdOrderByIsMainDescPositionAscIdAsc(id).stream().map(ImageResponse::of).toList(),
                related);
    }

    @Transactional
    public Long create(ProductRequest req) {
        if (products.existsByBaseSku(req.baseSku())) throw new BusinessException(ErrorCode.SKU_TAKEN);
        var p = new Product();
        p.baseSku = req.baseSku();
        if (req.slug() != null) {
            if (products.existsBySlug(req.slug())) throw new BusinessException(ErrorCode.SLUG_TAKEN);
            p.slug = req.slug();
        } else {
            p.slug = Slugs.unique(Slugs.of(req.name()), products::existsBySlug);
        }
        apply(p, req);
        products.saveAndFlush(p);
        releaseOldSlug(p.slug);
        denormalizer.recompute(List.of(p.id));
        return p.id;
    }

    /** SKU base não muda depois de criado: os SKUs das variantes derivam dele. */
    @Transactional
    public void update(Long id, ProductRequest req) {
        Product p = get(id);
        if (req.version() == null || req.version() != p.version) {
            throw new ObjectOptimisticLockingFailureException(Product.class, id);
        }
        if (req.slug() != null && !req.slug().equals(p.slug)) {
            if (products.existsBySlug(req.slug())) throw new BusinessException(ErrorCode.SLUG_TAKEN);
            // O endereço antigo continua funcionando (redirect 301 na loja) — não perde links nem SEO.
            jdbc.update("""
                    INSERT INTO product_slug_history (old_slug, product_id) VALUES (:old, :id)
                    ON CONFLICT (old_slug) DO UPDATE SET product_id = EXCLUDED.product_id
                    """, new MapSqlParameterSource("old", p.slug).addValue("id", p.id));
            releaseOldSlug(req.slug());
            p.slug = req.slug();
        }
        apply(p, req);
        products.saveAndFlush(p);
        denormalizer.recompute(List.of(p.id));
    }

    /** Publica: exige categoria ativa, ao menos uma variante ativa e ao menos uma imagem (RN-56). */
    @Transactional
    public void publish(Long id) {
        Product p = get(id);
        List<String> missing = new ArrayList<>();
        if (!variants.existsByProductIdAndActiveTrue(id)) missing.add("ao menos uma variante ativa");
        if (!images.existsByProductId(id)) missing.add("ao menos uma imagem");
        if (categories.findById(p.mainCategoryId).map(c -> !c.active).orElse(true)) missing.add("categoria ativa");
        if (!missing.isEmpty()) {
            throw new BusinessException(ErrorCode.PRODUCT_NOT_PUBLISHABLE, "Falta: " + String.join(", ", missing));
        }
        p.status = ProductStatus.ACTIVE;
        if (p.publishedAt == null) p.publishedAt = clock.instant();
    }

    @Transactional
    public void archive(Long id) {
        get(id).status = ProductStatus.ARCHIVED;
    }

    /** Só rascunhos sem variantes: o resto é arquivado (histórico de estoque e pedidos). */
    @Transactional
    public void delete(Long id) {
        Product p = get(id);
        if (p.status != ProductStatus.DRAFT || variants.existsByProductId(id)) {
            throw new BusinessException(ErrorCode.PRODUCT_HAS_VARIANTS);
        }
        products.delete(p);
    }

    /** Cria as combinações cor × tamanho que ainda não existem (idempotente). SKU: BASE-COR-TAMANHO. */
    @Transactional
    public void generateVariants(Long productId, GenerateVariantsRequest req) {
        Product p = get(productId);
        Map<Long, Color> colorById = new HashMap<>();
        colors.findAllById(req.colorIds()).forEach(c -> colorById.put(c.id, c));
        Map<Long, Size> sizeById = new HashMap<>();
        sizes.findAllById(req.sizeIds()).forEach(s -> sizeById.put(s.id, s));
        if (colorById.size() != req.colorIds().size() || sizeById.size() != req.sizeIds().size()) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "Cor ou tamanho inexistente");
        }
        Set<String> existing = new HashSet<>();
        variants.findByProductIdOrderByColorIdAscSizeIdAsc(productId).forEach(v -> existing.add(v.colorId + ":" + v.sizeId));

        for (Color color : colorById.values()) {
            for (Size size : sizeById.values()) {
                if (existing.contains(color.id + ":" + size.id)) continue;
                var v = new ProductVariant();
                v.productId = productId;
                v.colorId = color.id;
                v.sizeId = size.id;
                v.sku = (p.baseSku + "-" + color.slug + "-" + size.slug).toUpperCase();
                variants.saveAndFlush(v);
                inventory.createFor(v.id);
            }
        }
        denormalizer.recompute(List.of(productId));
    }

    @Transactional
    public void updateVariant(Long productId, Long variantId, VariantRequest req) {
        Product p = get(productId);
        ProductVariant v = variants.findByIdAndProductId(variantId, productId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        long fullPrice = req.price() != null ? req.price() : p.basePrice;
        if (req.salePrice() != null && req.salePrice() >= fullPrice) throw new BusinessException(ErrorCode.INVALID_SALE_PRICE);
        if (req.saleStartsAt() != null && req.saleEndsAt() != null && !req.saleEndsAt().isAfter(req.saleStartsAt())) {
            throw new BusinessException(ErrorCode.INVALID_SALE_PRICE, "O fim da promoção deve ser depois do início");
        }
        v.price = req.price();
        v.salePrice = req.salePrice();
        v.saleStartsAt = req.saleStartsAt();
        v.saleEndsAt = req.saleEndsAt();
        v.supplierRef = req.supplierRef();
        if (req.active() != null) v.active = req.active();
        variants.saveAndFlush(v);
        denormalizer.recompute(List.of(productId));
    }

    /** "Complete o look": até 12 produtos, na ordem informada. */
    @Transactional
    public void setRelated(Long productId, List<Long> relatedIds) {
        get(productId);
        List<Long> ids = relatedIds.stream().distinct().filter(r -> !r.equals(productId)).toList();
        if (products.findAllById(ids).size() != ids.size()) throw new BusinessException(ErrorCode.NOT_FOUND, "Produto relacionado inexistente");
        jdbc.update("DELETE FROM product_related WHERE product_id = :id", new MapSqlParameterSource("id", productId));
        for (int i = 0; i < ids.size(); i++) {
            jdbc.update("INSERT INTO product_related (product_id, related_id, position) VALUES (:id, :rel, :pos)",
                    new MapSqlParameterSource("id", productId).addValue("rel", ids.get(i)).addValue("pos", i));
        }
    }

    /** Um slug em uso por um produto deixa de ser redirect de outro (o produto vivo tem prioridade). */
    private void releaseOldSlug(String slug) {
        jdbc.update("DELETE FROM product_slug_history WHERE old_slug = :slug", new MapSqlParameterSource("slug", slug));
    }

    private Product get(Long id) {
        return products.findById(id).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
    }

    private void apply(Product p, ProductRequest req) {
        if (!categories.existsById(req.mainCategoryId())) throw new BusinessException(ErrorCode.NOT_FOUND, "Categoria inexistente");
        Set<Long> collectionIds = req.collectionIds() == null ? Set.of() : req.collectionIds();
        if (collections.findAllById(collectionIds).size() != collectionIds.size()) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "Coleção inexistente");
        }
        p.name = req.name().trim();
        p.description = req.description();
        p.material = req.material();
        p.careInstructions = req.careInstructions();
        p.gender = req.gender();
        p.mainCategoryId = req.mainCategoryId();
        p.basePrice = req.basePrice();
        p.weightGrams = req.weightGrams();
        p.featured = Boolean.TRUE.equals(req.featured());
        p.isNew = Boolean.TRUE.equals(req.isNew());
        p.collectionIds.clear();
        p.collectionIds.addAll(collectionIds);
        if (req.sizeChartId() != null && !sizeCharts.existsById(req.sizeChartId())) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "Tabela de medidas inexistente");
        }
        p.sizeChartId = req.sizeChartId();
        p.tags = req.tags() == null ? new String[0]
                : req.tags().stream().map(t -> t.trim().toLowerCase(Locale.ROOT)).distinct().toArray(String[]::new);
        p.metaTitle = req.metaTitle();
        p.metaDescription = req.metaDescription();
    }
}
