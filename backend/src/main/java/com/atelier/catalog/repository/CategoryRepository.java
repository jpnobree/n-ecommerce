package com.atelier.catalog.repository;

import com.atelier.catalog.domain.Category;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface CategoryRepository extends JpaRepository<Category, Long> {

    List<Category> findAllByOrderByDepthAscSortOrderAscNameAsc();

    List<Category> findByActiveTrueOrderByDepthAscSortOrderAscNameAsc();

    Optional<Category> findBySlugPathAndActiveTrue(String slugPath);

    boolean existsBySlugPath(String slugPath);

    boolean existsByParentId(Long parentId);

    /** Maior profundidade relativa da subárvore (0 = sem filhos). */
    @Query(value = """
            SELECT coalesce(max(depth), :depth) - :depth FROM category
             WHERE slug_path = :slugPath OR slug_path LIKE :slugPath || '/%'
            """, nativeQuery = true)
    int subtreeHeight(String slugPath, int depth);

    /** Renomear/mover: reescreve o prefixo do caminho e a profundidade de toda a subárvore. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            UPDATE category SET slug_path = :newPath || substring(slug_path from length(:oldPath) + 1),
                                depth = depth + :depthDelta
             WHERE slug_path LIKE :oldPath || '/%'
            """, nativeQuery = true)
    void rewriteDescendants(String oldPath, String newPath, int depthDelta);
}
