package com.atelier.cart;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.UUID;

/** Carrinho no servidor: do usuário logado (userId) ou de convidado (hash do token do navegador). */
@Entity
@Table(name = "cart")
public class Cart {

    @Id
    public UUID id;
    public Long userId;
    public String tokenHash;
    public Long couponId;
    public String shippingPostalCode;
    public String shippingOption;

    @Version
    public int version;

    @CreationTimestamp
    public Instant createdAt;

    @UpdateTimestamp
    public Instant updatedAt;
}
