-- 회원 hard delete 시 관련 데이터가 함께 삭제되도록 기존 FK를 ON DELETE CASCADE로 재설정
-- baseline 스키마의 FK 이름이 환경마다 다를 수 있어, 컬럼 기준으로 기존 FK를 찾아 필요한 경우에만 재생성

-- member_tag.member_id -> member
SET @fk := (SELECT k.CONSTRAINT_NAME FROM information_schema.KEY_COLUMN_USAGE k
            WHERE k.TABLE_SCHEMA = DATABASE() AND k.TABLE_NAME = 'member_tag'
              AND k.COLUMN_NAME = 'member_id' AND k.REFERENCED_TABLE_NAME = 'member' LIMIT 1);
SET @delete_rule := (SELECT rc.DELETE_RULE FROM information_schema.KEY_COLUMN_USAGE k
                     JOIN information_schema.REFERENTIAL_CONSTRAINTS rc
                       ON rc.CONSTRAINT_SCHEMA = k.CONSTRAINT_SCHEMA
                      AND rc.CONSTRAINT_NAME = k.CONSTRAINT_NAME
                     WHERE k.TABLE_SCHEMA = DATABASE() AND k.TABLE_NAME = 'member_tag'
                       AND k.COLUMN_NAME = 'member_id' AND k.REFERENCED_TABLE_NAME = 'member' LIMIT 1);
SET @needs_recreate := (@fk IS NULL OR COALESCE(@delete_rule, '') <> 'CASCADE');
SET @sql := IF(@fk IS NOT NULL AND @needs_recreate, CONCAT('ALTER TABLE member_tag DROP FOREIGN KEY `', REPLACE(@fk, '`', '``'), '`'), 'DO 1');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;
SET @sql := IF(@needs_recreate, 'ALTER TABLE member_tag ADD CONSTRAINT FK_MEMBER_TAG_MEMBER FOREIGN KEY (member_id) REFERENCES member (member_id) ON DELETE CASCADE', 'DO 1');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

-- analysis_report.member_id -> member
SET @fk := (SELECT k.CONSTRAINT_NAME FROM information_schema.KEY_COLUMN_USAGE k
            WHERE k.TABLE_SCHEMA = DATABASE() AND k.TABLE_NAME = 'analysis_report'
              AND k.COLUMN_NAME = 'member_id' AND k.REFERENCED_TABLE_NAME = 'member' LIMIT 1);
SET @delete_rule := (SELECT rc.DELETE_RULE FROM information_schema.KEY_COLUMN_USAGE k
                     JOIN information_schema.REFERENTIAL_CONSTRAINTS rc
                       ON rc.CONSTRAINT_SCHEMA = k.CONSTRAINT_SCHEMA
                      AND rc.CONSTRAINT_NAME = k.CONSTRAINT_NAME
                     WHERE k.TABLE_SCHEMA = DATABASE() AND k.TABLE_NAME = 'analysis_report'
                       AND k.COLUMN_NAME = 'member_id' AND k.REFERENCED_TABLE_NAME = 'member' LIMIT 1);
SET @needs_recreate := (@fk IS NULL OR COALESCE(@delete_rule, '') <> 'CASCADE');
SET @sql := IF(@fk IS NOT NULL AND @needs_recreate, CONCAT('ALTER TABLE analysis_report DROP FOREIGN KEY `', REPLACE(@fk, '`', '``'), '`'), 'DO 1');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;
SET @sql := IF(@needs_recreate, 'ALTER TABLE analysis_report ADD CONSTRAINT FK_ANALYSIS_REPORT_MEMBER FOREIGN KEY (member_id) REFERENCES member (member_id) ON DELETE CASCADE', 'DO 1');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

-- report_tag.report_id -> analysis_report
SET @fk := (SELECT k.CONSTRAINT_NAME FROM information_schema.KEY_COLUMN_USAGE k
            WHERE k.TABLE_SCHEMA = DATABASE() AND k.TABLE_NAME = 'report_tag'
              AND k.COLUMN_NAME = 'report_id' AND k.REFERENCED_TABLE_NAME = 'analysis_report' LIMIT 1);
SET @delete_rule := (SELECT rc.DELETE_RULE FROM information_schema.KEY_COLUMN_USAGE k
                     JOIN information_schema.REFERENTIAL_CONSTRAINTS rc
                       ON rc.CONSTRAINT_SCHEMA = k.CONSTRAINT_SCHEMA
                      AND rc.CONSTRAINT_NAME = k.CONSTRAINT_NAME
                     WHERE k.TABLE_SCHEMA = DATABASE() AND k.TABLE_NAME = 'report_tag'
                       AND k.COLUMN_NAME = 'report_id' AND k.REFERENCED_TABLE_NAME = 'analysis_report' LIMIT 1);
