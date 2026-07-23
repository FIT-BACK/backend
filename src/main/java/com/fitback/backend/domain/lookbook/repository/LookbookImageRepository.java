package com.fitback.backend.domain.lookbook.repository;

import com.fitback.backend.domain.image.entity.Image;
import com.fitback.backend.domain.image.entity.ImageStatus;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

public interface LookbookImageRepository extends Repository<Image, String> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT image
            FROM Image image
            WHERE image.id IN :imageIds
              AND image.owner.id = :ownerId
            """)
    List<Image> findAllOwnedImages(
            @Param("imageIds") Collection<String> imageIds,
            @Param("ownerId") Long ownerId
    );

    @Modifying(flushAutomatically = true)
    @Query("""
            UPDATE Image image
            SET image.status = :activeStatus,
                image.activatedAt = :activatedAt
            WHERE image.id IN :imageIds
              AND image.status = :readyStatus
            """)
    int activateReadyImages(
            @Param("imageIds") Collection<String> imageIds,
            @Param("readyStatus") ImageStatus readyStatus,
            @Param("activeStatus") ImageStatus activeStatus,
            @Param("activatedAt") Instant activatedAt
    );
}
