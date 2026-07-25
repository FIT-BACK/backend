CREATE TABLE IF NOT EXISTS withdrawal_email_block (
    withdrawal_id BIGINT NOT NULL AUTO_INCREMENT,
    email_hash VARCHAR(64) NOT NULL,
    blocked_until DATETIME(6) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    CONSTRAINT PK_WITHDRAWAL_EMAIL_BLOCK PRIMARY KEY (withdrawal_id),
    CONSTRAINT UK_WITHDRAWAL_EMAIL_BLOCK_EMAIL_HASH UNIQUE (email_hash),
    INDEX IDX_WITHDRAWAL_EMAIL_BLOCK_BLOCKED_UNTIL (blocked_until)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS member_notification_setting (
    member_id BIGINT NOT NULL,
    analysis_complete_enabled TINYINT(1) NOT NULL DEFAULT 1,
    lookbook_liked_enabled TINYINT(1) NOT NULL DEFAULT 1,
    trend_update_enabled TINYINT(1) NOT NULL DEFAULT 0,
    marketing_enabled TINYINT(1) NOT NULL DEFAULT 0,
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT PK_MEMBER_NOTIFICATION_SETTING PRIMARY KEY (member_id),
    CONSTRAINT FK_MEMBER_NOTIFICATION_SETTING_MEMBER
        FOREIGN KEY (member_id)
        REFERENCES member (member_id)
        ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT INTO member_notification_setting (
    member_id,
    analysis_complete_enabled,
    lookbook_liked_enabled,
    trend_update_enabled,
    marketing_enabled,
    updated_at
)
SELECT
    member.member_id,
    1,
    1,
    0,
    0,
    CURRENT_TIMESTAMP(6)
FROM member
LEFT JOIN member_notification_setting
    ON member_notification_setting.member_id = member.member_id
WHERE member_notification_setting.member_id IS NULL;

CREATE TABLE IF NOT EXISTS marketing_consent_history (
    marketing_consent_history_id BIGINT NOT NULL AUTO_INCREMENT,
    member_id BIGINT NOT NULL,
    is_agreed TINYINT(1) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    CONSTRAINT PK_MARKETING_CONSENT_HISTORY PRIMARY KEY (marketing_consent_history_id),
    CONSTRAINT FK_MARKETING_CONSENT_HISTORY_MEMBER
        FOREIGN KEY (member_id)
        REFERENCES member (member_id)
        ON DELETE CASCADE,
    INDEX IDX_MARKETING_CONSENT_HISTORY_MEMBER_ID (member_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
