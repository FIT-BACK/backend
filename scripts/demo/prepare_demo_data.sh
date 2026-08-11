#!/usr/bin/env bash

set -euo pipefail

MODE="${1:-prepare}"
BASE_URL="${FITBACK_DEMO_BASE_URL:-}"
OUTPUT_FILE="${FITBACK_DEMO_OUTPUT_FILE:-.local/demo/prepared-data.json}"
CURL_CONNECT_TIMEOUT_SECONDS="${FITBACK_DEMO_CONNECT_TIMEOUT_SECONDS:-10}"
CURL_MAX_TIME_SECONDS="${FITBACK_DEMO_MAX_TIME_SECONDS:-120}"

require_command() {
  command -v "$1" >/dev/null 2>&1 || {
    echo "Required command not found: $1" >&2
    exit 1
  }
}

require_env() {
  local name="$1"
  if [[ -z "${!name:-}" ]]; then
    echo "Required environment variable is missing: ${name}" >&2
    exit 1
  fi
}

escape_curl_config_value() {
  local value="$1"
  value="${value//\\/\\\\}"
  value="${value//\"/\\\"}"
  value="${value//$'\r'/}"
  value="${value//$'\n'/}"
  printf '%s' "${value}"
}

api_call() {
  local method="$1"
  local path="$2"
  local token="${3:-}"
  local body="${4:-}"
  local config_file
  config_file="$(mktemp)"
  chmod 600 "${config_file}"

  {
    printf 'silent\n'
    printf 'show-error\n'
    printf 'connect-timeout = %s\n' "${CURL_CONNECT_TIMEOUT_SECONDS}"
    printf 'max-time = %s\n' "${CURL_MAX_TIME_SECONDS}"
    printf 'request = "%s"\n' "$(escape_curl_config_value "${method}")"
    printf 'header = "Accept: application/json"\n'
    if [[ -n "${token}" ]]; then
      printf 'header = "Authorization: Bearer %s"\n' \
        "$(escape_curl_config_value "${token}")"
    fi
    if [[ -n "${body}" ]]; then
      printf 'header = "Content-Type: application/json"\n'
      printf 'data-binary = @-\n'
    fi
    printf 'url = "%s"\n' "$(escape_curl_config_value "${BASE_URL}${path}")"
  } >"${config_file}"

  local response
  if ! response="$(printf '%s' "${body}" | curl --config "${config_file}")"; then
    rm -f "${config_file}"
    return 1
  fi
  rm -f "${config_file}"
  printf '%s' "${response}"
}

require_success() {
  local response="$1"
  local step="$2"
  if ! jq -e . >/dev/null 2>&1 <<<"${response}"; then
    echo "${step} failed: non-JSON response" >&2
    exit 1
  fi
  if ! jq -e '.success == true' >/dev/null 2>&1 <<<"${response}"; then
    local code message
    code="$(jq -r '.code // "UNKNOWN"' <<<"${response}")"
    message="$(jq -r '.message // "오류 메시지 없음"' <<<"${response}")"
    echo "${step} failed: ${code} - ${message}" >&2
    exit 1
  fi
}

login_or_sign_up() {
  local email="$1"
  local password="$2"
  local payload
  payload="$(jq -nc --arg email "${email}" --arg password "${password}" \
    '{email: $email, password: $password}')"

  local response
  response="$(api_call POST "/api/v1/auth/sign" "" "${payload}")"
  if ! jq -e '.success == true' >/dev/null 2>&1 <<<"${response}"; then
    response="$(api_call POST "/api/v1/auth/login" "" "${payload}")"
  fi
  require_success "${response}" "account authentication"
  jq -r '.data.accessToken' <<<"${response}"
}

upload_to_s3() {
  local upload_response="$1"
  local image_path="$2"
  local content_type="$3"
  local upload_url
  upload_url="$(jq -r '.data.uploadUrl' <<<"${upload_response}")"
  local config_file
  config_file="$(mktemp)"
  chmod 600 "${config_file}"

  {
    printf 'silent\n'
    printf 'show-error\n'
    printf 'connect-timeout = %s\n' "${CURL_CONNECT_TIMEOUT_SECONDS}"
    printf 'max-time = %s\n' "${CURL_MAX_TIME_SECONDS}"
    printf 'output = "/dev/null"\n'
    printf 'write-out = "%%{http_code}"\n'
    printf 'url = "%s"\n' "$(escape_curl_config_value "${upload_url}")"
  } >"${config_file}"

  while IFS=$'\t' read -r key value; do
    printf 'form = "%s=%s"\n' \
      "$(escape_curl_config_value "${key}")" \
      "$(escape_curl_config_value "${value}")" >>"${config_file}"
  done < <(jq -r '.data.uploadFields | to_entries[] | [.key, .value] | @tsv' \
    <<<"${upload_response}")
  printf 'form = "file=@%s;type=%s"\n' \
    "$(escape_curl_config_value "${image_path}")" \
    "$(escape_curl_config_value "${content_type}")" >>"${config_file}"

  local status
  if ! status="$(curl --config "${config_file}")"; then
    rm -f "${config_file}"
    return 1
  fi
  rm -f "${config_file}"
  if [[ "${status}" != "201" && "${status}" != "204" ]]; then
    echo "S3 upload failed: HTTP ${status}" >&2
    exit 1
  fi
}

save_report() {
  local alias="$1"
  local token="$2"
  local report_id="$3"
  local report_response="$4"
  local selected_items
  selected_items="$(jq -c '
    [.data.recommendationGroups[]
      | select((.items | length) > 0)
      | {category: .category, productId: .items[0].productId}]
  ' <<<"${report_response}")"

  if [[ "$(jq 'length' <<<"${selected_items}")" -eq 0 ]]; then
    echo "${alias} has no recommendation item to save" >&2
    exit 1
  fi

  # 화면 재진입이 가능하도록 각 추천 카테고리의 1순위 상품을 선택해 저장한다.
  local save_body
  save_body="$(jq -nc --argjson selectedItems "${selected_items}" \
    '{selectedItems: $selectedItems}')"
  local save_response
  save_response="$(api_call PUT "/api/v1/analyses/${report_id}/save" \
    "${token}" "${save_body}")"
  require_success "${save_response}" "${alias} analysis save"

  local list_response
  list_response="$(api_call GET "/api/v1/analyses?pageSize=50" "${token}")"
  require_success "${list_response}" "${alias} analysis list"
  local listed
  listed="$(jq --argjson reportId "${report_id}" \
    'any(.data.items[]; .reportId == $reportId)' <<<"${list_response}")"
  if [[ "${listed}" != "true" ]]; then
    echo "${alias} saved report is missing from analysis list" >&2
    exit 1
  fi

  jq -nc \
    --argjson saved "$(jq '.data.saved' <<<"${save_response}")" \
    --argjson listed "${listed}" \
    --argjson selectedItemCount "$(jq 'length' <<<"${selected_items}")" \
    '{saved: $saved, listed: $listed, selectedItemCount: $selectedItemCount}'
}

find_tag_id() {
  local tags_response="$1"
  local tag_name="$2"
  jq -r --arg tagName "${tag_name}" \
    '.data.items[] | select(.tagName == $tagName) | .tagId' \
    <<<"${tags_response}" | head -n 1
}

upload_demo_image() {
  local token="$1"
  local image_path="$2"
  local purpose="$3"
  local step="$4"

  if [[ ! -f "${image_path}" ]]; then
    echo "${step} image not found: ${image_path}" >&2
    exit 1
  fi

  local content_type
  content_type="$(file -b --mime-type "${image_path}")"
  case "${content_type}" in
    image/jpeg|image/png|image/webp) ;;
    *)
      echo "${step} image type is not supported: ${content_type}" >&2
      exit 1
      ;;
  esac

  local file_size upload_body upload_response image_id complete_response
  file_size="$(wc -c <"${image_path}" | tr -d ' ')"
  upload_body="$(jq -nc \
    --arg purpose "${purpose}" \
    --arg contentType "${content_type}" \
    --argjson fileSize "${file_size}" \
    '{purpose: $purpose, contentType: $contentType, fileSize: $fileSize}')"
  upload_response="$(api_call POST "/api/v1/images/upload-requests" \
    "${token}" "${upload_body}")"
  require_success "${upload_response}" "${step} upload request"

  image_id="$(jq -r '.data.imageId' <<<"${upload_response}")"
  upload_to_s3 "${upload_response}" "${image_path}" "${content_type}"
  complete_response="$(api_call POST "/api/v1/images/${image_id}/complete" "${token}")"
  require_success "${complete_response}" "${step} upload completion"
  printf '%s' "${image_id}"
}

find_reusable_content_report_id() {
  local token="$1"
  local list_response
  list_response="$(api_call GET "/api/v1/analyses?pageSize=50" "${token}")"
  require_success "${list_response}" "Content analysis list"

  jq -r '
    [.data.items[]
      | select(
          (.tags | index("스트릿")) != null
          and (.tags | index("캐주얼")) != null
          and (.tags | index("streetwear")) != null
          and (.tags | index("oversized")) != null
          and (.tags | index("black")) != null
        )]
    | first
    | .reportId // empty
  ' <<<"${list_response}"
}

