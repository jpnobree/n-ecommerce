package com.atelier.catalog.service;

import com.atelier.catalog.api.dto.AttributeDtos.*;
import com.atelier.catalog.domain.Collection;
import com.atelier.catalog.domain.Color;
import com.atelier.catalog.domain.Size;
import com.atelier.catalog.repository.CollectionRepository;
import com.atelier.catalog.repository.ColorRepository;
import com.atelier.catalog.repository.SizeRepository;
import com.atelier.shared.error.BusinessException;
import com.atelier.shared.error.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Coleções, cores e tamanhos. Unicidade de nome/slug garantida pelo banco (violação vira 409).
 * Excluir cor/tamanho em uso por variantes também é barrado pela FK.
 */
@Service
public class AttributeService {

    private final CollectionRepository collections;
    private final ColorRepository colors;
    private final SizeRepository sizes;

    AttributeService(CollectionRepository collections, ColorRepository colors, SizeRepository sizes) {
        this.collections = collections;
        this.colors = colors;
        this.sizes = sizes;
    }

    // ---- coleções ----

    @Transactional
    public Collection createCollection(CollectionRequest req) {
        var c = new Collection();
        c.slug = req.slug() != null ? req.slug() : Slugs.unique(Slugs.of(req.name()), collections::existsBySlug);
        if (req.slug() != null && collections.existsBySlug(req.slug())) throw new BusinessException(ErrorCode.SLUG_TAKEN);
        apply(c, req);
        return collections.save(c);
    }

    @Transactional
    public Collection updateCollection(Long id, CollectionRequest req) {
        Collection c = collections.findById(id).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        if (req.slug() != null && !req.slug().equals(c.slug)) {
            if (collections.existsBySlug(req.slug())) throw new BusinessException(ErrorCode.SLUG_TAKEN);
            c.slug = req.slug();
        }
        apply(c, req);
        return c;
    }

    @Transactional
    public void deleteCollection(Long id) {
        collections.deleteById(id);
    }

    private static void apply(Collection c, CollectionRequest req) {
        c.name = req.name().trim();
        c.description = req.description();
        c.startsAt = req.startsAt();
        c.endsAt = req.endsAt();
        if (req.active() != null) c.active = req.active();
        if (req.sortOrder() != null) c.sortOrder = req.sortOrder();
        c.metaTitle = req.metaTitle();
        c.metaDescription = req.metaDescription();
    }

    // ---- cores ----

    @Transactional
    public Color saveColor(Long id, ColorRequest req) {
        Color c = id == null ? new Color() : colors.findById(id).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        c.name = req.name().trim();
        c.slug = req.slug() != null ? req.slug() : Slugs.of(req.name());
        c.hex = req.hex() == null ? null : req.hex().toUpperCase();
        return colors.saveAndFlush(c);
    }

    @Transactional
    public void deleteColor(Long id) {
        colors.deleteById(id);
        colors.flush();
    }

    // ---- tamanhos ----

    @Transactional
    public Size saveSize(Long id, SizeRequest req) {
        Size s = id == null ? new Size() : sizes.findById(id).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        s.name = req.name().trim();
        s.sizeGroup = req.sizeGroup().trim().toUpperCase();
        s.slug = req.slug() != null ? req.slug() : Slugs.of(req.name());
        if (req.sortOrder() != null) s.sortOrder = req.sortOrder();
        return sizes.saveAndFlush(s);
    }

    @Transactional
    public void deleteSize(Long id) {
        sizes.deleteById(id);
        sizes.flush();
    }
}
