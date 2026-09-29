package com.atelier.catalog.repository;

import com.atelier.catalog.domain.ProductVariant;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ProductVariantRepository extends JpaRepository<ProductVariant, Long> {

    List<ProductVariant> findByProductIdOrderByColorIdAscSizeIdAsc(Long productId);

    Optional<ProductVariant> findByIdAndProductId(Long id, Long productId);

    boolean existsByProductIdAndActiveTrue(Long productId);

    boolean existsByProductId(Long productId);
}
