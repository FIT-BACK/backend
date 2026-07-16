package com.fitback.backend.domain.lookbook.repository;

import com.fitback.backend.domain.lookbook.entity.LookbookLike;
import java.util.List;
import java.util.Set;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface LookbookLikeRepository extends JpaRepository<LookbookLike, Long> {

    boolean existsByLookbookIdAndMemberId(Long lookbookId, Long memberId);

    @Query("""
            SELECT lookbookLike.lookbook.id
            FROM LookbookLike lookbookLike
            WHERE lookbookLike.member.id = :memberId
              AND lookbookLike.lookbook.id IN :lookbookIds
            """)
    Set<Long> findLikedLookbookIds(
            @Param("memberId") Long memberId,
            @Param("lookbookIds") List<Long> lookbookIds
    );
}
