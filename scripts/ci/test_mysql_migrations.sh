#!/usr/bin/env bash

set -euo pipefail

mysql_image="${MYSQL_TEST_IMAGE:-mysql:8.4}"
container_name="fitback-migration-test-${GITHUB_RUN_ID:-local}-$$"

cleanup() {
  docker rm --force "$container_name" >/dev/null 2>&1 || true
}
trap cleanup EXIT

docker run --detach \
  --name "$container_name" \
  --env MYSQL_ALLOW_EMPTY_PASSWORD=yes \
  "$mysql_image" >/dev/null

ready=0
for _ in {1..60}; do
  mysql_port="$(docker exec "$container_name" mysql -uroot \
    --batch --skip-column-names -e 'SELECT @@port' 2>/dev/null || true)"
  if [ "$mysql_port" = '3306' ]; then
    ready=1
    break
  fi
  sleep 1
done

if [ "$ready" -ne 1 ]; then
  echo 'MySQL migration test container did not become ready.' >&2
  exit 1
fi

docker exec "$container_name" mysql -uroot -e \
  'CREATE DATABASE fitback; CREATE DATABASE fitback_existing_refresh_token;'

seed_baseline_schema() {
  local database="$1"
  local member_columns="$2"

  printf '%s\n' \
    "CREATE TABLE member (${member_columns});" \
    'INSERT INTO member (member_id) VALUES (1);' \
    'CREATE TABLE analysis_report (report_id BIGINT NOT NULL PRIMARY KEY, member_id BIGINT NOT NULL, image_url VARCHAR(255) NOT NULL, match_percentage INT NOT NULL, CONSTRAINT FK_ANALYSIS_REPORT_MEMBER_OLD FOREIGN KEY (member_id) REFERENCES member (member_id));' \
    'CREATE TABLE product (product_id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, external_product_id VARCHAR(100) NULL, name VARCHAR(255) NOT NULL, brand_name VARCHAR(100) NULL, seller_name VARCHAR(100) NOT NULL, price INT NOT NULL, average_price INT NULL, category VARCHAR(50) NOT NULL, season VARCHAR(20) NULL, gender VARCHAR(10) NULL, purchase_url VARCHAR(2048) NOT NULL, image_url VARCHAR(2048) NOT NULL, source_api VARCHAR(50) NOT NULL, created_at DATETIME(6) NOT NULL, updated_at DATETIME(6) NULL);' \
    "INSERT INTO product (external_product_id, name, brand_name, seller_name, price, average_price, category, season, gender, purchase_url, image_url, source_api, created_at) VALUES ('legacy-1', 'Legacy Product', NULL, 'Legacy Seller', 10000, NULL, 'legacy-custom-category', NULL, NULL, 'https://example.com/product', 'https://example.com/product.jpg', 'legacy', NOW());" \
    'CREATE TABLE member_tag (member_tag_id BIGINT NOT NULL PRIMARY KEY, member_id BIGINT NOT NULL, tag_id BIGINT NOT NULL, CONSTRAINT FK_MEMBER_TAG_MEMBER_OLD FOREIGN KEY (member_id) REFERENCES member (member_id));' \
    'CREATE TABLE report_tag (report_tag_id BIGINT NOT NULL PRIMARY KEY, report_id BIGINT NOT NULL, tag_id BIGINT NOT NULL, CONSTRAINT FK_REPORT_TAG_REPORT_OLD FOREIGN KEY (report_id) REFERENCES analysis_report (report_id));' \
    'CREATE TABLE closet_save (closet_save_id BIGINT NOT NULL PRIMARY KEY, member_id BIGINT NOT NULL, target_type VARCHAR(30) NOT NULL, target_id BIGINT NOT NULL, CONSTRAINT FK_CLOSET_SAVE_MEMBER_OLD FOREIGN KEY (member_id) REFERENCES member (member_id));' \
    'CREATE TABLE lookbook_like (lookbook_like_id BIGINT NOT NULL PRIMARY KEY, member_id BIGINT NOT NULL, lookbook_id BIGINT NOT NULL, CONSTRAINT FK_LOOKBOOK_LIKE_MEMBER_OLD FOREIGN KEY (member_id) REFERENCES member (member_id));' \
    'CREATE TABLE recommended_item (recommend_id BIGINT NOT NULL PRIMARY KEY, report_id BIGINT NOT NULL, product_id BIGINT NOT NULL, CONSTRAINT FK_RECOMMENDED_ITEM_REPORT_OLD FOREIGN KEY (report_id) REFERENCES analysis_report (report_id));' \
    'CREATE TABLE trend_content (trend_id BIGINT NOT NULL PRIMARY KEY, created_by BIGINT NOT NULL, title VARCHAR(100) NOT NULL, CONSTRAINT FK_TREND_CONTENT_MEMBER_OLD FOREIGN KEY (created_by) REFERENCES member (member_id));' \
    'CREATE TABLE trend_tag (trend_tag_id BIGINT NOT NULL PRIMARY KEY, trend_id BIGINT NOT NULL, tag_id BIGINT NOT NULL, CONSTRAINT FK_TREND_TAG_TREND_OLD FOREIGN KEY (trend_id) REFERENCES trend_content (trend_id));' \
    | docker exec -i "$container_name" mysql -uroot "$database"
}

