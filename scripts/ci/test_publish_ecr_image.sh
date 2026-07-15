#!/usr/bin/env bash

set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
subject="$repo_root/scripts/ci/publish_ecr_image.sh"
test_root="$(mktemp -d)"
stub_bin="$test_root/bin"

cleanup() {
  rm -rf "$test_root"
}

trap cleanup EXIT
mkdir -p "$stub_bin"

cat > "$stub_bin/aws" <<'AWS_STUB'
#!/usr/bin/env bash
set -euo pipefail

case "${AWS_STUB_MODE:?}" in
  existing)
    printf '%s\n' 'sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa'
    ;;
  invalid)
    printf '%s\n' 'not-a-valid-digest'
    ;;
  race)
    printf '%s\n' 'describe-images' >> "${AWS_CALL_LOG:?}"
    call_count="$(wc -l < "$AWS_CALL_LOG" | tr -d ' ')"
    if [ "$call_count" -eq 1 ]; then
      echo 'An error occurred (ImageNotFoundException) when calling the DescribeImages operation: image not found' >&2
      exit 254
    fi
    printf '%s\n' 'sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb'
    ;;
  missing | push_failed)
    printf '%s\n' 'describe-images' >> "${AWS_CALL_LOG:?}"
    echo 'An error occurred (ImageNotFoundException) when calling the DescribeImages operation: image not found' >&2
    exit 254
    ;;
  denied)
    printf '%s\n' 'describe-images' >> "${AWS_CALL_LOG:?}"
    echo 'An error occurred (AccessDeniedException) when calling the DescribeImages operation: denied' >&2
    exit 255
    ;;
  *)
    echo "Unexpected AWS_STUB_MODE: ${AWS_STUB_MODE}" >&2
    exit 2
    ;;
esac
AWS_STUB

cat > "$stub_bin/docker" <<'DOCKER_STUB'
#!/usr/bin/env bash
set -euo pipefail
printf '%s\n' "$*" >> "${DOCKER_LOG:?}"
if [ "${DOCKER_STUB_MODE:-success}" = 'fail' ] && [ "${1:-}" = 'push' ]; then
  echo 'simulated docker push failure' >&2
  exit 42
fi
DOCKER_STUB

chmod +x "$stub_bin/aws" "$stub_bin/docker"

run_subject() {
  local case_name="$1"
  local aws_mode="$2"
  local docker_mode="$3"
  local case_root="$test_root/$case_name"

  mkdir -p "$case_root"
  : > "$case_root/aws.log"
  : > "$case_root/docker.log"
  : > "$case_root/github-output"

  PATH="$stub_bin:$PATH" \
    AWS_STUB_MODE="$aws_mode" \
    AWS_CALL_LOG="$case_root/aws.log" \
    DOCKER_STUB_MODE="$docker_mode" \
    DOCKER_LOG="$case_root/docker.log" \
    ECR_REGISTRY='123209654535.dkr.ecr.ap-northeast-2.amazonaws.com' \
    ECR_REPOSITORY='fitback-backend' \
    IMAGE_TAG='git-0123456789abcdef' \
    LOCAL_IMAGE='fitback-backend:git-0123456789abcdef' \
    GITHUB_OUTPUT="$case_root/github-output" \
    bash "$subject"
}

run_subject existing existing success > "$test_root/existing.stdout" 2> "$test_root/existing.stderr"
if [ -s "$test_root/existing/docker.log" ]; then
  echo 'Existing immutable tag must not invoke docker tag or push.' >&2
  exit 1
fi
grep -Fq 'Reusing existing immutable ECR tag' "$test_root/existing.stdout"
grep -Fq 'image-uri=123209654535.dkr.ecr.ap-northeast-2.amazonaws.com/fitback-backend:git-0123456789abcdef' \
  "$test_root/existing/github-output"

run_subject missing missing success > "$test_root/missing.stdout" 2> "$test_root/missing.stderr"
if [ "$(wc -l < "$test_root/missing/docker.log" | tr -d ' ')" -ne 2 ]; then
  echo 'Missing tag must invoke docker tag and push exactly once each.' >&2
  exit 1
fi
grep -Fxq 'tag fitback-backend:git-0123456789abcdef 123209654535.dkr.ecr.ap-northeast-2.amazonaws.com/fitback-backend:git-0123456789abcdef' \
  "$test_root/missing/docker.log"
grep -Fxq 'push 123209654535.dkr.ecr.ap-northeast-2.amazonaws.com/fitback-backend:git-0123456789abcdef' \
  "$test_root/missing/docker.log"

if run_subject denied denied success > "$test_root/denied.stdout" 2> "$test_root/denied.stderr"; then
  echo 'Unexpected ECR lookup failures must stop publication.' >&2
  exit 1
fi
if [ -s "$test_root/denied/docker.log" ]; then
  echo 'ECR lookup failure must not invoke docker.' >&2
  exit 1
fi
grep -Fq 'AccessDeniedException' "$test_root/denied.stderr"

if run_subject invalid invalid success > "$test_root/invalid.stdout" 2> "$test_root/invalid.stderr"; then
  echo 'An invalid ECR digest must stop publication.' >&2
  exit 1
fi
if [ -s "$test_root/invalid/docker.log" ]; then
  echo 'An invalid existing digest must not invoke docker.' >&2
  exit 1
fi
grep -Fq 'ECR returned an invalid digest' "$test_root/invalid.stderr"

run_subject race race fail > "$test_root/race.stdout" 2> "$test_root/race.stderr"
if [ "$(wc -l < "$test_root/race/aws.log" | tr -d ' ')" -ne 2 ]; then
  echo 'A concurrent push failure must re-query ECR exactly once.' >&2
  exit 1
fi
if [ "$(wc -l < "$test_root/race/docker.log" | tr -d ' ')" -ne 2 ]; then
  echo 'A concurrent publication race must invoke docker tag and push exactly once each.' >&2
  exit 1
fi
grep -Fq 'Reusing concurrently published immutable ECR tag' "$test_root/race.stdout"
grep -Fq 'image-uri=123209654535.dkr.ecr.ap-northeast-2.amazonaws.com/fitback-backend:git-0123456789abcdef' \
  "$test_root/race/github-output"

if run_subject push-failed push_failed fail \
    > "$test_root/push-failed.stdout" 2> "$test_root/push-failed.stderr"; then
  echo 'An unrecovered docker push failure must stop publication.' >&2
  exit 1
fi
if [ "$(wc -l < "$test_root/push-failed/aws.log" | tr -d ' ')" -ne 2 ]; then
  echo 'An unrecovered push failure must re-query ECR exactly once.' >&2
  exit 1
fi
grep -Fq 'simulated docker push failure' "$test_root/push-failed.stderr"
if [ -s "$test_root/push-failed/github-output" ]; then
  echo 'A failed publication must not export an image URI.' >&2
  exit 1
fi

echo 'publish_ecr_image.sh tests passed.'
