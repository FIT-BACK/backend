SET @has_save_id := (
    SELECT COUNT(*)
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'closet_save'
      AND COLUMN_NAME = 'save_id'
);
SET @has_legacy_save_id := (
    SELECT COUNT(*)
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'closet_save'
      AND COLUMN_NAME = 'closet_save_id'
);
SET @sql := IF(
    @has_save_id = 0 AND @has_legacy_save_id = 1,
    'ALTER TABLE closet_save CHANGE closet_save_id save_id BIGINT NOT NULL AUTO_INCREMENT',
    'DO 1'
);
PREPARE statement FROM @sql;
EXECUTE statement;
DEALLOCATE PREPARE statement;

SET @has_created_at := (
    SELECT COUNT(*)
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'closet_save'
      AND COLUMN_NAME = 'created_at'
);
SET @sql := IF(
    @has_created_at = 0,
    'ALTER TABLE closet_save ADD COLUMN created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)',
    'DO 1'
);
PREPARE statement FROM @sql;
EXECUTE statement;
DEALLOCATE PREPARE statement;

SET @has_closet_unique := (
    SELECT COUNT(*)
    FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'closet_save'
      AND INDEX_NAME = 'UK_CLOSET_SAVE_MEMBER_ID_TARGET_TYPE_TARGET_ID'
);
SET @sql := IF(
    @has_closet_unique = 0,
    'ALTER TABLE closet_save ADD CONSTRAINT UK_CLOSET_SAVE_MEMBER_ID_TARGET_TYPE_TARGET_ID UNIQUE (member_id, target_type, target_id)',
    'DO 1'
);
PREPARE statement FROM @sql;
EXECUTE statement;
DEALLOCATE PREPARE statement;

CREATE TABLE saved_analysis_item (
    saved_analysis_item_id BIGINT NOT NULL AUTO_INCREMENT,
    save_id BIGINT NOT NULL,
    product_id BIGINT NOT NULL,
    category VARCHAR(30) NOT NULL,
    rank_no INT NOT NULL,
    image_url VARCHAR(2048) NULL,
    product_name VARCHAR(255) NULL,
    seller_name VARCHAR(255) NULL,
    price_amount DECIMAL(19, 2) NULL,
    price_currency CHAR(3) NULL,
    price_type VARCHAR(20) NULL,
    price_observed_at DATETIME(6) NULL,
    purchase_url VARCHAR(2048) NULL,
    similarity_score DECIMAL(5, 2) NOT NULL,
    final_score DECIMAL(5, 2) NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (saved_analysis_item_id),
    CONSTRAINT UK_SAVED_ANALYSIS_ITEM_SAVE_CATEGORY
        UNIQUE (save_id, category),
    CONSTRAINT FK_SAVED_ANALYSIS_ITEM_SAVE
        FOREIGN KEY (save_id)
        REFERENCES closet_save (save_id)
        ON DELETE CASCADE
        ON UPDATE RESTRICT,
    CONSTRAINT FK_SAVED_ANALYSIS_ITEM_PRODUCT
        FOREIGN KEY (product_id)
        REFERENCES product (product_id)
        ON DELETE RESTRICT
        ON UPDATE RESTRICT,
    CONSTRAINT CK_SAVED_ANALYSIS_ITEM_RANK
        CHECK (rank_no BETWEEN 1 AND 10),
    CONSTRAINT CK_SAVED_ANALYSIS_ITEM_SIMILARITY
        CHECK (similarity_score BETWEEN 0 AND 100),
    CONSTRAINT CK_SAVED_ANALYSIS_ITEM_FINAL_SCORE
        CHECK (final_score BETWEEN 0 AND 100),
    INDEX IDX_SAVED_ANALYSIS_ITEM_PRODUCT (product_id)
);
