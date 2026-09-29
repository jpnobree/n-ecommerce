package com.atelier.catalog.repository;

import com.atelier.catalog.domain.Size;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SizeRepository extends JpaRepository<Size, Long> {

    List<Size> findAllByOrderBySizeGroupAscSortOrderAsc();
}
