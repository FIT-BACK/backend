CREATE TABLE image_asset (
    image_id BIGINT NOT NULL AUTO_INCREMENT,
    public_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    owner_member_id BIGINT NOT NULL,
    purpose VARCHAR(20) NOT NULL,
    visibility VARCHAR(20) NOT NULL DEFAULT 'PRIVATE',
    storage_key VARCHAR(512) NOT NULL,
    mime_type VARCHAR(50) NOT NULL,
    file_size_bytes BIGINT NOT NULL,
    asset_status VARCHAR(20) NOT NULL DEFAULT 'PENDING_UPLOAD',
    presigned_expires_at DATETIME(6) NULL,
    uploaded_at DATETIME(6) NULL,
    activated_at DATETIME(6) NULL,
    deleted_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
        ON UPDATE CURRENT_TIMESTAMP(6),

    CONSTRAINT PK_IMAGE_ASSET PRIMARY KEY (image_id),
    CONSTRAINT UK_IMAGE_ASSET_PUBLIC_ID UNIQUE (public_id),
    CONSTRAINT UK_IMAGE_ASSET_STORAGE_KEY UNIQUE (storage_key),
    CONSTRAINT UK_IMAGE_ASSET_ID_OWNER UNIQUE (image_id, owner_member_id),
    CONSTRAINT FK_IMAGE_ASSET_OWNER
        FOREIGN KEY (owner_member_id) REFERENCES member (member_id)
        ON DELETE RESTRICT,
    CONSTRAINT CK_IMAGE_ASSET_PURPOSE
        CHECK (purpose IN ('ANALYSIS', 'LOOKBOOK', 'PROFILE')),
    CONSTRAINT CK_IMAGE_ASSET_VISIBILITY
        CHECK (visibility IN ('PRIVATE', 'PUBLIC')),
    CONSTRAINT CK_IMAGE_ASSET_MIME
        CHECK (mime_type IN ('image/jpeg', 'image/png', 'image/webp')),
    CONSTRAINT CK_IMAGE_ASSET_SIZE
        CHECK (file_size_bytes > 0 AND file_size_bytes <= 5242880),
    CONSTRAINT CK_IMAGE_ASSET_STATUS
        CHECK (asset_status IN (
            'PENDING_UPLOAD', 'READY', 'ACTIVE', 'REJECTED',
            'DELETING', 'DELETE_FAILED', 'DELETED'
        )),
    CONSTRAINT CK_IMAGE_ASSET_ACTIVE_AT
        CHECK (
            (asset_status = 'ACTIVE' AND activated_at IS NOT NULL)
            OR asset_status <> 'ACTIVE'
        ),
    CONSTRAINT CK_IMAGE_ASSET_DELETED_AT
        CHECK (
            (asset_status = 'DELETED' AND deleted_at IS NOT NULL)
            OR asset_status <> 'DELETED'
        ),
    CONSTRAINT CK_IMAGE_ASSET_PRESIGNED_EXPIRY
        CHECK (
            (asset_status = 'PENDING_UPLOAD' AND presigned_expires_at IS NOT NULL)
            OR (asset_status <> 'PENDING_UPLOAD' AND presigned_expires_at IS NULL)
        ),

    KEY IDX_IMAGE_OWNER_STATUS_CREATED
        (owner_member_id, asset_status, created_at),
    KEY IDX_IMAGE_CLEANUP (asset_status, uploaded_at, created_at),
    KEY IDX_IMAGE_PRESIGNED_EXPIRES (presigned_expires_at)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

ALTER TABLE analysis_report
    MODIFY image_url VARCHAR(2048) NULL,
    ADD COLUMN original_image_id BIGINT NULL AFTER member_id,
    ADD COLUMN deleted_at DATETIME(6) NULL,
    ADD COLUMN purge_after DATETIME(6) NULL,
    ADD CONSTRAINT FK_ANALYSIS_REPORT_IMAGE_OWNER
        FOREIGN KEY (original_image_id, member_id)
        REFERENCES image_asset (image_id, owner_member_id)
        ON DELETE RESTRICT,
    ADD CONSTRAINT CK_ANALYSIS_REPORT_IMAGE_SOURCE
        CHECK (
            (original_image_id IS NULL AND image_url IS NOT NULL)
            OR (original_image_id IS NOT NULL AND image_url IS NULL)
        ),
    ADD CONSTRAINT CK_ANALYSIS_REPORT_MATCH
        CHECK (match_percentage BETWEEN 0 AND 100),
    ADD INDEX IDX_ANALYSIS_MEMBER_DELETED_CURSOR
        (member_id, deleted_at, report_id DESC),
    ADD INDEX IDX_ANALYSIS_PURGE (purge_after),
    ADD INDEX IDX_ANALYSIS_IMAGE_OWNER (original_image_id, member_id);
