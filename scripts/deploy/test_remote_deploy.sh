#!/usr/bin/env bash

set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
test_root="$(cd "$(mktemp -d)" && pwd -P)"
trap 'rm -rf "$test_root"' EXIT

mock_bin="$test_root/bin"
mock_log="$test_root/mock.log"
curl_count_file="$test_root/curl-count"
special_password="pa\$\$\\'word\\value#=end"
special_jwt_secret="jwt\$\$\\'secret\\value#=end-for-fitback-test"
special_hmac_secret="hmac\$\$\\'secret\\value#=end-for-fitback-test"
special_kakao_rest_api_key="kakao-rest-api-key-for-fitback-test"
special_kakao_rest_api_secret="kakao\$\$\\'secret\\value#=end-for-fitback-test"
special_front_redirect_uri="https://frontend.example.com/oauth/success"
special_mail_email="mail@fitback.com"
special_mail_app_password="mail\$\$\\'password\\value#=end-for-fitback-test"
special_front_password_reset_url="https://frontend.example.com/reset-password"
special_cloudfront_private_key="Y2xvdWRmcm9udC1wcml2YXRlLWtleS1mb3ItdGVzdA=="
special_openai_api_key="openai-api-key-for-runtime-only-test"
app_cors_allowed_origins="https://frontend-chi-one-35.vercel.app,http://localhost:3000,http://localhost:5173"

mkdir -p "$mock_bin"
: > "$mock_log"
: > "$curl_count_file"

cat > "$mock_bin/aws" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail

printf 'aws %s\n' "$*" >> "$MOCK_LOG"

if [ "${1:-}" = 'ecr' ] && [ "${2:-}" = 'get-login-password' ]; then
  printf 'mock-ecr-password\n'
  exit 0
fi

if [ "${1:-}" = 'ssm' ] && [ "${2:-}" = 'get-parameter' ]; then
  parameter_name=''
  while [ "$#" -gt 0 ]; do
    if [ "$1" = '--name' ]; then
      parameter_name="$2"
      break
    fi
    shift
  done

  case "$parameter_name" in
    */db-url)
      printf 'jdbc:mysql://database.internal:3306/fitback\n'
      ;;
    */db-user)
      printf 'fitback_app\n'
      ;;
    */db-password)
      printf '%s\n' "$MOCK_DB_PASSWORD"
      ;;
    */jwt-secret-key)
      printf '%s\n' "$MOCK_JWT_SECRET_KEY"
      ;;
    */hmac-secret-key)
      printf '%s\n' "$MOCK_HMAC_SECRET_KEY"
      ;;
    */kakao-rest-api-key)
      printf '%s\n' "$MOCK_KAKAO_REST_API_KEY"
      ;;
    */kakao-rest-api-secret)
      printf '%s\n' "$MOCK_KAKAO_REST_API_SECRET"
      ;;
    */front-redirect-uri)
      printf '%s\n' "$MOCK_FRONT_REDIRECT_URI"
      ;;
    */mail-email)
      printf '%s\n' "$MOCK_MAIL_EMAIL"
      ;;
    */mail-app-password)
      printf '%s\n' "$MOCK_MAIL_APP_PASSWORD"
      ;;
    */front-password-reset-url)
      printf '%s\n' "$MOCK_FRONT_PASSWORD_RESET_URL"
      ;;
    */cloudfront-private-key)
      printf '%s\n' "$MOCK_CLOUDFRONT_PRIVATE_KEY"
      ;;
    */openai-api-key)
      printf '%s\n' "$MOCK_OPENAI_API_KEY"
      ;;
    *)
      printf 'Unexpected parameter: %s\n' "$parameter_name" >&2
      exit 1
      ;;
  esac
  exit 0
fi

printf 'Unexpected aws invocation: %s\n' "$*" >&2
exit 1
EOF

cat > "$mock_bin/docker" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail

printf 'docker %s\n' "$*" >> "$MOCK_LOG"

if [ "${FITBACK_AI_OPENAI_API_KEY+x}" = 'x' ] && [ -n "$FITBACK_AI_OPENAI_API_KEY" ]; then
  printf 'runtime FITBACK_AI_OPENAI_API_KEY=set\n' >> "$MOCK_LOG"
fi

if [ "${1:-}" = 'login' ]; then
  cat > /dev/null
  exit 0
fi