find_owned_content_lookbook_id() {
  local token="$1"
  local nickname="$2"
  local list_response
  list_response="$(api_call GET "/api/v1/lookbooks?pageSize=20" "${token}")"
  require_success "${list_response}" "Content lookbook list"

  local lookbook_id detail_response
  while read -r lookbook_id; do
    [[ -n "${lookbook_id}" ]] || continue
    detail_response="$(api_call GET "/api/v1/lookbooks/${lookbook_id}" "${token}")"
    if jq -e '
        .success == true
        and .data.isOwner == true
        and .data.matchedImageUrl != null
        and .data.comment == "스트릿 캐주얼 데모 룩"
      ' >/dev/null 2>&1 <<<"${detail_response}"; then
      printf '%s' "${lookbook_id}"
      return
    fi
  done < <(jq -r --arg nickname "${nickname}" '
    .data.items[]
      | select(
          .authorNickname == $nickname
          and (.tags | index("스트릿")) != null
          and (.tags | index("캐주얼")) != null
        )
      | .lookbookId
  ' <<<"${list_response}")
}

find_owned_lookbook_id_by_comment() {
  local token="$1"
  local comment="$2"
  local list_response
  list_response="$(api_call GET "/api/v1/lookbooks?pageSize=20" "${token}")"
  require_success "${list_response}" "Content lookbook sample lookup"

  local lookbook_id detail_response
  while read -r lookbook_id; do
    [[ -n "${lookbook_id}" ]] || continue
    detail_response="$(api_call GET "/api/v1/lookbooks/${lookbook_id}" "${token}")"
    if jq -e --arg comment "${comment}" '
        .success == true
        and .data.isOwner == true
        and .data.comment == $comment
      ' >/dev/null 2>&1 <<<"${detail_response}"; then
      printf '%s' "${lookbook_id}"
      return
    fi
  done < <(jq -r '.data.items[].lookbookId' <<<"${list_response}")
}

prepare_minimal_trend_lookbook_sample() {
  local email="$1"
  local password="$2"
  local original_image_path="$3"
  local matched_image_path="$4"
  local comment="미니멀 뉴트럴 상의 매칭 룩북"
  local token
  token="$(login_or_sign_up "${email}" "${password}")"

  # 같은 설명의 샘플이 남아 있으면 이미지와 룩북을 다시 생성하지 않는다.
  local existing_lookbook_id
  existing_lookbook_id="$(find_owned_lookbook_id_by_comment "${token}" "${comment}")"
  if [[ -n "${existing_lookbook_id}" ]]; then
    jq -nc \
      --argjson trendId 1 \
      --argjson lookbookId "${existing_lookbook_id}" \
      '{
        trendId: $trendId,
        lookbookId: $lookbookId,
        tags: ["미니멀", "뉴트럴", "와이드핏"],
        reused: true,
        relatedLookbookListed: true
      }'
    return
  fi

  local tags_response
  tags_response="$(api_call GET "/api/v1/tags" "${token}")"
  require_success "${tags_response}" "Minimal sample tag lookup"

  local minimal_tag_id neutral_tag_id wide_fit_tag_id
  minimal_tag_id="$(find_tag_id "${tags_response}" "미니멀")"
  neutral_tag_id="$(find_tag_id "${tags_response}" "뉴트럴")"
  wide_fit_tag_id="$(find_tag_id "${tags_response}" "와이드핏")"
  if [[ -z "${minimal_tag_id}" || -z "${neutral_tag_id}" || -z "${wide_fit_tag_id}" ]]; then
    echo "Minimal sample tags are missing: 미니멀, 뉴트럴, 와이드핏" >&2
    exit 1
  fi

  local original_image_id matched_image_id
  original_image_id="$(upload_demo_image \
    "${token}" "${original_image_path}" "LOOKBOOK" "Minimal sample original")"
  matched_image_id="$(upload_demo_image \
    "${token}" "${matched_image_path}" "LOOKBOOK" "Minimal sample matched")"

  local lookbook_body lookbook_response lookbook_id
  lookbook_body="$(jq -nc \
    --arg originalImageId "${original_image_id}" \
    --arg matchedImageId "${matched_image_id}" \
    --argjson minimalTagId "${minimal_tag_id}" \
    --argjson neutralTagId "${neutral_tag_id}" \
    --argjson wideFitTagId "${wide_fit_tag_id}" \
    --arg comment "${comment}" \
    '{
      originalImageId: $originalImageId,
      matchedImageId: $matchedImageId,
      matchedProductId: null,
      sourceReportId: null,
      purchaseUrl: null,
      tagIds: [$minimalTagId, $neutralTagId, $wideFitTagId],
      comment: $comment
    }')"
  lookbook_response="$(api_call POST "/api/v1/lookbooks" "${token}" "${lookbook_body}")"
  require_success "${lookbook_response}" "Minimal sample lookbook creation"
  lookbook_id="$(jq -r '.data.lookbookId' <<<"${lookbook_response}")"

  # 트렌드 태그 점수 조회에서 방금 만든 샘플이 첫 페이지에 노출되는지 확인한다.
  local related_response related_listed
  related_response="$(api_call GET "/api/v1/trends/1/lookbooks?pageSize=3" "${token}")"
  require_success "${related_response}" "Minimal sample related lookbook lookup"
  related_listed="$(jq --argjson lookbookId "${lookbook_id}" \
    'any(.data.items[]; .lookbookId == $lookbookId)' <<<"${related_response}")"
  if [[ "${related_listed}" != "true" ]]; then
    echo "Minimal sample is missing from trend 1 related lookbooks" >&2
    exit 1
  fi

  jq -nc \
    --argjson trendId 1 \
    --argjson lookbookId "${lookbook_id}" \
    --arg originalImageId "${original_image_id}" \
    --arg matchedImageId "${matched_image_id}" \
    --argjson relatedLookbookListed "${related_listed}" \
    '{
      trendId: $trendId,
      lookbookId: $lookbookId,
      originalImageId: $originalImageId,
      matchedImageId: $matchedImageId,
      tags: ["미니멀", "뉴트럴", "와이드핏"],
      reused: false,
      relatedLookbookListed: $relatedLookbookListed
    }'
}

update_minimal_trend_sample_tags() {
  local token="$1"
  local tags_response="$2"
  if [[ ! -f "${OUTPUT_FILE}" ]]; then
    echo "Minimal sample result is missing: ${OUTPUT_FILE}" >&2
    exit 1
  fi

  local lookbook_id original_image_id matched_image_id
  lookbook_id="$(jq -r '.trendLookbookSamples.minimal01.lookbookId // empty' \
    "${OUTPUT_FILE}")"
  original_image_id="$(jq -r '.trendLookbookSamples.minimal01.originalImageId // empty' \
    "${OUTPUT_FILE}")"
  matched_image_id="$(jq -r '.trendLookbookSamples.minimal01.matchedImageId // empty' \
    "${OUTPUT_FILE}")"
  if [[ -z "${lookbook_id}" || -z "${original_image_id}" || -z "${matched_image_id}" ]]; then
    echo "Minimal sample IDs are missing from ${OUTPUT_FILE}" >&2
    exit 1
  fi

  local minimal_tag_id neutral_tag_id
  minimal_tag_id="$(find_tag_id "${tags_response}" "미니멀")"
  neutral_tag_id="$(find_tag_id "${tags_response}" "뉴트럴")"
  if [[ -z "${minimal_tag_id}" || -z "${neutral_tag_id}" ]]; then
    echo "Minimal sample tags are missing: 미니멀, 뉴트럴" >&2
    exit 1
  fi

  # 상의 중심 이미지에 직접 드러나지 않는 와이드핏 태그는 제거한다.
  local update_body update_response
  update_body="$(jq -nc \
    --arg originalImageId "${original_image_id}" \
    --arg matchedImageId "${matched_image_id}" \
    --argjson minimalTagId "${minimal_tag_id}" \
    --argjson neutralTagId "${neutral_tag_id}" \
    '{
      originalImageId: $originalImageId,
      matchedImageId: $matchedImageId,
      matchedProductId: null,
      sourceReportId: null,
      purchaseUrl: null,
      tagIds: [$minimalTagId, $neutralTagId],
      comment: "미니멀 뉴트럴 상의 매칭 룩북"
    }')"
  update_response="$(api_call PUT "/api/v1/lookbooks/${lookbook_id}" \
    "${token}" "${update_body}")"
  require_success "${update_response}" "Minimal sample tag update"
}

prepare_trend_lookbook_sample() {
  local token="$1"
  local tags_response="$2"
  local trend_id="$3"
  local comment="$4"
  local original_image_path="$5"
  local matched_image_path="$6"
  shift 6

  local tag_ids='[]'
  local tag_names='[]'
  local tag_name tag_id
  for tag_name in "$@"; do
    tag_id="$(find_tag_id "${tags_response}" "${tag_name}")"
    if [[ -z "${tag_id}" ]]; then
      echo "Trend ${trend_id} sample tag is missing: ${tag_name}" >&2
      exit 1
    fi
    tag_ids="$(jq -c --argjson tagId "${tag_id}" '. + [$tagId]' <<<"${tag_ids}")"
    tag_names="$(jq -c --arg tagName "${tag_name}" '. + [$tagName]' <<<"${tag_names}")"
  done

  # 설명을 샘플 식별자로 사용해 중단 후 다시 실행해도 룩북을 중복 생성하지 않는다.
  local lookbook_id reused=false original_image_id='' matched_image_id=''
  lookbook_id="$(find_owned_lookbook_id_by_comment "${token}" "${comment}")"
  if [[ -n "${lookbook_id}" ]]; then
    reused=true
    if [[ -f "${OUTPUT_FILE}" ]]; then
      original_image_id="$(jq -r --argjson lookbookId "${lookbook_id}" '
        [.trendLookbookSamples[]
          | select(.lookbookId == $lookbookId)
          | .originalImageId // empty]
        | first // empty
      ' "${OUTPUT_FILE}")"
      matched_image_id="$(jq -r --argjson lookbookId "${lookbook_id}" '
        [.trendLookbookSamples[]
          | select(.lookbookId == $lookbookId)
          | .matchedImageId // empty]
        | first // empty
      ' "${OUTPUT_FILE}")"
    fi
  else
    original_image_id="$(upload_demo_image \
      "${token}" "${original_image_path}" "LOOKBOOK" "Trend ${trend_id} sample original")"
    matched_image_id="$(upload_demo_image \
      "${token}" "${matched_image_path}" "LOOKBOOK" "Trend ${trend_id} sample matched")"

    local lookbook_body lookbook_response
    lookbook_body="$(jq -nc \
      --arg originalImageId "${original_image_id}" \
      --arg matchedImageId "${matched_image_id}" \
      --argjson tagIds "${tag_ids}" \
      --arg comment "${comment}" \
      '{
        originalImageId: $originalImageId,
        matchedImageId: $matchedImageId,
        matchedProductId: null,
        sourceReportId: null,
        purchaseUrl: null,
        tagIds: $tagIds,
        comment: $comment
      }')"
    lookbook_response="$(api_call POST "/api/v1/lookbooks" "${token}" "${lookbook_body}")"
    require_success "${lookbook_response}" "Trend ${trend_id} sample lookbook creation"
    lookbook_id="$(jq -r '.data.lookbookId' <<<"${lookbook_response}")"
  fi

  # 같은 태그 점수를 사용하는 관련 룩북 첫 페이지에 새 샘플이 포함되는지 확인한다.
  local related_response related_listed
  related_response="$(api_call GET "/api/v1/trends/${trend_id}/lookbooks?pageSize=3" "${token}")"
  require_success "${related_response}" "Trend ${trend_id} related lookbook lookup"
  related_listed="$(jq --argjson lookbookId "${lookbook_id}" \
    'any(.data.items[]; .lookbookId == $lookbookId)' <<<"${related_response}")"
  if [[ "${related_listed}" != "true" ]]; then
    echo "Trend ${trend_id} sample is missing from related lookbooks" >&2
    exit 1
  fi

  local detail_response
  detail_response="$(api_call GET "/api/v1/lookbooks/${lookbook_id}" "${token}")"
  require_success "${detail_response}" "Trend ${trend_id} sample detail lookup"
  if ! jq -e '
      .data.originalImageUrl != null
      and .data.matchedImageUrl != null
    ' >/dev/null 2>&1 <<<"${detail_response}"; then
    echo "Trend ${trend_id} sample image URL verification failed" >&2
    exit 1
  fi

  jq -nc \
    --argjson trendId "${trend_id}" \
    --argjson lookbookId "${lookbook_id}" \
    --arg originalImageId "${original_image_id}" \
    --arg matchedImageId "${matched_image_id}" \
    --argjson tags "${tag_names}" \
    --argjson reused "${reused}" \
    --argjson relatedLookbookListed "${related_listed}" \
    '{
      trendId: $trendId,
      lookbookId: $lookbookId,
      originalImageId: (if $originalImageId == "" then null else $originalImageId end),
      matchedImageId: (if $matchedImageId == "" then null else $matchedImageId end),
      tags: $tags,
      reused: $reused,
      relatedLookbookListed: $relatedLookbookListed
    }'
}

