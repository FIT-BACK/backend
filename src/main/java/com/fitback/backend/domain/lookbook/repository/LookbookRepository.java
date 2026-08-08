package com.fitback.backend.domain.lookbook.repository;

import com.fitback.backend.domain.lookbook.entity.Lookbook;
import com.fitback.backend.domain.lookbook.entity.LookbookModerationStatus;
import com.fitback.backend.domain.member.entity.Member;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface LookbookRepository extends JpaRepository<Lookbook, Long> {

    // 관련도 정렬과 다음 커서 계산에 필요한 룩북 정보
    interface RelatedLookbookRank {

        Long getLookbookId();

        Long getRelevanceScore();

        LocalDateTime getCreatedAt();
    }

    long countByMemberIdAndDeletedAtIsNull(Long memberId);

    @Query("""
            select count(lookbook) > 0
            from Lookbook lookbook
            where lookbook.deletedAt is null
              and (
                    lookbook.originalImage.id = :imageId
                    or lookbook.matchedImage.id = :imageId
              )
            """)
    boolean existsActiveImageReference(@Param("imageId") String imageId);

    @EntityGraph(
            attributePaths = {
                "member", "matchedProduct", "matchedImage", "originalImage"
            }
    )
    Optional<Lookbook> findByIdAndDeletedAtIsNull(Long id);

    @EntityGraph(
            attributePaths = {
                "member", "matchedProduct", "matchedImage", "originalImage"
            }
    )
    List<Lookbook> findAllByDeletedAtIsNullAndModerationStatusOrderByCreatedAtDescIdDesc(
            LookbookModerationStatus moderationStatus,
            Pageable pageable
    );

    // 첫 페이지의 룩북을 공통 태그 가중치 합계 순으로 조회
    @Query("""
            SELECT lookbook.id AS lookbookId,
                   SUM(trendTag.relevanceWeight) AS relevanceScore,
                   lookbook.createdAt AS createdAt
            FROM Lookbook lookbook
            JOIN LookbookTag lookbookTag ON lookbookTag.lookbook = lookbook
            JOIN TrendTag trendTag ON trendTag.tag = lookbookTag.tag
            WHERE trendTag.trend.id = :trendId
              AND lookbook.deletedAt IS NULL
              AND lookbook.moderationStatus = :moderationStatus
            GROUP BY lookbook.id, lookbook.createdAt
            ORDER BY SUM(trendTag.relevanceWeight) DESC,
                     lookbook.createdAt DESC,
                     lookbook.id DESC
            """)
    List<RelatedLookbookRank> findRelatedLookbookRanks(
            @Param("trendId") Long trendId,
            @Param("moderationStatus") LookbookModerationStatus moderationStatus,
            Pageable pageable
    );

    // 삭제·숨김 상태와 무관하게 다음 페이지 기준이 되는 커서 룩북의 정렬값 복원
    @Query("""
            SELECT lookbook.id AS lookbookId,
                   SUM(trendTag.relevanceWeight) AS relevanceScore,
                   lookbook.createdAt AS createdAt
            FROM Lookbook lookbook
            JOIN LookbookTag lookbookTag ON lookbookTag.lookbook = lookbook
            JOIN TrendTag trendTag ON trendTag.tag = lookbookTag.tag
            WHERE trendTag.trend.id = :trendId
              AND lookbook.id = :lookbookId
            GROUP BY lookbook.id, lookbook.createdAt
            """)
    Optional<RelatedLookbookRank> findRelatedLookbookRank(
            @Param("trendId") Long trendId,
            @Param("lookbookId") Long lookbookId
    );

    // 관련도 점수, 생성 시간, id 순서로 커서 이후 룩북 조회
    @Query("""
            SELECT lookbook.id AS lookbookId,
                   SUM(trendTag.relevanceWeight) AS relevanceScore,
                   lookbook.createdAt AS createdAt
            FROM Lookbook lookbook
            JOIN LookbookTag lookbookTag ON lookbookTag.lookbook = lookbook
            JOIN TrendTag trendTag ON trendTag.tag = lookbookTag.tag
            WHERE trendTag.trend.id = :trendId
              AND lookbook.deletedAt IS NULL
              AND lookbook.moderationStatus = :moderationStatus
            GROUP BY lookbook.id, lookbook.createdAt
            HAVING SUM(trendTag.relevanceWeight) < :cursorScore
                OR (SUM(trendTag.relevanceWeight) = :cursorScore
                    AND lookbook.createdAt < :cursorCreatedAt)
                OR (SUM(trendTag.relevanceWeight) = :cursorScore
                    AND lookbook.createdAt = :cursorCreatedAt
                    AND lookbook.id < :cursorId)
            ORDER BY SUM(trendTag.relevanceWeight) DESC,
                     lookbook.createdAt DESC,
                     lookbook.id DESC
            """)
    List<RelatedLookbookRank> findNextRelatedLookbookRanks(
            @Param("trendId") Long trendId,
            @Param("moderationStatus") LookbookModerationStatus moderationStatus,
            @Param("cursorScore") Long cursorScore,
            @Param("cursorCreatedAt") LocalDateTime cursorCreatedAt,
            @Param("cursorId") Long cursorId,
            Pageable pageable
    );

    // 관련도 조회 결과 중 현재 노출 가능한 룩북과 응답 생성에 필요한 연관관계 일괄 조회
    @EntityGraph(
            attributePaths = {
                "member", "matchedProduct", "matchedImage", "originalImage"
            }
    )
    List<Lookbook> findAllByIdInAndDeletedAtIsNullAndModerationStatus(
            List<Long> lookbookIds,
            LookbookModerationStatus moderationStatus
    );

    @EntityGraph(
            attributePaths = {
                "member", "matchedProduct", "matchedImage", "originalImage"
            }
    )
    @Query("""
            SELECT lookbook
            FROM Lookbook lookbook
            WHERE lookbook.deletedAt IS NULL
              AND lookbook.moderationStatus = :moderationStatus
              AND (
                  LOCATE(:keyword, LOWER(COALESCE(lookbook.comment, ''))) > 0
                  OR LOCATE(:keyword, LOWER(lookbook.member.nickname)) > 0
                  OR EXISTS (
                      SELECT lookbookTag.id
                      FROM LookbookTag lookbookTag
                      WHERE lookbookTag.lookbook = lookbook
                        AND LOCATE(:keyword, LOWER(lookbookTag.tag.tagName)) > 0
                  )
              )
            ORDER BY lookbook.createdAt DESC, lookbook.id DESC
            """)
    List<Lookbook> searchByKeyword(
            @Param("keyword") String keyword,
            @Param("moderationStatus") LookbookModerationStatus moderationStatus,
            Pageable pageable
    );

    @EntityGraph(
            attributePaths = {
                "member", "matchedProduct", "matchedImage", "originalImage"
            }
    )
    @Query("""
            SELECT lookbook
            FROM Lookbook lookbook
            WHERE lookbook.deletedAt IS NULL
              AND lookbook.moderationStatus = :moderationStatus
              AND EXISTS (
                  SELECT lookbookTag.id
                  FROM LookbookTag lookbookTag
                  WHERE lookbookTag.lookbook = lookbook
                    AND lookbookTag.tag.tagName = :tagName
              )
            ORDER BY lookbook.createdAt DESC, lookbook.id DESC
            """)
    List<Lookbook> findAllByTagName(
            @Param("tagName") String tagName,
            @Param("moderationStatus") LookbookModerationStatus moderationStatus,
            Pageable pageable
    );

    @Query("""
            SELECT lookbook
            FROM Lookbook lookbook
            WHERE lookbook.id = :lookbookId
              AND EXISTS (
                  SELECT lookbookTag.id
                  FROM LookbookTag lookbookTag
                  WHERE lookbookTag.lookbook = lookbook
                    AND lookbookTag.tag.tagName = :tagName
              )
            """)
    Optional<Lookbook> findCursorByIdAndTagName(
            @Param("lookbookId") Long lookbookId,
            @Param("tagName") String tagName
    );

    @EntityGraph(
            attributePaths = {
                "member", "matchedProduct", "matchedImage", "originalImage"
            }
    )
    @Query("""
            SELECT lookbook
            FROM Lookbook lookbook
            WHERE lookbook.deletedAt IS NULL
              AND lookbook.moderationStatus = :moderationStatus
              AND (lookbook.createdAt < :cursorCreatedAt
               OR (lookbook.createdAt = :cursorCreatedAt AND lookbook.id < :cursorId))
            ORDER BY lookbook.createdAt DESC, lookbook.id DESC
            """)
    List<Lookbook> findNextPage(
            @Param("moderationStatus") LookbookModerationStatus moderationStatus,
            @Param("cursorCreatedAt") LocalDateTime cursorCreatedAt,
            @Param("cursorId") Long cursorId,
            Pageable pageable
    );

    @EntityGraph(
            attributePaths = {
                "member", "matchedProduct", "matchedImage", "originalImage"
            }
    )
    @Query("""
            SELECT lookbook
            FROM Lookbook lookbook
            WHERE lookbook.deletedAt IS NULL
              AND lookbook.moderationStatus = :moderationStatus
              AND EXISTS (
                  SELECT lookbookTag.id
                  FROM LookbookTag lookbookTag
                  WHERE lookbookTag.lookbook = lookbook
                    AND lookbookTag.tag.tagName = :tagName
              )
              AND (lookbook.createdAt < :cursorCreatedAt
               OR (lookbook.createdAt = :cursorCreatedAt AND lookbook.id < :cursorId))
            ORDER BY lookbook.createdAt DESC, lookbook.id DESC
            """)
    List<Lookbook> findNextPageByTagName(
            @Param("tagName") String tagName,
            @Param("moderationStatus") LookbookModerationStatus moderationStatus,
            @Param("cursorCreatedAt") LocalDateTime cursorCreatedAt,
            @Param("cursorId") Long cursorId,
            Pageable pageable
    );

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE Lookbook lookbook
            SET lookbook.likeCount = lookbook.likeCount + 1
            WHERE lookbook.id = :lookbookId
              AND lookbook.deletedAt IS NULL
            """)
    int incrementLikeCount(@Param("lookbookId") Long lookbookId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE Lookbook lookbook
            SET lookbook.likeCount = lookbook.likeCount - 1
            WHERE lookbook.id = :lookbookId
              AND lookbook.deletedAt IS NULL
            """)
    int decrementLikeCount(@Param("lookbookId") Long lookbookId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE Lookbook lookbook
            SET lookbook.likeCount = lookbook.likeCount - 1
            WHERE lookbook.id IN :lookbookIds
              AND lookbook.deletedAt IS NULL
            """)
    int decrementLikeCountByIds(@Param("lookbookIds") List<Long> lookbookIds);

    @Query("""
            SELECT lookbook.likeCount
            FROM Lookbook lookbook
            WHERE lookbook.id = :lookbookId
              AND lookbook.deletedAt IS NULL
            """)
    Optional<Integer> findLikeCountByIdAndDeletedAtIsNull(@Param("lookbookId") Long lookbookId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE Lookbook lookbook
            SET lookbook.reportCount = lookbook.reportCount + 1
            WHERE lookbook.id = :lookbookId
              AND lookbook.deletedAt IS NULL
            """)
    int incrementReportCount(@Param("lookbookId") Long lookbookId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE Lookbook lookbook
            SET lookbook.moderationStatus = :autoHiddenStatus,
                lookbook.autoHiddenAt = CURRENT_TIMESTAMP
            WHERE lookbook.id = :lookbookId
              AND lookbook.deletedAt IS NULL
              AND lookbook.reportCount >= :reportThreshold
              AND lookbook.moderationStatus = :visibleStatus
            """)
    int autoHideReportedLookbook(
            @Param("lookbookId") Long lookbookId,
            @Param("reportThreshold") int reportThreshold,
            @Param("visibleStatus") LookbookModerationStatus visibleStatus,
            @Param("autoHiddenStatus") LookbookModerationStatus autoHiddenStatus
    );

    //회원 탈퇴 시 해당 회원의 룩북을 탈퇴 회원 계정으로 익명화(재지정)
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE Lookbook lookbook
            SET lookbook.member = :withdrawnMember
            WHERE lookbook.member.id = :memberId
            """)
    void reassignToWithdrawnMember(
            @Param("memberId") Long memberId,
            @Param("withdrawnMember") Member withdrawnMember
    );
}