SET @needs_recreate := (@fk IS NULL OR COALESCE(@delete_rule, '') <> 'CASCADE');
SET @sql := IF(@fk IS NOT NULL AND @needs_recreate, CONCAT('ALTER TABLE report_tag DROP FOREIGN KEY `', REPLACE(@fk, '`', '``'), '`'), 'DO 1');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;
SET @sql := IF(@needs_recreate, 'ALTER TABLE report_tag ADD CONSTRAINT FK_REPORT_TAG_REPORT FOREIGN KEY (report_id) REFERENCES analysis_report (report_id) ON DELETE CASCADE', 'DO 1');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

-- closet_save.member_id -> member
SET @fk := (SELECT k.CONSTRAINT_NAME FROM information_schema.KEY_COLUMN_USAGE k
            WHERE k.TABLE_SCHEMA = DATABASE() AND k.TABLE_NAME = 'closet_save'
              AND k.COLUMN_NAME = 'member_id' AND k.REFERENCED_TABLE_NAME = 'member' LIMIT 1);
SET @delete_rule := (SELECT rc.DELETE_RULE FROM information_schema.KEY_COLUMN_USAGE k
                     JOIN information_schema.REFERENTIAL_CONSTRAINTS rc
                       ON rc.CONSTRAINT_SCHEMA = k.CONSTRAINT_SCHEMA
                      AND rc.CONSTRAINT_NAME = k.CONSTRAINT_NAME
                     WHERE k.TABLE_SCHEMA = DATABASE() AND k.TABLE_NAME = 'closet_save'
                       AND k.COLUMN_NAME = 'member_id' AND k.REFERENCED_TABLE_NAME = 'member' LIMIT 1);
SET @needs_recreate := (@fk IS NULL OR COALESCE(@delete_rule, '') <> 'CASCADE');
SET @sql := IF(@fk IS NOT NULL AND @needs_recreate, CONCAT('ALTER TABLE closet_save DROP FOREIGN KEY `', REPLACE(@fk, '`', '``'), '`'), 'DO 1');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;
SET @sql := IF(@needs_recreate, 'ALTER TABLE closet_save ADD CONSTRAINT FK_CLOSET_SAVE_MEMBER FOREIGN KEY (member_id) REFERENCES member (member_id) ON DELETE CASCADE', 'DO 1');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

-- lookbook_like.member_id -> member  (룩북 본체는 재지정하므로 lookbook_id에는 cascade 없음)
SET @fk := (SELECT k.CONSTRAINT_NAME FROM information_schema.KEY_COLUMN_USAGE k
            WHERE k.TABLE_SCHEMA = DATABASE() AND k.TABLE_NAME = 'lookbook_like'
              AND k.COLUMN_NAME = 'member_id' AND k.REFERENCED_TABLE_NAME = 'member' LIMIT 1);
SET @delete_rule := (SELECT rc.DELETE_RULE FROM information_schema.KEY_COLUMN_USAGE k
                     JOIN information_schema.REFERENTIAL_CONSTRAINTS rc
                       ON rc.CONSTRAINT_SCHEMA = k.CONSTRAINT_SCHEMA
                      AND rc.CONSTRAINT_NAME = k.CONSTRAINT_NAME
                     WHERE k.TABLE_SCHEMA = DATABASE() AND k.TABLE_NAME = 'lookbook_like'
                       AND k.COLUMN_NAME = 'member_id' AND k.REFERENCED_TABLE_NAME = 'member' LIMIT 1);
SET @needs_recreate := (@fk IS NULL OR COALESCE(@delete_rule, '') <> 'CASCADE');
SET @sql := IF(@fk IS NOT NULL AND @needs_recreate, CONCAT('ALTER TABLE lookbook_like DROP FOREIGN KEY `', REPLACE(@fk, '`', '``'), '`'), 'DO 1');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;
SET @sql := IF(@needs_recreate, 'ALTER TABLE lookbook_like ADD CONSTRAINT FK_LOOKBOOK_LIKE_MEMBER FOREIGN KEY (member_id) REFERENCES member (member_id) ON DELETE CASCADE', 'DO 1');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

-- recommended_item.report_id -> analysis_report  (report_id를 쓰는 FK가 복수라 단일 컬럼 FK만 선택)
SET @fk := (SELECT k.CONSTRAINT_NAME FROM information_schema.KEY_COLUMN_USAGE k
            WHERE k.TABLE_SCHEMA = DATABASE() AND k.TABLE_NAME = 'recommended_item'
              AND k.COLUMN_NAME = 'report_id' AND k.REFERENCED_TABLE_NAME = 'analysis_report'
              AND (SELECT COUNT(*) FROM information_schema.KEY_COLUMN_USAGE k2
                   WHERE k2.CONSTRAINT_SCHEMA = k.CONSTRAINT_SCHEMA
                     AND k2.CONSTRAINT_NAME = k.CONSTRAINT_NAME
                     AND k2.TABLE_SCHEMA = k.TABLE_SCHEMA
                     AND k2.TABLE_NAME = k.TABLE_NAME) = 1
            LIMIT 1);
