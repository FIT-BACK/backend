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
    'INSERT INTO member (member_id) VALUES (1), (8001);' \
    'CREATE TABLE analysis_report (report_id BIGINT NOT NULL PRIMARY KEY, member_id BIGINT NOT NULL, image_url VARCHAR(255) NOT NULL, match_percentage INT NOT NULL, CONSTRAINT FK_ANALYSIS_REPORT_MEMBER_OLD FOREIGN KEY (member_id) REFERENCES member (member_id));' \
    "INSERT INTO analysis_report (report_id, member_id, image_url, match_percentage) VALUES (7001, 8001, 'https://example.com/analysis.jpg', 70);" \
    'CREATE TABLE product (product_id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, external_product_id VARCHAR(100) NULL, name VARCHAR(255) NOT NULL, brand_name VARCHAR(100) NULL, seller_name VARCHAR(100) NOT NULL, price INT NOT NULL, average_price INT NULL, category VARCHAR(50) NOT NULL, season VARCHAR(20) NULL, gender VARCHAR(10) NULL, purchase_url VARCHAR(2048) NOT NULL, image_url VARCHAR(2048) NOT NULL, source_api VARCHAR(50) NOT NULL, created_at DATETIME(6) NOT NULL, updated_at DATETIME(6) NULL);' \
    "INSERT INTO product (external_product_id, name, brand_name, seller_name, price, average_price, category, season, gender, purchase_url, image_url, source_api, created_at) VALUES ('legacy-1', 'Legacy Product', NULL, 'Legacy Seller', 10000, NULL, 'legacy-custom-category', NULL, NULL, 'https://example.com/product', 'https://example.com/product.jpg', 'legacy', NOW());" \
    'CREATE TABLE recommended_item (recommend_id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, report_id BIGINT NOT NULL, product_id BIGINT NOT NULL, `rank` INT NOT NULL, category VARCHAR(50) NOT NULL, similarity_score INT NOT NULL, is_value_match BOOLEAN NOT NULL, created_at DATETIME(6) NOT NULL);' \
    "INSERT INTO recommended_item (report_id, product_id, \`rank\`, category, similarity_score, is_value_match, created_at) VALUES (7001, 1, 1, 'TOP', 90, TRUE, NOW());" \
    'CREATE TABLE tag (tag_id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, tag_name VARCHAR(50) NOT NULL, tag_type VARCHAR(30) NOT NULL, created_at DATETIME(6) NOT NULL, updated_at DATETIME(6) NULL, CONSTRAINT UK_TAG_NAME UNIQUE (tag_name));' \
    "INSERT INTO tag (tag_name, tag_type, created_at) VALUES ('기존태그', 'DETAIL', NOW());" \
    'CREATE TABLE member_tag (member_tag_id BIGINT NOT NULL PRIMARY KEY, member_id BIGINT NOT NULL, tag_id BIGINT NOT NULL, CONSTRAINT FK_MEMBER_TAG_MEMBER_OLD FOREIGN KEY (member_id) REFERENCES member (member_id));' \
    'CREATE TABLE report_tag (report_tag_id BIGINT NOT NULL PRIMARY KEY, report_id BIGINT NOT NULL, tag_id BIGINT NOT NULL, CONSTRAINT FK_REPORT_TAG_REPORT_OLD FOREIGN KEY (report_id) REFERENCES analysis_report (report_id));' \
    'CREATE TABLE closet_save (closet_save_id BIGINT NOT NULL PRIMARY KEY, member_id BIGINT NOT NULL, target_type VARCHAR(30) NOT NULL, target_id BIGINT NOT NULL, CONSTRAINT FK_CLOSET_SAVE_MEMBER_OLD FOREIGN KEY (member_id) REFERENCES member (member_id));' \
    'CREATE TABLE lookbook_like (lookbook_like_id BIGINT NOT NULL PRIMARY KEY, member_id BIGINT NOT NULL, lookbook_id BIGINT NOT NULL, CONSTRAINT FK_LOOKBOOK_LIKE_MEMBER_OLD FOREIGN KEY (member_id) REFERENCES member (member_id));' \
    'CREATE TABLE lookbook_tag (lookbook_tag_id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, lookbook_id BIGINT NOT NULL, tag_id BIGINT NOT NULL, created_at DATETIME(6) NOT NULL);' \
    'CREATE TABLE trend_content (trend_id BIGINT NOT NULL PRIMARY KEY, created_by BIGINT NOT NULL, title VARCHAR(100) NOT NULL, CONSTRAINT FK_TREND_CONTENT_MEMBER_OLD FOREIGN KEY (created_by) REFERENCES member (member_id));' \
    'CREATE TABLE trend_tag (trend_tag_id BIGINT NOT NULL PRIMARY KEY, trend_id BIGINT NOT NULL, tag_id BIGINT NOT NULL, CONSTRAINT FK_TREND_TAG_TREND_OLD FOREIGN KEY (trend_id) REFERENCES trend_content (trend_id));' \
    "INSERT INTO trend_content (trend_id, created_by, title) VALUES (7001, 8001, 'Legacy Trend');" \
    'INSERT INTO trend_tag (trend_tag_id, trend_id, tag_id) VALUES (7001, 7001, 1), (7002, 7001, 1);' \
    | docker exec -i "$container_name" mysql -uroot "$database"
}

seed_baseline_schema fitback "member_id BIGINT NOT NULL PRIMARY KEY, login_provider VARCHAR(20) NOT NULL DEFAULT 'EMAIL'"
seed_baseline_schema fitback_existing_refresh_token "member_id BIGINT NOT NULL PRIMARY KEY, login_provider VARCHAR(20) NOT NULL DEFAULT 'EMAIL', refresh_token VARCHAR(512) NULL"

