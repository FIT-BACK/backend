package com.fitback.backend.domain.image.repository;

import com.fitback.backend.domain.image.entity.ImageAsset;
import com.fitback.backend.domain.image.entity.ImageAssetStatus;
import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ImageAssetRepository extends JpaRepository<ImageAsset, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<ImageAsset> findByPublicIdAndOwnerId(String publicId, Long ownerId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select image
            from ImageAsset image
            where image.assetStatus in :statuses
              and (
                    (image.uploadedAt is null and image.createdAt < :createdBefore)
                    or image.uploadedAt < :createdBefore
              )
            order by image.id
            """)
    List<ImageAsset> findCleanupCandidates(
            @Param("statuses") Collection<ImageAssetStatus> statuses,
            @Param("createdBefore") LocalDateTime createdBefore,
            Pageable pageable
    );
}
