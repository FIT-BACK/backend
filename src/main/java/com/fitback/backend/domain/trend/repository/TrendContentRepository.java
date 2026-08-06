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

    // 회원 관심 태그와 일치하는 트렌드를 앞에 배치하고 각 그룹은 최신순 조회
    @Query("""
            SELECT trend
            FROM TrendContent trend
            ORDER BY CASE WHEN EXISTS (
                SELECT trendTag.id
                FROM TrendTag trendTag
                WHERE trendTag.trend = trend
                  AND EXISTS (
                      SELECT memberTag.id
                      FROM MemberTag memberTag
                      WHERE memberTag.member.id = :memberId
                        AND memberTag.tag = trendTag.tag
                  )
            ) THEN 0 ELSE 1 END,
            trend.createdAt DESC,
            trend.id DESC
            """)
    List<TrendContent> findAllPrioritizingMemberTags(
            @Param("memberId") Long memberId,
            Pageable pageable
    );

    @Query("""
            SELECT trend
            FROM TrendContent trend
            WHERE LOCATE(:keyword, LOWER(trend.title)) > 0
               OR LOCATE(:keyword, LOWER(CAST(COALESCE(trend.description, '') AS String))) > 0
               OR EXISTS (
                  SELECT trendTag.id
                  FROM TrendTag trendTag
                  WHERE trendTag.trend = trend
                    AND LOCATE(:keyword, LOWER(trendTag.tag.tagName)) > 0
               )
            ORDER BY trend.createdAt DESC, trend.id DESC
            """)
    List<TrendContent> searchByKeyword(
            @Param("keyword") String keyword,
            Pageable pageable
    );

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

    // 개인화 정렬 그룹과 최신순 조건을 함께 적용하여 커서 이후 조회
    @Query("""
            SELECT trend
            FROM TrendContent trend
            WHERE (
                :cursorMatchesInterest = true
                AND (
                    NOT EXISTS (
                        SELECT trendTag.id
                        FROM TrendTag trendTag
                        WHERE trendTag.trend = trend
                          AND EXISTS (
                              SELECT memberTag.id
                              FROM MemberTag memberTag
                              WHERE memberTag.member.id = :memberId
                                AND memberTag.tag = trendTag.tag
                          )
                    )
                    OR (
                        EXISTS (
                            SELECT trendTag.id
                            FROM TrendTag trendTag
                            WHERE trendTag.trend = trend
                              AND EXISTS (
                                  SELECT memberTag.id
                                  FROM MemberTag memberTag
                                  WHERE memberTag.member.id = :memberId
                                    AND memberTag.tag = trendTag.tag
                              )
                        )
                        AND (
                            trend.createdAt < :cursorCreatedAt
                            OR (trend.createdAt = :cursorCreatedAt AND trend.id < :cursorId)
                        )
                    )
                )
            ) OR (
                :cursorMatchesInterest = false
                AND NOT EXISTS (
                    SELECT trendTag.id
                    FROM TrendTag trendTag
                    WHERE trendTag.trend = trend
                      AND EXISTS (
                          SELECT memberTag.id
                          FROM MemberTag memberTag
                          WHERE memberTag.member.id = :memberId
                            AND memberTag.tag = trendTag.tag
                      )
                )
                AND (
                    trend.createdAt < :cursorCreatedAt
                    OR (trend.createdAt = :cursorCreatedAt AND trend.id < :cursorId)
                )
            )
            ORDER BY CASE WHEN EXISTS (
                SELECT trendTag.id
                FROM TrendTag trendTag
                WHERE trendTag.trend = trend
                  AND EXISTS (
                      SELECT memberTag.id
                      FROM MemberTag memberTag
                      WHERE memberTag.member.id = :memberId
                        AND memberTag.tag = trendTag.tag
                  )
            ) THEN 0 ELSE 1 END,
            trend.createdAt DESC,
            trend.id DESC
            """)
    List<TrendContent> findNextPagePrioritizingMemberTags(
            @Param("memberId") Long memberId,
            @Param("cursorMatchesInterest") boolean cursorMatchesInterest,
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
