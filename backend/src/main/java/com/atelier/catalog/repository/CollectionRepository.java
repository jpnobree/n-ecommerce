package com.atelier.catalog.repository;

import com.atelier.catalog.domain.Collection;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface CollectionRepository extends JpaRepository<Collection, Long> {

    boolean existsBySlug(String slug);

    List<Collection> findAllByOrderBySortOrderAscNameAsc();

    /** Coleções visíveis na loja: ativas e dentro da vigência. */
    @Query("""
            select c from Collection c
             where c.active = true and (c.startsAt is null or c.startsAt <= :now) and (c.endsAt is null or c.endsAt > :now)
             order by c.sortOrder, c.name
            """)
    List<Collection> findVisible(Instant now);

    @Query("""
            select c from Collection c
             where c.slug = :slug and c.active = true
               and (c.startsAt is null or c.startsAt <= :now) and (c.endsAt is null or c.endsAt > :now)
            """)
    Optional<Collection> findVisibleBySlug(String slug, Instant now);
}
