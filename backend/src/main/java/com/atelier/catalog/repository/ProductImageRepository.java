package com.atelier.catalog.repository;

import com.atelier.catalog.domain.ProductImage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface ProductImageRepository extends JpaRepository<ProductImage, Long> {

    List<ProductImage> findByProductIdOrderByIsMainDescPositionAscIdAsc(Long productId);

    Optional<ProductImage> findByIdAndProductId(Long id, Long productId);

    long countByProductId(Long productId);

    boolean existsByProductId(Long productId);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update ProductImage i set i.isMain = false where i.productId = :productId and i.isMain = true")
    void clearMain(Long productId);
}
