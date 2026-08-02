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
    local code
    code="$(jq -r '.code // "UNKNOWN"' <<<"${response}")"
    echo "${step} failed: ${code}" >&2
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
trap 'rm -f "${temporary_output}" "${temporary_output}.0" "${temporary_output}.1"' EXIT

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
  *)
    echo "Usage: $0 [prepare|--save-existing]" >&2
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
