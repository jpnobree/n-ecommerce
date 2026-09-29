package com.atelier.cart;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

interface CartItemRepository extends JpaRepository<CartItem, Long> {

    List<CartItem> findByCartIdOrderByCreatedAtAsc(UUID cartId);

    Optional<CartItem> findByIdAndCartId(Long id, UUID cartId);

    Optional<CartItem> findByCartIdAndVariantId(UUID cartId, Long variantId);
}
