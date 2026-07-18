package com.fitback.backend.domain.trend.repository;

import com.fitback.backend.domain.trend.entity.TrendContent;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TrendContentRepository extends JpaRepository<TrendContent, Long> {

    List<TrendContent> findAllByOrderByCreatedAtDescIdDesc(Pageable pageable);

    @Query("""
            SELECT trend
            FROM TrendContent trend
            WHERE trend.createdAt < :cursorCreatedAt
               OR (trend.createdAt = :cursorCreatedAt AND trend.id < :cursorId)
            ORDER BY trend.createdAt DESC, trend.id DESC
            """)
    List<TrendContent> findNextPage(
            @Param("cursorCreatedAt") LocalDateTime cursorCreatedAt,
            @Param("cursorId") Long cursorId,
            Pageable pageable
    );
}