for database in fitback fitback_existing_refresh_token; do
  while IFS= read -r migration; do
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
    if [ "$(basename "$migration")" = 'V14__link_lookbook_to_recommended_product.sql' ]; then
      if [ "$database" = 'fitback' ]; then
        printf '%s\n' \
          'CREATE TABLE lookbook (lookbook_id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, member_id BIGINT NOT NULL, original_image_id VARCHAR(36) NOT NULL, matched_image_id VARCHAR(36) NOT NULL, purchase_url VARCHAR(2048) NULL, comment VARCHAR(500) NULL, like_count INT NOT NULL DEFAULT 0, report_count INT NOT NULL DEFAULT 0, moderation_status VARCHAR(20) NOT NULL DEFAULT '\''VISIBLE'\'', auto_hidden_at DATETIME(6) NULL, deleted_at DATETIME(6) NULL, created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6), updated_at DATETIME(6) NULL, CONSTRAINT FK_LOOKBOOK_MEMBER_TEST FOREIGN KEY (member_id) REFERENCES member (member_id), CONSTRAINT FK_LOOKBOOK_ORIGINAL_IMAGE_TEST FOREIGN KEY (original_image_id) REFERENCES image (image_id), CONSTRAINT FK_LOOKBOOK_MATCHED_IMAGE_TEST FOREIGN KEY (matched_image_id) REFERENCES image (image_id)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;' \
          "INSERT INTO lookbook (member_id, original_image_id, matched_image_id) VALUES (9001, 'legacy-lookbook-original', 'legacy-lookbook-matched');" \
          | docker exec -i "$container_name" mysql -uroot "$database"
      else
        printf '%s\n' \
          'CREATE TABLE lookbook (lookbook_id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, created_at DATETIME(6) NOT NULL, updated_at DATETIME(6) NULL, matched_image_url VARCHAR(2048) NOT NULL, original_image_url VARCHAR(2048) NOT NULL, purchase_url VARCHAR(2048) NULL, member_id BIGINT NOT NULL, CONSTRAINT FK_LOOKBOOK_MEMBER_LEGACY_TEST FOREIGN KEY (member_id) REFERENCES member (member_id)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;' \
          | docker exec -i "$container_name" mysql -uroot "$database"
      fi
    fi
    if [ "$(basename "$migration")" = 'V19__contract_image_lifecycle_values.sql' ]; then
      printf '%s\n' \
        "INSERT INTO image (image_id, owner_id, object_key, purpose, content_type, file_size, status, visibility, retry_count, created_at) VALUES" \
        "('rollback-window-legacy-write', 9001, 'prod/images/lookbook_matched/rollback-window-legacy.jpg', 'LOOKBOOK_MATCHED', 'image/jpeg', 1024, 'PENDING', 'PRIVATE', 0, NOW());" \
        | docker exec -i "$container_name" mysql -uroot "$database"
    fi
    docker exec -i "$container_name" mysql -uroot "$database" < "$migration"
  done < <(printf '%s\n' src/main/resources/db/migration/V*.sql | sort -V)
done

docker exec "$container_name" mysql -uroot -e \
  "CREATE DATABASE fitback_mismatched_social_uid;
   CREATE TABLE fitback_mismatched_social_uid.member (
     member_id BIGINT NOT NULL PRIMARY KEY,
     login_provider VARCHAR(20) NOT NULL DEFAULT 'EMAIL',
     social_uid VARCHAR(100) NULL,
     CONSTRAINT UK_MEMBER_PROVIDER_UID UNIQUE (social_uid)
   );"

if docker exec -i "$container_name" mysql -uroot fitback_mismatched_social_uid \
  < src/main/resources/db/migration/V12__add_member_social_uid.sql \
  >/dev/null 2>&1; then
  echo 'V12 accepted a mismatched UK_MEMBER_PROVIDER_UID constraint.' >&2
  exit 1
fi

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

