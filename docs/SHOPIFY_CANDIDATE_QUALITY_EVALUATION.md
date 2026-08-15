# Shopify candidate-quality 수동 평가 계약

## 목적과 명칭

이 문서는 Shopify retrieval query 변경 전후의 후보 품질을 사람이 동일한 기준으로 비교하기 위한
수동 지표 계약이다. 전체 관련 상품 집합을 정의하거나 완전 탐색하지 않으므로 이 지표를 정식
Recall 또는 정확도로 부르지 않는다.

평가자는 추천 분석 category와 후보 상품의 시각적 대체 가능성을 확인해 각 후보를 `HIGH`,
`MEDIUM`, `LOW` 중 하나로 분류한다. 후보가 30개보다 적으면 실제 후보 수를 함께 기록하고,
지표 이름의 `30`은 selector가 browser handoff에 전달하는 최대 평가 창을 뜻한다.

## selector 원본 순서 지표

- `highCount30`: selector 원본 순서의 최대 30개 후보 중 `HIGH` 개수
- `highMediumCount30`: selector 원본 순서의 최대 30개 후보 중 `HIGH` 또는 `MEDIUM` 개수
- `firstHighSelectorOrdinal`: selector 원본 순서에서 첫 `HIGH`의 1-based ordinal. `HIGH`가 없으면
  `null`
- `top10HighCount`: selector 원본 순서 상위 10개 중 `HIGH` 개수
- `top10HighMediumCount`: selector 원본 순서 상위 10개 중 `HIGH` 또는 `MEDIUM` 개수

`firstHighSelectorOrdinal`은 Shopify raw result의 순번이나 Fashion-CLIP 재정렬 순번이 아니라,
기존 multi-tag priority 및 round-robin selector가 만든 최종 원본 순서를 기준으로 한다.

## Fashion-CLIP image-only 순위 지표

동일 후보 집합을 Fashion-CLIP `imageSimilarity`만으로 정렬해 평가할 때는 위 지표 이름을 재사용하지
않고 `imageOnlyHighCount30`, `imageOnlyHighMediumCount30`, `firstHighImageOnlyOrdinal`,
`imageOnlyTop10HighCount`, `imageOnlyTop10HighMediumCount`로 별도 기록한다. 동점은 기존 browser
계약과 동일하게 원본 handoff index 순으로 정렬한다.

이 구분은 retrieval/selector가 좋은 후보를 30개 안에 포함했는지와, Fashion-CLIP이 그 후보를
상위로 올렸는지를 섞어 해석하지 않기 위한 것이다. `tagSimilarity`, 70/30 final score, 가격 정렬은
image-only 지표에 포함하지 않는다.

## R1 이전 manual baseline

| Query | highCount30 | highMediumCount30 | 비고 |
| --- | ---: | ---: | --- |
| TOP | 0 | 0 | candidates 30 |
| BOTTOM | 0 | 5 | candidates 30 |
| OUTER | 0 | 3 | candidates 30 |
| DRESS | 1 | 5 | 별도 초기 evaluation, candidates 30 |

DRESS 초기 평가는 `HIGH 1 / MEDIUM 4 / LOW 25`였고, TOP/BOTTOM/OUTER cross-query는 각각
`HIGH 0 / MEDIUM 0 / LOW 30`, `HIGH 0 / MEDIUM 5 / LOW 25`,
`HIGH 0 / MEDIUM 3 / LOW 27`이었다.

## R1 평가 기록 규칙

- 동일한 evaluation runner와 동일한 승인 query를 변경 전후에 사용한다.
- query별 실제 후보 수, 다섯 selector 원본 순서 지표, 다섯 image-only 순위 지표를 분리해 기록한다.
- raw/category-filtered 결과에 `HIGH`가 존재하지만 selector 30개에 없다는 근거가 있을 때만 selector
  개선 R2를 검토한다.
- 최소 두 개의 서로 다른 승인 query에서 `HIGH`가 관측되고 candidate-quality가 baseline보다
  개선된 뒤에만 relevance floor와 price advantage를 재검증한다.
- 이 R1 Draft PR의 production/manual validation은 `NOT_RUN`이다.

평가 기록에는 raw provider payload, 상품 image URL, merchant ID, 인증 token 또는 구매자 식별자를
포함하지 않는다.

## R1 query planner 계약

checked-in `V25__seed_tag_master_taxonomy.sql` 기준 taxonomy는 STYLE 5개, SILHOUETTE 12개,
MATERIAL 8개, DETAIL 10개, COLOR 8개로 총 43개다. checked-in production evaluation catalog에는
추가 STYLE 4개(`뉴트럴`, `페미닌`, `데일리룩`, `오피스룩`)가 있으나, STYLE은 R1 retrieval에서
계속 제외한다.

R1은 다음 고신뢰 alias만 사용한다.

| Tag type | Curated mapping | Alias 없음 |
| --- | --- | --- |
| SILHOUETTE | `와이드핏→wide-leg`, `슬림핏→slim-fit`, `오버사이즈→oversized`, `레귤러핏→regular-fit`, `A라인→a-line`, `크롭→cropped`, `로우라이즈→low-rise`, `하이라이즈→high-rise`, `미디기장→midi`, `롱기장→maxi` | `H라인`, `숏기장` |
| DETAIL | `브이넥→v-neck`, `터틀넥→turtleneck`, `라운드넥→crewneck`, `러플/프릴→ruffle`, `지퍼→zip`, `벨트→belted`, `포켓→pocket`, `슬릿→slit`, `단추→button` | `턱` |
| MATERIAL | `데님→denim`, `니트→knit`, `코튼→cotton`, `린넨→linen`, `가죽→leather`, `트위드→tweed`, `시폰→chiffon` | `우븐/시어` |
| COLOR | `화이트→white`, `블랙→black`, `베이지→beige`, `네이비→navy`, `그레이→gray`, `브라운→brown`, `카키→khaki` | `파스텔/메탈릭` |
| STYLE | 없음 | 전체 |

primary alias는 요청의 canonical tag 순서에서 type별 첫 mapped tag 하나를 사용한다. query family
우선순위는 `SILHOUETTE`, `SILHOUETTE+COLOR`, `DETAIL`, `DETAIL+COLOR`, `MATERIAL`,
`MATERIAL+COLOR`이며, 중복을 제거한 앞의 최대 4개 뒤에 `CATEGORY` fallback을 항상 붙인다.
따라서 최대 query 수는 5개다. COLOR 단독 query, Cartesian product, custom tag 검색, alias 없는
Korean raw tag fallback은 만들지 않는다.

예를 들어 DRESS에 `A라인`, `네이비`, `브이넥`, `코튼`이 있으면 실제 Shopify query는 기존
category anchor 결합 후 `a-line dress`, `a-line navy dress`, `v-neck dress`,
`v-neck navy dress`, `dress` 순서가 된다. 한 query family에 결과가 없더라도 다음 family와
category-only fallback을 실행하고, 기존 category filter와 selector가 결과를 처리한다.
