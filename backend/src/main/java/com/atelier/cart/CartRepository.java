package com.atelier.cart;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

interface CartRepository extends JpaRepository<Cart, UUID> {

    Optional<Cart> findByUserId(Long userId);

    Optional<Cart> findByTokenHash(String tokenHash);

    /** Convidados inativos há 30 dias (PRD 7.2). */
    @Modifying
    @Query("delete from Cart c where c.userId is null and c.updatedAt < :before")
    int deleteGuestsInactiveSince(Instant before);
}
