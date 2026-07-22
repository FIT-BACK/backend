ALTER TABLE analysis_report
    MODIFY image_url VARCHAR(2048) NULL,
    ADD COLUMN original_image_id VARCHAR(36) NULL AFTER member_id,
    ADD COLUMN deleted_at DATETIME(6) NULL,
    ADD COLUMN purge_after DATETIME(6) NULL,
    ADD CONSTRAINT FK_ANALYSIS_REPORT_IMAGE_OWNER
        FOREIGN KEY (original_image_id, member_id)
        REFERENCES image (image_id, owner_id)
        ON DELETE RESTRICT,
    ADD CONSTRAINT CK_ANALYSIS_REPORT_IMAGE_SOURCE
        CHECK (
            (original_image_id IS NULL AND image_url IS NOT NULL)
            OR (original_image_id IS NOT NULL AND image_url IS NULL)
        ),
    ADD CONSTRAINT CK_ANALYSIS_REPORT_MATCH
        CHECK (match_percentage BETWEEN 0 AND 100),
    ADD INDEX IX_ANALYSIS_MEMBER_DELETED_CURSOR
        (member_id, deleted_at, report_id DESC),
    ADD INDEX IX_ANALYSIS_PURGE (purge_after),
    ADD INDEX IX_ANALYSIS_IMAGE_OWNER (original_image_id, member_id);
