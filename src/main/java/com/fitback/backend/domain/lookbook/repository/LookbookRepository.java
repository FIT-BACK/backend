package com.fitback.backend.domain.lookbook.repository;

import com.fitback.backend.domain.lookbook.entity.Lookbook;
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

    @EntityGraph(attributePaths = "member")
    Optional<Lookbook> findByIdAndDeletedAtIsNull(Long id);

    @EntityGraph(attributePaths = "member")
    List<Lookbook> findAllByDeletedAtIsNullOrderByCreatedAtDescIdDesc(Pageable pageable);

    @EntityGraph(attributePaths = "member")
    @Query("""
            SELECT lookbook
            FROM Lookbook lookbook
            WHERE lookbook.deletedAt IS NULL
              AND (lookbook.createdAt < :cursorCreatedAt
               OR (lookbook.createdAt = :cursorCreatedAt AND lookbook.id < :cursorId))
            ORDER BY lookbook.createdAt DESC, lookbook.id DESC
            """)
    List<Lookbook> findNextPage(
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

    @Query("""
            SELECT lookbook.likeCount
            FROM Lookbook lookbook
            WHERE lookbook.id = :lookbookId
              AND lookbook.deletedAt IS NULL
            """)
    Optional<Integer> findLikeCountByIdAndDeletedAtIsNull(@Param("lookbookId") Long lookbookId);
}
