#!/usr/bin/env bash

set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
test_root="$(cd "$(mktemp -d)" && pwd -P)"
trap 'rm -rf "$test_root"' EXIT

mock_bin="$test_root/bin"
mock_log="$test_root/mock.log"
curl_count_file="$test_root/curl-count"
special_password="pa\$\$\\'word\\value#=end"

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
  PARAMETER_PREFIX='/fitback/prod' \
  DEPLOY_ROOT="$deploy_root" \
  RELEASE_DIR="$release_dir" \
  HEALTH_ATTEMPTS=1 \
  HEALTH_INTERVAL_SECONDS=0 \
  HTTP_PORT=80 \
  MOCK_LOG="$mock_log" \
  MOCK_DB_PASSWORD="$special_password" \
  MOCK_CURL_FAIL_COUNT="${MOCK_CURL_FAIL_COUNT:-0}" \
  MOCK_DOCKER_FAIL_MATCH="${MOCK_DOCKER_FAIL_MATCH:-}" \
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
run_deploy "$deploy_root" "$release_one" "$first_image"

test "$(readlink "$deploy_root/current")" = "$release_one"
env_file="$release_one/.env"
test "$(file_mode "$env_file")" = '600'
grep -Fxq "BACKEND_IMAGE=$first_image" "$env_file"

parsed_password="$(DB_URL='jdbc:mysql://database.internal:3306/fitback' \
  DB_USER='fitback_app' \
  DB_PASSWORD="$special_password" \
  docker compose \
  --project-directory "$release_one" \
  --env-file "$env_file" \
  config --environment | sed -n 's/^DB_PASSWORD=//p')"
test "$parsed_password" = "$special_password"
! grep -Eq '^DB_(URL|USER|PASSWORD)=' "$env_file"

if grep -Fq "$special_password" "$mock_log"; then
  echo 'Database password leaked into a command log.' >&2
  exit 1
fi

create_release "$release_two"
: > "$mock_log"
: > "$curl_count_file"
export MOCK_CURL_FAIL_COUNT=1
unset MOCK_DOCKER_FAIL_MATCH
failed_image="123209654535.dkr.ecr.ap-northeast-2.amazonaws.com/fitback-backend@sha256:$(printf '2%.0s' {1..64})"

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
unset MOCK_FLOCK_FAIL MOCK_DOCKER_FAIL_MATCH MOCK_DOCKER_SIGNAL_MATCH MOCK_LN_FAIL MOCK_MV_SIGNAL
export MOCK_CURL_FAIL_COUNT=0
final_image="123209654535.dkr.ecr.ap-northeast-2.amazonaws.com/fitback-backend@sha256:$(printf '4%.0s' {1..64})"
run_deploy "$deploy_root" "$release_four" "$final_image"
test "$(readlink "$deploy_root/current")" = "$release_four"

echo 'remote_deploy.sh tests passed.'
