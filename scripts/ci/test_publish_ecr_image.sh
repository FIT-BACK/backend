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
  missing)
    echo 'An error occurred (ImageNotFoundException) when calling the DescribeImages operation: image not found' >&2
    exit 254
    ;;
  denied)
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
DOCKER_STUB

chmod +x "$stub_bin/aws" "$stub_bin/docker"

run_subject() {
  local mode="$1"
  local case_root="$test_root/$mode"

  mkdir -p "$case_root"
  : > "$case_root/docker.log"
  : > "$case_root/github-output"

  PATH="$stub_bin:$PATH" \
    AWS_STUB_MODE="$mode" \
    DOCKER_LOG="$case_root/docker.log" \
    ECR_REGISTRY='123209654535.dkr.ecr.ap-northeast-2.amazonaws.com' \
    ECR_REPOSITORY='fitback-backend' \
    IMAGE_TAG='git-0123456789abcdef' \
    LOCAL_IMAGE='fitback-backend:git-0123456789abcdef' \
    GITHUB_OUTPUT="$case_root/github-output" \
    bash "$subject"
}

run_subject existing > "$test_root/existing.stdout" 2> "$test_root/existing.stderr"
if [ -s "$test_root/existing/docker.log" ]; then
  echo 'Existing immutable tag must not invoke docker tag or push.' >&2
  exit 1
fi
grep -Fq 'Reusing existing immutable ECR tag' "$test_root/existing.stdout"
grep -Fq 'image-uri=123209654535.dkr.ecr.ap-northeast-2.amazonaws.com/fitback-backend:git-0123456789abcdef' \
  "$test_root/existing/github-output"

run_subject missing > "$test_root/missing.stdout" 2> "$test_root/missing.stderr"
if [ "$(wc -l < "$test_root/missing/docker.log" | tr -d ' ')" -ne 2 ]; then
  echo 'Missing tag must invoke docker tag and push exactly once each.' >&2
  exit 1
fi
grep -Fxq 'tag fitback-backend:git-0123456789abcdef 123209654535.dkr.ecr.ap-northeast-2.amazonaws.com/fitback-backend:git-0123456789abcdef' \
  "$test_root/missing/docker.log"
grep -Fxq 'push 123209654535.dkr.ecr.ap-northeast-2.amazonaws.com/fitback-backend:git-0123456789abcdef' \
  "$test_root/missing/docker.log"

if run_subject denied > "$test_root/denied.stdout" 2> "$test_root/denied.stderr"; then
  echo 'Unexpected ECR lookup failures must stop publication.' >&2
  exit 1
fi
if [ -s "$test_root/denied/docker.log" ]; then
  echo 'ECR lookup failure must not invoke docker.' >&2
  exit 1
fi
grep -Fq 'AccessDeniedException' "$test_root/denied.stderr"

echo 'publish_ecr_image.sh tests passed.'