validate_recommendation_contract() {
  local database="$1"
  local recommendation_contract
  local expected_recommendation_contract
  local legacy_recommendation
  local analysis_metadata
  local recommendation_constraints
  local expected_recommendation_constraints
  local recommendation_rank_check
  local normalized_recommendation_rank_check

  recommendation_contract="$(docker exec "$container_name" mysql -uroot \
    --batch --skip-column-names \
    -e "SELECT CONCAT(COLUMN_NAME, ':', IS_NULLABLE, ':', DATA_TYPE)
        FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = '$database'
          AND TABLE_NAME = 'recommended_item'
          AND COLUMN_NAME IN (
            'recommended_item_id',
            'input_revision',
            'rank_no',
            'category',
            'similarity_score',
            'final_score',
            'score_version',
            'reason_codes'
          )
        ORDER BY COLUMN_NAME;")"

  expected_recommendation_contract="$(printf '%s\n' \
    'category:NO:varchar' \
    'final_score:NO:decimal' \
    'input_revision:NO:int' \
    'rank_no:NO:int' \
    'reason_codes:NO:varchar' \
    'recommended_item_id:NO:bigint' \
    'score_version:NO:varchar' \
    'similarity_score:NO:decimal')"

  if [ "$recommendation_contract" != "$expected_recommendation_contract" ]; then
    echo "Unexpected recommendation migration contract in $database:" >&2
    printf '%s\n' "$recommendation_contract" >&2
    exit 1
  fi

  legacy_recommendation="$(docker exec "$container_name" mysql -uroot \
    --batch --skip-column-names \
    -e "SELECT CONCAT(
          input_revision, ':',
          rank_no, ':',
          category, ':',
          similarity_score, ':',
          final_score, ':',
          score_version, ':',
          reason_codes
        )
        FROM $database.recommended_item
        WHERE recommended_item_id = 1;")"

  if [ "$legacy_recommendation" != '1:1:TOP:90.00:90.00:SIMILARITY_V1:LEGACY_RESULT' ]; then
    echo "Unexpected legacy recommendation backfill in $database: $legacy_recommendation" >&2
    exit 1
  fi

  analysis_metadata="$(docker exec "$container_name" mysql -uroot \
    --batch --skip-column-names \
    -e "SELECT CONCAT(
          recommendation_input_revision, ':',
          IF(result_input_revision IS NULL, 'NULL', result_input_revision), ':',
          IF(result_score_version IS NULL, 'NULL', result_score_version), ':',
          IF(recommendation_generated_at IS NULL, 'NULL', 'SET')
        )
        FROM $database.analysis_report
        WHERE report_id = 7001;")"

  if [ "$analysis_metadata" != '1:1:SIMILARITY_V1:SET' ]; then
    echo "Unexpected analysis recommendation metadata in $database: $analysis_metadata" >&2
    exit 1
  fi

  recommendation_constraints="$(docker exec "$container_name" mysql -uroot \
    --batch --skip-column-names \
    -e "SELECT CONSTRAINT_NAME
        FROM information_schema.TABLE_CONSTRAINTS
        WHERE TABLE_SCHEMA = '$database'
          AND TABLE_NAME = 'recommended_item'
          AND CONSTRAINT_NAME IN (
            'UK_RECOMMENDED_REPORT_PRODUCT',
            'UK_RECOMMENDED_REPORT_CATEGORY_RANK',
            'CK_RECOMMENDED_INPUT_REVISION',
            'CK_RECOMMENDED_RANK',
            'CK_RECOMMENDED_CATEGORY',
            'CK_RECOMMENDED_SIMILARITY_SCORE',
            'CK_RECOMMENDED_FINAL_SCORE'
          )
        ORDER BY CONSTRAINT_NAME;")"

  expected_recommendation_constraints="$(printf '%s\n' \
    'CK_RECOMMENDED_CATEGORY' \
    'CK_RECOMMENDED_FINAL_SCORE' \
    'CK_RECOMMENDED_INPUT_REVISION' \
    'CK_RECOMMENDED_RANK' \
    'CK_RECOMMENDED_SIMILARITY_SCORE' \
    'UK_RECOMMENDED_REPORT_CATEGORY_RANK' \
    'UK_RECOMMENDED_REPORT_PRODUCT')"

  if [ "$recommendation_constraints" != "$expected_recommendation_constraints" ]; then
    echo "Unexpected recommendation constraints in $database:" >&2
    printf '%s\n' "$recommendation_constraints" >&2
    exit 1
  fi

  recommendation_rank_check="$(docker exec "$container_name" mysql -uroot \
    --batch --skip-column-names \
    -e "SELECT LOWER(CHECK_CLAUSE)
        FROM information_schema.CHECK_CONSTRAINTS
        WHERE CONSTRAINT_SCHEMA = '$database'
          AND CONSTRAINT_NAME = 'CK_RECOMMENDED_RANK';")"

  normalized_recommendation_rank_check="$(printf '%s\n' "$recommendation_rank_check" \
    | tr -d '`()[:space:]')"
  if [ "$normalized_recommendation_rank_check" != 'rank_nobetween1and10' ]; then
    echo "Unexpected recommendation rank check in $database: $recommendation_rank_check" >&2
    exit 1
  fi
}

for database in fitback fitback_existing_refresh_token; do
  validate_recommendation_contract "$database"
done

validate_report_custom_tag_contract() {
  local database="$1"
  local custom_tag_columns
  local custom_tag_constraints
  local custom_tag_foreign_key
  local custom_tag_name_check
  local normalized_custom_tag_name_check

  custom_tag_columns="$(docker exec "$container_name" mysql -uroot \
    --batch --skip-column-names \
    -e "SELECT CONCAT(COLUMN_NAME, ':', IS_NULLABLE, ':', DATA_TYPE)
        FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = '$database'
          AND TABLE_NAME = 'report_custom_tag'
        ORDER BY ORDINAL_POSITION;")"

  if [ "$custom_tag_columns" != "$(printf '%s\n' \
    'report_custom_tag_id:NO:bigint' \
    'report_id:NO:bigint' \
    'display_name:NO:varchar' \
    'normalized_name:NO:varchar' \
    'created_at:NO:datetime')" ]; then
    echo "Unexpected report_custom_tag columns in $database:" >&2
    printf '%s\n' "$custom_tag_columns" >&2
    exit 1
  fi

  custom_tag_constraints="$(docker exec "$container_name" mysql -uroot \
    --batch --skip-column-names \
    -e "SELECT CONSTRAINT_NAME
        FROM information_schema.TABLE_CONSTRAINTS
        WHERE TABLE_SCHEMA = '$database'
          AND TABLE_NAME = 'report_custom_tag'
          AND CONSTRAINT_NAME IN (
            'UK_REPORT_CUSTOM_TAG_NAME',
            'FK_REPORT_CUSTOM_TAG_REPORT',
            'CK_REPORT_CUSTOM_TAG_NAME'
          )
        ORDER BY CONSTRAINT_NAME;")"

  if [ "$custom_tag_constraints" != "$(printf '%s\n' \
    'CK_REPORT_CUSTOM_TAG_NAME' \
    'FK_REPORT_CUSTOM_TAG_REPORT' \
    'UK_REPORT_CUSTOM_TAG_NAME')" ]; then
    echo "Unexpected report_custom_tag constraints in $database:" >&2
    printf '%s\n' "$custom_tag_constraints" >&2
    exit 1
  fi

  custom_tag_foreign_key="$(docker exec "$container_name" mysql -uroot \
    --batch --skip-column-names \
    -e "SELECT CONCAT(DELETE_RULE, ':', UPDATE_RULE)
        FROM information_schema.REFERENTIAL_CONSTRAINTS
        WHERE CONSTRAINT_SCHEMA = '$database'
          AND TABLE_NAME = 'report_custom_tag'
          AND CONSTRAINT_NAME = 'FK_REPORT_CUSTOM_TAG_REPORT';")"

  if [ "$custom_tag_foreign_key" != 'CASCADE:RESTRICT' ]; then
    echo "Unexpected report_custom_tag foreign key in $database: $custom_tag_foreign_key" >&2
    exit 1
  fi

  custom_tag_name_check="$(docker exec "$container_name" mysql -uroot \
    --batch --skip-column-names \
    -e "SELECT LOWER(CHECK_CLAUSE)
        FROM information_schema.CHECK_CONSTRAINTS
        WHERE CONSTRAINT_SCHEMA = '$database'
          AND CONSTRAINT_NAME = 'CK_REPORT_CUSTOM_TAG_NAME';")"
  normalized_custom_tag_name_check="$(printf '%s\n' "$custom_tag_name_check" \
    | tr -d '`()[:space:]')"
  if [ "$normalized_custom_tag_name_check" \
      != 'char_lengthtrimdisplay_namebetween1and50' ]; then
    echo "Unexpected report_custom_tag name check in $database: $custom_tag_name_check" >&2
    exit 1
  fi
}

