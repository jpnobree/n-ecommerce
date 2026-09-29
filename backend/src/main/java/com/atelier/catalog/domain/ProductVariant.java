package com.atelier.catalog.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;

/** Unidade vendável (cor × tamanho). Nunca é apagada: desativa-se (histórico de pedidos e estoque). */
@Entity
@Table(name = "product_variant")
public class ProductVariant {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    public Long productId;
    public Long colorId;
    public Long sizeId;
    public String sku;
    public String supplierRef;
    /** Nulo = herda o preço base do produto. */
    public Long price;
    public Long salePrice;
    public Instant saleStartsAt;
    public Instant saleEndsAt;
    public boolean active = true;

    @Version
    public int version;

    @CreationTimestamp
    public Instant createdAt;

    @UpdateTimestamp
    public Instant updatedAt;

    /** Regra única de preço (PRD RN-06): promoção vigente, senão preço da variante, senão preço base. */
    public long effectivePrice(long basePrice, Instant now) {
        boolean saleActive = salePrice != null
                && (saleStartsAt == null || !saleStartsAt.isAfter(now))
                && (saleEndsAt == null || saleEndsAt.isAfter(now));
        if (saleActive) return salePrice;
        return price != null ? price : basePrice;
    }
}