if [ "${1:-}" = 'inspect' ]; then
  printf 'healthy\n'
  exit 0
fi

if [ "${1:-}" = 'compose' ] && [[ " $* " == *' ps -q backend '* ]]; then
  printf 'backend-container-id\n'
  exit 0
fi

if [ -n "${MOCK_DOCKER_FAIL_MATCH:-}" ] \
  && [[ "$*" == *"$MOCK_DOCKER_FAIL_MATCH"* ]] \
  && [[ " $* " == *' up -d --remove-orphans '* ]]; then
  exit 1
fi

if [ -n "${MOCK_DOCKER_PULL_FAIL_MATCH:-}" ] \
  && [[ "$*" == *"$MOCK_DOCKER_PULL_FAIL_MATCH"* ]] \
  && [[ " $* " == *' pull backend '* ]]; then
  exit 1
fi

if [ -n "${MOCK_DOCKER_SIGNAL_MATCH:-}" ] \
  && [[ "$*" == *"$MOCK_DOCKER_SIGNAL_MATCH"* ]] \
  && [[ " $* " == *' up -d --remove-orphans '* ]]; then
  kill -TERM "$PPID"
  sleep 0.1
  exit 0
fi

exit 0
EOF

cat > "$mock_bin/curl" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail

printf 'curl %s\n' "$*" >> "$MOCK_LOG"
count="$(cat "$CURL_COUNT_FILE" 2>/dev/null || printf '0')"
count="$((count + 1))"
printf '%s\n' "$count" > "$CURL_COUNT_FILE"

if [ "$count" -le "${MOCK_CURL_FAIL_COUNT:-0}" ]; then
  exit 1
fi
EOF

cat > "$mock_bin/flock" <<'EOF'
#!/usr/bin/env bash
if [ "${MOCK_FLOCK_FAIL:-0}" = '1' ]; then
  exit 1
fi
exit 0
EOF

cat > "$mock_bin/ln" <<'EOF'
#!/usr/bin/env bash
if [ "${MOCK_LN_FAIL:-0}" = '1' ]; then
  exit 1
fi
exec /bin/ln "$@"
EOF

cat > "$mock_bin/mv" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail

if [ "${MOCK_MV_SIGNAL:-0}" = '1' ] \
  && [ ! -e "$MOCK_MV_SIGNAL_FILE" ] \
  && [[ "$*" == *'.current.'* ]]; then
  touch "$MOCK_MV_SIGNAL_FILE"
  /bin/mv "$@"
  kill -TERM "$PPID"
  sleep 0.1
  exit 0
fi

exec /bin/mv "$@"
EOF

chmod +x "$mock_bin/aws" "$mock_bin/docker" "$mock_bin/curl" "$mock_bin/flock" "$mock_bin/ln" "$mock_bin/mv"

file_mode() {
  if stat -c '%a' "$1" > /dev/null 2>&1; then
    stat -c '%a' "$1"
  else
    stat -f '%Lp' "$1"
  fi
}

create_release() {
  local release_dir="$1"
  mkdir -p "$release_dir/deploy/nginx" "$release_dir/scripts/deploy"
  cp "$repo_root/compose.yaml" "$release_dir/compose.yaml"
  cp "$repo_root/deploy/nginx/default.conf" "$release_dir/deploy/nginx/default.conf"
  cp "$repo_root/scripts/deploy/remote_deploy.sh" "$release_dir/scripts/deploy/remote_deploy.sh"
}

