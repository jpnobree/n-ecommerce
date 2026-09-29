package com.atelier.catalog.repository;

import com.atelier.catalog.domain.SizeChart;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SizeChartRepository extends JpaRepository<SizeChart, Long> {

    List<SizeChart> findAllByOrderByNameAsc();
}
