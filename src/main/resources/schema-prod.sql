CREATE TABLE IF NOT EXISTS image (
    image_id VARCHAR(36) NOT NULL,
    owner_id BIGINT NOT NULL,
    object_key VARCHAR(512) NOT NULL,
    purpose VARCHAR(30) NOT NULL,
    content_type VARCHAR(30) NOT NULL,
    file_size BIGINT NOT NULL,
    status VARCHAR(20) NOT NULL,
    visibility VARCHAR(20) NOT NULL,
    presigned_expires_at DATETIME(6) NOT NULL,
    uploaded_at DATETIME(6) NULL,
    activated_at DATETIME(6) NULL,
    delete_requested_at DATETIME(6) NULL,
    deleted_at DATETIME(6) NULL,
    retry_count INT NOT NULL DEFAULT 0,
    next_retry_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL,
    CONSTRAINT PK_IMAGE PRIMARY KEY (image_id),
    CONSTRAINT UK_IMAGE_OBJECT_KEY UNIQUE (object_key),
    CONSTRAINT UK_IMAGE_ID_OWNER UNIQUE (image_id, owner_id),
    CONSTRAINT FK_IMAGE_OWNER FOREIGN KEY (owner_id) REFERENCES member (member_id),
    CONSTRAINT CK_IMAGE_CONTENT_TYPE
        CHECK (content_type IN ('image/jpeg', 'image/png', 'image/webp')),
    CONSTRAINT CK_IMAGE_FILE_SIZE
        CHECK (file_size > 0 AND file_size <= 5242880),
    CONSTRAINT CK_IMAGE_STATUS
        CHECK (status IN (
            'PENDING', 'READY', 'ACTIVE', 'DELETING',
            'DELETE_FAILED', 'DELETED', 'REJECTED'
        )),
    INDEX IX_IMAGE_OWNER_STATUS (owner_id, status),
    INDEX IX_IMAGE_STATUS_CREATED_AT (status, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