seed_baseline_schema fitback 'member_id BIGINT NOT NULL PRIMARY KEY'
seed_baseline_schema fitback_existing_refresh_token 'member_id BIGINT NOT NULL PRIMARY KEY, refresh_token VARCHAR(512) NULL'

for database in fitback fitback_existing_refresh_token; do
  for migration in src/main/resources/db/migration/V*.sql; do
    if [ "$(basename "$migration")" = 'V4__update_image_upload_policy.sql' ]; then
      printf '%s\n' \
        "INSERT INTO member (member_id) VALUES (9001);" \
        "INSERT INTO image (image_id, owner_id, object_key, purpose, content_type, file_size, status, visibility, retry_count, created_at) VALUES" \
        "('legacy-analysis', 9001, 'prod/images/analysis_original/legacy-analysis.jpg', 'ANALYSIS_ORIGINAL', 'image/jpeg', 1024, 'PENDING', 'PRIVATE', 0, NOW())," \
        "('legacy-lookbook-original', 9001, 'prod/images/lookbook_original/legacy-original.jpg', 'LOOKBOOK_ORIGINAL', 'image/jpeg', 1024, 'READY', 'PRIVATE', 0, NOW())," \
        "('legacy-lookbook-matched', 9001, 'prod/images/lookbook_matched/legacy-matched.jpg', 'LOOKBOOK_MATCHED', 'image/jpeg', 1024, 'DELETE_FAILED', 'PRIVATE', 1, NOW())," \
        "('legacy-profile', 9001, 'prod/images/profile/legacy-profile.jpg', 'PROFILE', 'image/jpeg', 1024, 'REJECTED', 'PRIVATE', 0, NOW());" \
        | docker exec -i "$container_name" mysql -uroot "$database"
    fi
    docker exec -i "$container_name" mysql -uroot "$database" < "$migration"
  done
done

expected_product_contract="$(printf '%s\n' \
  'availability:NO:varchar' \
  'category:YES:varchar' \
  'currency:YES:char' \
  'current_price:YES:decimal' \
  'identity_strategy:NO:varchar' \
  'materialization_key:YES:char' \
  'provider_identity_key:YES:char' \
  'snapshot_expires_at:YES:datetime' \
  'storage_mode:NO:varchar')"

