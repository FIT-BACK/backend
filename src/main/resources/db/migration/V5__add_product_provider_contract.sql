ALTER TABLE product
    MODIFY COLUMN external_product_id VARCHAR(255) NULL,
    MODIFY COLUMN name VARCHAR(255) NULL,
    MODIFY COLUMN brand_name VARCHAR(255) NULL,
    MODIFY COLUMN seller_name VARCHAR(255) NULL,
    MODIFY COLUMN price INT NULL,
    MODIFY COLUMN category VARCHAR(50) NULL,
    MODIFY COLUMN purchase_url VARCHAR(2048) NULL,
    MODIFY COLUMN image_url VARCHAR(2048) NULL,
    ADD COLUMN identity_strategy VARCHAR(30) NULL AFTER source_api,
    ADD COLUMN provider_identity_key CHAR(64) NULL AFTER identity_strategy,
    ADD COLUMN materialization_key CHAR(64) NULL AFTER provider_identity_key,
    ADD COLUMN external_variant_id VARCHAR(255) NULL AFTER external_product_id,
    ADD COLUMN merchant_id VARCHAR(255) NULL AFTER external_variant_id,
    ADD COLUMN storage_mode VARCHAR(20) NULL AFTER merchant_id,
    ADD COLUMN list_price DECIMAL(19, 2) NULL AFTER image_url,
    ADD COLUMN current_price DECIMAL(19, 2) NULL AFTER list_price,
    ADD COLUMN sale_price DECIMAL(19, 2) NULL AFTER current_price,
    ADD COLUMN currency CHAR(3) NULL AFTER sale_price,
    ADD COLUMN price_observed_at DATETIME(6) NULL AFTER currency,
    ADD COLUMN affiliate_url VARCHAR(2048) NULL AFTER purchase_url,
    ADD COLUMN availability VARCHAR(30) NULL AFTER affiliate_url,
    ADD COLUMN snapshot_expires_at DATETIME(6) NULL AFTER availability;

UPDATE product
SET category = CASE UPPER(TRIM(category))
    WHEN 'OUTER' THEN 'OUTER'
    WHEN 'TOP' THEN 'TOP'
    WHEN 'BOTTOM' THEN 'BOTTOM'
    WHEN 'DRESS' THEN 'DRESS'
    WHEN 'SHOES' THEN 'SHOES'
    WHEN 'BAG' THEN 'BAG'
    WHEN 'ACCESSORY' THEN 'ACCESSORY'
    WHEN 'OTHER' THEN 'OTHER'
    ELSE 'OTHER'
END
WHERE category IS NOT NULL;

UPDATE product
SET identity_strategy = 'SNAPSHOT_UUID',
    materialization_key = SHA2(CONCAT('legacy-product:', product_id), 256),
    storage_mode = 'SNAPSHOT',
    availability = 'UNKNOWN'
WHERE identity_strategy IS NULL;

ALTER TABLE product
    MODIFY COLUMN category VARCHAR(30) NULL,
    MODIFY COLUMN identity_strategy VARCHAR(30) NOT NULL,
    MODIFY COLUMN storage_mode VARCHAR(20) NOT NULL,
    MODIFY COLUMN availability VARCHAR(30) NOT NULL,
    ADD CONSTRAINT UK_PRODUCT_SOURCE_IDENTITY
        UNIQUE (source_api, provider_identity_key),
    ADD CONSTRAINT UK_PRODUCT_MATERIALIZATION_KEY
        UNIQUE (materialization_key),
    ADD INDEX IDX_PRODUCT_CATEGORY_AVAILABILITY (category, availability),
    ADD CONSTRAINT CK_PRODUCT_IDENTITY_STRATEGY CHECK (
        (identity_strategy = 'PROVIDER_KEY'
            AND provider_identity_key IS NOT NULL
            AND external_product_id IS NOT NULL)
        OR
        (identity_strategy = 'SNAPSHOT_UUID'
            AND materialization_key IS NOT NULL)
    ),
    ADD CONSTRAINT CK_PRODUCT_PRICE_METADATA CHECK (
        (
            list_price IS NULL
            AND current_price IS NULL
            AND sale_price IS NULL
            AND currency IS NULL
            AND price_observed_at IS NULL
        )
        OR
        (
            currency IS NOT NULL
            AND price_observed_at IS NOT NULL
            AND (
                list_price IS NOT NULL
                OR current_price IS NOT NULL
                OR sale_price IS NOT NULL
            )
        )
    ),
    ADD CONSTRAINT CK_PRODUCT_PRICE_VALUE CHECK (
        (list_price IS NULL OR list_price >= 0)
        AND (current_price IS NULL OR current_price >= 0)
        AND (sale_price IS NULL OR sale_price >= 0)
    );
