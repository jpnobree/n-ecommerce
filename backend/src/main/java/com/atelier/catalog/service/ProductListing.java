package com.atelier.catalog.service;

import com.atelier.catalog.api.dto.ListingDtos.*;
import com.atelier.catalog.domain.Gender;
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
import java.util.regex.Pattern;

/**
 * Listagem pública com filtros, ordenação e facetas (PRD, seção 4.2).
 * - OR dentro de um filtro, AND entre filtros.
 * - Tamanho, cor e disponibilidade valem para a MESMA variante; filtrar por tamanho implica ter estoque dele.
 * - Cada faceta conta aplicando todos os outros filtros, menos o dela.
 * SQL montado só com fragmentos fixos + parâmetros vinculados; ordenação por whitelist.
 * ponytail: facetas por GROUP BY a cada requisição (ok até ~20 mil produtos); acima disso, motor de busca (Fase V2).
 */
@Service
public class ProductListing {

    public static final int MAX_PAGE_SIZE = 48;
    private static final int MAX_OFFSET = 10_000;
    private static final int MAX_VALUES = 20;
    private static final Pattern SLUG = Pattern.compile("^[a-z0-9]+(-[a-z0-9]+)*$");
    private static final Pattern PATH = Pattern.compile("^[a-z0-9]+(-[a-z0-9]+)*(/[a-z0-9]+(-[a-z0-9]+)*){0,2}$");
    private static final Duration NEW_WINDOW = Duration.ofDays(30);

    private static final Map<Sort, String> ORDER = Map.of(
            Sort.newest, "p.published_at DESC",
            Sort.best_sellers, "p.sales_count DESC",
            Sort.price_asc, "p.min_effective_price ASC",
            Sort.price_desc, "p.min_effective_price DESC");

    private enum Facet { NONE, SIZE, COLOR, GENDER, COLLECTION, PRICE }

    private static final String STOCK = "EXISTS (SELECT 1 FROM inventory i WHERE i.variant_id = v.id AND i.on_hand - i.reserved > 0)";
    private static final String VISIBLE_COLLECTION =
            "c.active AND (c.starts_at IS NULL OR c.starts_at <= now()) AND (c.ends_at IS NULL OR c.ends_at > now())";

    private final NamedParameterJdbcTemplate jdbc;
    private final Clock clock;

