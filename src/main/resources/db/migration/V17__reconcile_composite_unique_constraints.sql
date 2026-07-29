DELETE duplicate_save
FROM closet_save duplicate_save
JOIN closet_save kept_save
  ON kept_save.member_id = duplicate_save.member_id
 AND kept_save.target_type = duplicate_save.target_type
 AND kept_save.target_id = duplicate_save.target_id
 AND kept_save.save_id < duplicate_save.save_id;

SET @has_closet_unique := (
    SELECT COUNT(*)
    FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'closet_save'
      AND INDEX_NAME = 'UK_CLOSET_SAVE_MEMBER_ID_TARGET_TYPE_TARGET_ID'
);
SET @sql := IF(
    @has_closet_unique = 0,
    'ALTER TABLE closet_save ADD CONSTRAINT UK_CLOSET_SAVE_MEMBER_ID_TARGET_TYPE_TARGET_ID UNIQUE (member_id, target_type, target_id)',
    'DO 1'
);
PREPARE statement FROM @sql;
EXECUTE statement;
DEALLOCATE PREPARE statement;

DELETE duplicate_tag
FROM trend_tag duplicate_tag
JOIN trend_tag kept_tag
  ON kept_tag.trend_id = duplicate_tag.trend_id
 AND kept_tag.tag_id = duplicate_tag.tag_id
 AND kept_tag.trend_tag_id < duplicate_tag.trend_tag_id;

SET @has_trend_tag_unique := (
    SELECT COUNT(*)
    FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'trend_tag'
      AND INDEX_NAME = 'UK_TREND_TAG_TREND_ID_TAG_ID'
);
SET @sql := IF(
    @has_trend_tag_unique = 0,
    'ALTER TABLE trend_tag ADD CONSTRAINT UK_TREND_TAG_TREND_ID_TAG_ID UNIQUE (trend_id, tag_id)',
    'DO 1'
);
PREPARE statement FROM @sql;
EXECUTE statement;
DEALLOCATE PREPARE statement;
