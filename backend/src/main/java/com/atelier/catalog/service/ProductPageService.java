package com.atelier.catalog.service;

import com.atelier.catalog.api.dto.CategoryDtos.Crumb;
import com.atelier.catalog.api.dto.ProductPageDtos.*;
import com.atelier.catalog.domain.*;
import com.atelier.catalog.domain.SizeChart;
import com.atelier.catalog.repository.*;
import com.atelier.shared.error.BusinessException;
import com.atelier.shared.error.ErrorCode;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

/** Página de produto da loja (PRD, seção 6). Só produtos ACTIVE; slugs antigos resolvem para o atual. */
@Service
public class ProductPageService {

    /** Até 3 unidades a loja mostra "últimas unidades"; o número exato nunca é exposto. */
    static final int LOW_STOCK = 3;

    private final ProductRepository products;
    private final ProductVariantRepository variants;
    private final ProductImageRepository images;
    private final ColorRepository colors;
    private final SizeRepository sizes;
    private final CategoryRepository categories;
    private final SizeChartRepository sizeCharts;
    private final AttributeService attributes;
    private final InventoryService inventory;
    private final ProductListing listing;
    private final NamedParameterJdbcTemplate jdbc;
    private final Clock clock;

    ProductPageService(ProductRepository products, ProductVariantRepository variants, ProductImageRepository images,
                       ColorRepository colors, SizeRepository sizes, CategoryRepository categories,
                       SizeChartRepository sizeCharts, AttributeService attributes, InventoryService inventory,
                       ProductListing listing, NamedParameterJdbcTemplate jdbc, Clock clock) {
        this.products = products;
        this.variants = variants;
        this.images = images;
        this.colors = colors;
        this.sizes = sizes;
        this.categories = categories;
        this.sizeCharts = sizeCharts;
        this.attributes = attributes;
        this.inventory = inventory;
        this.listing = listing;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public ProductPage page(String slug) {
        Product p = resolve(slug);
        Instant now = clock.instant();
        List<ProductVariant> active = variants.findByProductIdOrderByColorIdAscSizeIdAsc(p.id).stream()
                .filter(v -> v.active).toList();

        List<VariantOption> options = active.stream()
                .map(v -> new VariantOption(v.id, v.sku, v.colorId, v.sizeId, v.price != null ? v.price : p.basePrice,
                        v.effectivePrice(p.basePrice, now)))
                .toList();

        Set<Long> colorIds = new LinkedHashSet<>();
        Set<Long> sizeIds = new HashSet<>();
        active.forEach(v -> { colorIds.add(v.colorId); sizeIds.add(v.sizeId); });
        List<ColorOption> colorOptions = colors.findAllById(colorIds).stream()
                .sorted(Comparator.comparing(c -> c.name))
                .map(c -> new ColorOption(c.id, c.slug, c.name, c.hex)).toList();
        List<SizeOption> sizeOptions = sizes.findAllById(sizeIds).stream()
                .sorted(Comparator.comparing((Size s) -> s.sizeGroup).thenComparingInt(s -> s.sortOrder))
                .map(s -> new SizeOption(s.id, s.slug, s.name)).toList();

        long minFull = options.stream().mapToLong(VariantOption::price).min().orElse(p.basePrice);
        long minEffective = options.stream().mapToLong(VariantOption::effectivePrice).min().orElse(p.basePrice);
        boolean varies = options.stream().map(VariantOption::effectivePrice).distinct().count() > 1;

        List<String> badges = new ArrayList<>();
        if (p.isNew || (p.publishedAt != null && p.publishedAt.isAfter(now.minus(Duration.ofDays(30))))) badges.add("NEW");
        if (minEffective < minFull) badges.add("SALE");

        List<Image> gallery = images.findByProductIdOrderByIsMainDescPositionAscIdAsc(p.id).stream()
                .map(i -> new Image(i.url, i.altText, i.colorId, i.width, i.height)).toList();

        SizeChart chart = p.sizeChartId == null ? null : sizeCharts.findById(p.sizeChartId).orElse(null);
        var chartDto = chart == null ? null : attributes.toResponse(chart);

        String description = p.metaDescription != null ? p.metaDescription : summary(p.description);
        return new ProductPage(p.id, p.slug, p.baseSku, p.name, p.description, p.material, p.careInstructions, p.gender,
                breadcrumb(p.mainCategoryId), minFull, minEffective < minFull ? minEffective : null, varies, p.hasStock,
                badges, colorOptions, sizeOptions, options, gallery,
                chartDto == null ? null : new com.atelier.catalog.api.dto.ProductPageDtos.SizeChart(chartDto.name(), chartDto.columns(), chartDto.rows()),
                new Seo(p.metaTitle != null ? p.metaTitle : p.name, description));
    }

    @Transactional(readOnly = true)
    public List<VariantAvailability> availability(String slug) {
        Product p = resolve(slug);
        List<Long> ids = variants.findByProductIdOrderByColorIdAscSizeIdAsc(p.id).stream()
                .filter(v -> v.active).map(v -> v.id).toList();
        var levels = inventory.levels(ids);
        return ids.stream().map(id -> {
            int available = levels.containsKey(id) ? levels.get(id).available() : 0;
            Availability status = available <= 0 ? Availability.OUT : available <= LOW_STOCK ? Availability.LOW : Availability.IN_STOCK;
            return new VariantAvailability(id, status);
        }).toList();
    }

    /** "Complete o look" (curadoria do admin) + semelhantes (mesma categoria, mais vendidos, com estoque). */
    @Transactional(readOnly = true)
    public Related related(String slug) {
        Product p = resolve(slug);
        List<Long> curated = jdbc.queryForList(
                "SELECT related_id FROM product_related WHERE product_id = :id ORDER BY position",
                new MapSqlParameterSource("id", p.id), Long.class);
        var similar = listing.cards("p.main_category_id = :category AND p.id <> :id AND p.id NOT IN (:exclude) AND p.has_stock",
                "p.sales_count DESC",
                new MapSqlParameterSource("category", p.mainCategoryId).addValue("id", p.id)
                        .addValue("exclude", curated.isEmpty() ? List.of(-1L) : curated),
                8, 0);
        return new Related(listing.cardsByIds(curated), similar);
    }

    private Product resolve(String slug) {
        return products.findBySlugAndStatus(slug, ProductStatus.ACTIVE)
                .or(() -> jdbc.queryForList("SELECT product_id FROM product_slug_history WHERE old_slug = :slug",
                                new MapSqlParameterSource("slug", slug), Long.class).stream().findFirst()
                        .flatMap(products::findById)
                        .filter(p -> p.status == ProductStatus.ACTIVE))
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
    }

    private List<Crumb> breadcrumb(Long categoryId) {
        Category leaf = categories.findById(categoryId).orElse(null);
        if (leaf == null) return List.of();
        List<Crumb> crumbs = new ArrayList<>();
        String[] parts = leaf.slugPath.split("/");
        for (int i = 1; i <= parts.length; i++) {
            categories.findBySlugPathAndActiveTrue(String.join("/", Arrays.copyOf(parts, i)))
                    .ifPresent(c -> crumbs.add(new Crumb(c.name, c.slugPath)));
        }
        return crumbs;
    }

    /** Meta description automática: primeiros ~155 caracteres da descrição, cortados em palavra. */
    private static String summary(String text) {
        if (text == null || text.isBlank()) return null;
        String plain = text.replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ").trim();
        if (plain.length() <= 155) return plain;
        String cut = plain.substring(0, 155);
        return cut.substring(0, Math.max(cut.lastIndexOf(' '), 100)) + "…";
    }
}