run_deploy() {
  local deploy_root="$1"
  local release_dir="$2"
  local image_reference="$3"

  IMAGE_REFERENCE="$image_reference" \
  AWS_REGION='ap-northeast-2' \
  IMAGE_BUCKET='fitback-prod-images-123209654535-ap-northeast-2' \
  IMAGE_CDN_BASE_URL='https://d1p2ierkew26r1.cloudfront.net' \
  CLOUDFRONT_KEY_PAIR_ID='K1XNJ3JDEDCVL3' \
  APP_CORS_ALLOWED_ORIGINS="$app_cors_allowed_origins" \
  FITBACK_AI_TAG_ANALYZER="${FITBACK_AI_TAG_ANALYZER:-prototype}" \
  FITBACK_AI_REQUEST_TIMEOUT="${FITBACK_AI_REQUEST_TIMEOUT:-}" \
  FITBACK_AI_OPENAI_MODEL="${FITBACK_AI_OPENAI_MODEL:-}" \
  FITBACK_AI_BEDROCK_MODEL_ID="${FITBACK_AI_BEDROCK_MODEL_ID:-}" \
  SHOPPING_PROVIDER='shopify' \
  SHOPIFY_ENABLED='true' \
  PARAMETER_PREFIX='/fitback/prod' \
  DEPLOY_ROOT="$deploy_root" \
  RELEASE_DIR="$release_dir" \
  HEALTH_ATTEMPTS=1 \
  HEALTH_INTERVAL_SECONDS=0 \
  HTTP_PORT=80 \
  MOCK_LOG="$mock_log" \
  MOCK_DB_PASSWORD="$special_password" \
  MOCK_JWT_SECRET_KEY="$special_jwt_secret" \
  MOCK_HMAC_SECRET_KEY="$special_hmac_secret" \
  MOCK_KAKAO_REST_API_KEY="$special_kakao_rest_api_key" \
  MOCK_KAKAO_REST_API_SECRET="$special_kakao_rest_api_secret" \
  MOCK_FRONT_REDIRECT_URI="$special_front_redirect_uri" \
  MOCK_MAIL_EMAIL="$special_mail_email" \
  MOCK_MAIL_APP_PASSWORD="$special_mail_app_password" \
  MOCK_FRONT_PASSWORD_RESET_URL="$special_front_password_reset_url" \
  MOCK_CLOUDFRONT_PRIVATE_KEY="$special_cloudfront_private_key" \
  MOCK_OPENAI_API_KEY="$special_openai_api_key" \
  MOCK_CURL_FAIL_COUNT="${MOCK_CURL_FAIL_COUNT:-0}" \
  MOCK_DOCKER_FAIL_MATCH="${MOCK_DOCKER_FAIL_MATCH:-}" \
  MOCK_DOCKER_PULL_FAIL_MATCH="${MOCK_DOCKER_PULL_FAIL_MATCH:-}" \
  MOCK_DOCKER_SIGNAL_MATCH="${MOCK_DOCKER_SIGNAL_MATCH:-}" \
  MOCK_FLOCK_FAIL="${MOCK_FLOCK_FAIL:-0}" \
  MOCK_LN_FAIL="${MOCK_LN_FAIL:-0}" \
  MOCK_MV_SIGNAL="${MOCK_MV_SIGNAL:-0}" \
  MOCK_MV_SIGNAL_FILE="$test_root/mv-signal" \
  CURL_COUNT_FILE="$curl_count_file" \
  PATH="$mock_bin:$PATH" \
    bash "$repo_root/scripts/deploy/remote_deploy.sh"
}

deploy_root="$test_root/fitback"
release_one="$deploy_root/releases/release-one"
release_two="$deploy_root/releases/release-two"
release_three="$deploy_root/releases/release-three"
mkdir -p "$deploy_root/releases"
create_release "$release_one"

first_image="123209654535.dkr.ecr.ap-northeast-2.amazonaws.com/fitback-backend@sha256:$(printf '1%.0s' {1..64})"
failed_image="123209654535.dkr.ecr.ap-northeast-2.amazonaws.com/fitback-backend@sha256:$(printf '2%.0s' {1..64})"
run_deploy "$deploy_root" "$release_one" "$first_image"

test "$(readlink "$deploy_root/current")" = "$release_one"
env_file="$release_one/.env"
test "$(file_mode "$env_file")" = '600'
grep -Fxq "BACKEND_IMAGE=$first_image" "$env_file"
grep -Fxq 'AWS_REGION=ap-northeast-2' "$env_file"
grep -Fxq 'IMAGE_BUCKET=fitback-prod-images-123209654535-ap-northeast-2' "$env_file"
grep -Fxq 'IMAGE_CDN_BASE_URL=https://d1p2ierkew26r1.cloudfront.net' "$env_file"
grep -Fxq 'CLOUDFRONT_KEY_PAIR_ID=K1XNJ3JDEDCVL3' "$env_file"
grep -Fxq "APP_CORS_ALLOWED_ORIGINS=$app_cors_allowed_origins" "$env_file"
grep -Fxq 'FITBACK_AI_TAG_ANALYZER=prototype' "$env_file"
grep -Fxq 'FITBACK_AI_REQUEST_TIMEOUT=' "$env_file"
grep -Fxq 'FITBACK_AI_OPENAI_MODEL=' "$env_file"
grep -Fxq 'FITBACK_AI_BEDROCK_MODEL_ID=' "$env_file"
grep -Fxq 'SHOPPING_PROVIDER=shopify' "$env_file"
grep -Fxq 'SHOPIFY_ENABLED=true' "$env_file"

