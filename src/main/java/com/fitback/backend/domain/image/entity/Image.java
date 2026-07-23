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
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.Set;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity
@Table(
        name = "image",
        uniqueConstraints = {
            @UniqueConstraint(name = "UK_IMAGE_OBJECT_KEY", columnNames = "object_key"),
            @UniqueConstraint(
                    name = "UK_IMAGE_ID_OWNER",
                    columnNames = {"image_id", "owner_id"}
            )
        },
        indexes = {
            @Index(name = "IX_IMAGE_OWNER_STATUS", columnList = "owner_id,status"),
            @Index(name = "IX_IMAGE_STATUS_CREATED_AT", columnList = "status,created_at")
        }
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Image extends BaseCreateTimeEntity {

    public static final long MAX_FILE_SIZE = 5_242_880L;
    private static final Set<String> ALLOWED_CONTENT_TYPES = Set.of(
            "image/jpeg",
            "image/png",
            "image/webp"
    );

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

    @Column(name = "presigned_expires_at")
    private Instant presignedExpiresAt;

    @Column(name = "uploaded_at")
    private LocalDateTime uploadedAt;

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
        validateUploadMetadata(contentType, fileSize);
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.owner = Objects.requireNonNull(owner, "owner must not be null");
        this.objectKey = Objects.requireNonNull(objectKey, "objectKey must not be null");
        this.purpose = Objects.requireNonNull(purpose, "purpose must not be null");
        this.contentType = contentType;
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

    public void renewUpload(Instant expiresAt) {
        requirePendingUploadStatus();
        this.presignedExpiresAt = Objects.requireNonNull(expiresAt, "expiresAt must not be null");
    }

    public void completeUpload(
            long actualFileSize,
            String actualContentType,
            LocalDateTime completedAt
    ) {
        requirePendingUploadStatus();
        this.uploadedAt = Objects.requireNonNull(completedAt, "completedAt must not be null");
        this.presignedExpiresAt = null;
        if (fileSize != actualFileSize || !contentType.equalsIgnoreCase(actualContentType)) {
            this.status = ImageStatus.REJECTED;
            throw new IllegalArgumentException("uploaded object metadata does not match");
        }
        this.status = ImageStatus.READY;
    }

    public void reject(LocalDateTime rejectedAt) {
        requirePendingUploadStatus();
        this.status = ImageStatus.REJECTED;
        this.uploadedAt = Objects.requireNonNull(rejectedAt, "rejectedAt must not be null");
        this.presignedExpiresAt = null;
    }

    public void activateForAnalysis(Long memberId, Instant activatedAt) {
        requireOwner(memberId);
        if (!purpose.isAnalysis()) {
            throw new IllegalStateException("image purpose must be ANALYSIS");
        }
        requireStatus(ImageStatus.READY);
        this.status = ImageStatus.ACTIVE;
        this.activatedAt = Objects.requireNonNull(activatedAt, "activatedAt must not be null");
    }

    public void claimForDeletion(Instant requestedAt) {
        if (!status.isPendingUpload()
                && status != ImageStatus.READY
                && status != ImageStatus.REJECTED
                && status != ImageStatus.DELETE_FAILED) {
            throw new IllegalStateException("image is not a cleanup candidate");
        }
        this.status = ImageStatus.DELETING;
        this.deleteRequestedAt = Objects.requireNonNull(requestedAt, "requestedAt must not be null");
        this.presignedExpiresAt = null;
        this.nextRetryAt = null;
    }

    public void markDeleted(Instant deletedAt) {
        requireStatus(ImageStatus.DELETING);
        this.status = ImageStatus.DELETED;
        this.deletedAt = Objects.requireNonNull(deletedAt, "deletedAt must not be null");
    }

    public void markDeleteFailed(Instant nextRetryAt) {
        requireStatus(ImageStatus.DELETING);
        this.status = ImageStatus.DELETE_FAILED;
        this.retryCount++;
        this.nextRetryAt = Objects.requireNonNull(nextRetryAt, "nextRetryAt must not be null");
    }

    public void requireOwner(Long memberId) {
        if (!Objects.equals(owner.getId(), memberId)) {
            throw new IllegalArgumentException("image owner does not match");
        }
    }

    public boolean uploadExpired(Instant now) {
        return presignedExpiresAt != null && !presignedExpiresAt.isAfter(now);
    }

    private void requireStatus(ImageStatus expected) {
        if (status != expected) {
            throw new IllegalStateException("image status must be " + expected);
        }
    }

    private void requirePendingUploadStatus() {
        if (!status.isPendingUpload()) {
            throw new IllegalStateException("image status must be pending upload");
        }
    }

    private static void validateUploadMetadata(String contentType, long fileSize) {
        if (!ALLOWED_CONTENT_TYPES.contains(contentType)) {
            throw new IllegalArgumentException("unsupported image content type");
        }
        if (fileSize <= 0 || fileSize > MAX_FILE_SIZE) {
            throw new IllegalArgumentException("fileSize must be between 1 and 5242880");
        }
    }
}
