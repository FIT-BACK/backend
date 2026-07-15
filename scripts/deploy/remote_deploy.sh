#!/usr/bin/env bash

set -Eeuo pipefail

: "${IMAGE_REFERENCE:?IMAGE_REFERENCE is required}"
: "${AWS_REGION:?AWS_REGION is required}"

PARAMETER_PREFIX="${PARAMETER_PREFIX:-/fitback/prod}"
PARAMETER_PREFIX="${PARAMETER_PREFIX%/}"
DEPLOY_ROOT="${DEPLOY_ROOT:-/opt/fitback}"
APP_DIR="$DEPLOY_ROOT/app"
ENV_FILE="$APP_DIR/.env"
PREVIOUS_ENV_FILE="$DEPLOY_ROOT/.env.previous"
HTTP_BIND_ADDRESS="${HTTP_BIND_ADDRESS:-0.0.0.0}"
HTTP_PORT="${HTTP_PORT:-80}"
HEALTH_ATTEMPTS="${HEALTH_ATTEMPTS:-30}"
HEALTH_INTERVAL_SECONDS="${HEALTH_INTERVAL_SECONDS:-2}"

if [[ ! "$IMAGE_REFERENCE" =~ ^[0-9]+\.dkr\.ecr\.[a-z0-9-]+\.amazonaws\.com/.+@sha256:[0-9a-f]{64}$ ]]; then
  echo "IMAGE_REFERENCE must be a digest-pinned Amazon ECR image: $IMAGE_REFERENCE" >&2
  exit 1
fi

if [[ ! "$PARAMETER_PREFIX" =~ ^/[A-Za-z0-9_.\/-]+$ ]]; then
  echo "Invalid PARAMETER_PREFIX: $PARAMETER_PREFIX" >&2
  exit 1
fi

if [[ ! "$HTTP_PORT" =~ ^[0-9]+$ ]] || [ "$HTTP_PORT" -lt 1 ] || [ "$HTTP_PORT" -gt 65535 ]; then
  echo "Invalid HTTP_PORT: $HTTP_PORT" >&2
  exit 1
fi

if [[ ! "$HEALTH_ATTEMPTS" =~ ^[1-9][0-9]*$ ]]; then
  echo "HEALTH_ATTEMPTS must be a positive integer." >&2
  exit 1
fi

if [[ ! "$HEALTH_INTERVAL_SECONDS" =~ ^[0-9]+$ ]]; then
  echo "HEALTH_INTERVAL_SECONDS must be a non-negative integer." >&2
  exit 1
fi

for required_command in aws curl docker mktemp; do
  if ! command -v "$required_command" > /dev/null 2>&1; then
    echo "Required command is missing: $required_command" >&2
    exit 1
  fi
done

if [ ! -f "$APP_DIR/compose.yaml" ]; then
  echo "Compose file is missing: $APP_DIR/compose.yaml" >&2
  exit 1
fi

if [ ! -f "$APP_DIR/deploy/nginx/default.conf" ]; then
  echo "Nginx configuration is missing: $APP_DIR/deploy/nginx/default.conf" >&2
  exit 1
fi

mkdir -p "$APP_DIR"
umask 077

get_parameter() {
  aws ssm get-parameter \
    --name "${PARAMETER_PREFIX}/$1" \
    --with-decryption \
    --query 'Parameter.Value' \
    --output text
}

require_single_line() {
  local name="$1"
  local value="$2"

  if [ -z "$value" ] || [[ "$value" == *$'\n'* ]] || [[ "$value" == *$'\r'* ]]; then
    echo "Parameter must be a non-empty single line: $name" >&2
    exit 1
  fi
}

quote_env_value() {
  local value="$1"
  value="${value//\\/\\\\}"
  value="${value//\'/\\\'}"
  printf "'%s'" "$value"
}

