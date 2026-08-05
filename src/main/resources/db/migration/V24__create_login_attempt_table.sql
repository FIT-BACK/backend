-- 회원 존재 여부와 관계없이 이메일 단위 로그인 실패 및 잠금 상태 저장
-- 이메일 원문과 member FK 없이 HMAC-SHA256 결과만 저장
CREATE TABLE IF NOT EXISTS login_attempt (
    login_attempt_id BIGINT NOT NULL AUTO_INCREMENT,
    email_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    failed_count INT NOT NULL,
    last_failed_at DATETIME(6) NOT NULL,
    locked_until DATETIME(6) NULL,
    CONSTRAINT PK_LOGIN_ATTEMPT PRIMARY KEY (login_attempt_id),
    CONSTRAINT UK_LOGIN_ATTEMPT_EMAIL_HASH UNIQUE (email_hash),
    INDEX IDX_LOGIN_ATTEMPT_LAST_FAILED_AT (last_failed_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
