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
  if docker exec "$container_name" mysql -uroot -e 'SELECT 1' >/dev/null 2>&1; then
    ready=1
    break
  fi
  sleep 1
done

if [ "$ready" -ne 1 ]; then
  echo 'MySQL migration test container did not become ready.' >&2
  exit 1
fi

docker exec "$container_name" mysql -uroot -e 'CREATE DATABASE fitback'

printf '%s\n' \
  'CREATE TABLE member (member_id BIGINT NOT NULL PRIMARY KEY);' \
  'CREATE TABLE analysis_report (report_id BIGINT NOT NULL PRIMARY KEY, member_id BIGINT NOT NULL, image_url VARCHAR(255) NOT NULL, match_percentage INT NOT NULL);' \
  | docker exec -i "$container_name" mysql -uroot fitback

for migration in src/main/resources/db/migration/V*.sql; do
  docker exec -i "$container_name" mysql -uroot fitback < "$migration"
done

actual_contract="$(docker exec "$container_name" mysql -uroot \
  --batch --skip-column-names \
  -e "SELECT CONCAT(TABLE_NAME, '.', COLUMN_NAME, '=', IS_NULLABLE)
      FROM information_schema.COLUMNS
      WHERE TABLE_SCHEMA = 'fitback'
        AND (
          (TABLE_NAME = 'image' AND COLUMN_NAME = 'presigned_expires_at')
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
  'image.presigned_expires_at=YES')"

if [ "$actual_contract" != "$expected_contract" ]; then
  echo 'Unexpected MySQL migration contract:' >&2
  printf '%s\n' "$actual_contract" >&2
  exit 1
fi

echo 'MySQL migration tests passed.'