for database in fitback fitback_existing_refresh_token; do
  validate_report_custom_tag_contract "$database"
done

validate_saved_product_contract() {
  local database="$1"
  local saved_product_columns
  local saved_product_constraints
  local saved_product_indexes
  local relationship_count
  local product_count

  saved_product_columns="$(docker exec "$container_name" mysql -uroot \
    --batch --skip-column-names \
    -e "SELECT CONCAT(COLUMN_NAME, ':', IS_NULLABLE, ':', DATA_TYPE)
        FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = '$database'
          AND TABLE_NAME = 'saved_product'
        ORDER BY ORDINAL_POSITION;")"

  if [ "$saved_product_columns" != "$(printf '%s\n' \
    'member_id:NO:bigint' \
    'product_id:NO:bigint' \
    'created_at:NO:datetime')" ]; then
    echo "Unexpected saved_product columns in $database:" >&2
    printf '%s\n' "$saved_product_columns" >&2
    exit 1
  fi

  saved_product_constraints="$(docker exec "$container_name" mysql -uroot \
    --batch --skip-column-names \
    -e "SELECT CONCAT(rc.CONSTRAINT_NAME, ':', rc.DELETE_RULE)
        FROM information_schema.REFERENTIAL_CONSTRAINTS rc
        WHERE rc.CONSTRAINT_SCHEMA = '$database'
          AND rc.TABLE_NAME = 'saved_product'
        ORDER BY rc.CONSTRAINT_NAME;")"

  if [ "$saved_product_constraints" != "$(printf '%s\n' \
    'FK_SAVED_PRODUCT_MEMBER:CASCADE' \
    'FK_SAVED_PRODUCT_PRODUCT:RESTRICT')" ]; then
    echo "Unexpected saved_product foreign keys in $database:" >&2
    printf '%s\n' "$saved_product_constraints" >&2
    exit 1
  fi

  saved_product_indexes="$(docker exec "$container_name" mysql -uroot \
    --batch --skip-column-names \
    -e "SELECT DISTINCT INDEX_NAME
        FROM information_schema.STATISTICS
        WHERE TABLE_SCHEMA = '$database'
          AND TABLE_NAME = 'saved_product'
          AND INDEX_NAME IN ('PRIMARY', 'IDX_SAVED_PRODUCT_MEMBER_CURSOR')
        ORDER BY INDEX_NAME;")"

  if [ "$saved_product_indexes" != "$(printf '%s\n' \
    'IDX_SAVED_PRODUCT_MEMBER_CURSOR' \
    'PRIMARY')" ]; then
    echo "Unexpected saved_product indexes in $database:" >&2
    printf '%s\n' "$saved_product_indexes" >&2
    exit 1
  fi

  docker exec "$container_name" mysql -uroot "$database" -e \
    "DELETE FROM recommended_item WHERE product_id = 1;
     INSERT INTO member (member_id) VALUES (9101);
     INSERT INTO saved_product (member_id, product_id) VALUES (9101, 1);"

  if docker exec "$container_name" mysql -uroot "$database" -e \
    "INSERT INTO saved_product (member_id, product_id) VALUES (9101, 1);" \
    >/dev/null 2>&1; then
    echo "saved_product accepted a duplicate relationship in $database." >&2
    exit 1
  fi

  if docker exec "$container_name" mysql -uroot "$database" -e \
    "DELETE FROM product WHERE product_id = 1;" \
    >/dev/null 2>&1; then
    echo "saved_product did not restrict deletion of referenced product in $database." >&2
    exit 1
  fi

  docker exec "$container_name" mysql -uroot "$database" -e \
    "DELETE FROM member WHERE member_id = 9101;"
  relationship_count="$(docker exec "$container_name" mysql -uroot \
    --batch --skip-column-names \
    -e "SELECT COUNT(*) FROM $database.saved_product
        WHERE member_id = 9101 AND product_id = 1;")"
  product_count="$(docker exec "$container_name" mysql -uroot \
    --batch --skip-column-names \
    -e "SELECT COUNT(*) FROM $database.product WHERE product_id = 1;")"

  if [ "$relationship_count" != '0' ] || [ "$product_count" != '1' ]; then
    echo "Unexpected saved_product delete lifecycle in $database:" >&2
    echo "relationship_count=$relationship_count product_count=$product_count" >&2
    exit 1
  fi
}

for database in fitback fitback_existing_refresh_token; do
  validate_saved_product_contract "$database"
done