replace_trend_lookbook_matched_image() {
  local token="$1"
  local tags_response="$2"
  local sample_key="$3"
  local comment="$4"
  local matched_image_path="$5"
  shift 5

  local trend_id lookbook_id original_image_id
  trend_id="$(jq -r --arg sampleKey "${sample_key}" \
    '.trendLookbookSamples[$sampleKey].trendId // empty' "${OUTPUT_FILE}")"
  lookbook_id="$(jq -r --arg sampleKey "${sample_key}" \
    '.trendLookbookSamples[$sampleKey].lookbookId // empty' "${OUTPUT_FILE}")"
  original_image_id="$(jq -r --arg sampleKey "${sample_key}" \
    '.trendLookbookSamples[$sampleKey].originalImageId // empty' "${OUTPUT_FILE}")"
  if [[ -z "${trend_id}" || -z "${lookbook_id}" || -z "${original_image_id}" ]]; then
    echo "Trend sample IDs are missing: ${sample_key}" >&2
    exit 1
  fi

  local tag_ids='[]'
  local tag_name tag_id
  for tag_name in "$@"; do
    tag_id="$(find_tag_id "${tags_response}" "${tag_name}")"
    if [[ -z "${tag_id}" ]]; then
      echo "Trend sample tag is missing: ${tag_name}" >&2
      exit 1
    fi
    tag_ids="$(jq -c --argjson tagId "${tag_id}" '. + [$tagId]' <<<"${tag_ids}")"
  done

  local matched_image_id
  matched_image_id="$(upload_demo_image \
    "${token}" "${matched_image_path}" "LOOKBOOK" "${sample_key} replacement matched")"

  # 룩북 ID와 원본은 유지하고 별도로 촬영된 대체 착용 이미지만 교체한다.
  local update_body update_response
  update_body="$(jq -nc \
    --arg originalImageId "${original_image_id}" \
    --arg matchedImageId "${matched_image_id}" \
    --argjson tagIds "${tag_ids}" \
    --arg comment "${comment}" \
    '{
      originalImageId: $originalImageId,
      matchedImageId: $matchedImageId,
      matchedProductId: null,
      sourceReportId: null,
      purchaseUrl: null,
      tagIds: $tagIds,
      comment: $comment
    }')"
  update_response="$(api_call PUT "/api/v1/lookbooks/${lookbook_id}" \
    "${token}" "${update_body}")"
  require_success "${update_response}" "${sample_key} matched image update"

  local detail_response
  detail_response="$(api_call GET "/api/v1/lookbooks/${lookbook_id}" "${token}")"
  require_success "${detail_response}" "${sample_key} detail lookup"
  if ! jq -e '
      .data.originalImageUrl != null
      and .data.matchedImageUrl != null
    ' >/dev/null 2>&1 <<<"${detail_response}"; then
    echo "Trend sample image URL verification failed: ${sample_key}" >&2
    exit 1
  fi

  local related_response
  related_response="$(api_call GET "/api/v1/trends/${trend_id}/lookbooks?pageSize=3" \
    "${token}")"
  require_success "${related_response}" "${sample_key} related lookbook lookup"
  if ! jq -e --argjson lookbookId "${lookbook_id}" '
      any(.data.items[]; .lookbookId == $lookbookId)
    ' >/dev/null 2>&1 <<<"${related_response}"; then
    echo "Trend sample is missing from related lookbooks: ${sample_key}" >&2
    exit 1
  fi

  jq -nc \
    --arg sampleKey "${sample_key}" \
    --arg matchedImageId "${matched_image_id}" \
    '{sampleKey: $sampleKey, matchedImageId: $matchedImageId}'
}

