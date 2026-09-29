package com.atelier.catalog.repository;

import com.atelier.catalog.domain.Color;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ColorRepository extends JpaRepository<Color, Long> {

    List<Color> findAllByOrderByNameAsc();
}