parsed_password="$(DB_URL='jdbc:mysql://database.internal:3306/fitback' \
  DB_USER='fitback_app' \
  DB_PASSWORD="$special_password" \
  JWT_SECRET_KEY="$special_jwt_secret" \
  HMAC_SECRET_KEY="$special_hmac_secret" \
  KAKAO_REST_API_KEY="$special_kakao_rest_api_key" \
  KAKAO_REST_API_SECRET="$special_kakao_rest_api_secret" \
  FRONT_REDIRECT_URI="$special_front_redirect_uri" \
  MAIL_EMAIL="$special_mail_email" \
  MAIL_APP_PASSWORD="$special_mail_app_password" \
  FRONT_PASSWORD_RESET_URL="$special_front_password_reset_url" \
  CLOUDFRONT_PRIVATE_KEY_BASE64="$special_cloudfront_private_key" \
  docker compose \
  --project-directory "$release_one" \
  --env-file "$env_file" \
  config --environment | sed -n 's/^DB_PASSWORD=//p')"
test "$parsed_password" = "$special_password"
parsed_jwt_secret="$(DB_URL='jdbc:mysql://database.internal:3306/fitback' \
  DB_USER='fitback_app' \
  DB_PASSWORD="$special_password" \
  JWT_SECRET_KEY="$special_jwt_secret" \
  HMAC_SECRET_KEY="$special_hmac_secret" \
  KAKAO_REST_API_KEY="$special_kakao_rest_api_key" \
  KAKAO_REST_API_SECRET="$special_kakao_rest_api_secret" \
  FRONT_REDIRECT_URI="$special_front_redirect_uri" \
  MAIL_EMAIL="$special_mail_email" \
  MAIL_APP_PASSWORD="$special_mail_app_password" \
  FRONT_PASSWORD_RESET_URL="$special_front_password_reset_url" \
  CLOUDFRONT_PRIVATE_KEY_BASE64="$special_cloudfront_private_key" \
  docker compose \
  --project-directory "$release_one" \
  --env-file "$env_file" \
  config --environment | sed -n 's/^JWT_SECRET_KEY=//p')"
test "$parsed_jwt_secret" = "$special_jwt_secret"
parsed_hmac_secret="$(DB_URL='jdbc:mysql://database.internal:3306/fitback' \
  DB_USER='fitback_app' \
  DB_PASSWORD="$special_password" \
  JWT_SECRET_KEY="$special_jwt_secret" \
  HMAC_SECRET_KEY="$special_hmac_secret" \
  KAKAO_REST_API_KEY="$special_kakao_rest_api_key" \
  KAKAO_REST_API_SECRET="$special_kakao_rest_api_secret" \
  FRONT_REDIRECT_URI="$special_front_redirect_uri" \
  MAIL_EMAIL="$special_mail_email" \
  MAIL_APP_PASSWORD="$special_mail_app_password" \
  FRONT_PASSWORD_RESET_URL="$special_front_password_reset_url" \
  CLOUDFRONT_PRIVATE_KEY_BASE64="$special_cloudfront_private_key" \
  docker compose \
  --project-directory "$release_one" \
  --env-file "$env_file" \
  config --environment | sed -n 's/^HMAC_SECRET_KEY=//p')"
test "$parsed_hmac_secret" = "$special_hmac_secret"
parsed_cloudfront_private_key="$(DB_URL='jdbc:mysql://database.internal:3306/fitback' \
  DB_USER='fitback_app' \
  DB_PASSWORD="$special_password" \
  JWT_SECRET_KEY="$special_jwt_secret" \
  HMAC_SECRET_KEY="$special_hmac_secret" \
  KAKAO_REST_API_KEY="$special_kakao_rest_api_key" \
  KAKAO_REST_API_SECRET="$special_kakao_rest_api_secret" \
  FRONT_REDIRECT_URI="$special_front_redirect_uri" \
  MAIL_EMAIL="$special_mail_email" \
  MAIL_APP_PASSWORD="$special_mail_app_password" \
  FRONT_PASSWORD_RESET_URL="$special_front_password_reset_url" \
  CLOUDFRONT_PRIVATE_KEY_BASE64="$special_cloudfront_private_key" \
  docker compose \
  --project-directory "$release_one" \
  --env-file "$env_file" \
  config --environment | sed -n 's/^CLOUDFRONT_PRIVATE_KEY_BASE64=//p')"
