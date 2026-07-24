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
  'INSERT INTO member (member_id) VALUES (1);' \
  'CREATE TABLE analysis_report (report_id BIGINT NOT NULL PRIMARY KEY, member_id BIGINT NOT NULL, image_url VARCHAR(255) NOT NULL, match_percentage INT NOT NULL);' \
  | docker exec -i "$container_name" mysql -uroot fitback

printf '%s\n' \
  'CREATE TABLE member (member_id BIGINT NOT NULL PRIMARY KEY, refresh_token VARCHAR(512) NULL);' \
  'INSERT INTO member (member_id) VALUES (1);' \
  'CREATE TABLE analysis_report (report_id BIGINT NOT NULL PRIMARY KEY, member_id BIGINT NOT NULL, image_url VARCHAR(255) NOT NULL, match_percentage INT NOT NULL);' \
  | docker exec -i "$container_name" mysql -uroot fitback_existing_refresh_token

for database in fitback fitback_existing_refresh_token; do
  for migration in src/main/resources/db/migration/V*.sql; do
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

if [ "$notification_defaults" != '1:1:1:0:0' ]; then
  echo "Unexpected member_notification_setting defaults: $notification_defaults" >&2
  exit 1
fi

echo 'MySQL migration tests passed.'
