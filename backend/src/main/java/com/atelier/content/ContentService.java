package com.atelier.content;

import com.atelier.catalog.api.dto.AttributeDtos.CollectionResponse;
import com.atelier.catalog.api.dto.CategoryDtos.CategoryNode;
import com.atelier.catalog.repository.CollectionRepository;
import com.atelier.catalog.service.CategoryService;
import com.atelier.catalog.service.ProductListing;
import com.atelier.content.ContentDtos.*;
import com.atelier.media.StorageProperties;
import com.atelier.shared.error.BusinessException;
import com.atelier.shared.error.ErrorCode;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;

/** Home configurável (PRD 4.1), banners, newsletter e dados do sitemap. */
@Service
public class ContentService {

    private static final int SHELF = 12;

    private final BannerRepository banners;
    private final CategoryService categories;
    private final CollectionRepository collections;
    private final ProductListing listing;
    private final NamedParameterJdbcTemplate jdbc;
    private final StorageProperties storage;
    private final Clock clock;

    ContentService(BannerRepository banners, CategoryService categories, CollectionRepository collections,
                   ProductListing listing, NamedParameterJdbcTemplate jdbc, StorageProperties storage, Clock clock) {
        this.banners = banners;
        this.categories = categories;
        this.collections = collections;
        this.listing = listing;
        this.jdbc = jdbc;
        this.storage = storage;
        this.clock = clock;
    }

    /** Uma resposta com tudo o que a home precisa (uma ida à API; cache de 60 s na CDN). */
    @Transactional(readOnly = true)
    public Home home() {
        Instant now = clock.instant();
        List<BannerResponse> visible = banners.findVisible(now).stream().map(BannerResponse::of).toList();
        List<CategoryNode> featuredCategories = categories.publicTree().stream().filter(CategoryNode::featured).toList();
        List<CollectionResponse> activeCollections = collections.findVisible(now).stream().map(CollectionResponse::of).toList();
        var none = new MapSqlParameterSource();
        return new Home(
                visible.stream().filter(b -> b.position() == Banner.Position.HERO).limit(5).toList(),
                visible.stream().filter(b -> b.position() == Banner.Position.CAMPAIGN).limit(4).toList(),
                visible.stream().filter(b -> b.position() == Banner.Position.STRIP).findFirst().orElse(null),
                featuredCategories,
                activeCollections,
                listing.cards("p.featured AND p.has_stock", "p.published_at DESC", none, SHELF, 0),
                listing.cards("p.has_stock", "p.published_at DESC", new MapSqlParameterSource(), SHELF, 0),
                listing.cards("p.has_stock", "p.sales_count DESC", new MapSqlParameterSource(), SHELF, 0));
    }

    // ---- banners (admin) ----

    @Transactional(readOnly = true)
    public List<Banner> allBanners() {
        return banners.findAllByOrderByPositionAscSortOrderAsc();
    }

    @Transactional
    public Banner saveBanner(Long id, BannerRequest req) {
        Banner b = id == null ? new Banner() : banners.findById(id).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        if (req.position() != Banner.Position.STRIP && req.imageDesktopUrl() == null) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "Banners HERO e CAMPAIGN precisam de imagem");
        }
        b.position = req.position();
        b.title = req.title().trim();
        b.subtitle = req.subtitle();
        b.ctaLabel = req.ctaLabel();
        b.linkUrl = req.linkUrl();
        b.imageDesktopUrl = checkImageUrl(req.imageDesktopUrl());
        b.imageMobileUrl = checkImageUrl(req.imageMobileUrl());
        b.startsAt = req.startsAt();
        b.endsAt = req.endsAt();
        if (req.active() != null) b.active = req.active();
        if (req.sortOrder() != null) b.sortOrder = req.sortOrder();
        return banners.saveAndFlush(b);
    }

    @Transactional
    public void deleteBanner(Long id) {
        banners.deleteById(id);
    }

    /** Só imagens do nosso storage (enviadas por /api/admin/uploads): evita hotlink e conteúdo externo na home. */
    private String checkImageUrl(String url) {
        if (url == null || url.isBlank()) return null;
        if (!url.startsWith(storage.publicBaseUrl().replaceAll("/$", "") + "/") && !url.startsWith("/placeholders/")) {
            throw new BusinessException(ErrorCode.INVALID_URL, "Envie a imagem pelo upload do painel");
        }
        return url;
    }

    // ---- newsletter ----

    /** Idempotente: reinscrever quem saiu reativa; nunca revela se o e-mail já estava na lista. */
    @Transactional
    public void subscribe(String email) {
        jdbc.update("""
                INSERT INTO newsletter_subscriber (email, consent_at) VALUES (:email, now())
                ON CONFLICT (email) DO UPDATE SET consent_at = now(), unsubscribed_at = NULL
                """, new MapSqlParameterSource("email", email.trim().toLowerCase(Locale.ROOT)));
    }

    // ---- sitemap ----

    @Transactional(readOnly = true)
    public List<SitemapEntry> sitemap() {
        return jdbc.query("""
                SELECT '/p/' || slug, updated_at FROM product WHERE status = 'ACTIVE'
                UNION ALL
                SELECT '/c/' || slug_path, updated_at FROM category WHERE active
                UNION ALL
                SELECT '/colecao/' || slug, updated_at FROM collection
                 WHERE active AND (starts_at IS NULL OR starts_at <= now()) AND (ends_at IS NULL OR ends_at > now())
                """, new MapSqlParameterSource(), (rs, n) -> new SitemapEntry(rs.getString(1), rs.getTimestamp(2).toInstant()));
    }
}