validate_product_contract() {
  local database="$1"
  local product_contract
  local legacy_product_contract
  local product_category_length

  product_contract="$(docker exec "$container_name" mysql -uroot \
    --batch --skip-column-names \
    -e "SELECT CONCAT(COLUMN_NAME, ':', IS_NULLABLE, ':', DATA_TYPE)
        FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = '$database'
          AND TABLE_NAME = 'product'
          AND COLUMN_NAME IN (
            'category',
            'identity_strategy',
            'provider_identity_key',
            'materialization_key',
            'storage_mode',
            'current_price',
            'currency',
            'availability',
            'snapshot_expires_at'
          )
        ORDER BY COLUMN_NAME;")"

  if [ "$product_contract" != "$expected_product_contract" ]; then
    echo "Unexpected product migration contract in $database:" >&2
    printf '%s\n' "$product_contract" >&2
    exit 1
  fi

  legacy_product_contract="$(docker exec "$container_name" mysql -uroot \
    --batch --skip-column-names \
    -e "SELECT CONCAT(
          identity_strategy, ':',
          storage_mode, ':',
          availability, ':',
          CHAR_LENGTH(materialization_key), ':',
          IF(current_price IS NULL, 'NO_GUESSED_PRICE', 'GUESSED_PRICE'), ':',
          category
        )
        FROM $database.product
        WHERE external_product_id = 'legacy-1';")"

  if [ "$legacy_product_contract" != 'SNAPSHOT_UUID:SNAPSHOT:UNKNOWN:64:NO_GUESSED_PRICE:OTHER' ]; then
    echo "Unexpected legacy product backfill in $database: $legacy_product_contract" >&2
    exit 1
  fi

  product_category_length="$(docker exec "$container_name" mysql -uroot \
    --batch --skip-column-names \
    -e "SELECT CHARACTER_MAXIMUM_LENGTH
        FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = '$database'
          AND TABLE_NAME = 'product'
          AND COLUMN_NAME = 'category';")"

  if [ "$product_category_length" != '30' ]; then
    echo "Unexpected product.category length in $database: $product_category_length" >&2
    exit 1
  fi
}

for database in fitback fitback_existing_refresh_token; do
  validate_product_contract "$database"
done

actual_contract="$(docker exec "$container_name" mysql -uroot \
  --batch --skip-column-names \
  -e "SELECT CONCAT(TABLE_NAME, '.', COLUMN_NAME, '=', IS_NULLABLE)
      FROM information_schema.COLUMNS
      WHERE TABLE_SCHEMA = 'fitback'
        AND (
          (TABLE_NAME = 'image' AND COLUMN_NAME = 'presigned_expires_at')
          OR (TABLE_NAME = 'member' AND COLUMN_NAME = 'refresh_token')
          OR (
            TABLE_NAME = 'member_notification_setting'
            AND COLUMN_NAME IN (
              'member_id',
              'analysis_complete_enabled',
              'lookbook_liked_enabled',
              'trend_update_enabled',
              'marketing_enabled',
              'updated_at'
            )
          )
          OR (
            TABLE_NAME = 'marketing_consent_history'
            AND COLUMN_NAME IN ('marketing_consent_history_id', 'member_id', 'is_agreed', 'created_at')
          )
          OR (
            TABLE_NAME = 'withdrawal_email_block'
            AND COLUMN_NAME IN ('withdrawal_id', 'email_hash', 'blocked_until', 'created_at')
          )
          OR (
            TABLE_NAME = 'analysis_report'
            AND COLUMN_NAME IN ('original_image_id', 'deleted_at', 'purge_after')
          )
        )
      ORDER BY TABLE_NAME, COLUMN_NAME;")"

expected_contract="$(printf '%s\n' \
  'analysis_report.deleted_at=YES' \
  'analysis_report.original_image_id=YES' \
  'analysis_report.purge_after=YES' \
  'image.presigned_expires_at=YES' \
  'marketing_consent_history.created_at=NO' \
  'marketing_consent_history.is_agreed=NO' \
  'marketing_consent_history.marketing_consent_history_id=NO' \
  'marketing_consent_history.member_id=NO' \
  'member.refresh_token=YES' \
  'member_notification_setting.analysis_complete_enabled=NO' \
  'member_notification_setting.lookbook_liked_enabled=NO' \
  'member_notification_setting.marketing_enabled=NO' \
  'member_notification_setting.member_id=NO' \
  'member_notification_setting.trend_update_enabled=NO' \
  'member_notification_setting.updated_at=NO' \
  'withdrawal_email_block.blocked_until=NO' \
  'withdrawal_email_block.created_at=NO' \
  'withdrawal_email_block.email_hash=NO' \
  'withdrawal_email_block.withdrawal_id=NO')"

if [ "$actual_contract" != "$expected_contract" ]; then
  echo 'Unexpected MySQL migration contract:' >&2
  printf '%s\n' "$actual_contract" >&2
  exit 1
fi

