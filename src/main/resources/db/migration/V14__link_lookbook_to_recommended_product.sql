SET @has_original_image_id := (
    SELECT COUNT(*)
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'lookbook'
      AND COLUMN_NAME = 'original_image_id'
);

SET @legacy_lookbook_rows := (
    SELECT COUNT(*)
    FROM lookbook
);

SET @sql := IF(
    @has_original_image_id = 0 AND @legacy_lookbook_rows = 0,
    'ALTER TABLE lookbook
        MODIFY COLUMN original_image_url VARCHAR(2048) NULL,
        MODIFY COLUMN matched_image_url VARCHAR(2048) NULL,
        ADD COLUMN original_image_id VARCHAR(36)
            CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL,
        ADD COLUMN matched_image_id VARCHAR(36)
            CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL,
        ADD COLUMN matched_product_id BIGINT NULL,
        ADD COLUMN matched_product_image_url VARCHAR(2048) NULL,
        ADD COLUMN comment VARCHAR(500) NULL,
        ADD COLUMN like_count INT NOT NULL DEFAULT 0,
        ADD COLUMN report_count INT NOT NULL DEFAULT 0,
        ADD COLUMN moderation_status VARCHAR(20) NOT NULL DEFAULT ''VISIBLE'',
        ADD COLUMN auto_hidden_at DATETIME(6) NULL,
        ADD COLUMN deleted_at DATETIME(6) NULL,
        ADD CONSTRAINT FK_LOOKBOOK_ORIGINAL_IMAGE
            FOREIGN KEY (original_image_id)
            REFERENCES image (image_id)
            ON DELETE RESTRICT
            ON UPDATE RESTRICT,
        ADD CONSTRAINT FK_LOOKBOOK_MATCHED_IMAGE
            FOREIGN KEY (matched_image_id)
            REFERENCES image (image_id)
            ON DELETE RESTRICT
            ON UPDATE RESTRICT,
        ADD CONSTRAINT FK_LOOKBOOK_MATCHED_PRODUCT
            FOREIGN KEY (matched_product_id)
            REFERENCES product (product_id)
            ON DELETE RESTRICT
            ON UPDATE RESTRICT,
        ADD CONSTRAINT CK_LOOKBOOK_MATCH_SOURCE
            CHECK (
                (matched_image_id IS NOT NULL AND matched_product_id IS NULL)
                OR (matched_image_id IS NULL AND matched_product_id IS NOT NULL)
            ),
        ADD CONSTRAINT CK_LOOKBOOK_MATCHED_PRODUCT_IMAGE
            CHECK (
                matched_product_id IS NULL OR matched_product_image_url IS NOT NULL
            )',
    IF(
        @has_original_image_id = 0,
        'SIGNAL SQLSTATE ''45000''
            SET MESSAGE_TEXT = ''Legacy lookbook rows require an explicit image migration''',
        'ALTER TABLE lookbook
            MODIFY COLUMN matched_image_id VARCHAR(36)
                CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL,
            ADD COLUMN matched_product_id BIGINT NULL,
            ADD COLUMN matched_product_image_url VARCHAR(2048) NULL,
            ADD CONSTRAINT FK_LOOKBOOK_MATCHED_PRODUCT
                FOREIGN KEY (matched_product_id)
                REFERENCES product (product_id)
                ON DELETE RESTRICT
                ON UPDATE RESTRICT,
            ADD CONSTRAINT CK_LOOKBOOK_MATCH_SOURCE
                CHECK (
                    (matched_image_id IS NOT NULL AND matched_product_id IS NULL)
                    OR (matched_image_id IS NULL AND matched_product_id IS NOT NULL)
                ),
            ADD CONSTRAINT CK_LOOKBOOK_MATCHED_PRODUCT_IMAGE
                CHECK (
                    matched_product_id IS NULL OR matched_product_image_url IS NOT NULL
                )'
    )
);
PREPARE statement FROM @sql;
EXECUTE statement;
DEALLOCATE PREPARE statement;

SET @has_like_id := (
    SELECT COUNT(*)
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'lookbook_like'
      AND COLUMN_NAME = 'like_id'
);
SET @has_legacy_like_id := (
    SELECT COUNT(*)
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'lookbook_like'
      AND COLUMN_NAME = 'lookbook_like_id'
);
SET @sql := IF(
    @has_like_id = 0 AND @has_legacy_like_id = 1,
    'ALTER TABLE lookbook_like
        CHANGE COLUMN lookbook_like_id like_id BIGINT NOT NULL AUTO_INCREMENT',
    'DO 1'
);
PREPARE statement FROM @sql;
EXECUTE statement;
DEALLOCATE PREPARE statement;

SET @has_like_unique := (
    SELECT COUNT(*)
    FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'lookbook_like'
      AND INDEX_NAME = 'UK_LOOKBOOK_LIKE_LOOKBOOK_ID_MEMBER_ID'
);
SET @sql := IF(
    @has_like_unique = 0,
    'ALTER TABLE lookbook_like
        ADD CONSTRAINT UK_LOOKBOOK_LIKE_LOOKBOOK_ID_MEMBER_ID
        UNIQUE (lookbook_id, member_id)',
    'DO 1'
);
PREPARE statement FROM @sql;
EXECUTE statement;
DEALLOCATE PREPARE statement;

SET @has_tag_unique := (
    SELECT COUNT(*)
    FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'lookbook_tag'
      AND INDEX_NAME = 'UK_LOOKBOOK_TAG_LOOKBOOK_ID_TAG_ID'
);
SET @sql := IF(
    @has_tag_unique = 0,
    'ALTER TABLE lookbook_tag
        ADD CONSTRAINT UK_LOOKBOOK_TAG_LOOKBOOK_ID_TAG_ID
        UNIQUE (lookbook_id, tag_id)',
    'DO 1'
);
PREPARE statement FROM @sql;
EXECUTE statement;
DEALLOCATE PREPARE statement;

CREATE TABLE IF NOT EXISTS lookbook_report (
    report_id BIGINT NOT NULL AUTO_INCREMENT,
    lookbook_id BIGINT NOT NULL,
    member_id BIGINT NOT NULL,
    reason VARCHAR(50) NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (report_id),
    CONSTRAINT UK_LOOKBOOK_REPORT_LOOKBOOK_ID_MEMBER_ID
        UNIQUE (lookbook_id, member_id),
    CONSTRAINT FK_LOOKBOOK_REPORT_LOOKBOOK
        FOREIGN KEY (lookbook_id)
        REFERENCES lookbook (lookbook_id)
        ON DELETE RESTRICT
        ON UPDATE RESTRICT,
    CONSTRAINT FK_LOOKBOOK_REPORT_MEMBER
        FOREIGN KEY (member_id)
        REFERENCES member (member_id)
        ON DELETE RESTRICT
        ON UPDATE RESTRICT
);