validate_saved_analysis_contract() {
  local database="$1"
  local closet_contract
  local saved_analysis_contract
  local saved_analysis_foreign_keys

  closet_contract="$(docker exec "$container_name" mysql -uroot \
    --batch --skip-column-names \
    -e "SELECT CONCAT(COLUMN_NAME, ':', IS_NULLABLE, ':', EXTRA)
        FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = '$database'
          AND TABLE_NAME = 'closet_save'
          AND COLUMN_NAME IN ('save_id', 'created_at')
        ORDER BY COLUMN_NAME;")"

  if [ "$closet_contract" != "$(printf '%s\n' \
    'created_at:NO:DEFAULT_GENERATED' \
    'save_id:NO:auto_increment')" ]; then
    echo "Unexpected closet_save normalized contract in $database:" >&2
    printf '%s\n' "$closet_contract" >&2
    exit 1
  fi

  saved_analysis_contract="$(docker exec "$container_name" mysql -uroot \
    --batch --skip-column-names \
    -e "SELECT CONCAT(COLUMN_NAME, ':', IS_NULLABLE, ':', DATA_TYPE)
        FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = '$database'
          AND TABLE_NAME = 'saved_analysis_item'
          AND COLUMN_NAME IN (
            'saved_analysis_item_id',
            'save_id',
            'product_id',
            'category',
            'rank_no',
            'price_amount',
            'similarity_score',
            'final_score',
            'created_at'
          )
        ORDER BY COLUMN_NAME;")"

  if [ "$saved_analysis_contract" != "$(printf '%s\n' \
    'category:NO:varchar' \
    'created_at:NO:datetime' \
    'final_score:NO:decimal' \
    'price_amount:YES:decimal' \
    'product_id:NO:bigint' \
    'rank_no:NO:int' \
    'save_id:NO:bigint' \
    'saved_analysis_item_id:NO:bigint' \
    'similarity_score:NO:decimal')" ]; then
    echo "Unexpected saved_analysis_item contract in $database:" >&2
    printf '%s\n' "$saved_analysis_contract" >&2
    exit 1
  fi

  saved_analysis_foreign_keys="$(docker exec "$container_name" mysql -uroot \
    --batch --skip-column-names \
    -e "SELECT CONCAT(k.COLUMN_NAME, '->', k.REFERENCED_TABLE_NAME, '=', rc.DELETE_RULE)
        FROM information_schema.KEY_COLUMN_USAGE k
        JOIN information_schema.REFERENTIAL_CONSTRAINTS rc
          ON rc.CONSTRAINT_SCHEMA = k.CONSTRAINT_SCHEMA
         AND rc.CONSTRAINT_NAME = k.CONSTRAINT_NAME
        WHERE k.TABLE_SCHEMA = '$database'
          AND k.TABLE_NAME = 'saved_analysis_item'
        ORDER BY k.COLUMN_NAME;")"

  if [ "$saved_analysis_foreign_keys" != "$(printf '%s\n' \
    'product_id->product=RESTRICT' \
    'save_id->closet_save=CASCADE')" ]; then
    echo "Unexpected saved_analysis_item foreign keys in $database:" >&2
    printf '%s\n' "$saved_analysis_foreign_keys" >&2
    exit 1
  fi
}

for database in fitback fitback_existing_refresh_token; do
  validate_saved_analysis_contract "$database"
done