prepare_content_account() {
  local email="$1"
  local password="$2"
  local nickname="$3"
  local image_path="$4"
  local matched_image_path="$5"

  if [[ ! -f "${image_path}" ]]; then
    echo "Content image not found: ${image_path}" >&2
    exit 1
  fi
  if [[ ! -f "${matched_image_path}" ]]; then
    echo "Content matched image not found: ${matched_image_path}" >&2
    exit 1
  fi

  local token
  token="$(login_or_sign_up "${email}" "${password}")"

  local onboarding_body
  onboarding_body="$(jq -nc --arg nickname "${nickname}" \
    '{nickname: $nickname, profileImageId: null, tagIds: []}')"
  local onboarding_response
  onboarding_response="$(api_call PUT "/api/v1/members/me/onboarding" \
    "${token}" "${onboarding_body}")"
  require_success "${onboarding_response}" "Content onboarding"

  # 이전 실행 결과가 유효하면 같은 룩북을 재사용해 중복 생성을 막는다.
  if [[ -f "${OUTPUT_FILE}" ]] && jq -e '.content.lookbookId != null' \
      "${OUTPUT_FILE}" >/dev/null 2>&1; then
    local previous_lookbook_id
    previous_lookbook_id="$(jq -r '.content.lookbookId' "${OUTPUT_FILE}")"
    local previous_detail
    previous_detail="$(api_call GET "/api/v1/lookbooks/${previous_lookbook_id}" "${token}")"
    if jq -e '.success == true' >/dev/null 2>&1 <<<"${previous_detail}"; then
      jq -c '.content + {reused: true}' "${OUTPUT_FILE}"
      return
    fi
  fi

  local tags_response
  tags_response="$(api_call GET "/api/v1/tags" "${token}")"
  require_success "${tags_response}" "Content tag lookup"
  local street_tag_id casual_tag_id
  street_tag_id="$(find_tag_id "${tags_response}" "스트릿")"
  casual_tag_id="$(find_tag_id "${tags_response}" "캐주얼")"
  if [[ -z "${street_tag_id}" || -z "${casual_tag_id}" ]]; then
    echo "Content tags are missing: 스트릿, 캐주얼" >&2
    exit 1
  fi

  local report_id image_id recommendation_response analysis_suggested_tags save_result
  report_id="$(find_reusable_content_report_id "${token}")"
  if [[ -n "${report_id}" ]]; then
    # 앞선 실행에서 저장까지 끝난 분석 결과는 다시 만들지 않고 그대로 사용한다.
    recommendation_response="$(api_call GET "/api/v1/analyses/${report_id}" "${token}")"
    require_success "${recommendation_response}" "Content analysis detail"
    if ! jq -e '
        .data.recommendationStatus == "CURRENT"
        and .data.saved == true
        and (.data.recommendationGroups | length) > 0
      ' >/dev/null 2>&1 <<<"${recommendation_response}"; then
      echo "Content reusable analysis is not ready" >&2
      exit 1
    fi
    image_id="$(jq -r '.data.originalImageId' <<<"${recommendation_response}")"
    analysis_suggested_tags='["미니멀", "와이드핏", "베이지톤"]'
    save_result="$(jq -nc \
      --argjson selectedItemCount "$(
        jq '.data.selectedItems | length' <<<"${recommendation_response}"
      )" \
      '{saved: true, listed: true, selectedItemCount: $selectedItemCount}')"
  else
    image_id="$(upload_demo_image "${token}" "${image_path}" "ANALYSIS" "Content")"
    local analysis_body analysis_response recommendation_body
    analysis_body="$(jq -nc --arg imageId "${image_id}" '{imageId: $imageId}')"
    analysis_response="$(api_call POST "/api/v1/analyses" "${token}" "${analysis_body}")"
    require_success "${analysis_response}" "Content analysis"
    report_id="$(jq -r '.data.reportId' <<<"${analysis_response}")"
    analysis_suggested_tags="$(jq '[.data.suggestedTags[].tagName]' \
      <<<"${analysis_response}")"

    # prototype 분석 태그를 사용자가 확정한 스트릿 계열 태그로 교체한다.
    recommendation_body="$(jq -nc \
      --argjson streetTagId "${street_tag_id}" \
      --argjson casualTagId "${casual_tag_id}" \
      '{
        confirmedTagIds: [$streetTagId, $casualTagId],
        customTagNames: ["streetwear", "oversized", "black"],
        matchPercentage: 70
      }')"
    recommendation_response="$(api_call POST \
      "/api/v1/analyses/${report_id}/recommendations" \
      "${token}" "${recommendation_body}")"
    require_success "${recommendation_response}" "Content recommendation"
    save_result="$(save_report \
      "Content" "${token}" "${report_id}" "${recommendation_response}")"
  fi

  local recommended_product_id
  recommended_product_id="$(jq -r \
    '[.data.recommendationGroups[].items[].productId] | first // null' \
    <<<"${recommendation_response}")"

  # 결과 파일을 쓰기 전에 중단됐더라도 운영에 생성된 룩북은 다시 찾아 사용한다.
  local existing_lookbook_id
  existing_lookbook_id="$(find_owned_content_lookbook_id "${token}" "${nickname}")"
  if [[ -n "${existing_lookbook_id}" ]]; then
    jq -nc \
      --arg alias "Content" \
      --arg imageFile "$(basename "${image_path}")" \
      --arg matchedImageFile "$(basename "${matched_image_path}")" \
      --arg imageId "${image_id}" \
      --argjson reportId "${report_id}" \
      --argjson lookbookId "${existing_lookbook_id}" \
      --argjson recommendedProductId "${recommended_product_id}" \
      --argjson recommendationCount "$(
        jq '[.data.recommendationGroups[].items[]] | length' \
          <<<"${recommendation_response}"
      )" \
      --argjson analysisSuggestedTags "${analysis_suggested_tags}" \
      --argjson recommendationTags "$(
        jq '.data.tags // .data.analysisTags' <<<"${recommendation_response}"
      )" \
      '{
        alias: $alias,
        imageFile: $imageFile,
        matchedImageFile: $matchedImageFile,
        imageId: $imageId,
        matchedImageId: null,
        reportId: $reportId,
        lookbookId: $lookbookId,
        recommendedProductId: $recommendedProductId,
        recommendationCount: $recommendationCount,
        analysisSuggestedTags: $analysisSuggestedTags,
        recommendationTags: $recommendationTags,
        reportSaved: true,
        reused: true
      }'
    return
  fi

  # 추천 상품 DB에는 이미지 주소가 없어, 룩북용 이미지를 별도로 업로드해 연결한다.
  local matched_image_id
  matched_image_id="$(upload_demo_image \
    "${token}" "${matched_image_path}" "LOOKBOOK" "Content matched")"

  local lookbook_body
  lookbook_body="$(jq -nc \
    --arg originalImageId "${image_id}" \
    --arg matchedImageId "${matched_image_id}" \
    --argjson streetTagId "${street_tag_id}" \
    --argjson casualTagId "${casual_tag_id}" \
    '{
      originalImageId: $originalImageId,
      matchedImageId: $matchedImageId,
      matchedProductId: null,
      sourceReportId: null,
      purchaseUrl: null,
      tagIds: [$streetTagId, $casualTagId],
      comment: "스트릿 캐주얼 데모 룩"
    }')"
  local lookbook_response
  lookbook_response="$(api_call POST "/api/v1/lookbooks" "${token}" "${lookbook_body}")"
  require_success "${lookbook_response}" "Content lookbook creation"
  local lookbook_id
  lookbook_id="$(jq -r '.data.lookbookId' <<<"${lookbook_response}")"

  local detail_response
  detail_response="$(api_call GET "/api/v1/lookbooks/${lookbook_id}" "${token}")"
  require_success "${detail_response}" "Content lookbook detail"
  if [[ "$(jq -r '.data.isOwner' <<<"${detail_response}")" != "true" ]]; then
    echo "Content lookbook owner verification failed" >&2
    exit 1
  fi

  jq -nc \
    --arg alias "Content" \
    --arg imageFile "$(basename "${image_path}")" \
    --arg matchedImageFile "$(basename "${matched_image_path}")" \
    --arg imageId "${image_id}" \
    --arg matchedImageId "${matched_image_id}" \
    --argjson reportId "${report_id}" \
    --argjson lookbookId "${lookbook_id}" \
    --argjson recommendedProductId "${recommended_product_id}" \
    --argjson recommendationCount "$(
      jq '[.data.recommendationGroups[].items[]] | length' \
        <<<"${recommendation_response}"
    )" \
    --argjson analysisSuggestedTags "${analysis_suggested_tags}" \
    --argjson recommendationTags "$(
      jq '.data.tags // .data.analysisTags' <<<"${recommendation_response}"
    )" \
    --argjson saveResult "${save_result}" \
    '{
      alias: $alias,
      imageFile: $imageFile,
      matchedImageFile: $matchedImageFile,
      imageId: $imageId,
      matchedImageId: $matchedImageId,
      reportId: $reportId,
      lookbookId: $lookbookId,
      recommendedProductId: $recommendedProductId,
      recommendationCount: $recommendationCount,
      analysisSuggestedTags: $analysisSuggestedTags,
      recommendationTags: $recommendationTags,
      reportSaved: $saveResult.saved,
      reused: false
    }'
}

closet_contains_target() {
  local token="$1"
  local target_type="$2"
  local target_id="$3"
  local cursor=""

  while true; do
    local path="/api/v1/closet-saves?target_type=${target_type}"
    if [[ -n "${cursor}" ]]; then
      path+="&cursor=${cursor}"
    fi

    local response
    response="$(api_call GET "${path}" "${token}")"
    require_success "${response}" "${target_type} closet lookup"
    if jq -e --argjson targetId "${target_id}" \
        'any(.data.items[]; .targetId == $targetId)' \
        >/dev/null 2>&1 <<<"${response}"; then
      return 0
    fi
    if [[ "$(jq -r '.data.hasNext' <<<"${response}")" != "true" ]]; then
      return 1
    fi
    cursor="$(jq -r '.data.nextCursor // empty' <<<"${response}")"
    if [[ -z "${cursor}" ]]; then
      return 1
    fi
  done
}

ensure_closet_saved() {
  local token="$1"
  local target_type="$2"
  local target_id="$3"
  if closet_contains_target "${token}" "${target_type}" "${target_id}"; then
    return
  fi

  local body
  body="$(jq -nc \
    --arg targetType "${target_type}" \
    --argjson targetId "${target_id}" \
    '{targetType: $targetType, targetId: $targetId}')"
  local response
  response="$(api_call POST "/api/v1/closet-saves" "${token}" "${body}")"
  require_success "${response}" "${target_type} closet save"
}

prepare_account_interaction() {
  local alias="$1"
  local email="$2"
  local password="$3"
  local lookbook_id="$4"
  local trend_index="$5"
  local token
  token="$(login_or_sign_up "${email}" "${password}")"

  local like_response
  like_response="$(api_call POST "/api/v1/lookbooks/${lookbook_id}/likes" "${token}")"
  require_success "${like_response}" "${alias} lookbook like"
  ensure_closet_saved "${token}" "LOOKBOOK" "${lookbook_id}"

  local trends_response
  trends_response="$(api_call GET "/api/v1/trends" "${token}")"
  require_success "${trends_response}" "${alias} trend lookup"
  local trend_id
  trend_id="$(jq -r --argjson index "${trend_index}" \
    '.data.items[$index].trendId // .data.items[0].trendId // empty' \
    <<<"${trends_response}")"
  local trend_saved=false
  # 운영 트렌드가 준비되지 않은 경우 룩북 상호작용만 기록한다.
  if [[ -n "${trend_id}" ]]; then
    ensure_closet_saved "${token}" "TREND" "${trend_id}"
    trend_saved=true
  fi

  local lookbook_detail
  lookbook_detail="$(api_call GET "/api/v1/lookbooks/${lookbook_id}" "${token}")"
  require_success "${lookbook_detail}" "${alias} lookbook verification"
  if [[ "$(jq -r '.data.isLiked' <<<"${lookbook_detail}")" != "true" ]]; then
    echo "${alias} interaction verification failed" >&2
    exit 1
  fi
  if [[ "${trend_saved}" == "true" ]]; then
    local trend_detail
    trend_detail="$(api_call GET "/api/v1/trends/${trend_id}" "${token}")"
    require_success "${trend_detail}" "${alias} trend verification"
    if [[ "$(jq -r '.data.isSaved' <<<"${trend_detail}")" != "true" ]]; then
      echo "${alias} trend save verification failed" >&2
      exit 1
    fi
  fi

  jq -nc \
    --arg alias "${alias}" \
    --argjson lookbookId "${lookbook_id}" \
    --argjson trendId "${trend_id:-null}" \
    --argjson trendSaved "${trend_saved}" \
    --argjson likeCount "$(jq '.data.likeCount' <<<"${lookbook_detail}")" \
    '{
      alias: $alias,
      lookbookId: $lookbookId,
      trendId: $trendId,
      isLiked: true,
      lookbookSaved: true,
      trendSaved: $trendSaved,
      likeCount: $likeCount
    }'
}

prepare_demo_interactions() {
  local lookbook_id="$1"
  local demo_0_result demo_1_result
  demo_0_result="$(prepare_account_interaction \
    "Demo-0" \
    "${FITBACK_DEMO_0_EMAIL}" \
    "${FITBACK_DEMO_0_PASSWORD}" \
    "${lookbook_id}" \
    0)"
  demo_1_result="$(prepare_account_interaction \
    "Demo-1" \
    "${FITBACK_DEMO_1_EMAIL}" \
    "${FITBACK_DEMO_1_PASSWORD}" \
    "${lookbook_id}" \
    1)"

  local verification_token final_detail
  verification_token="$(login_or_sign_up \
    "${FITBACK_DEMO_0_EMAIL}" "${FITBACK_DEMO_0_PASSWORD}")"
  final_detail="$(api_call GET "/api/v1/lookbooks/${lookbook_id}" \
    "${verification_token}")"
  require_success "${final_detail}" "Final lookbook verification"

  jq -nc \
    --argjson accounts "$(jq -s '.' <<<"${demo_0_result}"$'\n'"${demo_1_result}")" \
    --argjson finalLikeCount "$(jq '.data.likeCount' <<<"${final_detail}")" \
    '{accounts: $accounts, finalLikeCount: $finalLikeCount}'
}