test "$parsed_cloudfront_private_key" = "$special_cloudfront_private_key"
parsed_kakao_rest_api_key="$(DB_URL='jdbc:mysql://database.internal:3306/fitback' \
  DB_USER='fitback_app' \
  DB_PASSWORD="$special_password" \
  JWT_SECRET_KEY="$special_jwt_secret" \
  HMAC_SECRET_KEY="$special_hmac_secret" \
  KAKAO_REST_API_KEY="$special_kakao_rest_api_key" \
  KAKAO_REST_API_SECRET="$special_kakao_rest_api_secret" \
  FRONT_REDIRECT_URI="$special_front_redirect_uri" \
  MAIL_EMAIL="$special_mail_email" \
  MAIL_APP_PASSWORD="$special_mail_app_password" \
  FRONT_PASSWORD_RESET_URL="$special_front_password_reset_url" \
  CLOUDFRONT_PRIVATE_KEY_BASE64="$special_cloudfront_private_key" \
  docker compose \
  --project-directory "$release_one" \
  --env-file "$env_file" \
  config --environment | sed -n 's/^KAKAO_REST_API_KEY=//p')"
test "$parsed_kakao_rest_api_key" = "$special_kakao_rest_api_key"
parsed_kakao_rest_api_secret="$(DB_URL='jdbc:mysql://database.internal:3306/fitback' \
  DB_USER='fitback_app' \
  DB_PASSWORD="$special_password" \
  JWT_SECRET_KEY="$special_jwt_secret" \
  HMAC_SECRET_KEY="$special_hmac_secret" \
  KAKAO_REST_API_KEY="$special_kakao_rest_api_key" \
  KAKAO_REST_API_SECRET="$special_kakao_rest_api_secret" \
  FRONT_REDIRECT_URI="$special_front_redirect_uri" \
  MAIL_EMAIL="$special_mail_email" \
  MAIL_APP_PASSWORD="$special_mail_app_password" \
  FRONT_PASSWORD_RESET_URL="$special_front_password_reset_url" \
  CLOUDFRONT_PRIVATE_KEY_BASE64="$special_cloudfront_private_key" \
  docker compose \
  --project-directory "$release_one" \
  --env-file "$env_file" \
  config --environment | sed -n 's/^KAKAO_REST_API_SECRET=//p')"
test "$parsed_kakao_rest_api_secret" = "$special_kakao_rest_api_secret"
parsed_front_redirect_uri="$(DB_URL='jdbc:mysql://database.internal:3306/fitback' \
  DB_USER='fitback_app' \
  DB_PASSWORD="$special_password" \
  JWT_SECRET_KEY="$special_jwt_secret" \
  HMAC_SECRET_KEY="$special_hmac_secret" \
  KAKAO_REST_API_KEY="$special_kakao_rest_api_key" \
  KAKAO_REST_API_SECRET="$special_kakao_rest_api_secret" \
  FRONT_REDIRECT_URI="$special_front_redirect_uri" \
  MAIL_EMAIL="$special_mail_email" \
  MAIL_APP_PASSWORD="$special_mail_app_password" \
  FRONT_PASSWORD_RESET_URL="$special_front_password_reset_url" \
  CLOUDFRONT_PRIVATE_KEY_BASE64="$special_cloudfront_private_key" \
  docker compose \
  --project-directory "$release_one" \
  --env-file "$env_file" \
  config --environment | sed -n 's/^FRONT_REDIRECT_URI=//p')"
test "$parsed_front_redirect_uri" = "$special_front_redirect_uri"
parsed_mail_app_password="$(DB_URL='jdbc:mysql://database.internal:3306/fitback' \
  DB_USER='fitback_app' \
  DB_PASSWORD="$special_password" \
  JWT_SECRET_KEY="$special_jwt_secret" \
  HMAC_SECRET_KEY="$special_hmac_secret" \
  KAKAO_REST_API_KEY="$special_kakao_rest_api_key" \
  KAKAO_REST_API_SECRET="$special_kakao_rest_api_secret" \
  FRONT_REDIRECT_URI="$special_front_redirect_uri" \
  MAIL_EMAIL="$special_mail_email" \
  MAIL_APP_PASSWORD="$special_mail_app_password" \
  FRONT_PASSWORD_RESET_URL="$special_front_password_reset_url" \
  CLOUDFRONT_PRIVATE_KEY_BASE64="$special_cloudfront_private_key" \
  docker compose \
  --project-directory "$release_one" \
  --env-file "$env_file" \
  config --environment | sed -n 's/^MAIL_APP_PASSWORD=//p')"
