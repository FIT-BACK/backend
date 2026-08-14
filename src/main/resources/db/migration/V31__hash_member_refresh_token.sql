-- 이전 버전으로 롤백해도 기동할 수 있도록 기존 컬럼을 유지한 채 해시 컬럼 추가
ALTER TABLE member
    ADD COLUMN refresh_token_hash
    CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL
    AFTER refresh_token;

-- 기존 평문 Refresh Token을 폐기해 배포 후 재로그인 처리
UPDATE member
SET refresh_token = NULL
WHERE refresh_token IS NOT NULL;