prepare_account_trend_lookbook_interactions() {
  local alias="$1"
  local email="$2"
  local password="$3"
  shift 3

  local token
  token="$(login_or_sign_up "${email}" "${password}")"
  local results='[]'
  local lookbook_id
  for lookbook_id in "$@"; do
    # 좋아요 API와 마이 클로젯 저장 함수는 이미 처리된 요청을 성공으로 재사용한다.
    local like_response
    like_response="$(api_call POST "/api/v1/lookbooks/${lookbook_id}/likes" "${token}")"
    require_success "${like_response}" "${alias} trend lookbook like"
    ensure_closet_saved "${token}" "LOOKBOOK" "${lookbook_id}"

    local lookbook_detail
    lookbook_detail="$(api_call GET "/api/v1/lookbooks/${lookbook_id}" "${token}")"
    require_success "${lookbook_detail}" "${alias} trend lookbook verification"
    if [[ "$(jq -r '.data.isLiked' <<<"${lookbook_detail}")" != "true" ]]; then
      echo "${alias} trend lookbook ${lookbook_id} like verification failed" >&2
      exit 1
    fi
    if ! closet_contains_target "${token}" "LOOKBOOK" "${lookbook_id}"; then
      echo "${alias} trend lookbook ${lookbook_id} save verification failed" >&2
      exit 1
    fi

    results="$(jq -c \
      --argjson lookbookId "${lookbook_id}" \
      --argjson likeCount "$(jq '.data.likeCount' <<<"${lookbook_detail}")" \
      '. + [{
        lookbookId: $lookbookId,
        isLiked: true,
        lookbookSaved: true,
        likeCount: $likeCount
      }]' <<<"${results}")"
  done

  jq -nc \
    --arg alias "${alias}" \
    --argjson lookbooks "${results}" \
    '{alias: $alias, lookbooks: $lookbooks}'
}

prepare_account() {
  local alias="$1"
  local email="$2"
  local password="$3"
  local nickname="$4"
  local image_path="$5"
  if [[ ! -f "${image_path}" ]]; then
    echo "${alias} image not found: ${image_path}" >&2
    exit 1
  fi

  local content_type
  content_type="$(file -b --mime-type "${image_path}")"
  case "${content_type}" in
    image/jpeg|image/png|image/webp) ;;
    *)
      echo "${alias} image type is not supported: ${content_type}" >&2
      exit 1
      ;;
  esac

  local file_size
  file_size="$(wc -c <"${image_path}" | tr -d ' ')"
  local token
  token="$(login_or_sign_up "${email}" "${password}")"

  local onboarding_body
  onboarding_body="$(jq -nc --arg nickname "${nickname}" \
    '{nickname: $nickname, profileImageId: null, tagIds: []}')"
  local onboarding_response
  onboarding_response="$(api_call PUT "/api/v1/members/me/onboarding" \
    "${token}" "${onboarding_body}")"
  require_success "${onboarding_response}" "${alias} onboarding"

  local upload_body
  upload_body="$(jq -nc \
    --arg contentType "${content_type}" \
    --argjson fileSize "${file_size}" \
    '{purpose: "ANALYSIS", contentType: $contentType, fileSize: $fileSize}')"
  local upload_response
  upload_response="$(api_call POST "/api/v1/images/upload-requests" \
    "${token}" "${upload_body}")"
  require_success "${upload_response}" "${alias} upload request"

  local image_id
  image_id="$(jq -r '.data.imageId' <<<"${upload_response}")"
  upload_to_s3 "${upload_response}" "${image_path}" "${content_type}"
  local complete_response
  complete_response="$(api_call POST "/api/v1/images/${image_id}/complete" "${token}")"
  require_success "${complete_response}" "${alias} upload completion"

  local analysis_body
  analysis_body="$(jq -nc --arg imageId "${image_id}" '{imageId: $imageId}')"
  local analysis_response
  analysis_response="$(api_call POST "/api/v1/analyses" "${token}" "${analysis_body}")"
  require_success "${analysis_response}" "${alias} analysis"
  local report_id
  report_id="$(jq -r '.data.reportId' <<<"${analysis_response}")"

  local recommendation_response
  recommendation_response="$(api_call POST \
    "/api/v1/analyses/${report_id}/recommendations" "${token}")"
  require_success "${recommendation_response}" "${alias} recommendation"
  local save_result
  save_result="$(save_report \
    "${alias}" "${token}" "${report_id}" "${recommendation_response}")"

  local first_product_id
  first_product_id="$(jq -r \
    '[.data.recommendationGroups[].items[].productId] | first // empty' \
    <<<"${recommendation_response}")"
  local detail_status="NOT_CHECKED"
  if [[ -n "${first_product_id}" ]]; then
    local detail_response
    detail_response="$(api_call GET "/api/v1/products/${first_product_id}" "${token}")"
    require_success "${detail_response}" "${alias} product detail"
    detail_status="$(jq -r '.data.dataStatus' <<<"${detail_response}")"
  fi

  jq -nc \
    --arg alias "${alias}" \
    --arg imageFile "$(basename "${image_path}")" \
    --arg contentType "${content_type}" \
    --arg imageId "${image_id}" \
    --argjson reportId "${report_id}" \
    --arg detailStatus "${detail_status}" \
    --argjson recommendationCount "$(
      jq '[.data.recommendationGroups[].items[]] | length' \
        <<<"${recommendation_response}"
    )" \
    --argjson liveFieldCount "$(
      jq '[.data.recommendationGroups[].items[]
        | select(
            .productId != null
            and .name != null
            and .imageUrl != null
            and .price.amount != null
            and .purchaseUrl != null
          )] | length' <<<"${recommendation_response}"
    )" \
    --argjson saveResult "${save_result}" \
    '{
      alias: $alias,
      imageFile: $imageFile,
      contentType: $contentType,
      imageId: $imageId,
      reportId: $reportId,
      recommendationCount: $recommendationCount,
      liveFieldCount: $liveFieldCount,
      productDetailDataStatus: $detailStatus,
      saved: $saveResult.saved,
      listed: $saveResult.listed,
      selectedItemCount: $saveResult.selectedItemCount
    }'
}

save_existing_account() {
  local alias="$1"
  local email="$2"
  local password="$3"
  local report_id="$4"
  local token
  token="$(login_or_sign_up "${email}" "${password}")"
  local report_response
  report_response="$(api_call GET "/api/v1/analyses/${report_id}" "${token}")"
  require_success "${report_response}" "${alias} analysis detail"
  local save_result
  save_result="$(save_report "${alias}" "${token}" "${report_id}" "${report_response}")"

  jq -nc \
    --arg alias "${alias}" \
    --argjson reportId "${report_id}" \
    --argjson saveResult "${save_result}" \
    '{
      alias: $alias,
      reportId: $reportId,
      saved: $saveResult.saved,
      listed: $saveResult.listed,
      selectedItemCount: $saveResult.selectedItemCount
    }'
}

for command in curl jq file wc; do
  require_command "${command}"
done
require_env FITBACK_DEMO_BASE_URL
echo "Demo target: ${BASE_URL}"
for name in \
  FITBACK_DEMO_0_EMAIL FITBACK_DEMO_0_PASSWORD \
  FITBACK_DEMO_1_EMAIL FITBACK_DEMO_1_PASSWORD; do
  require_env "${name}"
done

mkdir -p "$(dirname "${OUTPUT_FILE}")"
temporary_output="$(mktemp)"
trap 'rm -f "${temporary_output}" "${temporary_output}.0" "${temporary_output}.1" "${temporary_output}.content" "${temporary_output}.interactions" "${temporary_output}.trend-interactions-0" "${temporary_output}.trend-interactions-1" "${temporary_output}.trend-sample" "${temporary_output}.trend-2" "${temporary_output}.trend-3" "${temporary_output}.trend-4" "${temporary_output}.trend-5" "${temporary_output}.trend-6" "${temporary_output}.replace-1" "${temporary_output}.replace-2" "${temporary_output}.replace-3" "${temporary_output}.replace-4" "${temporary_output}.replace-5" "${temporary_output}.replace-6"' EXIT

