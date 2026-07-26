#!/usr/bin/env bash

set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
container_name="fitback-nginx-entrypoint-test-$$"
nginx_image='nginxinc/nginx-unprivileged:1.28.0-alpine@sha256:c97ff0bf7cbae369953c6da1232ec14ad9f971d66360c5698db0856a4cd657a0'
response_headers="$(mktemp)"
response_body="$(mktemp)"

grep -Fq "image: $nginx_image" "$repo_root/compose.yaml"

cleanup() {
  docker rm -f "$container_name" > /dev/null 2>&1 || true
  rm -f "$response_headers" "$response_body"
}
trap cleanup EXIT

docker run \
  --detach \
  --rm \
  --name "$container_name" \
  --add-host backend:127.0.0.1 \
  --publish 127.0.0.1::8080 \
  --volume "$repo_root/deploy/nginx/default.conf:/etc/nginx/conf.d/default.conf:ro" \
  "$nginx_image" > /dev/null

published_address="$(docker port "$container_name" 8080/tcp)"
base_url="http://$published_address"

for _ in {1..20}; do
  if curl \
      --fail \
      --silent \
      --header 'Host: localhost' \
      "$base_url/nginx-health" > /dev/null 2>&1; then
    break
  fi
  sleep 0.2
done

test "$(
  curl \
    --fail \
    --silent \
    --show-error \
    --header 'Host: localhost' \
    "$base_url/nginx-health"
)" = 'ok'

root_status="$(
  curl \
    --fail \
    --silent \
    --show-error \
    --header 'Host: localhost' \
    --dump-header "$response_headers" \
    --output "$response_body" \
    --write-out '%{http_code}' \
    "$base_url/"
)"

test "$root_status" = '200'
grep -Eiq '^Content-Type: text/html([;[:space:]]|$)' "$response_headers"
grep -Fq 'FITBACK Backend API' "$response_body"
grep -Fq 'href="/swagger-ui.html"' "$response_body"
grep -Fq 'href="/actuator/health/readiness"' "$response_body"

echo 'Nginx public entrypoint tests passed.'
