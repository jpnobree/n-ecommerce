package com.atelier.catalog.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;

/** Foto do produto. Associada a uma cor quando é daquela cor (galeria troca ao escolher a cor). */
@Entity
@Table(name = "product_image")
public class ProductImage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    public Long productId;
    public Long colorId;
    public String storageKey;
    public String url;
    public String altText;
    public Integer width;
    public Integer height;
    public int position;
    public boolean isMain;

    @CreationTimestamp
    public Instant createdAt;
}
