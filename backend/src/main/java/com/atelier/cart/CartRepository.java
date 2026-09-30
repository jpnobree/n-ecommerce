package com.atelier.cart;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

interface CartRepository extends JpaRepository<Cart, UUID> {

    Optional<Cart> findByUserId(Long userId);

    Optional<Cart> findByTokenHash(String tokenHash);

    /** Checkout: cotações simultâneas do mesmo carrinho (duplo clique) passam uma de cada vez. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from Cart c where c.userId = :userId")
    Optional<Cart> lockByUserId(Long userId);

    /** Convidados inativos há 30 dias (PRD 7.2). */
    @Modifying
    @Query("delete from Cart c where c.userId is null and c.updatedAt < :before")
    int deleteGuestsInactiveSince(Instant before);
}
