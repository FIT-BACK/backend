ALTER TABLE analysis_report
    ADD COLUMN recommendation_input_revision INT NOT NULL DEFAULT 1,
    ADD COLUMN result_input_revision INT NULL,
    ADD COLUMN result_score_version VARCHAR(30) NULL,
    ADD COLUMN recommendation_generated_at DATETIME(6) NULL;

ALTER TABLE recommended_item
    CHANGE COLUMN recommend_id recommended_item_id BIGINT NOT NULL AUTO_INCREMENT,
    CHANGE COLUMN `rank` rank_no INT NOT NULL,
    MODIFY COLUMN category VARCHAR(30) NOT NULL,
    MODIFY COLUMN similarity_score DECIMAL(5, 2) NOT NULL,
    DROP COLUMN is_value_match,
    ADD COLUMN input_revision INT NOT NULL DEFAULT 1 AFTER product_id,
    ADD COLUMN final_score DECIMAL(5, 2) NULL AFTER similarity_score,
    ADD COLUMN score_version VARCHAR(30) NULL AFTER final_score,
    ADD COLUMN reason_codes VARCHAR(500) NULL AFTER score_version;

UPDATE recommended_item
SET category = CASE UPPER(TRIM(category))
    WHEN 'OUTER' THEN 'OUTER'
    WHEN '아우터' THEN 'OUTER'
    WHEN 'TOP' THEN 'TOP'
    WHEN '상의' THEN 'TOP'
    WHEN 'BOTTOM' THEN 'BOTTOM'
    WHEN '하의' THEN 'BOTTOM'
    WHEN 'DRESS' THEN 'DRESS'
    WHEN '원피스' THEN 'DRESS'
    WHEN 'SHOES' THEN 'SHOES'
    WHEN '신발' THEN 'SHOES'
    WHEN 'BAG' THEN 'BAG'
    WHEN '가방' THEN 'BAG'
    WHEN 'ACCESSORY' THEN 'ACCESSORY'
    WHEN '액세서리' THEN 'ACCESSORY'
    ELSE 'OTHER'
END,
    final_score = similarity_score,
    score_version = 'SIMILARITY_V1',
    reason_codes = 'LEGACY_RESULT';

CREATE TEMPORARY TABLE recommendation_product_dedup AS
SELECT recommended_item_id,
       ROW_NUMBER() OVER (
           PARTITION BY report_id, product_id
           ORDER BY similarity_score DESC, recommended_item_id ASC
       ) AS duplicate_order
FROM recommended_item;

DELETE item
FROM recommended_item item
JOIN recommendation_product_dedup dedup
    ON dedup.recommended_item_id = item.recommended_item_id
WHERE dedup.duplicate_order > 1;

DROP TEMPORARY TABLE recommendation_product_dedup;

CREATE TEMPORARY TABLE recommendation_category_rank AS
SELECT recommended_item_id,
       ROW_NUMBER() OVER (
           PARTITION BY report_id, category
           ORDER BY similarity_score DESC, recommended_item_id ASC
       ) AS next_rank
FROM recommended_item;

DELETE item
FROM recommended_item item
JOIN recommendation_category_rank ranked
    ON ranked.recommended_item_id = item.recommended_item_id
WHERE ranked.next_rank > 5;

UPDATE recommended_item item
JOIN recommendation_category_rank ranked
    ON ranked.recommended_item_id = item.recommended_item_id
SET item.rank_no = ranked.next_rank;

DROP TEMPORARY TABLE recommendation_category_rank;

UPDATE analysis_report report
JOIN (
    SELECT report_id, MAX(created_at) AS generated_at
    FROM recommended_item
    GROUP BY report_id
) legacy_result
    ON legacy_result.report_id = report.report_id
SET report.result_input_revision = report.recommendation_input_revision,
    report.result_score_version = 'SIMILARITY_V1',
    report.recommendation_generated_at = legacy_result.generated_at;

ALTER TABLE recommended_item
    MODIFY COLUMN final_score DECIMAL(5, 2) NOT NULL,
    MODIFY COLUMN score_version VARCHAR(30) NOT NULL,
    MODIFY COLUMN reason_codes VARCHAR(500) NOT NULL,
    ADD CONSTRAINT UK_RECOMMENDED_REPORT_PRODUCT
        UNIQUE (report_id, product_id),
    ADD CONSTRAINT UK_RECOMMENDED_REPORT_CATEGORY_RANK
        UNIQUE (report_id, category, rank_no),
    ADD INDEX IDX_RECOMMENDED_REPORT_CATEGORY_SCORE (
        report_id,
        category,
        final_score DESC,
        product_id
    ),
    ADD CONSTRAINT FK_RECOMMENDED_REPORT
        FOREIGN KEY (report_id)
        REFERENCES analysis_report (report_id)
        ON DELETE CASCADE
        ON UPDATE RESTRICT,
    ADD CONSTRAINT FK_RECOMMENDED_PRODUCT
        FOREIGN KEY (product_id)
        REFERENCES product (product_id)
        ON DELETE RESTRICT
        ON UPDATE RESTRICT,
    ADD CONSTRAINT CK_RECOMMENDED_INPUT_REVISION
        CHECK (input_revision >= 1),
    ADD CONSTRAINT CK_RECOMMENDED_RANK
        CHECK (rank_no BETWEEN 1 AND 5),
    ADD CONSTRAINT CK_RECOMMENDED_CATEGORY
        CHECK (category IN (
            'OUTER',
            'TOP',
            'BOTTOM',
            'DRESS',
            'SHOES',
            'BAG',
            'ACCESSORY',
            'OTHER'
        )),
    ADD CONSTRAINT CK_RECOMMENDED_SIMILARITY_SCORE
        CHECK (similarity_score BETWEEN 0 AND 100),
    ADD CONSTRAINT CK_RECOMMENDED_FINAL_SCORE
        CHECK (final_score BETWEEN 0 AND 100);

ALTER TABLE analysis_report
    ADD CONSTRAINT CK_ANALYSIS_RECOMMENDATION_INPUT_REVISION
        CHECK (recommendation_input_revision >= 1),
    ADD CONSTRAINT CK_ANALYSIS_RESULT_INPUT_REVISION
        CHECK (result_input_revision IS NULL OR result_input_revision >= 1),
    ADD CONSTRAINT CK_ANALYSIS_RESULT_METADATA
        CHECK (
            (
                recommendation_generated_at IS NULL
                AND result_input_revision IS NULL
                AND result_score_version IS NULL
            )
            OR
            (
                recommendation_generated_at IS NOT NULL
                AND result_input_revision IS NOT NULL
                AND result_score_version IS NOT NULL
            )
        );
