package com.fitback.backend.domain.image.entity;

import com.fitback.backend.domain.member.entity.Member;
import com.fitback.backend.global.entity.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity
@Table(
        name = "image_asset",
        uniqueConstraints = {
            @UniqueConstraint(name = "UK_IMAGE_ASSET_PUBLIC_ID", columnNames = "public_id"),
            @UniqueConstraint(name = "UK_IMAGE_ASSET_STORAGE_KEY", columnNames = "storage_key"),
            @UniqueConstraint(
                    name = "UK_IMAGE_ASSET_ID_OWNER",
                    columnNames = {"image_id", "owner_member_id"}
            )
        }
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ImageAsset extends BaseTimeEntity {

    public static final long MAX_FILE_SIZE_BYTES = 5L * 1024 * 1024;
    private static final Set<String> ALLOWED_MIME_TYPES = Set.of(
            "image/jpeg",
            "image/png",
            "image/webp"
    );

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "image_id")
    private Long id;

    @Column(name = "public_id", nullable = false, length = 36, updatable = false)
    private String publicId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "owner_member_id", nullable = false)
    private Member owner;

    @Enumerated(EnumType.STRING)
    @Column(name = "purpose", nullable = false, length = 20)
    private ImagePurpose purpose;

    @Enumerated(EnumType.STRING)
    @Column(name = "visibility", nullable = false, length = 20)
    private ImageVisibility visibility;

    @Column(name = "storage_key", nullable = false, length = 512)
    private String storageKey;

    @Column(name = "mime_type", nullable = false, length = 50)
    private String mimeType;

    @Column(name = "file_size_bytes", nullable = false)
    private Long fileSizeBytes;

    @Enumerated(EnumType.STRING)
    @Column(name = "asset_status", nullable = false, length = 20)
    private ImageAssetStatus assetStatus;

    @Column(name = "presigned_expires_at")
    private Instant presignedExpiresAt;

    @Column(name = "uploaded_at")
    private LocalDateTime uploadedAt;

    @Column(name = "activated_at")
    private Instant activatedAt;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    private ImageAsset(
            Member owner,
            ImagePurpose purpose,
            String storageKey,
            String mimeType,
            long fileSizeBytes,
            Instant presignedExpiresAt
    ) {
        validateUploadMetadata(mimeType, fileSizeBytes);
        this.publicId = UUID.randomUUID().toString();
        this.owner = Objects.requireNonNull(owner, "owner must not be null");
        this.purpose = Objects.requireNonNull(purpose, "purpose must not be null");
        this.visibility = purpose == ImagePurpose.LOOKBOOK
                ? ImageVisibility.PUBLIC
                : ImageVisibility.PRIVATE;
        this.storageKey = Objects.requireNonNull(storageKey, "storageKey must not be null");
        this.mimeType = mimeType;
        this.fileSizeBytes = fileSizeBytes;
        this.assetStatus = ImageAssetStatus.PENDING_UPLOAD;
        this.presignedExpiresAt = Objects.requireNonNull(
                presignedExpiresAt,
                "presignedExpiresAt must not be null"
        );
    }

    public static ImageAsset create(
            Member owner,
            ImagePurpose purpose,
            String storageKey,
            String mimeType,
            long fileSizeBytes,
            Instant presignedExpiresAt
    ) {
        return new ImageAsset(
                owner,
                purpose,
                storageKey,
                mimeType,
                fileSizeBytes,
                presignedExpiresAt
        );
    }

    public void renewUpload(Instant expiresAt) {
        requireStatus(ImageAssetStatus.PENDING_UPLOAD);
        this.presignedExpiresAt = Objects.requireNonNull(expiresAt, "expiresAt must not be null");
    }

    public void completeUpload(
            long actualFileSize,
            String actualMimeType,
            LocalDateTime uploadedAt
    ) {
        requireStatus(ImageAssetStatus.PENDING_UPLOAD);
        this.uploadedAt = Objects.requireNonNull(uploadedAt, "uploadedAt must not be null");
        if (fileSizeBytes != actualFileSize || !mimeType.equalsIgnoreCase(actualMimeType)) {
            this.assetStatus = ImageAssetStatus.REJECTED;
            this.presignedExpiresAt = null;
            throw new IllegalArgumentException("uploaded object metadata does not match");
        }
        this.assetStatus = ImageAssetStatus.READY;
        this.presignedExpiresAt = null;
    }

    public void reject(LocalDateTime uploadedAt) {
        requireStatus(ImageAssetStatus.PENDING_UPLOAD);
        this.assetStatus = ImageAssetStatus.REJECTED;
        this.uploadedAt = Objects.requireNonNull(uploadedAt, "uploadedAt must not be null");
        this.presignedExpiresAt = null;
    }

    public void activateForAnalysis(Long memberId, Instant activatedAt) {
        requireOwner(memberId);
        if (purpose != ImagePurpose.ANALYSIS) {
            throw new IllegalStateException("image purpose must be ANALYSIS");
        }
        requireStatus(ImageAssetStatus.READY);
        this.assetStatus = ImageAssetStatus.ACTIVE;
        this.activatedAt = Objects.requireNonNull(activatedAt, "activatedAt must not be null");
    }

    public void claimForDeletion() {
        if (assetStatus != ImageAssetStatus.PENDING_UPLOAD
                && assetStatus != ImageAssetStatus.READY
                && assetStatus != ImageAssetStatus.REJECTED
                && assetStatus != ImageAssetStatus.DELETE_FAILED) {
            throw new IllegalStateException("image is not a cleanup candidate");
        }
        this.assetStatus = ImageAssetStatus.DELETING;
        this.presignedExpiresAt = null;
    }

    public void markDeleted(Instant deletedAt) {
        requireStatus(ImageAssetStatus.DELETING);
        this.assetStatus = ImageAssetStatus.DELETED;
        this.deletedAt = Objects.requireNonNull(deletedAt, "deletedAt must not be null");
    }

    public void markDeleteFailed() {
        requireStatus(ImageAssetStatus.DELETING);
        this.assetStatus = ImageAssetStatus.DELETE_FAILED;
    }

    public void requireOwner(Long memberId) {
        if (!Objects.equals(owner.getId(), memberId)) {
            throw new IllegalArgumentException("image owner does not match");
        }
    }

    public boolean uploadExpired(Instant now) {
        return presignedExpiresAt != null && !presignedExpiresAt.isAfter(now);
    }

    private void requireStatus(ImageAssetStatus expected) {
        if (assetStatus != expected) {
            throw new IllegalStateException("image status must be " + expected);
        }
    }

    private static void validateUploadMetadata(String mimeType, long fileSizeBytes) {
        if (!ALLOWED_MIME_TYPES.contains(mimeType)) {
            throw new IllegalArgumentException("unsupported image MIME type");
        }
        if (fileSizeBytes <= 0 || fileSizeBytes > MAX_FILE_SIZE_BYTES) {
            throw new IllegalArgumentException("image size must be between 1 byte and 5 MiB");
        }
    }
}
