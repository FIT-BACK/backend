SET @refresh_token_column_exists = (
    SELECT COUNT(*)
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'member'
      AND COLUMN_NAME = 'refresh_token'
);

SET @refresh_token_migration = IF(
    @refresh_token_column_exists = 0,
    'ALTER TABLE member ADD COLUMN refresh_token VARCHAR(512) NULL',
    'DO 1'
);

PREPARE refresh_token_statement FROM @refresh_token_migration;
EXECUTE refresh_token_statement;
DEALLOCATE PREPARE refresh_token_statement;
