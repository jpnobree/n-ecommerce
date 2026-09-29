package com.atelier.content;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.List;

public interface BannerRepository extends JpaRepository<Banner, Long> {

    List<Banner> findAllByOrderByPositionAscSortOrderAsc();

    @Query("""
            select b from Banner b
             where b.active = true and (b.startsAt is null or b.startsAt <= :now) and (b.endsAt is null or b.endsAt > :now)
             order by b.position, b.sortOrder, b.id
            """)
    List<Banner> findVisible(Instant now);
}
