package com.atelier.catalog.service;

import com.atelier.catalog.api.dto.CategoryDtos.Crumb;
import com.atelier.catalog.api.dto.ListingDtos.*;
import com.atelier.shared.error.BusinessException;
import com.atelier.shared.error.ErrorCode;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.text.Normalizer;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Busca de produtos (PRD, seção 5) sobre o Full-Text Search do PostgreSQL.
 * Sem resultado: 1) qualquer um dos termos; 2) correção de digitação por trigramas; 3) sugestões (mais vendidos).
 * ponytail: FTS + pg_trgm atende dezenas de milhares de produtos; acima disso, Elasticsearch/OpenSearch
 * atrás da mesma API (a porta é este serviço).
 */
@Service
public class SearchService {

    public record SearchResponse(String query, String correctedQuery, String exactMatch, ListingResponse result,
                                 List<ProductCard> suggestions) {}

    public record Suggestions(List<ProductCard> products, List<Crumb> categories) {}

    public record SearchLogged(String term, long results) {}

    private final ProductListing listing;
    private final NamedParameterJdbcTemplate jdbc;
    private final ApplicationEventPublisher events;

    SearchService(ProductListing listing, NamedParameterJdbcTemplate jdbc, ApplicationEventPublisher events) {
        this.listing = listing;
        this.jdbc = jdbc;
        this.events = events;
    }

    @Transactional(readOnly = true)
    public SearchResponse search(String rawQuery, ListingFilter filter) {
        String q = clean(rawQuery);
        String exact = exactSku(q);

        ListingResponse result = listing.list(withQuery(filter, q));
        String corrected = null;
        List<ProductCard> suggestions = List.of();

        boolean firstPageWithoutFilters = filter.page() == 0 && !hasFilters(filter);
        if (result.totalElements() == 0 && firstPageWithoutFilters) {
            String[] terms = terms(q);
            if (terms.length > 1) {
                result = listing.list(withQuery(filter, String.join(" or ", terms)));
            }
            if (result.totalElements() == 0) {
                String fixed = correct(terms);
                if (fixed != null && !fixed.equals(String.join(" ", terms))) {
                    ListingResponse retry = listing.list(withQuery(filter, fixed));
                    if (retry.totalElements() > 0) {
                        result = retry;
                        corrected = fixed;
                    }
                }
            }
            if (result.totalElements() == 0) {
                suggestions = listing.cards("p.has_stock", "p.sales_count DESC", new MapSqlParameterSource(), 8, 0);
            }
        }
        if (filter.page() == 0) events.publishEvent(new SearchLogged(q.toLowerCase(Locale.ROOT), result.totalElements()));
        return new SearchResponse(q, corrected, exact, result, suggestions);
    }

    /** Autocompletar: até 5 produtos por prefixo e até 3 categorias. */
    @Transactional(readOnly = true)
    public Suggestions suggest(String rawQuery) {
        String[] terms = terms(clean(rawQuery));
        if (terms.length == 0) return new Suggestions(List.of(), List.of());
        // "vestido lon" -> 'vestido & lon:*'
        String prefix = String.join(" & ", terms) + ":*";
        var params = new MapSqlParameterSource("prefix", prefix);
        List<ProductCard> products = listing.cards(
                "p.search_vector @@ to_tsquery('portuguese', :prefix)",
                "ts_rank(p.search_vector, to_tsquery('portuguese', :prefix)) DESC, p.sales_count DESC",
                params, 5, 0);
        List<Crumb> categories = jdbc.query("""
                SELECT name, slug_path FROM category
                 WHERE active AND lower(unaccent(name)) LIKE '%' || :term || '%'
                 ORDER BY depth, sort_order LIMIT 3
                """, new MapSqlParameterSource("term", terms[terms.length - 1]),
                (rs, n) -> new Crumb(rs.getString(1), rs.getString(2)));
        return new Suggestions(products, categories);
    }

    @Async
    @EventListener
    void log(SearchLogged event) {
        jdbc.update("INSERT INTO search_log (term, results) VALUES (:term, :results)",
                new MapSqlParameterSource("term", event.term()).addValue("results", event.results()));
    }

    /** SKU exato (do produto ou de uma variante) leva direto à página do produto. */
    private String exactSku(String q) {
        String sku = q.toUpperCase(Locale.ROOT);
        if (!sku.matches("[A-Z0-9]+(-[A-Z0-9]+)*")) return null;
        return jdbc.queryForList("""
                SELECT p.slug FROM product p
                 WHERE p.status = 'ACTIVE'
                   AND (p.base_sku = :sku OR EXISTS (SELECT 1 FROM product_variant v WHERE v.product_id = p.id AND v.sku = :sku))
                 LIMIT 1
                """, new MapSqlParameterSource("sku", sku), String.class).stream().findFirst().orElse(null);
    }

    /** Troca cada termo pela palavra mais parecida dos nomes de produto ("camizeta" -> "camiseta"). */
    private String correct(String[] terms) {
        if (terms.length == 0) return null;
        String[] fixed = Arrays.stream(terms).map(term -> jdbc.queryForList("""
                SELECT w FROM (SELECT DISTINCT unnest(string_to_array(search_name, ' ')) AS w
                                 FROM product WHERE status = 'ACTIVE') words
                 WHERE length(w) > 2 AND similarity(w, :term) > 0.3
                 ORDER BY similarity(w, :term) DESC LIMIT 1
                """, new MapSqlParameterSource("term", term), String.class).stream().findFirst().orElse(term))
                .toArray(String[]::new);
        return String.join(" ", fixed);
    }

    private static ListingFilter withQuery(ListingFilter f, String q) {
        return new ListingFilter(f.category(), f.collections(), f.sizes(), f.colors(), f.minPrice(), f.maxPrice(),
                f.inStock(), f.onSale(), f.genders(), f.sort(), f.page(), f.pageSize(), q);
    }

    private static boolean hasFilters(ListingFilter f) {
        return f.category() != null || !f.collections().isEmpty() || !f.sizes().isEmpty() || !f.colors().isEmpty()
                || f.minPrice() != null || f.maxPrice() != null || f.inStock() || f.onSale() || !f.genders().isEmpty();
    }

    static String clean(String raw) {
        String q = raw == null ? "" : raw.replaceAll("\\p{Cntrl}", " ").replaceAll("\\s+", " ").trim();
        if (q.length() < 2 || q.length() > 100) {
            throw new BusinessException(ErrorCode.INVALID_FILTER, "A busca deve ter entre 2 e 100 caracteres");
        }
        return q;
    }

    /** Termos só com [a-z0-9], sem acento: seguros para to_tsquery (que tem sintaxe própria). */
    static String[] terms(String q) {
        String ascii = Normalizer.normalize(q, Normalizer.Form.NFD).replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT);
        return Arrays.stream(ascii.split("[^a-z0-9]+")).filter(t -> !t.isEmpty()).limit(6).toArray(String[]::new);
    }
}
