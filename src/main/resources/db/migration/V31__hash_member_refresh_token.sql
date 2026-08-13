-- 기존 평문 Refresh Token을 폐기해 배포 후 재로그인 처리
UPDATE member
SET refresh_token = NULL
WHERE refresh_token IS NOT NULL;

-- HMAC-SHA256 결과만 저장하도록 컬럼 계약 변경
ALTER TABLE member
    CHANGE COLUMN refresh_token refresh_token_hash
    CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL;