    ProductListing(NamedParameterJdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public ListingResponse list(ListingFilter f) {
        validate(f);
        var params = params(f);

        String where = productWhere(f, Facet.NONE);
        long total = jdbc.queryForObject("SELECT count(*) FROM product p WHERE " + where, params, Long.class);

        String order = f.sort() == Sort.relevance && f.query() != null
                ? "ts_rank(p.search_vector, websearch_to_tsquery('portuguese', unaccent(:q))) DESC"
                : ORDER.get(f.sort() == Sort.relevance ? Sort.newest : f.sort());
        List<ProductCard> cards = cards(where, order, params, f.pageSize(), f.page() * f.pageSize());

        int totalPages = (int) Math.ceil(total / (double) f.pageSize());
        return new ListingResponse(cards, f.page(), f.pageSize(), total, totalPages, facets(f, params));
    }

    private record Row(Long id, String slug, String name, long base, long effective, boolean inStock, boolean isNew,
                       Instant publishedAt, String imageUrl) {}

    /** Cards de produtos ativos em qualquer recorte (vitrines da home, relacionados). Esgotados sempre por último. */
    public List<ProductCard> cards(String where, String order, MapSqlParameterSource params, int limit, int offset) {
        params.addValue("limit", limit).addValue("offset", offset);
        List<Row> rows = jdbc.query("""
                SELECT p.id, p.slug, p.name, p.min_base_price, p.min_effective_price, p.has_stock, p.is_new, p.published_at,
                       (SELECT pi.url FROM product_image pi WHERE pi.product_id = p.id
                         ORDER BY pi.is_main DESC, pi.position, pi.id LIMIT 1)
                  FROM product p WHERE p.status = 'ACTIVE' AND %s
                 ORDER BY p.has_stock DESC, %s, p.id DESC
                 LIMIT :limit OFFSET :offset
                """.formatted(where, order), params, (rs, n) -> new Row(
                rs.getLong(1), rs.getString(2), rs.getString(3), rs.getLong(4), rs.getLong(5), rs.getBoolean(6),
                rs.getBoolean(7), rs.getTimestamp(8) == null ? Instant.EPOCH : rs.getTimestamp(8).toInstant(),
                rs.getString(9)));

        Map<Long, List<ColorChip>> colors = colorsOf(rows.stream().map(Row::id).toList());
        Instant newSince = clock.instant().minus(NEW_WINDOW);
        return rows.stream().map(r -> {
            boolean onSale = r.effective() < r.base();
            List<String> badges = new ArrayList<>();
            if (r.isNew() || r.publishedAt().isAfter(newSince)) badges.add("NEW");
            if (onSale) badges.add("SALE");
            return new ProductCard(r.id(), r.slug(), r.name(), r.base(), onSale ? r.effective() : null, "BRL",
                    r.imageUrl(), colors.getOrDefault(r.id(), List.of()), r.inStock(), badges);
        }).toList();
    }

    /** Cards na ordem dos ids informados (ex.: "complete o look" definido no admin). */
    public List<ProductCard> cardsByIds(List<Long> ids) {
        if (ids.isEmpty()) return List.of();
        List<ProductCard> found = cards("p.id IN (:ids)", "p.id", new MapSqlParameterSource("ids", ids), ids.size(), 0);
        return ids.stream().flatMap(id -> found.stream().filter(c -> c.id().equals(id))).toList();
    }

    // ---- facetas ----

    private Facets facets(ListingFilter f, MapSqlParameterSource params) {
        List<FacetValue> sizes = jdbc.query("""
                SELECT s.slug, s.name, count(DISTINCT p.id)
                  FROM product p JOIN product_variant v ON v.product_id = p.id JOIN size s ON s.id = v.size_id
                 WHERE %s AND %s
                 GROUP BY s.slug, s.name, s.size_group, s.sort_order
                 ORDER BY s.size_group, s.sort_order
                """.formatted(productWhere(f, Facet.SIZE), variantWhere(f, Facet.SIZE, true)), params,
                (rs, n) -> new FacetValue(rs.getString(1), rs.getString(2), null, rs.getLong(3)));

        List<FacetValue> colors = jdbc.query("""
                SELECT c.slug, c.name, c.hex, count(DISTINCT p.id)
                  FROM product p JOIN product_variant v ON v.product_id = p.id JOIN color c ON c.id = v.color_id
                 WHERE %s AND %s
                 GROUP BY c.slug, c.name, c.hex
                 ORDER BY c.name
                """.formatted(productWhere(f, Facet.COLOR), variantWhere(f, Facet.COLOR, false)), params,
                (rs, n) -> new FacetValue(rs.getString(1), rs.getString(2), rs.getString(3), rs.getLong(4)));

        List<FacetValue> genders = jdbc.query("""
                SELECT p.gender, count(*) FROM product p WHERE %s GROUP BY p.gender ORDER BY p.gender
                """.formatted(productWhere(f, Facet.GENDER)), params,
                (rs, n) -> new FacetValue(rs.getString(1), genderLabel(rs.getString(1)), null, rs.getLong(2)));

        List<FacetValue> collections = jdbc.query("""
                SELECT c.slug, c.name, count(DISTINCT p.id)
                  FROM product p JOIN product_collection pc ON pc.product_id = p.id
                  JOIN collection c ON c.id = pc.collection_id AND %s
                 WHERE %s
                 GROUP BY c.slug, c.name, c.sort_order
                 ORDER BY c.sort_order, c.name
                """.formatted(VISIBLE_COLLECTION, productWhere(f, Facet.COLLECTION)), params,
                (rs, n) -> new FacetValue(rs.getString(1), rs.getString(2), null, rs.getLong(3)));

        PriceRange price = jdbc.queryForObject(
                "SELECT min(p.min_effective_price), max(p.min_effective_price) FROM product p WHERE " + productWhere(f, Facet.PRICE),
                params, (rs, n) -> new PriceRange((Long) rs.getObject(1), (Long) rs.getObject(2)));

        return new Facets(sizes, colors, genders, collections, price);
    }

    // ---- WHERE ----

    /**
     * Condições no nível do produto. Para as facetas de tamanho/cor, as condições de variante saem daqui
     * e vão para o JOIN (senão a contagem não respeitaria a "mesma variante").
     */
    private static String productWhere(ListingFilter f, Facet exclude) {
        List<String> c = new ArrayList<>();
        c.add("p.status = 'ACTIVE'");
        if (f.query() != null) c.add("p.search_vector @@ websearch_to_tsquery('portuguese', unaccent(:q))");
        if (f.category() != null) {
            c.add("p.main_category_id IN (SELECT id FROM category WHERE active AND (slug_path = :category OR slug_path LIKE :categoryPrefix))");
        }
        if (!f.collections().isEmpty() && exclude != Facet.COLLECTION) {
            c.add("EXISTS (SELECT 1 FROM product_collection pc JOIN collection c ON c.id = pc.collection_id AND "
                    + VISIBLE_COLLECTION + " WHERE pc.product_id = p.id AND c.slug IN (:collections))");
        }
        if (exclude != Facet.PRICE) {
            if (f.minPrice() != null) c.add("p.min_effective_price >= :minPrice");
            if (f.maxPrice() != null) c.add("p.min_effective_price <= :maxPrice");
        }
        if (f.onSale()) c.add("p.min_effective_price < p.min_base_price");
        if (!f.genders().isEmpty() && exclude != Facet.GENDER) c.add("p.gender IN (:genders)");
        boolean variantInJoin = exclude == Facet.SIZE || exclude == Facet.COLOR;
        if (!variantInJoin && hasVariantConditions(f)) {
            c.add("EXISTS (SELECT 1 FROM product_variant v WHERE v.product_id = p.id AND " + variantWhere(f, Facet.NONE, false) + ")");
        }
        return String.join(" AND ", c);
    }

    private static boolean hasVariantConditions(ListingFilter f) {
        return !f.sizes().isEmpty() || !f.colors().isEmpty() || f.inStock();
    }

    private static String variantWhere(ListingFilter f, Facet exclude, boolean requireStock) {
        List<String> c = new ArrayList<>();
        c.add("v.active");
        boolean sizeFilter = !f.sizes().isEmpty() && exclude != Facet.SIZE;
        if (sizeFilter) c.add("v.size_id IN (SELECT id FROM size WHERE slug IN (:sizes))");
        if (!f.colors().isEmpty() && exclude != Facet.COLOR) c.add("v.color_id IN (SELECT id FROM color WHERE slug IN (:colors))");
        if (requireStock || sizeFilter || f.inStock()) c.add(STOCK);
        return String.join(" AND ", c);
    }

    private static MapSqlParameterSource params(ListingFilter f) {
        var p = new MapSqlParameterSource();
        if (f.query() != null) p.addValue("q", f.query());
        if (f.category() != null) p.addValue("category", f.category()).addValue("categoryPrefix", f.category() + "/%");
        if (!f.collections().isEmpty()) p.addValue("collections", f.collections());
        if (!f.sizes().isEmpty()) p.addValue("sizes", f.sizes());
        if (!f.colors().isEmpty()) p.addValue("colors", f.colors());
        if (!f.genders().isEmpty()) p.addValue("genders", f.genders().stream().map(Enum::name).toList());
        p.addValue("minPrice", f.minPrice()).addValue("maxPrice", f.maxPrice());
        return p;
    }

    private Map<Long, List<ColorChip>> colorsOf(List<Long> productIds) {
        Map<Long, List<ColorChip>> result = new HashMap<>();
        if (productIds.isEmpty()) return result;
        jdbc.query("""
                SELECT DISTINCT v.product_id, c.slug, c.name, c.hex
                  FROM product_variant v JOIN color c ON c.id = v.color_id
                 WHERE v.product_id IN (:ids) AND v.active
                 ORDER BY c.name
                """, new MapSqlParameterSource("ids", productIds), rs -> {
            result.computeIfAbsent(rs.getLong(1), k -> new ArrayList<>())
                    .add(new ColorChip(rs.getString(2), rs.getString(3), rs.getString(4)));
        });
        return result;
    }

    private static void validate(ListingFilter f) {
        if (f.page() < 0 || f.pageSize() < 1 || f.pageSize() > MAX_PAGE_SIZE || (long) f.page() * f.pageSize() > MAX_OFFSET) {
            throw new BusinessException(ErrorCode.INVALID_FILTER, "Paginação inválida");
        }
        if (f.category() != null && !PATH.matcher(f.category()).matches()) {
            throw new BusinessException(ErrorCode.INVALID_FILTER, "Categoria inválida");
        }
        for (List<String> values : List.of(f.collections(), f.sizes(), f.colors())) {
            if (values.size() > MAX_VALUES || values.stream().anyMatch(v -> !SLUG.matcher(v).matches())) {
                throw new BusinessException(ErrorCode.INVALID_FILTER, "Valor de filtro inválido");
            }
        }
        if (f.minPrice() != null && f.maxPrice() != null && f.minPrice() > f.maxPrice()) {
            throw new BusinessException(ErrorCode.INVALID_FILTER, "Faixa de preço inválida");
        }
    }

    private static String genderLabel(String gender) {
        return switch (Gender.valueOf(gender)) {
            case FEMALE -> "Feminino";
            case MALE -> "Masculino";
            case UNISEX -> "Unissex";
        };
    }
}