case "${MODE}" in
  prepare)
    require_env FITBACK_DEMO_0_IMAGE
    require_env FITBACK_DEMO_1_IMAGE
    prepare_account \
      "Demo-0" \
      "${FITBACK_DEMO_0_EMAIL}" \
      "${FITBACK_DEMO_0_PASSWORD}" \
      "${FITBACK_DEMO_0_NICKNAME:-demo_fit}" \
      "${FITBACK_DEMO_0_IMAGE}" >"${temporary_output}.0"
    prepare_account \
      "Demo-1" \
      "${FITBACK_DEMO_1_EMAIL}" \
      "${FITBACK_DEMO_1_PASSWORD}" \
      "${FITBACK_DEMO_1_NICKNAME:-demo_back}" \
      "${FITBACK_DEMO_1_IMAGE}" >"${temporary_output}.1"
    ;;
  --save-existing)
    require_env FITBACK_DEMO_0_REPORT_ID
    require_env FITBACK_DEMO_1_REPORT_ID
    save_existing_account \
      "Demo-0" \
      "${FITBACK_DEMO_0_EMAIL}" \
      "${FITBACK_DEMO_0_PASSWORD}" \
      "${FITBACK_DEMO_0_REPORT_ID}" >"${temporary_output}.0"
    save_existing_account \
      "Demo-1" \
      "${FITBACK_DEMO_1_EMAIL}" \
      "${FITBACK_DEMO_1_PASSWORD}" \
      "${FITBACK_DEMO_1_REPORT_ID}" >"${temporary_output}.1"
    ;;
  --prepare-interactions)
    require_env FITBACK_DEMO_CONTENT_EMAIL
    require_env FITBACK_DEMO_CONTENT_PASSWORD
    require_env FITBACK_DEMO_CONTENT_IMAGE
    require_env FITBACK_DEMO_CONTENT_MATCHED_IMAGE
    prepare_content_account \
      "${FITBACK_DEMO_CONTENT_EMAIL}" \
      "${FITBACK_DEMO_CONTENT_PASSWORD}" \
      "${FITBACK_DEMO_CONTENT_NICKNAME:-fitback_creator}" \
      "${FITBACK_DEMO_CONTENT_IMAGE}" \
      "${FITBACK_DEMO_CONTENT_MATCHED_IMAGE}" >"${temporary_output}.content"
    content_lookbook_id="$(jq -r '.lookbookId' "${temporary_output}.content")"
    prepare_demo_interactions \
      "${content_lookbook_id}" >"${temporary_output}.interactions"

    if [[ -f "${OUTPUT_FILE}" ]] && jq -e . "${OUTPUT_FILE}" >/dev/null 2>&1; then
      jq \
        --arg preparedAt "$(date -u +"%Y-%m-%dT%H:%M:%SZ")" \
        --arg mode "${MODE}" \
        --slurpfile content "${temporary_output}.content" \
        --slurpfile interactions "${temporary_output}.interactions" \
        '. + {
          preparedAt: $preparedAt,
          mode: $mode,
          content: $content[0],
          interactions: $interactions[0]
        }' "${OUTPUT_FILE}" >"${temporary_output}"
    else
      jq -n \
        --arg preparedAt "$(date -u +"%Y-%m-%dT%H:%M:%SZ")" \
        --arg mode "${MODE}" \
        --slurpfile content "${temporary_output}.content" \
        --slurpfile interactions "${temporary_output}.interactions" \
        '{
          preparedAt: $preparedAt,
          mode: $mode,
          accounts: [],
          content: $content[0],
          interactions: $interactions[0]
        }' >"${temporary_output}"
    fi
    mv "${temporary_output}" "${OUTPUT_FILE}"
    rm -f "${temporary_output}.content" "${temporary_output}.interactions"
    chmod 600 "${OUTPUT_FILE}"
    trap - EXIT
    echo "Demo interactions prepared: ${OUTPUT_FILE}"
    exit 0
    ;;
  --prepare-trend-lookbook-interactions)
    require_env FITBACK_DEMO_0_EMAIL
    require_env FITBACK_DEMO_0_PASSWORD
    require_env FITBACK_DEMO_1_EMAIL
    require_env FITBACK_DEMO_1_PASSWORD
    if [[ ! -f "${OUTPUT_FILE}" ]] || ! jq -e . "${OUTPUT_FILE}" >/dev/null 2>&1; then
      echo "Trend sample result is missing: ${OUTPUT_FILE}" >&2
      exit 1
    fi

    # 두 데모 계정이 각 트렌드에서 서로 다른 룩북을 사용하도록 02·03 샘플을 나눈다.
    mapfile -t demo_0_lookbook_ids < <(jq -r '
      [
        .trendLookbookSamples.minimal02.lookbookId,
        .trendLookbookSamples.street02.lookbookId,
        .trendLookbookSamples.lovely02.lookbookId,
        .trendLookbookSamples.casual02.lookbookId,
        .trendLookbookSamples.formal02.lookbookId,
        .trendLookbookSamples.casualFormal02.lookbookId
      ][] // empty
    ' "${OUTPUT_FILE}")
    mapfile -t demo_1_lookbook_ids < <(jq -r '
      [
        .trendLookbookSamples.minimal03.lookbookId,
        .trendLookbookSamples.street03.lookbookId,
        .trendLookbookSamples.lovely03.lookbookId,
        .trendLookbookSamples.casual03.lookbookId,
        .trendLookbookSamples.formal03.lookbookId,
        .trendLookbookSamples.casualFormal03.lookbookId
      ][] // empty
    ' "${OUTPUT_FILE}")
    if [[ "${#demo_0_lookbook_ids[@]}" -ne 6 || "${#demo_1_lookbook_ids[@]}" -ne 6 ]]; then
      echo "Trend lookbook samples must be prepared before interactions" >&2
      exit 1
    fi

    prepare_account_trend_lookbook_interactions \
      "Demo-0" "${FITBACK_DEMO_0_EMAIL}" "${FITBACK_DEMO_0_PASSWORD}" \
      "${demo_0_lookbook_ids[@]}" >"${temporary_output}.trend-interactions-0"
    prepare_account_trend_lookbook_interactions \
      "Demo-1" "${FITBACK_DEMO_1_EMAIL}" "${FITBACK_DEMO_1_PASSWORD}" \
      "${demo_1_lookbook_ids[@]}" >"${temporary_output}.trend-interactions-1"

    jq \
      --arg preparedAt "$(date -u +"%Y-%m-%dT%H:%M:%SZ")" \
      --arg mode "${MODE}" \
      --slurpfile demo0 "${temporary_output}.trend-interactions-0" \
      --slurpfile demo1 "${temporary_output}.trend-interactions-1" \
      '. + {
        preparedAt: $preparedAt,
        mode: $mode,
        trendLookbookInteractions: {
          accounts: [$demo0[0], $demo1[0]]
        }
      }' "${OUTPUT_FILE}" >"${temporary_output}"
    mv "${temporary_output}" "${OUTPUT_FILE}"
    rm -f \
      "${temporary_output}.trend-interactions-0" \
      "${temporary_output}.trend-interactions-1"
    chmod 600 "${OUTPUT_FILE}"
    trap - EXIT
    echo "Trend lookbook interactions prepared: ${OUTPUT_FILE}"
    exit 0
    ;;
  --prepare-trend-lookbook-sample)
    require_env FITBACK_DEMO_CONTENT_EMAIL
    require_env FITBACK_DEMO_CONTENT_PASSWORD
    require_env FITBACK_DEMO_TREND_MINIMAL_ORIGINAL_IMAGE
    require_env FITBACK_DEMO_TREND_MINIMAL_MATCHED_IMAGE
    prepare_minimal_trend_lookbook_sample \
      "${FITBACK_DEMO_CONTENT_EMAIL}" \
      "${FITBACK_DEMO_CONTENT_PASSWORD}" \
      "${FITBACK_DEMO_TREND_MINIMAL_ORIGINAL_IMAGE}" \
      "${FITBACK_DEMO_TREND_MINIMAL_MATCHED_IMAGE}" \
      >"${temporary_output}.trend-sample"

    if [[ -f "${OUTPUT_FILE}" ]] && jq -e . "${OUTPUT_FILE}" >/dev/null 2>&1; then
      jq \
        --arg preparedAt "$(date -u +"%Y-%m-%dT%H:%M:%SZ")" \
        --arg mode "${MODE}" \
        --slurpfile sample "${temporary_output}.trend-sample" \
        '. + {
          preparedAt: $preparedAt,
          mode: $mode,
          trendLookbookSamples: ((.trendLookbookSamples // {}) + {minimal01: $sample[0]})
        }' "${OUTPUT_FILE}" >"${temporary_output}"
    else
      jq -n \
        --arg preparedAt "$(date -u +"%Y-%m-%dT%H:%M:%SZ")" \
        --arg mode "${MODE}" \
        --slurpfile sample "${temporary_output}.trend-sample" \
        '{
          preparedAt: $preparedAt,
          mode: $mode,
          accounts: [],
          trendLookbookSamples: {minimal01: $sample[0]}
        }' >"${temporary_output}"
    fi
    mv "${temporary_output}" "${OUTPUT_FILE}"
    rm -f "${temporary_output}.trend-sample"
    chmod 600 "${OUTPUT_FILE}"
    trap - EXIT
    echo "Minimal trend lookbook sample prepared: ${OUTPUT_FILE}"
    exit 0
    ;;
  --prepare-remaining-trend-lookbook-samples)
    require_env FITBACK_DEMO_CONTENT_EMAIL
    require_env FITBACK_DEMO_CONTENT_PASSWORD
    for name in \
      FITBACK_DEMO_TREND_STREET_ORIGINAL_IMAGE \
      FITBACK_DEMO_TREND_STREET_MATCHED_IMAGE \
      FITBACK_DEMO_TREND_LOVELY_ORIGINAL_IMAGE \
      FITBACK_DEMO_TREND_LOVELY_MATCHED_IMAGE \
      FITBACK_DEMO_TREND_CASUAL_ORIGINAL_IMAGE \
      FITBACK_DEMO_TREND_CASUAL_MATCHED_IMAGE \
      FITBACK_DEMO_TREND_FORMAL_ORIGINAL_IMAGE \
      FITBACK_DEMO_TREND_FORMAL_MATCHED_IMAGE \
      FITBACK_DEMO_TREND_CASUAL_FORMAL_ORIGINAL_IMAGE \
      FITBACK_DEMO_TREND_CASUAL_FORMAL_MATCHED_IMAGE; do
      require_env "${name}"
    done

    content_token="$(login_or_sign_up \
      "${FITBACK_DEMO_CONTENT_EMAIL}" "${FITBACK_DEMO_CONTENT_PASSWORD}")"
    all_tags_response="$(api_call GET "/api/v1/tags" "${content_token}")"
    require_success "${all_tags_response}" "Trend sample tag lookup"

    update_minimal_trend_sample_tags "${content_token}" "${all_tags_response}"
    prepare_trend_lookbook_sample \
      "${content_token}" "${all_tags_response}" 2 \
      "스트릿 오버사이즈 아우터 매칭 룩북" \
      "${FITBACK_DEMO_TREND_STREET_ORIGINAL_IMAGE}" \
      "${FITBACK_DEMO_TREND_STREET_MATCHED_IMAGE}" \
      "스트릿" "오버사이즈" >"${temporary_output}.trend-2"
    prepare_trend_lookbook_sample \
      "${content_token}" "${all_tags_response}" 3 \
      "러블리 페미닌 상의 매칭 룩북" \
      "${FITBACK_DEMO_TREND_LOVELY_ORIGINAL_IMAGE}" \
      "${FITBACK_DEMO_TREND_LOVELY_MATCHED_IMAGE}" \
      "러블리" "페미닌" >"${temporary_output}.trend-3"
    prepare_trend_lookbook_sample \
      "${content_token}" "${all_tags_response}" 4 \
      "캐주얼 데일리룩 하의 매칭 룩북" \
      "${FITBACK_DEMO_TREND_CASUAL_ORIGINAL_IMAGE}" \
      "${FITBACK_DEMO_TREND_CASUAL_MATCHED_IMAGE}" \
      "캐주얼" "데일리룩" >"${temporary_output}.trend-4"
    prepare_trend_lookbook_sample \
      "${content_token}" "${all_tags_response}" 5 \
      "포멀 오피스룩 아우터 매칭 룩북" \
      "${FITBACK_DEMO_TREND_FORMAL_ORIGINAL_IMAGE}" \
      "${FITBACK_DEMO_TREND_FORMAL_MATCHED_IMAGE}" \
      "포멀" "오피스룩" >"${temporary_output}.trend-5"
    prepare_trend_lookbook_sample \
      "${content_token}" "${all_tags_response}" 6 \
      "캐주얼 포멀 하의 매칭 룩북" \
      "${FITBACK_DEMO_TREND_CASUAL_FORMAL_ORIGINAL_IMAGE}" \
      "${FITBACK_DEMO_TREND_CASUAL_FORMAL_MATCHED_IMAGE}" \
      "캐주얼" "포멀" >"${temporary_output}.trend-6"

    jq \
      --arg preparedAt "$(date -u +"%Y-%m-%dT%H:%M:%SZ")" \
      --arg mode "${MODE}" \
      --slurpfile street "${temporary_output}.trend-2" \
      --slurpfile lovely "${temporary_output}.trend-3" \
      --slurpfile casual "${temporary_output}.trend-4" \
      --slurpfile formal "${temporary_output}.trend-5" \
      --slurpfile casualFormal "${temporary_output}.trend-6" \
      '. + {
        preparedAt: $preparedAt,
        mode: $mode,
        trendLookbookSamples: ((.trendLookbookSamples // {}) + {
          minimal01: (.trendLookbookSamples.minimal01 + {
            tags: ["미니멀", "뉴트럴"]
          }),
          street01: $street[0],
          lovely01: $lovely[0],
          casual01: $casual[0],
          formal01: $formal[0],
          casualFormal01: $casualFormal[0]
        })
      }' "${OUTPUT_FILE}" >"${temporary_output}"
    mv "${temporary_output}" "${OUTPUT_FILE}"
    rm -f \
      "${temporary_output}.trend-2" "${temporary_output}.trend-3" \
      "${temporary_output}.trend-4" "${temporary_output}.trend-5" \
      "${temporary_output}.trend-6"
    chmod 600 "${OUTPUT_FILE}"
    trap - EXIT
    echo "Remaining trend lookbook samples prepared: ${OUTPUT_FILE}"
    exit 0
    ;;
  --prepare-additional-trend-lookbook-samples)
    require_env FITBACK_DEMO_CONTENT_EMAIL
    require_env FITBACK_DEMO_CONTENT_PASSWORD
    for name in \
      FITBACK_DEMO_TREND_MINIMAL_02_ORIGINAL_IMAGE \
      FITBACK_DEMO_TREND_MINIMAL_02_MATCHED_IMAGE \
      FITBACK_DEMO_TREND_MINIMAL_03_ORIGINAL_IMAGE \
      FITBACK_DEMO_TREND_MINIMAL_03_MATCHED_IMAGE \
      FITBACK_DEMO_TREND_STREET_02_ORIGINAL_IMAGE \
      FITBACK_DEMO_TREND_STREET_02_MATCHED_IMAGE \
      FITBACK_DEMO_TREND_STREET_03_ORIGINAL_IMAGE \
      FITBACK_DEMO_TREND_STREET_03_MATCHED_IMAGE \
      FITBACK_DEMO_TREND_LOVELY_02_ORIGINAL_IMAGE \
      FITBACK_DEMO_TREND_LOVELY_02_MATCHED_IMAGE \
      FITBACK_DEMO_TREND_LOVELY_03_ORIGINAL_IMAGE \
      FITBACK_DEMO_TREND_LOVELY_03_MATCHED_IMAGE \
      FITBACK_DEMO_TREND_CASUAL_02_ORIGINAL_IMAGE \
      FITBACK_DEMO_TREND_CASUAL_02_MATCHED_IMAGE \
      FITBACK_DEMO_TREND_CASUAL_03_ORIGINAL_IMAGE \
      FITBACK_DEMO_TREND_CASUAL_03_MATCHED_IMAGE \
      FITBACK_DEMO_TREND_FORMAL_02_ORIGINAL_IMAGE \
      FITBACK_DEMO_TREND_FORMAL_02_MATCHED_IMAGE \
      FITBACK_DEMO_TREND_FORMAL_03_ORIGINAL_IMAGE \
      FITBACK_DEMO_TREND_FORMAL_03_MATCHED_IMAGE \
      FITBACK_DEMO_TREND_CASUAL_FORMAL_02_ORIGINAL_IMAGE \
      FITBACK_DEMO_TREND_CASUAL_FORMAL_02_MATCHED_IMAGE \
      FITBACK_DEMO_TREND_CASUAL_FORMAL_03_ORIGINAL_IMAGE \
      FITBACK_DEMO_TREND_CASUAL_FORMAL_03_MATCHED_IMAGE; do
      require_env "${name}"
    done

    content_token="$(login_or_sign_up \
      "${FITBACK_DEMO_CONTENT_EMAIL}" "${FITBACK_DEMO_CONTENT_PASSWORD}")"
    all_tags_response="$(api_call GET "/api/v1/tags" "${content_token}")"
    require_success "${all_tags_response}" "Additional trend sample tag lookup"

    # 트렌드별 3개 구성을 완성하도록 기존 01 샘플에 02·03 샘플을 추가한다.
    prepare_trend_lookbook_sample \
      "${content_token}" "${all_tags_response}" 1 \
      "미니멀 와이드핏 슬랙스 매칭 룩북" \
      "${FITBACK_DEMO_TREND_MINIMAL_02_ORIGINAL_IMAGE}" \
      "${FITBACK_DEMO_TREND_MINIMAL_02_MATCHED_IMAGE}" \
      "미니멀" "와이드핏" >"${temporary_output}.trend-1-2"
    prepare_trend_lookbook_sample \
      "${content_token}" "${all_tags_response}" 1 \
      "미니멀 뉴트럴 울 셔츠 매칭 룩북" \
      "${FITBACK_DEMO_TREND_MINIMAL_03_ORIGINAL_IMAGE}" \
      "${FITBACK_DEMO_TREND_MINIMAL_03_MATCHED_IMAGE}" \
      "미니멀" "뉴트럴" >"${temporary_output}.trend-1-3"

    prepare_trend_lookbook_sample \
      "${content_token}" "${all_tags_response}" 2 \
      "스트릿 오버사이즈 카고 팬츠 매칭 룩북" \
      "${FITBACK_DEMO_TREND_STREET_02_ORIGINAL_IMAGE}" \
      "${FITBACK_DEMO_TREND_STREET_02_MATCHED_IMAGE}" \
      "스트릿" "오버사이즈" >"${temporary_output}.trend-2-2"
    prepare_trend_lookbook_sample \
      "${content_token}" "${all_tags_response}" 2 \
      "스트릿 오버사이즈 봄버 매칭 룩북" \
      "${FITBACK_DEMO_TREND_STREET_03_ORIGINAL_IMAGE}" \
      "${FITBACK_DEMO_TREND_STREET_03_MATCHED_IMAGE}" \
      "스트릿" "오버사이즈" >"${temporary_output}.trend-2-3"

    prepare_trend_lookbook_sample \
      "${content_token}" "${all_tags_response}" 3 \
      "러블리 페미닌 플리츠 스커트 매칭 룩북" \
      "${FITBACK_DEMO_TREND_LOVELY_02_ORIGINAL_IMAGE}" \
      "${FITBACK_DEMO_TREND_LOVELY_02_MATCHED_IMAGE}" \
      "러블리" "페미닌" >"${temporary_output}.trend-3-2"
    prepare_trend_lookbook_sample \
      "${content_token}" "${all_tags_response}" 3 \
      "러블리 페미닌 라벤더 가디건 매칭 룩북" \
      "${FITBACK_DEMO_TREND_LOVELY_03_ORIGINAL_IMAGE}" \
      "${FITBACK_DEMO_TREND_LOVELY_03_MATCHED_IMAGE}" \
      "러블리" "페미닌" >"${temporary_output}.trend-3-3"

    prepare_trend_lookbook_sample \
      "${content_token}" "${all_tags_response}" 4 \
      "캐주얼 데일리룩 데님 셔츠 매칭 룩북" \
      "${FITBACK_DEMO_TREND_CASUAL_02_ORIGINAL_IMAGE}" \
      "${FITBACK_DEMO_TREND_CASUAL_02_MATCHED_IMAGE}" \
      "캐주얼" "데일리룩" >"${temporary_output}.trend-4-2"
    prepare_trend_lookbook_sample \
      "${content_token}" "${all_tags_response}" 4 \
      "캐주얼 데일리룩 필드 재킷 매칭 룩북" \
      "${FITBACK_DEMO_TREND_CASUAL_03_ORIGINAL_IMAGE}" \
      "${FITBACK_DEMO_TREND_CASUAL_03_MATCHED_IMAGE}" \
      "캐주얼" "데일리룩" >"${temporary_output}.trend-4-3"

    prepare_trend_lookbook_sample \
      "${content_token}" "${all_tags_response}" 5 \
      "포멀 오피스룩 아이보리 블라우스 매칭 룩북" \
      "${FITBACK_DEMO_TREND_FORMAL_02_ORIGINAL_IMAGE}" \
      "${FITBACK_DEMO_TREND_FORMAL_02_MATCHED_IMAGE}" \
      "포멀" "오피스룩" >"${temporary_output}.trend-5-2"
    prepare_trend_lookbook_sample \
      "${content_token}" "${all_tags_response}" 5 \
      "포멀 오피스룩 네이비 슬랙스 매칭 룩북" \
      "${FITBACK_DEMO_TREND_FORMAL_03_ORIGINAL_IMAGE}" \
      "${FITBACK_DEMO_TREND_FORMAL_03_MATCHED_IMAGE}" \
      "포멀" "오피스룩" >"${temporary_output}.trend-5-3"

    prepare_trend_lookbook_sample \
      "${content_token}" "${all_tags_response}" 6 \
      "캐주얼 포멀 니트 폴로 매칭 룩북" \
      "${FITBACK_DEMO_TREND_CASUAL_FORMAL_02_ORIGINAL_IMAGE}" \
      "${FITBACK_DEMO_TREND_CASUAL_FORMAL_02_MATCHED_IMAGE}" \
      "캐주얼" "포멀" >"${temporary_output}.trend-6-2"
    prepare_trend_lookbook_sample \
      "${content_token}" "${all_tags_response}" 6 \
      "캐주얼 포멀 차콜 블레이저 매칭 룩북" \
      "${FITBACK_DEMO_TREND_CASUAL_FORMAL_03_ORIGINAL_IMAGE}" \
      "${FITBACK_DEMO_TREND_CASUAL_FORMAL_03_MATCHED_IMAGE}" \
      "캐주얼" "포멀" >"${temporary_output}.trend-6-3"

    # 생성 결과를 샘플 키별로 남겨 재실행 시 이미지 ID와 룩북 ID를 재사용한다.
    jq \
      --arg preparedAt "$(date -u +"%Y-%m-%dT%H:%M:%SZ")" \
      --arg mode "${MODE}" \
      --slurpfile minimal02 "${temporary_output}.trend-1-2" \
      --slurpfile minimal03 "${temporary_output}.trend-1-3" \
      --slurpfile street02 "${temporary_output}.trend-2-2" \
      --slurpfile street03 "${temporary_output}.trend-2-3" \
      --slurpfile lovely02 "${temporary_output}.trend-3-2" \
      --slurpfile lovely03 "${temporary_output}.trend-3-3" \
      --slurpfile casual02 "${temporary_output}.trend-4-2" \
      --slurpfile casual03 "${temporary_output}.trend-4-3" \
      --slurpfile formal02 "${temporary_output}.trend-5-2" \
      --slurpfile formal03 "${temporary_output}.trend-5-3" \
      --slurpfile casualFormal02 "${temporary_output}.trend-6-2" \
      --slurpfile casualFormal03 "${temporary_output}.trend-6-3" \
      '. + {
        preparedAt: $preparedAt,
        mode: $mode,
        trendLookbookSamples: ((.trendLookbookSamples // {}) + {
          minimal02: $minimal02[0],
          minimal03: $minimal03[0],
          street02: $street02[0],
          street03: $street03[0],
          lovely02: $lovely02[0],
          lovely03: $lovely03[0],
          casual02: $casual02[0],
          casual03: $casual03[0],
          formal02: $formal02[0],
          formal03: $formal03[0],
          casualFormal02: $casualFormal02[0],
          casualFormal03: $casualFormal03[0]
        })
      }' "${OUTPUT_FILE}" >"${temporary_output}"
    mv "${temporary_output}" "${OUTPUT_FILE}"
    rm -f \
      "${temporary_output}.trend-1-2" "${temporary_output}.trend-1-3" \
      "${temporary_output}.trend-2-2" "${temporary_output}.trend-2-3" \
      "${temporary_output}.trend-3-2" "${temporary_output}.trend-3-3" \
      "${temporary_output}.trend-4-2" "${temporary_output}.trend-4-3" \
      "${temporary_output}.trend-5-2" "${temporary_output}.trend-5-3" \
      "${temporary_output}.trend-6-2" "${temporary_output}.trend-6-3"
    chmod 600 "${OUTPUT_FILE}"
    trap - EXIT
    echo "Additional trend lookbook samples prepared: ${OUTPUT_FILE}"
    exit 0
    ;;
  --replace-trend-lookbook-matched-images)
    require_env FITBACK_DEMO_CONTENT_EMAIL
    require_env FITBACK_DEMO_CONTENT_PASSWORD
    for name in \
      FITBACK_DEMO_TREND_MINIMAL_MATCHED_IMAGE \
      FITBACK_DEMO_TREND_STREET_MATCHED_IMAGE \
      FITBACK_DEMO_TREND_LOVELY_MATCHED_IMAGE \
      FITBACK_DEMO_TREND_CASUAL_MATCHED_IMAGE \
      FITBACK_DEMO_TREND_FORMAL_MATCHED_IMAGE \
      FITBACK_DEMO_TREND_CASUAL_FORMAL_MATCHED_IMAGE; do
      require_env "${name}"
    done
    if [[ ! -f "${OUTPUT_FILE}" ]] || ! jq -e . "${OUTPUT_FILE}" >/dev/null 2>&1; then
      echo "Trend sample result is missing: ${OUTPUT_FILE}" >&2
      exit 1
    fi

    content_token="$(login_or_sign_up \
      "${FITBACK_DEMO_CONTENT_EMAIL}" "${FITBACK_DEMO_CONTENT_PASSWORD}")"
    all_tags_response="$(api_call GET "/api/v1/tags" "${content_token}")"
    require_success "${all_tags_response}" "Trend replacement tag lookup"

    replace_trend_lookbook_matched_image \
      "${content_token}" "${all_tags_response}" "minimal01" \
      "미니멀 뉴트럴 상의 매칭 룩북" \
      "${FITBACK_DEMO_TREND_MINIMAL_MATCHED_IMAGE}" \
      "미니멀" "뉴트럴" >"${temporary_output}.replace-1"
    replace_trend_lookbook_matched_image \
      "${content_token}" "${all_tags_response}" "street01" \
      "스트릿 오버사이즈 아우터 매칭 룩북" \
      "${FITBACK_DEMO_TREND_STREET_MATCHED_IMAGE}" \
      "스트릿" "오버사이즈" >"${temporary_output}.replace-2"
    replace_trend_lookbook_matched_image \
      "${content_token}" "${all_tags_response}" "lovely01" \
      "러블리 페미닌 상의 매칭 룩북" \
      "${FITBACK_DEMO_TREND_LOVELY_MATCHED_IMAGE}" \
      "러블리" "페미닌" >"${temporary_output}.replace-3"
    replace_trend_lookbook_matched_image \
      "${content_token}" "${all_tags_response}" "casual01" \
      "캐주얼 데일리룩 하의 매칭 룩북" \
      "${FITBACK_DEMO_TREND_CASUAL_MATCHED_IMAGE}" \
      "캐주얼" "데일리룩" >"${temporary_output}.replace-4"
    replace_trend_lookbook_matched_image \
      "${content_token}" "${all_tags_response}" "formal01" \
      "포멀 오피스룩 아우터 매칭 룩북" \
      "${FITBACK_DEMO_TREND_FORMAL_MATCHED_IMAGE}" \
      "포멀" "오피스룩" >"${temporary_output}.replace-5"
    replace_trend_lookbook_matched_image \
      "${content_token}" "${all_tags_response}" "casualFormal01" \
      "캐주얼 포멀 하의 매칭 룩북" \
      "${FITBACK_DEMO_TREND_CASUAL_FORMAL_MATCHED_IMAGE}" \
      "캐주얼" "포멀" >"${temporary_output}.replace-6"

    jq \
      --arg preparedAt "$(date -u +"%Y-%m-%dT%H:%M:%SZ")" \
      --arg mode "${MODE}" \
      --slurpfile minimal "${temporary_output}.replace-1" \
      --slurpfile street "${temporary_output}.replace-2" \
      --slurpfile lovely "${temporary_output}.replace-3" \
      --slurpfile casual "${temporary_output}.replace-4" \
      --slurpfile formal "${temporary_output}.replace-5" \
      --slurpfile casualFormal "${temporary_output}.replace-6" \
      '. + {
        preparedAt: $preparedAt,
        mode: $mode,
        trendLookbookSamples: (.trendLookbookSamples
          | .minimal01.matchedImageId = $minimal[0].matchedImageId
          | .street01.matchedImageId = $street[0].matchedImageId
          | .lovely01.matchedImageId = $lovely[0].matchedImageId
          | .casual01.matchedImageId = $casual[0].matchedImageId
          | .formal01.matchedImageId = $formal[0].matchedImageId
          | .casualFormal01.matchedImageId = $casualFormal[0].matchedImageId)
      }' "${OUTPUT_FILE}" >"${temporary_output}"
    mv "${temporary_output}" "${OUTPUT_FILE}"
    rm -f \
      "${temporary_output}.replace-1" "${temporary_output}.replace-2" \
      "${temporary_output}.replace-3" "${temporary_output}.replace-4" \
      "${temporary_output}.replace-5" "${temporary_output}.replace-6"
    chmod 600 "${OUTPUT_FILE}"
    trap - EXIT
    echo "Trend lookbook matched images replaced: ${OUTPUT_FILE}"
    exit 0
    ;;
  *)
    echo "Usage: $0 [prepare|--save-existing|--prepare-interactions|--prepare-trend-lookbook-interactions|--prepare-trend-lookbook-sample|--prepare-remaining-trend-lookbook-samples|--prepare-additional-trend-lookbook-samples|--replace-trend-lookbook-matched-images]" >&2
    exit 1
    ;;
esac

jq -s \
  --arg preparedAt "$(date -u +"%Y-%m-%dT%H:%M:%SZ")" \
  --arg mode "${MODE}" \
  '{preparedAt: $preparedAt, mode: $mode, accounts: .}' \
  "${temporary_output}.0" "${temporary_output}.1" >"${temporary_output}"
mv "${temporary_output}" "${OUTPUT_FILE}"
rm -f "${temporary_output}.0" "${temporary_output}.1"
chmod 600 "${OUTPUT_FILE}"
trap - EXIT

echo "Demo data prepared: ${OUTPUT_FILE}"
