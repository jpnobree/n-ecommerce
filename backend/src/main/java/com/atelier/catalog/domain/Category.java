package com.atelier.catalog.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;

@Entity
@Table(name = "category")
public class Category {

    /** Até 3 níveis: raiz (0) → subcategoria (1) → sub-subcategoria (2). */
    public static final int MAX_DEPTH = 2;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    public Long parentId;
    public String name;
    public String slug;
    public String slugPath;
    public short depth;
    public String description;
    public int sortOrder;
    public boolean active = true;
    public boolean featured;
    public String metaTitle;
    public String metaDescription;

    @Version
    public int version;

    @CreationTimestamp
    public Instant createdAt;

    @UpdateTimestamp
    public Instant updatedAt;
}
