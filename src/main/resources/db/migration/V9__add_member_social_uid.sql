-- 카카오 소셜 로그인: member.social_uid 컬럼 + (login_provider, social_uid) 유니크 제약 추가
-- baseline/재실행 안전을 위해 존재 여부를 확인 후 조건부로 적용 (V3 동적 패턴)

-- social_uid 컬럼 추가 (없을 때만)
SET @social_uid_exists = (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'member'
      AND COLUMN_NAME = 'social_uid'
);
SET @sql = IF(@social_uid_exists = 0,
    'ALTER TABLE member ADD COLUMN social_uid VARCHAR(100) NULL',
    'DO 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- (login_provider, social_uid) 유니크 제약 추가 (없을 때만)
-- 이메일 회원은 social_uid=NULL이며 MySQL 유니크는 NULL 다중 허용이라 충돌 없음
SET @uk_exists = (
    SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'member'
      AND CONSTRAINT_TYPE = 'UNIQUE'
      AND CONSTRAINT_NAME = 'UK_MEMBER_PROVIDER_UID'
);
SET @sql = IF(@uk_exists = 0,
    'ALTER TABLE member ADD CONSTRAINT UK_MEMBER_PROVIDER_UID UNIQUE (login_provider, social_uid)',
    'DO 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
