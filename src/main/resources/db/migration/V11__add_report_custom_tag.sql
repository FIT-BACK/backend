CREATE TABLE report_custom_tag (
    report_custom_tag_id BIGINT NOT NULL AUTO_INCREMENT,
    report_id BIGINT NOT NULL,
    display_name VARCHAR(50) NOT NULL,
    normalized_name VARCHAR(50) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (report_custom_tag_id),
    CONSTRAINT UK_REPORT_CUSTOM_TAG_NAME
        UNIQUE (report_id, normalized_name),
    CONSTRAINT FK_REPORT_CUSTOM_TAG_REPORT
        FOREIGN KEY (report_id)
        REFERENCES analysis_report (report_id)
        ON DELETE CASCADE
        ON UPDATE RESTRICT,
    CONSTRAINT CK_REPORT_CUSTOM_TAG_NAME
        CHECK (CHAR_LENGTH(TRIM(display_name)) BETWEEN 1 AND 50)
);