test "$parsed_mail_app_password" = "$special_mail_app_password"
grep_status=0
grep -Eq '^(DB_(URL|USER|PASSWORD)|JWT_SECRET_KEY|HMAC_SECRET_KEY|KAKAO_REST_API_KEY|KAKAO_REST_API_SECRET|FRONT_REDIRECT_URI|MAIL_EMAIL|MAIL_APP_PASSWORD|FRONT_PASSWORD_RESET_URL|CLOUDFRONT_PRIVATE_KEY_BASE64)=' "$env_file" || grep_status=$?
if [ "$grep_status" -eq 0 ]; then
  echo 'Secret was written to .env.' >&2
  exit 1
elif [ "$grep_status" -ne 1 ]; then
  echo "Failed to inspect $env_file." >&2
  exit 1
fi

if grep -Fq "$special_password" "$mock_log"; then
  echo 'Database password leaked into a command log.' >&2
  exit 1
fi
if grep -Fq "$special_jwt_secret" "$mock_log"; then
  echo 'JWT secret leaked into a command log.' >&2
  exit 1
fi
if grep -Fq "$special_hmac_secret" "$mock_log"; then
  echo 'HMAC secret leaked into a command log.' >&2
  exit 1
fi
if grep -Fq "$special_kakao_rest_api_secret" "$mock_log"; then
  echo 'Kakao REST API secret leaked into a command log.' >&2
  exit 1
fi
if grep -Fq "$special_mail_app_password" "$mock_log"; then
  echo 'Mail app password leaked into a command log.' >&2
  exit 1
fi
if grep -Fq "$special_cloudfront_private_key" "$mock_log"; then
  echo 'CloudFront private key leaked into a command log.' >&2
  exit 1
fi

ai_deploy_root="$test_root/ai-fitback"
mkdir -p "$ai_deploy_root/releases"
openai_release="$ai_deploy_root/releases/release-openai"
create_release "$openai_release"
: > "$mock_log"
: > "$curl_count_file"
FITBACK_AI_TAG_ANALYZER='openai' \
FITBACK_AI_REQUEST_TIMEOUT='PT30S' \
FITBACK_AI_OPENAI_MODEL='gpt-test-model' \
FITBACK_AI_BEDROCK_MODEL_ID='' \
  run_deploy "$ai_deploy_root" "$openai_release" "$failed_image"
openai_env_file="$openai_release/.env"
grep -Fq '/fitback/prod/openai-api-key' "$mock_log"
grep -Fq 'runtime FITBACK_AI_OPENAI_API_KEY=set' "$mock_log"
grep -Fxq 'FITBACK_AI_REQUEST_TIMEOUT=PT30S' "$openai_env_file"
grep -Fxq 'FITBACK_AI_OPENAI_MODEL=gpt-test-model' "$openai_env_file"
grep -Fq 'FITBACK_AI_BEDROCK_MODEL_ID=' "$openai_env_file"
if grep -Fq 'FITBACK_AI_OPENAI_API_KEY=' "$openai_env_file"; then
  echo 'OpenAI API key was written to .env.' >&2
  exit 1
fi
if grep -Fq "$special_openai_api_key" "$mock_log"; then
  echo 'OpenAI API key leaked into a command log.' >&2
  exit 1
fi

bedrock_release="$ai_deploy_root/releases/release-bedrock"
create_release "$bedrock_release"
: > "$mock_log"
: > "$curl_count_file"
FITBACK_AI_TAG_ANALYZER='bedrock' \
FITBACK_AI_REQUEST_TIMEOUT='PT45S' \
FITBACK_AI_OPENAI_MODEL='' \
FITBACK_AI_BEDROCK_MODEL_ID='global.anthropic.claude-test-v1' \
  run_deploy "$ai_deploy_root" "$bedrock_release" "$failed_image"
