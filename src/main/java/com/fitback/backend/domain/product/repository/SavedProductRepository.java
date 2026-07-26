package com.fitback.backend.domain.product.repository;

import com.fitback.backend.domain.product.entity.SavedProduct;
import com.fitback.backend.domain.product.entity.SavedProductId;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SavedProductRepository extends JpaRepository<SavedProduct, SavedProductId> {

    Optional<SavedProduct> findByIdMemberIdAndIdProductId(Long memberId, Long productId);

    boolean existsByIdMemberIdAndIdProductId(Long memberId, Long productId);

    @Query("""
            SELECT saved.id.productId
            FROM SavedProduct saved
            WHERE saved.id.memberId = :memberId
              AND saved.id.productId IN :productIds
            """)
    List<Long> findSavedProductIds(
            @Param("memberId") Long memberId,
            @Param("productIds") List<Long> productIds
    );

    @Query("""
            SELECT saved
            FROM SavedProduct saved
            WHERE saved.id.memberId = :memberId
            ORDER BY saved.createdAt DESC, saved.id.productId DESC
            """)
    Slice<SavedProduct> findFirstPage(
            @Param("memberId") Long memberId,
            Pageable pageable
    );

    @Query("""
            SELECT saved
            FROM SavedProduct saved
            WHERE saved.id.memberId = :memberId
              AND (
                    saved.createdAt < :cursorCreatedAt
                    OR (
                        saved.createdAt = :cursorCreatedAt
                        AND saved.id.productId < :cursorProductId
                    )
              )
            ORDER BY saved.createdAt DESC, saved.id.productId DESC
            """)
    Slice<SavedProduct> findNextPage(
            @Param("memberId") Long memberId,
            @Param("cursorCreatedAt") Instant cursorCreatedAt,
            @Param("cursorProductId") Long cursorProductId,
            Pageable pageable
    );

    @Modifying
    @Query("""
            DELETE FROM SavedProduct saved
            WHERE saved.id.memberId = :memberId
              AND saved.id.productId = :productId
            """)
    int deleteSavedProduct(
            @Param("memberId") Long memberId,
            @Param("productId") Long productId
    );
}
