CREATE TABLE IF NOT EXISTS notification (
    notification_id BIGINT NOT NULL AUTO_INCREMENT,
    member_id BIGINT NOT NULL,
    notification_type VARCHAR(30) NOT NULL,
    actor_member_id BIGINT NULL,
    lookbook_id BIGINT NULL,
    report_id BIGINT NULL,
    trend_id BIGINT NULL,
    title VARCHAR(100) NOT NULL,
    body VARCHAR(500) NOT NULL,
    read_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL,
    CONSTRAINT PK_NOTIFICATION PRIMARY KEY (notification_id),
    CONSTRAINT FK_NOTIFICATION_MEMBER
        FOREIGN KEY (member_id)
        REFERENCES member (member_id)
        ON DELETE CASCADE,
    INDEX IDX_NOTIFICATION_MEMBER_ID_NOTIFICATION_ID (member_id, notification_id),
    INDEX IDX_NOTIFICATION_MEMBER_ID_READ_AT (member_id, read_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