if grep -Fq '/fitback/prod/openai-api-key' "$mock_log"; then
  echo 'Bedrock deployment queried the OpenAI API key.' >&2
  exit 1
fi

missing_timeout_release="$ai_deploy_root/releases/release-missing-timeout"
create_release "$missing_timeout_release"
: > "$mock_log"
if FITBACK_AI_TAG_ANALYZER='openai' FITBACK_AI_REQUEST_TIMEOUT='' FITBACK_AI_OPENAI_MODEL='gpt-test-model' \
  run_deploy "$ai_deploy_root" "$missing_timeout_release" "$failed_image" > /dev/null 2>&1; then
  echo 'Expected missing OpenAI timeout to fail.' >&2
  exit 1
fi
test ! -e "$missing_timeout_release/.env"
test ! -s "$mock_log"

missing_model_release="$ai_deploy_root/releases/release-missing-model"
create_release "$missing_model_release"
: > "$mock_log"
if FITBACK_AI_TAG_ANALYZER='bedrock' FITBACK_AI_REQUEST_TIMEOUT='PT30S' FITBACK_AI_BEDROCK_MODEL_ID='' \
  run_deploy "$ai_deploy_root" "$missing_model_release" "$failed_image" > /dev/null 2>&1; then
  echo 'Expected missing Bedrock model to fail.' >&2
  exit 1
fi
test ! -e "$missing_model_release/.env"
test ! -s "$mock_log"

workflow_file="$repo_root/.github/workflows/backend-cd.yml"
grep -Fq 'FITBACK_AI_REQUEST_TIMEOUT: ${{ vars.FITBACK_AI_REQUEST_TIMEOUT }}' "$workflow_file"
grep -Fq 'FITBACK_AI_OPENAI_MODEL: ${{ vars.FITBACK_AI_OPENAI_MODEL }}' "$workflow_file"
grep -Fq 'FITBACK_AI_BEDROCK_MODEL_ID: ${{ vars.FITBACK_AI_BEDROCK_MODEL_ID }}' "$workflow_file"
grep -Fq 'fitback_ai_request_timeout' "$workflow_file"
grep -Fq 'fitback_ai_openai_model' "$workflow_file"
grep -Fq 'fitback_ai_bedrock_model_id' "$workflow_file"
if grep -Eq '(^|[[:space:]])OPENAI_API_KEY=' "$workflow_file"; then
  echo 'OpenAI API key was added to the workflow.' >&2
  exit 1
fi

pull_failed_release="$deploy_root/releases/release-pull-failure"
create_release "$pull_failed_release"
: > "$mock_log"
: > "$curl_count_file"
export MOCK_DOCKER_PULL_FAIL_MATCH="$pull_failed_release"

if pull_failure_output="$(run_deploy "$deploy_root" "$pull_failed_release" "$failed_image" 2>&1)"; then
  echo 'Expected the image pull failure to return non-zero.' >&2
  exit 1
fi

grep -Fq 'rollback was skipped' <<< "$pull_failure_output"
test "$(readlink "$deploy_root/current")" = "$release_one"
test ! -e "$pull_failed_release/.env"
if grep -F -- "--project-directory $release_one" "$mock_log" | grep -Fq ' up -d --remove-orphans'; then
  echo 'The previous release was restarted before stack mutation.' >&2
  exit 1
fi

unset MOCK_DOCKER_PULL_FAIL_MATCH

create_release "$release_two"
: > "$mock_log"
: > "$curl_count_file"
export MOCK_CURL_FAIL_COUNT=1
unset MOCK_DOCKER_FAIL_MATCH

if rollback_output="$(run_deploy "$deploy_root" "$release_two" "$failed_image" 2>&1)"; then
  echo 'Expected the unhealthy deployment to fail after rollback.' >&2
  exit 1
fi

grep -Fq 'Rollback succeeded.' <<< "$rollback_output"
test "$(readlink "$deploy_root/current")" = "$release_one"
grep -Fxq "BACKEND_IMAGE=$first_image" "$release_one/.env"
grep -Fq -- "--project-directory $release_two" "$mock_log"
grep -Fq -- "--project-directory $release_one" "$mock_log"

create_release "$release_three"
: > "$mock_log"
: > "$curl_count_file"
export MOCK_DOCKER_FAIL_MATCH="$release_one"

if rollback_failure_output="$(run_deploy "$deploy_root" "$release_three" "$failed_image" 2>&1)"; then
  echo 'Expected deployment and rollback failure to return non-zero.' >&2
  exit 1
