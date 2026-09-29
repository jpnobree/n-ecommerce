package com.atelier.catalog.repository;

import com.atelier.catalog.domain.Product;
import com.atelier.catalog.domain.ProductStatus;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface ProductRepository extends JpaRepository<Product, Long>, JpaSpecificationExecutor<Product> {

    boolean existsBySlug(String slug);

    boolean existsByBaseSku(String baseSku);

    boolean existsByMainCategoryId(Long categoryId);

    /** Busca do admin: nome ou SKU base contém o texto; status opcional. */
    static Specification<Product> adminSearch(String q, ProductStatus status) {
        Specification<Product> spec = (root, query, cb) -> cb.conjunction();
        if (status != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("status"), status));
        }
        if (q != null && !q.isBlank()) {
            String like = "%" + q.trim().toLowerCase().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
            spec = spec.and((root, query, cb) -> cb.or(
                    cb.like(cb.lower(root.get("name")), like, '\\'),
                    cb.like(cb.lower(root.get("baseSku")), like, '\\')));
        }
        return spec;
    }
}
