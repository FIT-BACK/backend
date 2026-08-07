#!/usr/bin/env bash

set -Eeuo pipefail

: "${IMAGE_REFERENCE:?IMAGE_REFERENCE is required}"
: "${AWS_REGION:?AWS_REGION is required}"
: "${IMAGE_BUCKET:?IMAGE_BUCKET is required}"
: "${IMAGE_CDN_BASE_URL:?IMAGE_CDN_BASE_URL is required}"
: "${CLOUDFRONT_KEY_PAIR_ID:?CLOUDFRONT_KEY_PAIR_ID is required}"
: "${APP_CORS_ALLOWED_ORIGINS:?APP_CORS_ALLOWED_ORIGINS is required}"

PARAMETER_PREFIX="${PARAMETER_PREFIX:-/fitback/prod}"
PARAMETER_PREFIX="${PARAMETER_PREFIX%/}"
DEPLOY_ROOT="${DEPLOY_ROOT:-/opt/fitback}"
RELEASES_DIR="$DEPLOY_ROOT/releases"
RELEASE_DIR="${RELEASE_DIR:-$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)}"
CURRENT_LINK="$DEPLOY_ROOT/current"
LOCK_FILE="$DEPLOY_ROOT/deploy.lock"
HTTP_BIND_ADDRESS="${HTTP_BIND_ADDRESS:-0.0.0.0}"
HTTP_PORT="${HTTP_PORT:-80}"
HEALTH_ATTEMPTS="${HEALTH_ATTEMPTS:-30}"
HEALTH_INTERVAL_SECONDS="${HEALTH_INTERVAL_SECONDS:-2}"
FITBACK_AI_TAG_ANALYZER="${FITBACK_AI_TAG_ANALYZER:-unavailable}"
FITBACK_AI_REQUEST_TIMEOUT="${FITBACK_AI_REQUEST_TIMEOUT:-PT30S}"
FITBACK_AI_OPENAI_MODEL="${FITBACK_AI_OPENAI_MODEL:-}"
FITBACK_AI_BEDROCK_MODEL_ID="${FITBACK_AI_BEDROCK_MODEL_ID:-}"
SHOPPING_PROVIDER="${SHOPPING_PROVIDER:-fixture}"
SHOPIFY_ENABLED="${SHOPIFY_ENABLED:-false}"

if [[ ! "$IMAGE_REFERENCE" =~ ^[0-9]+\.dkr\.ecr\.[a-z0-9-]+\.amazonaws\.com/.+@sha256:[0-9a-f]{64}$ ]]; then
  echo "IMAGE_REFERENCE must be a digest-pinned Amazon ECR image: $IMAGE_REFERENCE" >&2
  exit 1
fi

if [[ ! "$PARAMETER_PREFIX" =~ ^/[A-Za-z0-9_.\/-]+$ ]]; then
  echo "Invalid PARAMETER_PREFIX: $PARAMETER_PREFIX" >&2
  exit 1
fi

if [[ ! "$IMAGE_BUCKET" =~ ^[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]$ ]]; then
  echo "Invalid IMAGE_BUCKET: $IMAGE_BUCKET" >&2
  exit 1
fi

if [[ ! "$IMAGE_CDN_BASE_URL" =~ ^https://[a-z0-9]+\.cloudfront\.net$ ]]; then
  echo "Invalid IMAGE_CDN_BASE_URL: $IMAGE_CDN_BASE_URL" >&2
  exit 1
fi

if [[ ! "$CLOUDFRONT_KEY_PAIR_ID" =~ ^[A-Z0-9]+$ ]]; then
  echo "Invalid CLOUDFRONT_KEY_PAIR_ID: $CLOUDFRONT_KEY_PAIR_ID" >&2
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

for ai_model_setting in "$FITBACK_AI_OPENAI_MODEL" "$FITBACK_AI_BEDROCK_MODEL_ID"; do
  if [ -n "$ai_model_setting" ] && [[ ! "$ai_model_setting" =~ ^[^[:space:]]+$ ]]; then
    echo 'AI model settings must be empty or contain no whitespace.' >&2
    exit 1
  fi
done

case "$FITBACK_AI_TAG_ANALYZER" in
  unavailable|prototype)
    ;;
  openai)
    if [[ ! "$FITBACK_AI_OPENAI_MODEL" =~ ^[^[:space:]]+$ ]]; then
      echo 'FITBACK_AI_OPENAI_MODEL is required for the OpenAI analyzer.' >&2
      exit 1
    fi
    ;;
  bedrock)
    if [[ ! "$FITBACK_AI_BEDROCK_MODEL_ID" =~ ^[^[:space:]]+$ ]]; then
      echo 'FITBACK_AI_BEDROCK_MODEL_ID is required for the Bedrock analyzer.' >&2
      exit 1
    fi
    ;;
  *)
    echo 'FITBACK_AI_TAG_ANALYZER must be unavailable, prototype, openai, or bedrock.' >&2
    exit 1
    ;;