validate_lookbook_product_link_contract() {
  local database="$1"
  local columns
  local product_foreign_key
  local match_check
  local legacy_row
  local like_id
  local report_columns
  local image_reference_collations

  columns="$(docker exec "$container_name" mysql -uroot \
    --batch --skip-column-names \
    -e "SELECT CONCAT(COLUMN_NAME, ':', IS_NULLABLE, ':', DATA_TYPE)
        FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = '$database'
          AND TABLE_NAME = 'lookbook'
          AND COLUMN_NAME IN (
            'auto_hidden_at',
            'comment',
            'deleted_at',
            'like_count',
            'matched_image_id',
            'matched_product_id',
            'matched_product_image_url',
            'moderation_status',
            'original_image_id',
            'report_count'
          )
        ORDER BY COLUMN_NAME;")"

  if [ "$columns" != "$(printf '%s\n' \
    'auto_hidden_at:YES:datetime' \
    'comment:YES:varchar' \
    'deleted_at:YES:datetime' \
    'like_count:NO:int' \
    'matched_image_id:YES:varchar' \
    'matched_product_id:YES:bigint' \
    'matched_product_image_url:YES:varchar' \
    'moderation_status:NO:varchar' \
    'original_image_id:NO:varchar' \
    'report_count:NO:int')" ]; then
    echo "Unexpected lookbook match source columns in $database:" >&2
    printf '%s\n' "$columns" >&2
    exit 1
  fi

  image_reference_collations="$(docker exec "$container_name" mysql -uroot \
    --batch --skip-column-names \
    -e "SELECT CONCAT(COLUMN_NAME, ':', COLLATION_NAME)
        FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = '$database'
          AND TABLE_NAME = 'lookbook'
          AND COLUMN_NAME IN ('matched_image_id', 'original_image_id')
        ORDER BY COLUMN_NAME;")"

  if [ "$image_reference_collations" != "$(printf '%s\n' \
    'matched_image_id:utf8mb4_unicode_ci' \
    'original_image_id:utf8mb4_unicode_ci')" ]; then
    echo "Unexpected lookbook image reference collations in $database:" >&2
    printf '%s\n' "$image_reference_collations" >&2
    exit 1
  fi

  product_foreign_key="$(docker exec "$container_name" mysql -uroot \
    --batch --skip-column-names \
    -e "SELECT CONCAT(k.COLUMN_NAME, '->', k.REFERENCED_TABLE_NAME, '=', rc.DELETE_RULE)
        FROM information_schema.KEY_COLUMN_USAGE k
        JOIN information_schema.REFERENTIAL_CONSTRAINTS rc
          ON rc.CONSTRAINT_SCHEMA = k.CONSTRAINT_SCHEMA
         AND rc.CONSTRAINT_NAME = k.CONSTRAINT_NAME
        WHERE k.TABLE_SCHEMA = '$database'
          AND k.TABLE_NAME = 'lookbook'
          AND k.COLUMN_NAME = 'matched_product_id';")"

  if [ "$product_foreign_key" != 'matched_product_id->product=RESTRICT' ]; then
    echo "Unexpected lookbook matched product foreign key in $database:" >&2
    printf '%s\n' "$product_foreign_key" >&2
    exit 1
  fi

  match_check="$(docker exec "$container_name" mysql -uroot \
    --batch --skip-column-names \
    -e "SELECT COUNT(*)
        FROM information_schema.TABLE_CONSTRAINTS tc
        JOIN information_schema.CHECK_CONSTRAINTS cc
          ON cc.CONSTRAINT_SCHEMA = tc.CONSTRAINT_SCHEMA
         AND cc.CONSTRAINT_NAME = tc.CONSTRAINT_NAME
        WHERE tc.CONSTRAINT_SCHEMA = '$database'
          AND tc.TABLE_NAME = 'lookbook'
          AND tc.CONSTRAINT_NAME = 'CK_LOOKBOOK_MATCH_SOURCE'
          AND tc.CONSTRAINT_TYPE = 'CHECK'
          AND LOWER(cc.CHECK_CLAUSE) LIKE '%matched_image_id%'
          AND LOWER(cc.CHECK_CLAUSE) LIKE '%matched_product_id%'
          AND LOWER(cc.CHECK_CLAUSE) LIKE '%is null%'
          AND LOWER(cc.CHECK_CLAUSE) LIKE '%is not null%';")"

  if [ "$match_check" != '1' ]; then
    echo "Unexpected lookbook match source check in $database." >&2
    exit 1
  fi

  product_image_check="$(docker exec "$container_name" mysql -uroot \
    --batch --skip-column-names \
    -e "SELECT COUNT(*)
        FROM information_schema.TABLE_CONSTRAINTS tc
        JOIN information_schema.CHECK_CONSTRAINTS cc
          ON cc.CONSTRAINT_SCHEMA = tc.CONSTRAINT_SCHEMA
         AND cc.CONSTRAINT_NAME = tc.CONSTRAINT_NAME
        WHERE tc.CONSTRAINT_SCHEMA = '$database'
          AND tc.TABLE_NAME = 'lookbook'
          AND tc.CONSTRAINT_NAME = 'CK_LOOKBOOK_MATCHED_PRODUCT_IMAGE'
          AND tc.CONSTRAINT_TYPE = 'CHECK'
          AND LOWER(cc.CHECK_CLAUSE) LIKE '%matched_product_id%'
          AND LOWER(cc.CHECK_CLAUSE) LIKE '%matched_product_image_url%'
          AND LOWER(cc.CHECK_CLAUSE) LIKE '%is null%'
          AND LOWER(cc.CHECK_CLAUSE) LIKE '%is not null%';")"

  if [ "$product_image_check" != '1' ]; then
    echo "Unexpected lookbook matched product image check in $database." >&2
    exit 1
  fi

  if [ "$database" = 'fitback' ]; then
    legacy_row="$(docker exec "$container_name" mysql -uroot \
      --batch --skip-column-names \
      -e "SELECT CONCAT(matched_image_id, ':', IFNULL(matched_product_id, 'NULL'))
          FROM $database.lookbook
          WHERE lookbook_id = 1;")"

    if [ "$legacy_row" != 'legacy-lookbook-matched:NULL' ]; then
      echo "Legacy lookbook match image was not preserved in $database." >&2
      exit 1
    fi
  else
    legacy_row="$(docker exec "$container_name" mysql -uroot \
      --batch --skip-column-names \
      -e "SELECT CONCAT(COLUMN_NAME, ':', IS_NULLABLE)
          FROM information_schema.COLUMNS
          WHERE TABLE_SCHEMA = '$database'
            AND TABLE_NAME = 'lookbook'
            AND COLUMN_NAME IN ('matched_image_url', 'original_image_url')
          ORDER BY COLUMN_NAME;")"

    if [ "$legacy_row" != "$(printf '%s\n' \
      'matched_image_url:YES' \
      'original_image_url:YES')" ]; then
      echo "Legacy lookbook URL columns were not made optional in $database:" >&2
      printf '%s\n' "$legacy_row" >&2
      exit 1
    fi
  fi

  like_id="$(docker exec "$container_name" mysql -uroot \
    --batch --skip-column-names \
    -e "SELECT CONCAT(COLUMN_NAME, ':', EXTRA)
        FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = '$database'
          AND TABLE_NAME = 'lookbook_like'
          AND COLUMN_NAME = 'like_id';")"

  if [ "$like_id" != 'like_id:auto_increment' ]; then
    echo "Unexpected lookbook like identifier in $database: $like_id" >&2
    exit 1
  fi

  report_columns="$(docker exec "$container_name" mysql -uroot \
    --batch --skip-column-names \
    -e "SELECT CONCAT(COLUMN_NAME, ':', IS_NULLABLE, ':', DATA_TYPE)
        FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = '$database'
          AND TABLE_NAME = 'lookbook_report'
        ORDER BY COLUMN_NAME;")"

  if [ "$report_columns" != "$(printf '%s\n' \
    'created_at:NO:datetime' \
    'lookbook_id:NO:bigint' \
    'member_id:NO:bigint' \
    'reason:NO:varchar' \
    'report_id:NO:bigint')" ]; then
    echo "Unexpected lookbook report contract in $database:" >&2
    printf '%s\n' "$report_columns" >&2
    exit 1
  fi
}

for database in fitback fitback_existing_refresh_token; do
  validate_lookbook_product_link_contract "$database"
done

