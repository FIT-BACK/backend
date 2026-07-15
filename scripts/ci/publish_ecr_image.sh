#!/usr/bin/env bash

set -euo pipefail

: "${ECR_REGISTRY:?ECR_REGISTRY is required}"
: "${ECR_REPOSITORY:?ECR_REPOSITORY is required}"
: "${IMAGE_TAG:?IMAGE_TAG is required}"
: "${LOCAL_IMAGE:?LOCAL_IMAGE is required}"
: "${GITHUB_OUTPUT:?GITHUB_OUTPUT is required}"

image_uri="${ECR_REGISTRY}/${ECR_REPOSITORY}:${IMAGE_TAG}"
lookup_error_file="$(mktemp)"

cleanup() {
  rm -f "$lookup_error_file"
}

trap cleanup EXIT

if existing_digest="$(aws ecr describe-images \
    --repository-name "$ECR_REPOSITORY" \
    --image-ids imageTag="$IMAGE_TAG" \
    --query 'imageDetails[0].imageDigest' \
    --output text 2> "$lookup_error_file")"; then
  if [[ "$existing_digest" != sha256:* ]]; then
    echo "ECR returned an invalid digest for $IMAGE_TAG: $existing_digest" >&2
    exit 1
  fi

  echo "Reusing existing immutable ECR tag: $IMAGE_TAG"
else
  lookup_status=$?

  if grep -Fq 'ImageNotFoundException' "$lookup_error_file"; then
    docker tag "$LOCAL_IMAGE" "$image_uri"
    docker push "$image_uri"
  else
    cat "$lookup_error_file" >&2
    exit "$lookup_status"
  fi
fi

echo "image-uri=$image_uri" >> "$GITHUB_OUTPUT"
