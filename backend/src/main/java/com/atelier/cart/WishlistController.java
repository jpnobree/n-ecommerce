package com.atelier.cart;

import com.atelier.catalog.api.dto.ListingDtos.ProductCard;
import com.atelier.catalog.service.ProductListing;
import com.atelier.shared.error.BusinessException;
import com.atelier.shared.error.ErrorCode;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** Favoritos por produto (PRD, seção 14.1). Só logado; idempotente. */
@RestController
@RequestMapping("/api/me/wishlist")
public class WishlistController {

    static final int MAX_ITEMS = 200;

    private final NamedParameterJdbcTemplate jdbc;
    private final ProductListing listing;

    WishlistController(NamedParameterJdbcTemplate jdbc, ProductListing listing) {
        this.jdbc = jdbc;
        this.listing = listing;
    }

    /** Mais recentes primeiro; produtos que saíram do ar somem da lista (continuam salvos). */
    @GetMapping
    @Transactional(readOnly = true)
    List<ProductCard> list(@AuthenticationPrincipal Jwt jwt) {
        return listing.cardsByIds(ids(jwt));
    }

    /** Para marcar os corações nas listagens sem uma chamada por produto. */
    @GetMapping("/ids")
    List<Long> ids(@AuthenticationPrincipal Jwt jwt) {
        return jdbc.queryForList("SELECT product_id FROM wishlist_item WHERE user_id = :user ORDER BY created_at DESC",
                new MapSqlParameterSource("user", userId(jwt)), Long.class);
    }

    @PutMapping("/{productId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    void add(@AuthenticationPrincipal Jwt jwt, @PathVariable long productId) {
        var params = new MapSqlParameterSource("user", userId(jwt)).addValue("product", productId);
        Long count = jdbc.queryForObject("SELECT count(*) FROM wishlist_item WHERE user_id = :user", params, Long.class);
        if (count != null && count >= MAX_ITEMS) throw new BusinessException(ErrorCode.WISHLIST_LIMIT);
        int inserted = jdbc.update("""
                INSERT INTO wishlist_item (user_id, product_id)
                SELECT :user, id FROM product WHERE id = :product AND status = 'ACTIVE'
                ON CONFLICT DO NOTHING
                """, params);
        if (inserted == 0 && !exists(params)) throw new BusinessException(ErrorCode.NOT_FOUND, "Produto não encontrado");
    }

    @DeleteMapping("/{productId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void remove(@AuthenticationPrincipal Jwt jwt, @PathVariable long productId) {
        jdbc.update("DELETE FROM wishlist_item WHERE user_id = :user AND product_id = :product",
                new MapSqlParameterSource("user", userId(jwt)).addValue("product", productId));
    }

    private boolean exists(MapSqlParameterSource params) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM wishlist_item WHERE user_id = :user AND product_id = :product)", params, Boolean.class));
    }

    private static long userId(Jwt jwt) {
        return Long.parseLong(jwt.getSubject());
    }
}
