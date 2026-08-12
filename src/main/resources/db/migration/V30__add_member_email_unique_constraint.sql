-- 동시 회원가입에서도 동일 이메일이 하나만 저장되도록 UNIQUE 제약 보장
SET @email_unique_columns = (
    SELECT GROUP_CONCAT(k.COLUMN_NAME ORDER BY k.ORDINAL_POSITION)
    FROM information_schema.TABLE_CONSTRAINTS tc
    JOIN information_schema.KEY_COLUMN_USAGE k
      ON k.CONSTRAINT_SCHEMA = tc.CONSTRAINT_SCHEMA
     AND k.TABLE_NAME = tc.TABLE_NAME
     AND k.CONSTRAINT_NAME = tc.CONSTRAINT_NAME
    WHERE tc.TABLE_SCHEMA = DATABASE()
      AND tc.TABLE_NAME = 'member'
      AND tc.CONSTRAINT_TYPE = 'UNIQUE'
      AND tc.CONSTRAINT_NAME = 'UK_MEMBER_EMAIL'
);

SET @sql = CASE
    WHEN @email_unique_columns IS NULL THEN
        'ALTER TABLE member ADD CONSTRAINT UK_MEMBER_EMAIL UNIQUE (email)'
    WHEN @email_unique_columns = 'email' THEN
        'DO 1'
    -- 같은 이름의 잘못된 제약이 있으면 마이그레이션 중단
    ELSE
        'ALTER TABLE member ADD CONSTRAINT UK_MEMBER_EMAIL UNIQUE (email)'
    END;

PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
