#!/usr/bin/env bash

set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
test_root="$(mktemp -d)"
trap 'rm -rf "$test_root"' EXIT

mock_bin="$test_root/bin"
deploy_root="$test_root/fitback"
app_dir="$deploy_root/app"
mock_log="$test_root/mock.log"

mkdir -p "$mock_bin" "$app_dir/deploy/nginx"
: > "$app_dir/compose.yaml"
: > "$app_dir/deploy/nginx/default.conf"
: > "$mock_log"

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
      printf 'mock-db-password\n'
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

if [ "${1:-}" = 'login' ]; then
  cat > /dev/null
  exit 0
fi

if [ "${1:-}" = 'inspect' ]; then
  printf '%s\n' "${MOCK_BACKEND_HEALTH:-healthy}"
  exit 0
fi

if [ "${1:-}" = 'compose' ] && [[ " $* " == *' ps -q backend '* ]]; then
  printf 'backend-container-id\n'
  exit 0
fi

exit 0
EOF

cat > "$mock_bin/curl" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail

printf 'curl %s\n' "$*" >> "$MOCK_LOG"
[ "${MOCK_CURL_FAIL:-0}" != '1' ]
EOF

chmod +x "$mock_bin/aws" "$mock_bin/docker" "$mock_bin/curl"

file_mode() {
  if stat -c '%a' "$1" > /dev/null 2>&1; then
    stat -c '%a' "$1"
  else
    stat -f '%Lp' "$1"
  fi
}

run_deploy() {
  IMAGE_REFERENCE="$1" \
  AWS_REGION='ap-northeast-2' \
  PARAMETER_PREFIX='/fitback/prod' \
  DEPLOY_ROOT="$deploy_root" \
  HEALTH_ATTEMPTS=1 \
  HEALTH_INTERVAL_SECONDS=0 \
  HTTP_PORT=80 \
  MOCK_LOG="$mock_log" \
  MOCK_CURL_FAIL="${MOCK_CURL_FAIL:-0}" \
  MOCK_BACKEND_HEALTH="${MOCK_BACKEND_HEALTH:-healthy}" \
  PATH="$mock_bin:$PATH" \
    bash "$repo_root/scripts/deploy/remote_deploy.sh"
}

first_image="123209654535.dkr.ecr.ap-northeast-2.amazonaws.com/fitback-backend@sha256:$(printf '1%.0s' {1..64})"
run_deploy "$first_image"

env_file="$app_dir/.env"
test "$(file_mode "$env_file")" = '600'
grep -Fxq "BACKEND_IMAGE=$first_image" "$env_file"
grep -Fxq "DB_URL='jdbc:mysql://database.internal:3306/fitback'" "$env_file"
grep -Fxq "DB_USER='fitback_app'" "$env_file"
grep -Fxq "DB_PASSWORD='mock-db-password'" "$env_file"
grep -Fq ' pull backend' "$mock_log"
grep -Fq ' up -d --remove-orphans' "$mock_log"

: > "$mock_log"
export MOCK_CURL_FAIL=1
export MOCK_BACKEND_HEALTH=unhealthy
failed_image="123209654535.dkr.ecr.ap-northeast-2.amazonaws.com/fitback-backend@sha256:$(printf '2%.0s' {1..64})"

if run_deploy "$failed_image"; then
  echo 'Expected the unhealthy deployment to fail.' >&2
  exit 1
fi

grep -Fxq "BACKEND_IMAGE=$first_image" "$env_file"

up_count="$(grep -Fc ' up -d --remove-orphans' "$mock_log")"
test "$up_count" -eq 2

echo 'remote_deploy.sh tests passed.'
