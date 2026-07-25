package com.fitback.backend.domain.recommendation.repository;

import com.fitback.backend.domain.recommendation.entity.RecommendedItem;
import java.util.List;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RecommendedItemRepository extends JpaRepository<RecommendedItem, Long> {

    @EntityGraph(attributePaths = "product")
    List<RecommendedItem> findByReportIdOrderByCategoryAscRankNoAsc(Long reportId);

    @Modifying
    @Query("delete from RecommendedItem item where item.report.id = :reportId")
    void deleteCurrentSetByReportId(@Param("reportId") Long reportId);
}
