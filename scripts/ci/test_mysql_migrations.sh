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

printf '%s\n' \
  'CREATE TABLE member (member_id BIGINT NOT NULL PRIMARY KEY);' \
  'CREATE TABLE analysis_report (report_id BIGINT NOT NULL PRIMARY KEY, member_id BIGINT NOT NULL, image_url VARCHAR(255) NOT NULL, match_percentage INT NOT NULL);' \
  | docker exec -i "$container_name" mysql -uroot fitback

printf '%s\n' \
  'CREATE TABLE member (member_id BIGINT NOT NULL PRIMARY KEY, refresh_token VARCHAR(512) NULL);' \
  'CREATE TABLE analysis_report (report_id BIGINT NOT NULL PRIMARY KEY, member_id BIGINT NOT NULL, image_url VARCHAR(255) NOT NULL, match_percentage INT NOT NULL);' \
  | docker exec -i "$container_name" mysql -uroot fitback_existing_refresh_token

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

actual_contract="$(docker exec "$container_name" mysql -uroot \
  --batch --skip-column-names \
  -e "SELECT CONCAT(TABLE_NAME, '.', COLUMN_NAME, '=', IS_NULLABLE)
      FROM information_schema.COLUMNS
      WHERE TABLE_SCHEMA = 'fitback'
        AND (
          (TABLE_NAME = 'image' AND COLUMN_NAME = 'presigned_expires_at')
          OR (TABLE_NAME = 'member' AND COLUMN_NAME = 'refresh_token')
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
  'member.refresh_token=YES')"

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

echo 'MySQL migration tests passed.'
