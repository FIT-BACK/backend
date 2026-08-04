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
trap 'rm -f "${temporary_output}" "${temporary_output}.0" "${temporary_output}.1" "${temporary_output}.content" "${temporary_output}.interactions"' EXIT

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
  *)
    echo "Usage: $0 [prepare|--save-existing|--prepare-interactions]" >&2
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