actual_contract="$(docker exec "$container_name" mysql -uroot \
  --batch --skip-column-names \
  -e "SELECT CASE
          WHEN TABLE_NAME = 'member' AND COLUMN_NAME IN ('social_uid', 'profile_image_id')
            THEN CONCAT(TABLE_NAME, '.', COLUMN_NAME, '=', IS_NULLABLE, ':', COLUMN_TYPE)
          ELSE CONCAT(TABLE_NAME, '.', COLUMN_NAME, '=', IS_NULLABLE)
        END
      FROM information_schema.COLUMNS
      WHERE TABLE_SCHEMA = 'fitback'
        AND (
          (TABLE_NAME = 'image' AND COLUMN_NAME = 'presigned_expires_at')
          OR (
            TABLE_NAME = 'member'
            AND COLUMN_NAME IN ('refresh_token', 'social_uid', 'profile_image_id')
          )
          OR (
            TABLE_NAME = 'analysis_report'
            AND COLUMN_NAME IN ('original_image_id', 'deleted_at', 'purge_after')
          )
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
  'member.profile_image_id=YES:varchar(36)' \
  'member.refresh_token=YES' \
  'member.social_uid=YES:varchar(100)' \
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

profile_image_fk="$(docker exec "$container_name" mysql -uroot \
  --batch --skip-column-names \
  -e "SELECT CONCAT(
        tc.CONSTRAINT_NAME, ':',
        GROUP_CONCAT(
          CONCAT(k.COLUMN_NAME, '->', k.REFERENCED_COLUMN_NAME)
          ORDER BY k.ORDINAL_POSITION
        )
      )
      FROM information_schema.TABLE_CONSTRAINTS tc
      JOIN information_schema.KEY_COLUMN_USAGE k
        ON k.CONSTRAINT_SCHEMA = tc.CONSTRAINT_SCHEMA
       AND k.TABLE_NAME = tc.TABLE_NAME
       AND k.CONSTRAINT_NAME = tc.CONSTRAINT_NAME
      WHERE tc.TABLE_SCHEMA = 'fitback'
        AND tc.TABLE_NAME = 'member'
        AND tc.CONSTRAINT_TYPE = 'FOREIGN KEY'
        AND tc.CONSTRAINT_NAME = 'FK_MEMBER_PROFILE_IMAGE_OWNER'
      GROUP BY tc.CONSTRAINT_NAME;")"

if [ "$profile_image_fk" != \
  'FK_MEMBER_PROFILE_IMAGE_OWNER:profile_image_id->image_id,member_id->owner_id' ]; then
  echo "Unexpected member profile image FK: $profile_image_fk" >&2
  exit 1
fi

docker exec "$container_name" mysql -uroot fitback -e \
  "UPDATE member
   SET profile_image_id = 'legacy-profile'
   WHERE member_id = 9001;"

if docker exec "$container_name" mysql -uroot fitback -e \
  "UPDATE member
   SET profile_image_id = 'legacy-profile'
   WHERE member_id = 8001;" \
  >/dev/null 2>&1; then
  echo 'Member profile FK accepted an image owned by another member.' >&2
  exit 1
fi

image_policy_contract="$(docker exec "$container_name" mysql -uroot \
  --batch --skip-column-names \
  -e "SELECT CONCAT(image_id, ':', purpose, ':', status, ':', object_key)
      FROM fitback.image
      WHERE image_id LIKE 'legacy-%'
      ORDER BY image_id;")"

expected_image_policy_contract="$(printf '%s\n' \
  'legacy-analysis:ANALYSIS:PENDING_UPLOAD:prod/images/analysis_original/legacy-analysis.jpg' \
  'legacy-lookbook-matched:LOOKBOOK:DELETE_FAILED:prod/images/lookbook_matched/legacy-matched.jpg' \
  'legacy-lookbook-original:LOOKBOOK:READY:prod/images/lookbook_original/legacy-original.jpg' \
  'legacy-profile:PROFILE:REJECTED:prod/images/profile/legacy-profile.jpg')"

if [ "$image_policy_contract" != "$expected_image_policy_contract" ]; then
  echo 'Unexpected V18 image lifecycle backfill result:' >&2
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
  "INSERT INTO image (
       image_id, owner_id, object_key, purpose, content_type,
       file_size, status, visibility, retry_count, created_at
   ) VALUES
   (
       'future-contract-write', 9001, 'images/analysis/9001/2026/07/future-contract.jpg',
       'ANALYSIS', 'image/jpeg', 1024, 'PENDING_UPLOAD', 'PRIVATE', 0, NOW()
   );"

release_c_contract="$(docker exec "$container_name" mysql -uroot \
  --batch --skip-column-names \
  -e "SELECT CONCAT(image_id, ':', purpose, ':', status)
      FROM fitback.image
      WHERE image_id IN (
        'rollback-window-legacy-write',
        'future-contract-write'
      )
      ORDER BY image_id;")"

expected_release_c_contract="$(printf '%s\n' \
  'future-contract-write:ANALYSIS:PENDING_UPLOAD' \
  'rollback-window-legacy-write:LOOKBOOK:PENDING_UPLOAD')"

if [ "$release_c_contract" != "$expected_release_c_contract" ]; then
  echo 'Unexpected Release C contract result:' >&2
  printf '%s\n' "$release_c_contract" >&2
  exit 1
fi

if docker exec "$container_name" mysql -uroot fitback -e \
  "INSERT INTO image (
       image_id, owner_id, object_key, purpose, content_type,
       file_size, status, visibility, retry_count, created_at
   ) VALUES (
       'legacy-contract-write', 9001, 'images/analysis/9001/2026/07/legacy-contract.jpg',
       'ANALYSIS_ORIGINAL', 'image/jpeg', 1024, 'PENDING', 'PRIVATE', 0, NOW()
   );" \
  >/dev/null 2>&1; then
  echo 'Release C constraints accepted legacy purpose/status.' >&2
  exit 1
fi

if docker exec "$container_name" mysql -uroot fitback -e \
  "UPDATE image SET purpose = 'LOOKBOOK_MATCHED'
   WHERE image_id = 'future-contract-write';" \
  >/dev/null 2>&1; then
  echo 'CK_IMAGE_PURPOSE accepted a legacy purpose after V19.' >&2
  exit 1
fi

if docker exec "$container_name" mysql -uroot fitback -e \
  "UPDATE image SET status = 'PENDING'
   WHERE image_id = 'future-contract-write';" \
  >/dev/null 2>&1; then
  echo 'CK_IMAGE_STATUS accepted a legacy status after V19.' >&2
  exit 1
fi

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

member_social_uid_unique="$(docker exec "$container_name" mysql -uroot \
  --batch --skip-column-names \
  -e "SELECT CONCAT(tc.CONSTRAINT_NAME, ':', GROUP_CONCAT(k.COLUMN_NAME ORDER BY k.ORDINAL_POSITION))
      FROM information_schema.TABLE_CONSTRAINTS tc
      JOIN information_schema.KEY_COLUMN_USAGE k
        ON k.CONSTRAINT_SCHEMA = tc.CONSTRAINT_SCHEMA
       AND k.TABLE_NAME = tc.TABLE_NAME
       AND k.CONSTRAINT_NAME = tc.CONSTRAINT_NAME
      WHERE tc.TABLE_SCHEMA = 'fitback'
        AND tc.TABLE_NAME = 'member'
        AND tc.CONSTRAINT_TYPE = 'UNIQUE'
        AND tc.CONSTRAINT_NAME = 'UK_MEMBER_PROVIDER_UID'
      GROUP BY tc.CONSTRAINT_NAME;")"

if [ "$member_social_uid_unique" != 'UK_MEMBER_PROVIDER_UID:login_provider,social_uid' ]; then
  echo "Unexpected member social uid unique constraint: $member_social_uid_unique" >&2
  exit 1
