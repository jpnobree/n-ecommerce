package com.atelier.catalog.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

@Entity
@Table(name = "product")
public class Product {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    public String name;
    public String slug;
    public String baseSku;
    public String description;
    public String material;
    public String careInstructions;

    @Enumerated(EnumType.STRING)
    public Gender gender;

    public Long mainCategoryId;
    public long basePrice;

    @Enumerated(EnumType.STRING)
    public ProductStatus status = ProductStatus.DRAFT;

    public boolean featured;
    public boolean isNew;
    public int weightGrams;
    public String metaTitle;
    public String metaDescription;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "product_collection", joinColumns = @JoinColumn(name = "product_id"))
    @Column(name = "collection_id")
    public Set<Long> collectionIds = new HashSet<>();

    /** Mantidos por ProductDenormalizer; somente leitura aqui. */
    @Column(insertable = false, updatable = false)
    public Long minBasePrice;
    @Column(insertable = false, updatable = false)
    public Long minEffectivePrice;
    @Column(insertable = false, updatable = false)
    public boolean hasStock;
    @Column(insertable = false, updatable = false)
    public int salesCount;

    public Instant publishedAt;

    @Version
    public int version;

    @CreationTimestamp
    public Instant createdAt;

    @UpdateTimestamp
    public Instant updatedAt;
}
