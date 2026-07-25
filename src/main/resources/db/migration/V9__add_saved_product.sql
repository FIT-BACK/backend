CREATE TABLE saved_product (
    member_id BIGINT NOT NULL,
    product_id BIGINT NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (member_id, product_id),
    CONSTRAINT FK_SAVED_PRODUCT_MEMBER
        FOREIGN KEY (member_id)
        REFERENCES member (member_id)
        ON DELETE CASCADE
        ON UPDATE RESTRICT,
    CONSTRAINT FK_SAVED_PRODUCT_PRODUCT
        FOREIGN KEY (product_id)
        REFERENCES product (product_id)
        ON DELETE RESTRICT
        ON UPDATE RESTRICT,
    INDEX IDX_SAVED_PRODUCT_MEMBER_CURSOR (
        member_id,
        created_at DESC,
        product_id DESC
    )
);
