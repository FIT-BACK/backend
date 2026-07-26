package com.fitback.backend.domain.image.repository;

import com.fitback.backend.domain.image.entity.Image;
import com.fitback.backend.domain.image.entity.ImageStatus;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ImageRepository extends JpaRepository<Image, String> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<Image> findByIdAndOwnerId(String imageId, Long ownerId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select image
            from Image image
            where image.status in :statuses
              and (:afterId is null or image.id > :afterId)
              and (
                    (
                        image.status in (
                            com.fitback.backend.domain.image.entity.ImageStatus.PENDING,
                            com.fitback.backend.domain.image.entity.ImageStatus.PENDING_UPLOAD
                        )
                        and image.createdAt < :createdBefore
                    )
                    or (
                        image.status in (
                            com.fitback.backend.domain.image.entity.ImageStatus.READY,
                            com.fitback.backend.domain.image.entity.ImageStatus.REJECTED
                        )
                        and coalesce(image.uploadedAt, image.createdAt) < :createdBefore
                    )
                    or (
                        image.status = com.fitback.backend.domain.image.entity.ImageStatus.DELETE_FAILED
                        and (image.nextRetryAt is null or image.nextRetryAt <= :now)
                    )
              )
            order by image.id
            """)
    List<Image> findCleanupCandidates(
            @Param("statuses") Collection<ImageStatus> statuses,
            @Param("createdBefore") LocalDateTime createdBefore,
            @Param("now") Instant now,
            @Param("afterId") String afterId,
            Pageable pageable
    );
}
