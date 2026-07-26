ALTER TABLE lookbook
    MODIFY COLUMN matched_image_id VARCHAR(36) NULL,
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
    ADD INDEX IDX_LOOKBOOK_MATCHED_PRODUCT (matched_product_id);