esac

if [[ ! "$FITBACK_AI_REQUEST_TIMEOUT" =~ ^PT[1-9][0-9]*S$ ]]; then
  echo 'FITBACK_AI_REQUEST_TIMEOUT must be a positive whole-second ISO-8601 duration such as PT30S.' >&2
  exit 1
fi

if [ "$SHOPPING_PROVIDER" != 'fixture' ] && [ "$SHOPPING_PROVIDER" != 'shopify' ]; then
  echo 'SHOPPING_PROVIDER must be fixture or shopify.' >&2
  exit 1
fi

if [ "$SHOPIFY_ENABLED" != 'true' ] && [ "$SHOPIFY_ENABLED" != 'false' ]; then
  echo 'SHOPIFY_ENABLED must be true or false.' >&2
  exit 1
fi

if { [ "$SHOPPING_PROVIDER" = 'shopify' ] && [ "$SHOPIFY_ENABLED" != 'true' ]; } \
    || { [ "$SHOPPING_PROVIDER" != 'shopify' ] && [ "$SHOPIFY_ENABLED" = 'true' ]; }; then
  echo 'SHOPPING_PROVIDER=shopify and SHOPIFY_ENABLED=true must be configured together.' >&2
  exit 1
fi

for required_command in aws curl docker flock grep ln mktemp mv; do
  if ! command -v "$required_command" > /dev/null 2>&1; then
    echo "Required command is missing: $required_command" >&2
    exit 1
  fi
done

mkdir -p "$DEPLOY_ROOT" "$RELEASES_DIR"

exec 9> "$LOCK_FILE"
if ! flock -n 9; then
  echo 'Another deployment is already running on this host.' >&2
  exit 75
fi

releases_dir_canonical="$(cd "$RELEASES_DIR" && pwd -P)"
if [ ! -d "$RELEASE_DIR" ]; then
  echo "Release directory is missing: $RELEASE_DIR" >&2
  exit 1
fi
release_dir_canonical="$(cd "$RELEASE_DIR" && pwd -P)"

