#!/usr/bin/env bash

set -euo pipefail

: "${ECR_REGISTRY:?ECR_REGISTRY is required}"
: "${ECR_REPOSITORY:?ECR_REPOSITORY is required}"
: "${IMAGE_TAG:?IMAGE_TAG is required}"
: "${LOCAL_IMAGE:?LOCAL_IMAGE is required}"
: "${GITHUB_OUTPUT:?GITHUB_OUTPUT is required}"

image_uri="${ECR_REGISTRY}/${ECR_REPOSITORY}:${IMAGE_TAG}"
lookup_error_file="$(mktemp)"
push_output_file="$(mktemp)"

cleanup() {
  rm -f "$lookup_error_file" "$push_output_file"
}

trap cleanup EXIT

describe_digest() {
  local error_file="$1"

  aws ecr describe-images \
    --repository-name "$ECR_REPOSITORY" \
    --image-ids imageTag="$IMAGE_TAG" \
    --query 'imageDetails[0].imageDigest' \
    --output text 2> "$error_file"
}

if existing_digest="$(describe_digest "$lookup_error_file")"; then
  if [[ "$existing_digest" != sha256:* ]]; then
    echo "ECR returned an invalid digest for $IMAGE_TAG: $existing_digest" >&2
    exit 1
  fi

  echo "Reusing existing immutable ECR tag: $IMAGE_TAG"
else
  lookup_status=$?

  if grep -Fq 'ImageNotFoundException' "$lookup_error_file"; then
    docker tag "$LOCAL_IMAGE" "$image_uri"
    if docker push "$image_uri" > "$push_output_file" 2>&1; then
      cat "$push_output_file"
    else
      push_status=$?
      : > "$lookup_error_file"

      if concurrent_digest="$(describe_digest "$lookup_error_file")" \
          && [[ "$concurrent_digest" == sha256:* ]]; then
        echo "Reusing concurrently published immutable ECR tag: $IMAGE_TAG"
      else
        cat "$push_output_file" >&2
        exit "$push_status"
      fi
    fi
  else
    cat "$lookup_error_file" >&2
    exit "$lookup_status"
  fi
fi

echo "image-uri=$image_uri" >> "$GITHUB_OUTPUT"