image_policy_contract="$(docker exec "$container_name" mysql -uroot \
  --batch --skip-column-names \
  -e "SELECT CONCAT(image_id, ':', purpose, ':', status, ':', object_key)
      FROM fitback.image
      WHERE image_id LIKE 'legacy-%'
      ORDER BY image_id;")"

expected_image_policy_contract="$(printf '%s\n' \
  'legacy-analysis:ANALYSIS_ORIGINAL:PENDING:prod/images/analysis_original/legacy-analysis.jpg' \
  'legacy-lookbook-matched:LOOKBOOK_MATCHED:DELETE_FAILED:prod/images/lookbook_matched/legacy-matched.jpg' \
  'legacy-lookbook-original:LOOKBOOK_ORIGINAL:READY:prod/images/lookbook_original/legacy-original.jpg' \
  'legacy-profile:PROFILE:REJECTED:prod/images/profile/legacy-profile.jpg')"

if [ "$image_policy_contract" != "$expected_image_policy_contract" ]; then
  echo 'Unexpected V4 image policy migration result:' >&2
  printf '%s\n' "$image_policy_contract" >&2
  exit 1
fi

image_constraints="$(docker exec "$container_name" mysql -uroot \
  --batch --skip-column-names \
  -e "SELECT CONSTRAINT_NAME
      FROM information_schema.TABLE_CONSTRAINTS
      WHERE TABLE_SCHEMA = 'fitback'
        AND TABLE_NAME = 'image'
        AND CONSTRAINT_NAME IN ('CK_IMAGE_PURPOSE', 'CK_IMAGE_STATUS')
      ORDER BY CONSTRAINT_NAME;")"

expected_image_constraints="$(printf '%s\n' \
  'CK_IMAGE_PURPOSE' \
  'CK_IMAGE_STATUS')"

if [ "$image_constraints" != "$expected_image_constraints" ]; then
  echo 'Unexpected image check constraints after V4:' >&2
  printf '%s\n' "$image_constraints" >&2
  exit 1
fi

docker exec "$container_name" mysql -uroot fitback -e \
  "UPDATE image SET purpose = 'ANALYSIS' WHERE image_id = 'legacy-profile';
   UPDATE image SET purpose = 'PROFILE' WHERE image_id = 'legacy-profile';
   UPDATE image SET purpose = 'LOOKBOOK_ORIGINAL' WHERE image_id = 'legacy-profile';"

docker exec "$container_name" mysql -uroot fitback -e \
  "UPDATE image SET status = 'PENDING_UPLOAD' WHERE image_id = 'legacy-profile';
   UPDATE image SET status = 'REJECTED' WHERE image_id = 'legacy-profile';
   UPDATE image SET status = 'PENDING' WHERE image_id = 'legacy-profile';"

docker exec "$container_name" mysql -uroot fitback -e \
  "INSERT INTO image (
       image_id, owner_id, object_key, purpose, content_type,
       file_size, status, visibility, retry_count, created_at
   ) VALUES
   (
       'rollback-legacy-write', 9001, 'images/analysis/9001/2026/07/rollback-legacy.jpg',
       'ANALYSIS_ORIGINAL', 'image/jpeg', 1024, 'PENDING', 'PRIVATE', 0, NOW()
   ),
   (
       'future-contract-write', 9001, 'images/analysis/9001/2026/07/future-contract.jpg',
       'ANALYSIS', 'image/jpeg', 1024, 'PENDING_UPLOAD', 'PRIVATE', 0, NOW()
   );"

if docker exec "$container_name" mysql -uroot fitback -e \
  "UPDATE image SET purpose = 'UNKNOWN' WHERE image_id = 'legacy-profile';" \
  >/dev/null 2>&1; then
  echo 'CK_IMAGE_PURPOSE accepted an unknown purpose after V4.' >&2
  exit 1
fi

if docker exec "$container_name" mysql -uroot fitback -e \
  "UPDATE image SET status = 'UNKNOWN' WHERE image_id = 'legacy-profile';" \
  >/dev/null 2>&1; then
  echo 'CK_IMAGE_STATUS accepted an unknown status after V4.' >&2
  exit 1
fi