SET @delete_rule := (SELECT rc.DELETE_RULE FROM information_schema.KEY_COLUMN_USAGE k
                     JOIN information_schema.REFERENTIAL_CONSTRAINTS rc
                       ON rc.CONSTRAINT_SCHEMA = k.CONSTRAINT_SCHEMA
                      AND rc.CONSTRAINT_NAME = k.CONSTRAINT_NAME
                     WHERE k.TABLE_SCHEMA = DATABASE() AND k.TABLE_NAME = 'recommended_item'
                       AND k.COLUMN_NAME = 'report_id' AND k.REFERENCED_TABLE_NAME = 'analysis_report'
                       AND (SELECT COUNT(*) FROM information_schema.KEY_COLUMN_USAGE k2
                            WHERE k2.CONSTRAINT_SCHEMA = k.CONSTRAINT_SCHEMA
                              AND k2.CONSTRAINT_NAME = k.CONSTRAINT_NAME
                              AND k2.TABLE_SCHEMA = k.TABLE_SCHEMA
                              AND k2.TABLE_NAME = k.TABLE_NAME) = 1
                     LIMIT 1);
SET @needs_recreate := (@fk IS NULL OR COALESCE(@delete_rule, '') <> 'CASCADE');
SET @sql := IF(@fk IS NOT NULL AND @needs_recreate, CONCAT('ALTER TABLE recommended_item DROP FOREIGN KEY `', REPLACE(@fk, '`', '``'), '`'), 'DO 1');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;
SET @sql := IF(@needs_recreate, 'ALTER TABLE recommended_item ADD CONSTRAINT FK_RECOMMENDED_ITEM_REPORT FOREIGN KEY (report_id) REFERENCES analysis_report (report_id) ON DELETE CASCADE', 'DO 1');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

-- trend_content.created_by -> member
SET @fk := (SELECT k.CONSTRAINT_NAME FROM information_schema.KEY_COLUMN_USAGE k
            WHERE k.TABLE_SCHEMA = DATABASE() AND k.TABLE_NAME = 'trend_content'
              AND k.COLUMN_NAME = 'created_by' AND k.REFERENCED_TABLE_NAME = 'member' LIMIT 1);
SET @delete_rule := (SELECT rc.DELETE_RULE FROM information_schema.KEY_COLUMN_USAGE k
                     JOIN information_schema.REFERENTIAL_CONSTRAINTS rc
                       ON rc.CONSTRAINT_SCHEMA = k.CONSTRAINT_SCHEMA
                      AND rc.CONSTRAINT_NAME = k.CONSTRAINT_NAME
                     WHERE k.TABLE_SCHEMA = DATABASE() AND k.TABLE_NAME = 'trend_content'
                       AND k.COLUMN_NAME = 'created_by' AND k.REFERENCED_TABLE_NAME = 'member' LIMIT 1);
SET @needs_recreate := (@fk IS NULL OR COALESCE(@delete_rule, '') <> 'CASCADE');
SET @sql := IF(@fk IS NOT NULL AND @needs_recreate, CONCAT('ALTER TABLE trend_content DROP FOREIGN KEY `', REPLACE(@fk, '`', '``'), '`'), 'DO 1');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;
SET @sql := IF(@needs_recreate, 'ALTER TABLE trend_content ADD CONSTRAINT FK_TREND_CONTENT_MEMBER FOREIGN KEY (created_by) REFERENCES member (member_id) ON DELETE CASCADE', 'DO 1');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

-- trend_tag.trend_id -> trend_content
SET @fk := (SELECT k.CONSTRAINT_NAME FROM information_schema.KEY_COLUMN_USAGE k
            WHERE k.TABLE_SCHEMA = DATABASE() AND k.TABLE_NAME = 'trend_tag'
              AND k.COLUMN_NAME = 'trend_id' AND k.REFERENCED_TABLE_NAME = 'trend_content' LIMIT 1);
SET @delete_rule := (SELECT rc.DELETE_RULE FROM information_schema.KEY_COLUMN_USAGE k
                     JOIN information_schema.REFERENTIAL_CONSTRAINTS rc
                       ON rc.CONSTRAINT_SCHEMA = k.CONSTRAINT_SCHEMA
                      AND rc.CONSTRAINT_NAME = k.CONSTRAINT_NAME
                     WHERE k.TABLE_SCHEMA = DATABASE() AND k.TABLE_NAME = 'trend_tag'
                       AND k.COLUMN_NAME = 'trend_id' AND k.REFERENCED_TABLE_NAME = 'trend_content' LIMIT 1);
SET @needs_recreate := (@fk IS NULL OR COALESCE(@delete_rule, '') <> 'CASCADE');
SET @sql := IF(@fk IS NOT NULL AND @needs_recreate, CONCAT('ALTER TABLE trend_tag DROP FOREIGN KEY `', REPLACE(@fk, '`', '``'), '`'), 'DO 1');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;
SET @sql := IF(@needs_recreate, 'ALTER TABLE trend_tag ADD CONSTRAINT FK_TREND_TAG_TREND FOREIGN KEY (trend_id) REFERENCES trend_content (trend_id) ON DELETE CASCADE', 'DO 1');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;
