package com.fitback.backend.domain.lookbook.repository;

import com.fitback.backend.domain.lookbook.entity.Lookbook;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface LookbookRepository extends JpaRepository<Lookbook, Long> {

    @EntityGraph(attributePaths = "member")
    List<Lookbook> findAllByOrderByCreatedAtDescIdDesc(Pageable pageable);

    @EntityGraph(attributePaths = "member")
    @Query("""
            SELECT lookbook
            FROM Lookbook lookbook
            WHERE lookbook.createdAt < :cursorCreatedAt
               OR (lookbook.createdAt = :cursorCreatedAt AND lookbook.id < :cursorId)
            ORDER BY lookbook.createdAt DESC, lookbook.id DESC
            """)
    List<Lookbook> findNextPage(
            @Param("cursorCreatedAt") LocalDateTime cursorCreatedAt,
            @Param("cursorId") Long cursorId,
            Pageable pageable
    );
}