for database in fitback fitback_existing_refresh_token; do
  refresh_token_contract="$(docker exec "$container_name" mysql -uroot \
    --batch --skip-column-names \
    -e "SELECT CONCAT(IS_NULLABLE, ':', CHARACTER_MAXIMUM_LENGTH)
        FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = '$database'
          AND TABLE_NAME = 'member'
          AND COLUMN_NAME = 'refresh_token';")"

  if [ "$refresh_token_contract" != 'YES:512' ]; then
    echo "Unexpected member.refresh_token contract in $database: $refresh_token_contract" >&2
    exit 1
  fi
done

notification_defaults="$(docker exec "$container_name" mysql -uroot \
  --batch --skip-column-names \
  -e "SELECT CONCAT(
          member_id, ':',
          analysis_complete_enabled, ':',
          lookbook_liked_enabled, ':',
          trend_update_enabled, ':',
          marketing_enabled
      )
      FROM fitback.member_notification_setting
      ORDER BY member_id;")"

expected_notification_defaults="$(printf '%s\n' \
  '1:1:1:0:0' \
  '9001:1:1:0:0')"

if [ "$notification_defaults" != "$expected_notification_defaults" ]; then
  echo "Unexpected member_notification_setting defaults: $notification_defaults" >&2
  exit 1
fi

member_delete_cascades="$(docker exec "$container_name" mysql -uroot \
  --batch --skip-column-names \
  -e "SELECT CONCAT(k.TABLE_NAME, '.', k.COLUMN_NAME, '->', k.REFERENCED_TABLE_NAME, '=', rc.DELETE_RULE)
      FROM information_schema.KEY_COLUMN_USAGE k
      JOIN information_schema.REFERENTIAL_CONSTRAINTS rc
        ON rc.CONSTRAINT_SCHEMA = k.CONSTRAINT_SCHEMA
       AND rc.CONSTRAINT_NAME = k.CONSTRAINT_NAME
      WHERE k.TABLE_SCHEMA = 'fitback'
        AND (
          (k.TABLE_NAME = 'member_tag' AND k.COLUMN_NAME = 'member_id' AND k.REFERENCED_TABLE_NAME = 'member')
          OR (k.TABLE_NAME = 'analysis_report' AND k.COLUMN_NAME = 'member_id' AND k.REFERENCED_TABLE_NAME = 'member')
          OR (k.TABLE_NAME = 'report_tag' AND k.COLUMN_NAME = 'report_id' AND k.REFERENCED_TABLE_NAME = 'analysis_report')
          OR (k.TABLE_NAME = 'closet_save' AND k.COLUMN_NAME = 'member_id' AND k.REFERENCED_TABLE_NAME = 'member')
          OR (k.TABLE_NAME = 'lookbook_like' AND k.COLUMN_NAME = 'member_id' AND k.REFERENCED_TABLE_NAME = 'member')
          OR (k.TABLE_NAME = 'recommended_item' AND k.COLUMN_NAME = 'report_id' AND k.REFERENCED_TABLE_NAME = 'analysis_report')
          OR (k.TABLE_NAME = 'trend_content' AND k.COLUMN_NAME = 'created_by' AND k.REFERENCED_TABLE_NAME = 'member')
          OR (k.TABLE_NAME = 'trend_tag' AND k.COLUMN_NAME = 'trend_id' AND k.REFERENCED_TABLE_NAME = 'trend_content')
        )
      ORDER BY k.TABLE_NAME, k.COLUMN_NAME, k.REFERENCED_TABLE_NAME;")"

expected_member_delete_cascades="$(printf '%s\n' \
  'analysis_report.member_id->member=CASCADE' \
  'closet_save.member_id->member=CASCADE' \
  'lookbook_like.member_id->member=CASCADE' \
  'member_tag.member_id->member=CASCADE' \
  'recommended_item.report_id->analysis_report=CASCADE' \
  'report_tag.report_id->analysis_report=CASCADE' \
  'trend_content.created_by->member=CASCADE' \
  'trend_tag.trend_id->trend_content=CASCADE')"

if [ "$member_delete_cascades" != "$expected_member_delete_cascades" ]; then
  echo 'Unexpected member delete cascade contract:' >&2
  printf '%s\n' "$member_delete_cascades" >&2
  exit 1
fi

echo 'MySQL migration tests passed.'
