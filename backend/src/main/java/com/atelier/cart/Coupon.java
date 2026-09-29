package com.atelier.cart;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

/** Cupom (PRD, seção 13). Categoria/produto vazios = vale para tudo. */
@Entity
@Table(name = "coupon")
public class Coupon {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(columnDefinition = "citext")
    public String code;
    public String description;

    @Enumerated(EnumType.STRING)
    public Pricing.DiscountType type;

    /** Percentual (1–100) ou centavos, conforme o tipo. */
    public long value;
    public Long maxDiscountAmount;
    public Long maxShippingDiscount;
    public Long minOrderAmount;
    public boolean firstOrderOnly;
    public boolean excludeSaleItems;
    public Instant startsAt;
    public Instant endsAt;
    public Integer usageLimit;
    public Integer usageLimitPerUser;
    public boolean active = true;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "coupon_category", joinColumns = @JoinColumn(name = "coupon_id"))
    @Column(name = "category_id")
    public Set<Long> categoryIds = new HashSet<>();

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "coupon_product", joinColumns = @JoinColumn(name = "coupon_id"))
    @Column(name = "product_id")
    public Set<Long> productIds = new HashSet<>();

    @Version
    public int version;

    @CreationTimestamp
    public Instant createdAt;

    @UpdateTimestamp
    public Instant updatedAt;

    Pricing.Discount toDiscount() {
        return new Pricing.Discount(type, value, maxDiscountAmount, maxShippingDiscount, minOrderAmount);
    }
}
