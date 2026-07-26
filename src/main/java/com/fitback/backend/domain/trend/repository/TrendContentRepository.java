package com.fitback.backend.domain.trend.repository;

import com.fitback.backend.domain.trend.entity.TrendContent;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
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

    @Query("""
            SELECT trend
            FROM TrendContent trend
            WHERE EXISTS (
                SELECT trendTag.id
                FROM TrendTag trendTag
                WHERE trendTag.trend = trend
                  AND trendTag.tag.tagName = :tagName
            )
            ORDER BY trend.createdAt DESC, trend.id DESC
            """)
    List<TrendContent> findAllByTagName(@Param("tagName") String tagName, Pageable pageable);

    @Query("""
            SELECT trend
            FROM TrendContent trend
            WHERE trend.id = :trendId
              AND EXISTS (
                  SELECT trendTag.id
                  FROM TrendTag trendTag
                  WHERE trendTag.trend = trend
                    AND trendTag.tag.tagName = :tagName
              )
            """)
    Optional<TrendContent> findCursorByIdAndTagName(
            @Param("trendId") Long trendId,
            @Param("tagName") String tagName
    );

    @Query("""
            SELECT trend
            FROM TrendContent trend
            WHERE EXISTS (
                SELECT trendTag.id
                FROM TrendTag trendTag
                WHERE trendTag.trend = trend
                  AND trendTag.tag.tagName = :tagName
            )
              AND (trend.createdAt < :cursorCreatedAt
               OR (trend.createdAt = :cursorCreatedAt AND trend.id < :cursorId))
            ORDER BY trend.createdAt DESC, trend.id DESC
            """)
    List<TrendContent> findNextPageByTagName(
            @Param("tagName") String tagName,
            @Param("cursorCreatedAt") LocalDateTime cursorCreatedAt,
            @Param("cursorId") Long cursorId,
            Pageable pageable
    );
}