case "$release_dir_canonical" in
  "$releases_dir_canonical"/*)
    RELEASE_DIR="$release_dir_canonical"
    ;;
  *)
    echo "RELEASE_DIR must be inside $RELEASES_DIR" >&2
    exit 1
    ;;
esac

if [ ! -f "$RELEASE_DIR/compose.yaml" ]; then
  echo "Compose file is missing: $RELEASE_DIR/compose.yaml" >&2
  exit 1
fi

if [ ! -f "$RELEASE_DIR/deploy/nginx/default.conf" ]; then
  echo "Nginx configuration is missing: $RELEASE_DIR/deploy/nginx/default.conf" >&2
  exit 1
fi

if [ -e "$CURRENT_LINK" ] && [ ! -L "$CURRENT_LINK" ]; then
  echo "Current release path must be a symbolic link: $CURRENT_LINK" >&2
  exit 1
fi

previous_release=''
if [ -L "$CURRENT_LINK" ]; then
  if [ ! -d "$CURRENT_LINK" ]; then
    echo "Current release link is broken: $CURRENT_LINK" >&2
    exit 1
  fi
  previous_release="$(cd "$CURRENT_LINK" && pwd -P)"

  case "$previous_release" in
    "$releases_dir_canonical"/*)
      ;;
    *)
      echo "Current release must be inside $RELEASES_DIR" >&2
      exit 1
      ;;
  esac
fi

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

write_environment() {
  local release_dir="$1"
  local image_reference="$2"
  local env_file="$release_dir/.env"
  local temporary_env

  temporary_env="$(mktemp "$release_dir/.env.XXXXXX")"
  {
    printf 'BACKEND_IMAGE=%s\n' "$image_reference"
    printf 'HTTP_BIND_ADDRESS=%s\n' "$HTTP_BIND_ADDRESS"
    printf 'HTTP_PORT=%s\n' "$HTTP_PORT"
    printf 'AWS_REGION=%s\n' "$AWS_REGION"
    printf 'IMAGE_BUCKET=%s\n' "$IMAGE_BUCKET"
    printf 'IMAGE_CDN_BASE_URL=%s\n' "$IMAGE_CDN_BASE_URL"
    printf 'CLOUDFRONT_KEY_PAIR_ID=%s\n' "$CLOUDFRONT_KEY_PAIR_ID"
    printf 'APP_CORS_ALLOWED_ORIGINS=%s\n' "$APP_CORS_ALLOWED_ORIGINS"
    printf 'FITBACK_AI_TAG_ANALYZER=%s\n' "$FITBACK_AI_TAG_ANALYZER"
    printf 'FITBACK_AI_REQUEST_TIMEOUT=%s\n' "$FITBACK_AI_REQUEST_TIMEOUT"
    printf 'FITBACK_AI_OPENAI_MODEL=%s\n' "$FITBACK_AI_OPENAI_MODEL"
    printf 'FITBACK_AI_BEDROCK_MODEL_ID=%s\n' "$FITBACK_AI_BEDROCK_MODEL_ID"
    printf 'SHOPPING_PROVIDER=%s\n' "$SHOPPING_PROVIDER"
    printf 'SHOPIFY_ENABLED=%s\n' "$SHOPIFY_ENABLED"
    printf 'SPRING_DATASOURCE_DRIVER_CLASS_NAME=com.mysql.cj.jdbc.Driver\n'
    printf 'SPRING_JPA_HIBERNATE_DDL_AUTO=validate\n'
  } > "$temporary_env"
  chmod 600 "$temporary_env"
  mv "$temporary_env" "$env_file"
}

compose_in() {
  local release_dir="$1"
  local release_ai_tag_analyzer
  local release_openai_api_key
  local -a compose_environment
  shift
  release_ai_tag_analyzer="$(sed -n 's/^FITBACK_AI_TAG_ANALYZER=//p' "$release_dir/.env")"
  compose_environment=(
    -u BACKEND_IMAGE
    -u HTTP_BIND_ADDRESS
    -u HTTP_PORT
    -u AWS_REGION
    -u IMAGE_BUCKET
    -u IMAGE_CDN_BASE_URL
    -u CLOUDFRONT_KEY_PAIR_ID
    -u APP_CORS_ALLOWED_ORIGINS
    -u FITBACK_AI_TAG_ANALYZER
    -u FITBACK_AI_REQUEST_TIMEOUT
    -u FITBACK_AI_OPENAI_MODEL
    -u FITBACK_AI_BEDROCK_MODEL_ID
    -u FITBACK_AI_OPENAI_API_KEY
    -u SHOPPING_PROVIDER
    -u SHOPIFY_ENABLED
    -u SPRING_DATASOURCE_DRIVER_CLASS_NAME
    -u SPRING_JPA_HIBERNATE_DDL_AUTO
    DB_URL="$db_url"
    DB_USER="$db_user"
    DB_PASSWORD="$db_password"
    JWT_SECRET_KEY="$jwt_secret_key"
    HMAC_SECRET_KEY="$hmac_secret_key"
    KAKAO_REST_API_KEY="$kakao_rest_api_key"
    KAKAO_REST_API_SECRET="$kakao_rest_api_secret"
    FRONT_REDIRECT_URI="$front_redirect_uri"
    MAIL_EMAIL="$mail_email"
    MAIL_APP_PASSWORD="$mail_app_password"
    FRONT_PASSWORD_RESET_URL="$front_password_reset_url"
    CLOUDFRONT_PRIVATE_KEY_BASE64="$cloudfront_private_key_base64"
  )
  if [ "$release_ai_tag_analyzer" = 'openai' ]; then
    release_openai_api_key="$fitback_ai_openai_api_key"
    if [ -z "$release_openai_api_key" ]; then
      release_openai_api_key="$(get_parameter 'openai-api-key')"
    fi
    compose_environment+=(FITBACK_AI_OPENAI_API_KEY="$release_openai_api_key")
  fi

  env "${compose_environment[@]}" \
    docker compose \
    --project-directory "$release_dir" \
    --env-file "$release_dir/.env" \
    "$@"
}

verify_release_health() {
  local release_dir="$1"
  local attempt
  local backend_container
  local backend_health

  for ((attempt = 1; attempt <= HEALTH_ATTEMPTS; attempt += 1)); do
    if curl --fail --silent --show-error --max-time 5 \
        "http://127.0.0.1:${HTTP_PORT}/nginx-health" > /dev/null 2>&1; then
      backend_container="$(compose_in "$release_dir" ps -q backend)"
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

  echo "Release did not become healthy after ${HEALTH_ATTEMPTS} attempts: $release_dir" >&2
  compose_in "$release_dir" ps >&2 || true
  return 1
}

activate_release() {
  local release_dir="$1"
  local temporary_link="$DEPLOY_ROOT/.current.$$"

  rm -f "$temporary_link"
  ln -s "$release_dir" "$temporary_link"
  if mv --help 2>&1 | grep -q -- '--no-target-directory'; then
    mv -Tf "$temporary_link" "$CURRENT_LINK"
  else
    mv -fh "$temporary_link" "$CURRENT_LINK"
  fi
}

rollback() {
  local active_release=''
  local current_release=''

  if [ -n "$previous_release" ]; then
    echo "Deployment failed; restoring release: $previous_release" >&2

    if ! compose_in "$previous_release" pull backend; then
      echo 'Rollback failed while pulling the previous image.' >&2
      return 1
    fi

    if ! compose_in "$previous_release" up -d --remove-orphans; then
      echo 'Rollback failed while starting the previous release.' >&2
      return 1
    fi

    if ! verify_release_health "$previous_release"; then
      echo 'Rollback failed health verification.' >&2
      return 1
    fi

    if [ -L "$CURRENT_LINK" ] && [ -d "$CURRENT_LINK" ]; then
      current_release="$(cd "$CURRENT_LINK" && pwd -P)"
    fi
    if [ "$current_release" != "$previous_release" ]; then
      if ! activate_release "$previous_release"; then
        echo 'Rollback failed while restoring the current release link.' >&2
        return 1
      fi
    fi

    echo 'Rollback succeeded.' >&2
    return 0
  fi

  echo 'Deployment failed and no previous release is available.' >&2
  if ! compose_in "$RELEASE_DIR" down --remove-orphans; then
    echo 'Failed to stop the unhealthy first deployment.' >&2
    return 1
  fi
  rm -f "$RELEASE_DIR/.env"
  if [ -L "$CURRENT_LINK" ] && [ -d "$CURRENT_LINK" ]; then
    active_release="$(cd "$CURRENT_LINK" && pwd -P)"
    if [ "$active_release" = "$RELEASE_DIR" ]; then
      rm -f "$CURRENT_LINK"
    fi
  fi
  return 0
}

db_url="$(get_parameter 'db-url')"
db_user="$(get_parameter 'db-user')"
db_password="$(get_parameter 'db-password')"
jwt_secret_key="$(get_parameter 'jwt-secret-key')"
hmac_secret_key="$(get_parameter 'hmac-secret-key')"
kakao_rest_api_key="$(get_parameter 'kakao-rest-api-key')"
kakao_rest_api_secret="$(get_parameter 'kakao-rest-api-secret')"
front_redirect_uri="$(get_parameter 'front-redirect-uri')"
mail_email="$(get_parameter 'mail-email')"
mail_app_password="$(get_parameter 'mail-app-password')"
front_password_reset_url="$(get_parameter 'front-password-reset-url')"
cloudfront_private_key_base64="$(get_parameter 'cloudfront-private-key')"
fitback_ai_openai_api_key=''
if [ "$FITBACK_AI_TAG_ANALYZER" = 'openai' ]; then
  fitback_ai_openai_api_key="$(get_parameter 'openai-api-key')"
fi

require_single_line 'APP_CORS_ALLOWED_ORIGINS' "$APP_CORS_ALLOWED_ORIGINS"
require_single_line 'FITBACK_AI_REQUEST_TIMEOUT' "$FITBACK_AI_REQUEST_TIMEOUT"
require_single_line 'db-url' "$db_url"
require_single_line 'db-user' "$db_user"
require_single_line 'db-password' "$db_password"
require_single_line 'jwt-secret-key' "$jwt_secret_key"
require_single_line 'hmac-secret-key' "$hmac_secret_key"
require_single_line 'kakao-rest-api-key' "$kakao_rest_api_key"
require_single_line 'kakao-rest-api-secret' "$kakao_rest_api_secret"
require_single_line 'front-redirect-uri' "$front_redirect_uri"
require_single_line 'mail-email' "$mail_email"
require_single_line 'mail-app-password' "$mail_app_password"
require_single_line 'front-password-reset-url' "$front_password_reset_url"
require_single_line 'cloudfront-private-key' "$cloudfront_private_key_base64"
if [ "$FITBACK_AI_TAG_ANALYZER" = 'openai' ]; then
  require_single_line 'openai-api-key' "$fitback_ai_openai_api_key"
fi

if [[ ! "$front_redirect_uri" =~ ^https://[^[:space:]]+$ ]]; then
  echo 'front-redirect-uri must be an HTTPS URL.' >&2
  exit 1
fi

if [[ ! "$mail_email" =~ ^[^[:space:]@]+@[^[:space:]@]+$ ]]; then
  echo 'mail-email must be a valid email address.' >&2
  exit 1
fi

if [[ ! "$front_password_reset_url" =~ ^https://[^[:space:]]+$ ]]; then
  echo 'front-password-reset-url must be an HTTPS URL.' >&2
  exit 1
fi

if [[ ! "$cloudfront_private_key_base64" =~ ^[A-Za-z0-9+/]+={0,2}$ ]]; then
  echo 'cloudfront-private-key must contain a Base64-encoded private key.' >&2
  exit 1
fi

write_environment "$RELEASE_DIR" "$IMAGE_REFERENCE"

ecr_registry="${IMAGE_REFERENCE%%/*}"
deployment_failed=0
mutation_started=0
rollback_in_progress=0

rollback_on_abort() {
  local exit_code="$1"

  trap - ERR INT TERM
  if [ "$mutation_started" -eq 1 ] && [ "$rollback_in_progress" -eq 0 ]; then
    rollback_in_progress=1
    echo 'Deployment interrupted; starting rollback.' >&2
    if rollback; then
      exit "$exit_code"
    fi

    echo 'Rollback failed after deployment interruption.' >&2
    exit 2
  fi

  exit "$exit_code"
}

trap 'rollback_on_abort $?' ERR
trap 'rollback_on_abort 130' INT
trap 'rollback_on_abort 143' TERM

if ! aws ecr get-login-password --region "$AWS_REGION" |
    docker login --username AWS --password-stdin "$ecr_registry"; then
  deployment_failed=1
elif ! compose_in "$RELEASE_DIR" pull backend; then
  deployment_failed=1
else
  mutation_started=1
  if ! compose_in "$RELEASE_DIR" up -d --remove-orphans; then
    deployment_failed=1
  elif ! verify_release_health "$RELEASE_DIR"; then
    deployment_failed=1
  elif ! activate_release "$RELEASE_DIR"; then
    deployment_failed=1
  fi
fi

if [ "$deployment_failed" -ne 0 ]; then
  trap - ERR INT TERM
  if [ "$mutation_started" -eq 0 ]; then
    rm -f "$RELEASE_DIR/.env"
    echo 'Deployment failed before the running stack was changed; rollback was skipped.' >&2
    exit 1
  fi

  rollback_in_progress=1
  if rollback; then
    exit 1
  fi

  echo 'Rollback failed.' >&2
  exit 2
fi

mutation_started=0
trap - ERR INT TERM
echo "Deployment succeeded: $IMAGE_REFERENCE"