write_environment() {
  local image_reference="$1"
  local db_url="$2"
  local db_user="$3"
  local db_password="$4"
  local temporary_env

  temporary_env="$(mktemp "$APP_DIR/.env.XXXXXX")"
  {
    printf 'BACKEND_IMAGE=%s\n' "$image_reference"
    printf 'HTTP_BIND_ADDRESS=%s\n' "$HTTP_BIND_ADDRESS"
    printf 'HTTP_PORT=%s\n' "$HTTP_PORT"
    printf 'DB_URL=%s\n' "$(quote_env_value "$db_url")"
    printf 'DB_USER=%s\n' "$(quote_env_value "$db_user")"
    printf 'DB_PASSWORD=%s\n' "$(quote_env_value "$db_password")"
    printf 'SPRING_DATASOURCE_DRIVER_CLASS_NAME=com.mysql.cj.jdbc.Driver\n'
    printf 'SPRING_JPA_HIBERNATE_DDL_AUTO=validate\n'
  } > "$temporary_env"
  chmod 600 "$temporary_env"
  mv "$temporary_env" "$ENV_FILE"
}

compose() {
  docker compose \
    --project-directory "$APP_DIR" \
    --env-file "$ENV_FILE" \
    "$@"
}

verify_health() {
  local attempt
  local backend_container
  local backend_health

  for ((attempt = 1; attempt <= HEALTH_ATTEMPTS; attempt += 1)); do
    if curl --fail --silent --show-error --max-time 5 \
        "http://127.0.0.1:${HTTP_PORT}/nginx-health" > /dev/null 2>&1; then
      backend_container="$(compose ps -q backend)"
      if [ -n "$backend_container" ]; then
        backend_health="$(docker inspect \
          --format '{{.State.Health.Status}}' "$backend_container" 2>/dev/null || true)"
        if [ "$backend_health" = 'healthy' ]; then
          return 0
        fi
      fi
    fi

    sleep "$HEALTH_INTERVAL_SECONDS"
  done

  echo "Deployment did not become healthy after ${HEALTH_ATTEMPTS} attempts." >&2
  compose ps >&2 || true
  return 1
}

rollback() {
  if [ -f "$PREVIOUS_ENV_FILE" ]; then
    echo 'Deployment failed; restoring the previous image.' >&2
    cp "$PREVIOUS_ENV_FILE" "$ENV_FILE"
    chmod 600 "$ENV_FILE"
    compose pull backend > /dev/null 2>&1 || true
    compose up -d --remove-orphans > /dev/null 2>&1 || true
  else
    echo 'Deployment failed and no previous image is available.' >&2
    compose down --remove-orphans > /dev/null 2>&1 || true
    rm -f "$ENV_FILE"
  fi
}

db_url="$(get_parameter 'db-url')"
db_user="$(get_parameter 'db-user')"
db_password="$(get_parameter 'db-password')"

require_single_line 'db-url' "$db_url"
require_single_line 'db-user' "$db_user"
require_single_line 'db-password' "$db_password"

rm -f "$PREVIOUS_ENV_FILE"
if [ -f "$ENV_FILE" ]; then
  cp "$ENV_FILE" "$PREVIOUS_ENV_FILE"
  chmod 600 "$PREVIOUS_ENV_FILE"
fi

write_environment "$IMAGE_REFERENCE" "$db_url" "$db_user" "$db_password"

ecr_registry="${IMAGE_REFERENCE%%/*}"

if ! aws ecr get-login-password --region "$AWS_REGION" |
    docker login --username AWS --password-stdin "$ecr_registry"; then
  rollback
  exit 1
fi

if ! compose pull backend; then
  rollback
  exit 1
fi

if ! compose up -d --remove-orphans; then
  rollback
  exit 1
fi

if ! verify_health; then
  rollback
  exit 1
fi

rm -f "$PREVIOUS_ENV_FILE"
echo "Deployment succeeded: $IMAGE_REFERENCE"
