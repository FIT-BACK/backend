package com.fitback.backend.domain.image.entity;

import com.fitback.backend.domain.member.entity.Member;
import com.fitback.backend.global.entity.BaseCreateTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity
@Table(
        name = "image",
        uniqueConstraints = @UniqueConstraint(
                name = "UK_IMAGE_OBJECT_KEY",
                columnNames = "object_key"
        ),
        indexes = {
            @Index(name = "IX_IMAGE_OWNER_STATUS", columnList = "owner_id,status"),
            @Index(name = "IX_IMAGE_STATUS_CREATED_AT", columnList = "status,created_at")
        }
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Image extends BaseCreateTimeEntity {

    private static final long MAX_FILE_SIZE = 5_242_880L;

    @Id
    @Column(name = "image_id", length = 36)
    private String id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "owner_id", nullable = false)
    private Member owner;

    @Column(name = "object_key", nullable = false, length = 512)
    private String objectKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "purpose", nullable = false, length = 30)
    private ImagePurpose purpose;

    @Column(name = "content_type", nullable = false, length = 30)
    private String contentType;

    @Column(name = "file_size", nullable = false)
    private long fileSize;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private ImageStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "visibility", nullable = false, length = 20)
    private ImageVisibility visibility;

    @Column(name = "presigned_expires_at", nullable = false)
    private Instant presignedExpiresAt;

    @Column(name = "activated_at")
    private Instant activatedAt;

    @Column(name = "delete_requested_at")
    private Instant deleteRequestedAt;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    @Column(name = "retry_count", nullable = false)
    private int retryCount;

    @Column(name = "next_retry_at")
    private Instant nextRetryAt;

    private Image(
            String id,
            Member owner,
            String objectKey,
            ImagePurpose purpose,
            String contentType,
            long fileSize,
            ImageVisibility visibility,
            Instant presignedExpiresAt
    ) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.owner = Objects.requireNonNull(owner, "owner must not be null");
        this.objectKey = Objects.requireNonNull(objectKey, "objectKey must not be null");
        this.purpose = Objects.requireNonNull(purpose, "purpose must not be null");
        this.contentType = Objects.requireNonNull(contentType, "contentType must not be null");
        this.fileSize = fileSize;
        this.status = ImageStatus.PENDING;
        this.visibility = Objects.requireNonNull(visibility, "visibility must not be null");
        this.presignedExpiresAt = Objects.requireNonNull(
                presignedExpiresAt,
                "presignedExpiresAt must not be null"
        );
    }

    public static Image createPending(
            String id,
            Member owner,
            String objectKey,
            ImagePurpose purpose,
            String contentType,
            long fileSize,
            ImageVisibility visibility,
            Instant presignedExpiresAt
    ) {
        if (fileSize <= 0 || fileSize > MAX_FILE_SIZE) {
            throw new IllegalArgumentException("fileSize must be between 1 and 5242880");
        }
        return new Image(
                id,
                owner,
                objectKey,
                purpose,
                contentType,
                fileSize,
                visibility,
                presignedExpiresAt
        );
    }
}