fi

grep -Fq 'Rollback failed.' <<< "$rollback_failure_output"
test "$(readlink "$deploy_root/current")" = "$release_one"

first_deploy_root="$test_root/first-deploy"
first_failed_release="$first_deploy_root/releases/release-one"
mkdir -p "$first_deploy_root/releases"
create_release "$first_failed_release"
: > "$mock_log"
: > "$curl_count_file"
unset MOCK_DOCKER_FAIL_MATCH

if run_deploy "$first_deploy_root" "$first_failed_release" "$failed_image" > /dev/null 2>&1; then
  echo 'Expected an unhealthy first deployment to fail.' >&2
  exit 1
fi

test ! -e "$first_deploy_root/current"
test ! -e "$first_failed_release/.env"
grep -Fq ' down --remove-orphans' "$mock_log"

locked_release="$deploy_root/releases/release-locked"
create_release "$locked_release"
: > "$mock_log"
export MOCK_FLOCK_FAIL=1

if lock_output="$(run_deploy "$deploy_root" "$locked_release" "$failed_image" 2>&1)"; then
  echo 'Expected a concurrent deployment to be rejected.' >&2
  exit 1
fi

grep -Fq 'Another deployment is already running' <<< "$lock_output"
test ! -s "$mock_log"

activation_release="$deploy_root/releases/release-activation-failure"
create_release "$activation_release"
: > "$mock_log"
: > "$curl_count_file"
unset MOCK_FLOCK_FAIL MOCK_DOCKER_FAIL_MATCH MOCK_DOCKER_SIGNAL_MATCH
export MOCK_CURL_FAIL_COUNT=0
export MOCK_LN_FAIL=1

if activation_output="$(run_deploy "$deploy_root" "$activation_release" "$failed_image" 2>&1)"; then
  echo 'Expected activation failure to roll back.' >&2
  exit 1
fi

grep -Fq 'Rollback succeeded.' <<< "$activation_output"
test "$(readlink "$deploy_root/current")" = "$release_one"

signal_release="$deploy_root/releases/release-signal"
create_release "$signal_release"
: > "$mock_log"
: > "$curl_count_file"
unset MOCK_LN_FAIL
export MOCK_DOCKER_SIGNAL_MATCH="$signal_release"

if signal_output="$(run_deploy "$deploy_root" "$signal_release" "$failed_image" 2>&1)"; then
  echo 'Expected a terminated deployment to roll back.' >&2
  exit 1
fi

grep -Fq 'Deployment interrupted; starting rollback.' <<< "$signal_output"
grep -Fq 'Rollback succeeded.' <<< "$signal_output"
test "$(readlink "$deploy_root/current")" = "$release_one"

post_activation_signal_release="$deploy_root/releases/release-post-activation-signal"
create_release "$post_activation_signal_release"
: > "$mock_log"
: > "$curl_count_file"
unset MOCK_DOCKER_SIGNAL_MATCH
export MOCK_MV_SIGNAL=1
rm -f "$test_root/mv-signal"

if post_activation_signal_output="$(run_deploy "$deploy_root" "$post_activation_signal_release" "$failed_image" 2>&1)"; then
  echo 'Expected a post-activation termination to roll back.' >&2
  exit 1
fi

grep -Fq 'Deployment interrupted; starting rollback.' <<< "$post_activation_signal_output"
grep -Fq 'Rollback succeeded.' <<< "$post_activation_signal_output"
test "$(readlink "$deploy_root/current")" = "$release_one"

release_four="$deploy_root/releases/release-four"
create_release "$release_four"
: > "$mock_log"
: > "$curl_count_file"
unset MOCK_FLOCK_FAIL MOCK_DOCKER_FAIL_MATCH MOCK_DOCKER_PULL_FAIL_MATCH MOCK_DOCKER_SIGNAL_MATCH MOCK_LN_FAIL MOCK_MV_SIGNAL
export MOCK_CURL_FAIL_COUNT=0
final_image="123209654535.dkr.ecr.ap-northeast-2.amazonaws.com/fitback-backend@sha256:$(printf '4%.0s' {1..64})"
run_deploy "$deploy_root" "$release_four" "$final_image"
test "$(readlink "$deploy_root/current")" = "$release_four"

echo 'remote_deploy.sh tests passed.'
