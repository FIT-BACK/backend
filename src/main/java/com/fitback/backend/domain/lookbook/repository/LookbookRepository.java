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

    long countByMemberIdAndDeletedAtIsNull(Long memberId);

    @EntityGraph(attributePaths = "member")
    Optional<Lookbook> findByIdAndDeletedAtIsNull(Long id);

    @EntityGraph(attributePaths = "member")
    List<Lookbook> findAllByDeletedAtIsNullAndModerationStatusOrderByCreatedAtDescIdDesc(
            LookbookModerationStatus moderationStatus,
            Pageable pageable
    );

    @EntityGraph(attributePaths = "member")
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

    @EntityGraph(attributePaths = "member")
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

    @EntityGraph(attributePaths = "member")
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