fi

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
  '8001:1:1:0:0' \
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

password_reset_contract="$(docker exec "$container_name" mysql -uroot \
  --batch --skip-column-names \
  -e "SELECT CONCAT(
        COLUMN_NAME, ':',
        IS_NULLABLE, ':',
        DATA_TYPE, ':',
        COALESCE(CHARACTER_MAXIMUM_LENGTH, DATETIME_PRECISION, 0), ':',
        COALESCE(COLLATION_NAME, '-')
      )
      FROM information_schema.COLUMNS
      WHERE TABLE_SCHEMA = 'fitback'
        AND TABLE_NAME = 'password_reset_token'
      ORDER BY ORDINAL_POSITION;")"

expected_password_reset_contract="$(printf '%s\n' \
  'member_id:NO:bigint:0:-' \
  'token_hash:NO:char:64:ascii_bin' \
  'expires_at:NO:datetime:6:-' \
  'created_at:NO:datetime:6:-')"

if [ "$password_reset_contract" != "$expected_password_reset_contract" ]; then
  echo 'Unexpected password reset token column contract:' >&2
  printf '%s\n' "$password_reset_contract" >&2
  exit 1
fi

password_reset_constraints="$(docker exec "$container_name" mysql -uroot \
  --batch --skip-column-names \
  -e "SELECT CONCAT(
        tc.CONSTRAINT_TYPE, ':',
        tc.CONSTRAINT_NAME, ':',
        GROUP_CONCAT(k.COLUMN_NAME ORDER BY k.ORDINAL_POSITION), ':',
        COALESCE(MAX(k.REFERENCED_TABLE_NAME), '-'), ':',
        COALESCE(MAX(rc.DELETE_RULE), '-')
      )
      FROM information_schema.TABLE_CONSTRAINTS tc
      JOIN information_schema.KEY_COLUMN_USAGE k
        ON k.CONSTRAINT_SCHEMA = tc.CONSTRAINT_SCHEMA
       AND k.TABLE_NAME = tc.TABLE_NAME
       AND k.CONSTRAINT_NAME = tc.CONSTRAINT_NAME
      LEFT JOIN information_schema.REFERENTIAL_CONSTRAINTS rc
        ON rc.CONSTRAINT_SCHEMA = tc.CONSTRAINT_SCHEMA
       AND rc.CONSTRAINT_NAME = tc.CONSTRAINT_NAME
      WHERE tc.TABLE_SCHEMA = 'fitback'
        AND tc.TABLE_NAME = 'password_reset_token'
      GROUP BY tc.CONSTRAINT_TYPE, tc.CONSTRAINT_NAME
      ORDER BY tc.CONSTRAINT_TYPE, tc.CONSTRAINT_NAME;")"

expected_password_reset_constraints="$(printf '%s\n' \
  'FOREIGN KEY:FK_PASSWORD_RESET_TOKEN_MEMBER:member_id:member:CASCADE' \
  'PRIMARY KEY:PRIMARY:member_id:-:-' \
  'UNIQUE:UK_PASSWORD_RESET_TOKEN_TOKEN_HASH:token_hash:-:-')"

if [ "$password_reset_constraints" != "$expected_password_reset_constraints" ]; then
  echo 'Unexpected password reset token constraints:' >&2
  printf '%s\n' "$password_reset_constraints" >&2
  exit 1
fi

docker exec -i "$container_name" mysql -uroot fitback \
  < src/main/resources/db/migration/V16__seed_prototype_analysis_tags.sql

prototype_tag_contract="$(docker exec "$container_name" mysql -uroot \
  --batch --skip-column-names \
  -e "SELECT CONCAT(
        SUM(tag_name = '미니멀' AND tag_type = 'DETAIL'), ':',
        SUM(tag_name = '와이드핏' AND tag_type = 'SILHOUETTE'), ':',
        SUM(tag_name = '베이지톤' AND tag_type = 'COLOR'), ':',
        SUM(tag_name = '기존태그' AND tag_type = 'DETAIL'), ':',
        COUNT(*)
      )
      FROM fitback.tag;")"

if [ "$prototype_tag_contract" != '1:1:1:1:4' ]; then
  echo "Unexpected prototype tag contract: $prototype_tag_contract" >&2
  exit 1
fi

composite_unique_contract="$(docker exec "$container_name" mysql -uroot \
  --batch --skip-column-names \
  -e "SELECT CONCAT(TABLE_NAME, ':', INDEX_NAME, ':', GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX))
      FROM information_schema.STATISTICS
      WHERE TABLE_SCHEMA = 'fitback'
        AND INDEX_NAME IN (
          'UK_CLOSET_SAVE_MEMBER_ID_TARGET_TYPE_TARGET_ID',
          'UK_TREND_TAG_TREND_ID_TAG_ID'
        )
      GROUP BY TABLE_NAME, INDEX_NAME
      ORDER BY TABLE_NAME;")"

expected_composite_unique_contract="$(printf '%s\n' \
  'closet_save:UK_CLOSET_SAVE_MEMBER_ID_TARGET_TYPE_TARGET_ID:member_id,target_type,target_id' \
  'trend_tag:UK_TREND_TAG_TREND_ID_TAG_ID:trend_id,tag_id')"

if [ "$composite_unique_contract" != "$expected_composite_unique_contract" ]; then
  echo 'Unexpected composite unique contract:' >&2
  printf '%s\n' "$composite_unique_contract" >&2
  exit 1
fi

trend_tag_duplicate_count="$(docker exec "$container_name" mysql -uroot \
  --batch --skip-column-names \
  -e "SELECT COUNT(*)
      FROM (
        SELECT trend_id, tag_id
        FROM fitback.trend_tag
        GROUP BY trend_id, tag_id
        HAVING COUNT(*) > 1
      ) duplicate_groups;")"

if [ "$trend_tag_duplicate_count" != '0' ]; then
  echo "Unexpected trend_tag duplicate groups: $trend_tag_duplicate_count" >&2
  exit 1
fi

echo 'MySQL migration tests passed.'
