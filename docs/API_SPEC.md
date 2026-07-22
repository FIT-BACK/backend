# Recommendation/Product 및 이미지 업로드 API 계약

## 0. 문서 정보

| 항목 | 값 |
| --- | --- |
| 기준일 | 2026-07-22 |
| 적용 범위 | 원상품 후보·가격 확인, 상품 검색·상세, 추천 생성·조회, 상품 찜, 사용자 이미지 업로드·완료, 분석 리포트 |
| API prefix | `/api/v1` |
| 기준 응답 | `ApiResponse<T>`의 `success`, `code`, `message`, `data` |
| 연동 참고 | Auth `#20`은 PR `#34`로 병합되어 `AuthMember` principal 계약을 확인함. Analysis `#35`는 실제 연동 전 병합본 재확인 |
| 문서 성격 | Phase 0 계약. Controller·Entity·외부 Adapter 구현은 후속 Phase에서 진행 |

이 문서는 제공된 임시 API 명세의 URI와 화면 흐름을 최대한 유지하면서 현재 backend 구조와
확정된 Recommendation/Product 정책을 구체화한다. 이 범위 밖 Auth, Member, Tag, Trend,
Lookbook, Closet API는 해당 도메인의 명세를 따른다.

### 요구사항 반영 범위

| 요구 | API 반영 |
| --- | --- |
| 쇼핑몰 파트너 미확정 | partner 전용 ID·URI를 공개 계약에 넣지 않고 provider-neutral token/Product 사용 |
| 비용 최소화 | pagination, Top 5, live lookup 최소화, fixture fallback을 전제로 함 |
| 원상품 가격+유사도 | 선택 가능한 원상품 가격과 Score V1, similarity-only fallback 정의 |
| 사용자 찜 | 추천 현재 세트와 분리된 `/members/me/saved-products` 정의 |
| 원상품 자동 확정 금지 | 자동 결과는 candidate이며 사용자 확정 단계 분리 |
| 3D 가상 피팅 제외 | 이 문서와 Recommendation/Product MVP endpoint에서 제외 |
| 외부 데이터 정책 준수 | candidateToken, materialization, snapshot/identity-only 경계 정의 |

---

## 1. 공통 계약

### 1.1 인증과 소유권

- 이 문서의 **모든 API는 JWT 인증 필수**다. 상품 검색·상세도 예외가 아니다.
- `Authorization: Bearer {accessToken}`을 사용한다.
- Request body, path, query에서 `memberId`를 받지 않는다.
- 회원 ID는 Auth `#20`이 제공할 인증 principal 또는 공통 현재 회원 abstraction에서 얻는다.
- 리포트 기반 API는 인증 회원이 소유한 `AnalysisReport`에만 접근한다.
- 타인 소유 리포트와 존재하지 않는 리포트는 모두 404로 처리해 리소스 존재 여부를 숨긴다.
- `#20`이 병합되기 전 임시 회원 헤더나 임의의 `memberId` DTO를 만들지 않는다.

### 1.2 공통 요청 헤더

```http
Authorization: Bearer {accessToken}
Accept: application/json
Content-Type: application/json
```

GET과 body 없는 DELETE에는 `Content-Type`을 생략할 수 있다.

### 1.3 공통 성공 응답

```json
{
  "success": true,
  "code": "COMMON200_1",
  "message": "성공적으로 요청을 처리했습니다.",
  "data": {}
}
```

리소스를 처음 만든 응답은 `COMMON201_1`을 사용한다. 멱등 요청에서 이미 존재하는 리소스를
그대로 반환하면 `COMMON200_1`을 사용한다.

### 1.4 공통 실패 응답

```json
{
  "success": false,
  "code": "PRODUCT503_1",
  "message": "상품 공급자를 일시적으로 사용할 수 없습니다.",
  "data": null
}
```

ErrorCode enum 식별자와 wire code는 구분한다. 예를 들어 Java 식별자
`PRODUCT_PROVIDER_UNAVAILABLE`의 wire code는 `PRODUCT503_1`이다.

### 1.5 금액과 시간

- 금액은 JSON number, Java `BigDecimal`, DB `DECIMAL(19,2)`를 사용한다.
- 통화는 ISO 4217 대문자 3자리다. 예: `KRW`.
- 외부 가격의 의미를 추정하지 않고 `LIST`, `CURRENT`, `SALE`을 구분한다.
- 시간은 UTC 기준 ISO 8601 문자열로 반환한다. 예: `2026-07-18T03:00:00Z`.
- MVP는 환율, 배송비, 관세를 계산하지 않는다.

### 1.6 페이지네이션

- 목록 API는 opaque cursor를 사용한다. 클라이언트가 cursor 내부 값을 해석하지 않는다.
- `pageSize` 기본값은 10, 허용 범위는 1~20이다.
- 응답은 `items`, `nextCursor`, `hasNext`, `pageSize`를 포함한다.
- `hasNext=false`이면 `nextCursor=null`이다.

---

## 2. 확정 도메인 계약

### 2.1 내부 상품 카테고리

응답 그룹 순서는 아래와 같다.

```text
OUTER
TOP
BOTTOM
DRESS
SHOES
BAG
ACCESSORY
OTHER
```

- 추천 응답은 8개 그룹을 항상 포함한다.
- 각 그룹은 최대 5개다.
- 항목이 없는 그룹도 `items: []`로 반환한다.
- 외부 카테고리는 Adapter에서 위 enum으로 매핑한다.
- 매핑할 수 없는 구매 가능 패션 상품은 `OTHER`다.

### 2.2 원상품 상태와 가격 근거

| 구분 | 값 | 의미 |
| --- | --- | --- |
| 매칭 상태 | `SUGGESTED` | 자동 탐색 후보이며 아직 사용자 미확정 |
| 매칭 상태 | `USER_CONFIRMED` | 사용자가 원상품 후보 또는 직접 입력 정보를 확정 |
| 매칭 상태 | `REJECTED` | 사용자가 후보를 거절 |
| 가격 검증 | `PROVIDER_VERIFIED` | 외부 공급자가 조회 시점 가격을 제공 |
| 가격 검증 | `USER_ENTERED` | 사용자가 직접 입력했으며 외부 검증 가격이 아님 |
| 가격 검증 | `ADMIN_VERIFIED` | 운영자가 근거를 확인 |
| 가격 검증 | `UNKNOWN` | 비교 가능한 가격 근거가 없음 |
| 기준 가격 유형 | `LIST` | 확인된 정가를 비교 기준으로 선택 |
| 기준 가격 유형 | `CURRENT_SALE` | 확인된 현재 판매가를 비교 기준으로 선택 |

원상품 선택은 추천 생성의 필수조건이 아니다. 선택 또는 비교 가격이 없으면 추천은
similarity-only로 생성한다.

MVP는 report당 원상품 선택 하나를 유지한다. 가격 점수는 원상품과 내부 카테고리가 같은
후보에만 적용하고 다른 7개 그룹은 similarity-only로 평가한다. 한 이미지에서 여러 원상품을
각각 확정하는 기능은 사용성 검증 후 별도 이슈로 확장한다.

### 2.3 Score V1

모든 점수는 0~100으로 정규화한다.

```text
referenceComparablePrice =
  priceVerificationType이 PROVIDER_VERIFIED 또는 ADMIN_VERIFIED이고
  referencePriceType=LIST이면 referenceListPrice
  referencePriceType=CURRENT_SALE이면 referenceCurrentPrice

candidateEffectivePrice =
  유효한 salePrice가 있으면 salePrice
  아니면 currentPrice

rawPriceSavingRate =
  (referenceComparablePrice - candidateEffectivePrice)
  / referenceComparablePrice

priceScore = clamp(rawPriceSavingRate * 100, 0, 100)
priceSavingRate = rawPriceSavingRate
finalScore = similarityScore * 0.70 + priceScore * 0.30
```

계산 정밀도:

- 입력 금액과 가중치는 `BigDecimal`을 사용한다.
- 나눗셈 중간값은 `MathContext.DECIMAL128`을 사용한다.
- `priceScore` 계산용 `rawPriceSavingRate * 100`만 반올림 전에 0~100으로 clamp한다.
- 저장·응답 `priceSavingRate`는 비싼 후보의 음수도 보존하는 signed ratio이며 scale 6,
  점수는 scale 2로
  `RoundingMode.HALF_UP` 반올림한다.
- DB는 전체 `DECIMAL(19,2)` 가격 범위와 최소 기준가 0.01의 signed ratio를 담도록
  `DECIMAL(26,6)`을 사용한다.
- `finalScore`는 scale 2의 similarity/price score에 `0.70`, `0.30`을 곱한 뒤 마지막에
  한 번 scale 2로 반올림한다.

`valueMatch=true` 조건:

1. `similarityScore >= 60`
2. 기준 가격 검증 유형이 `PROVIDER_VERIFIED` 또는 `ADMIN_VERIFIED`
3. 원상품과 후보의 내부 카테고리가 같음
4. 기준·후보 통화가 같음
5. 후보 유효 가격이 기준 가격보다 낮음

원상품 선택이 없거나 카테고리가 다르거나 가격이 없거나 통화가 다르면 다음과 같다.

```text
priceScore = null
priceSavingRate = null
finalScore = similarityScore
valueMatch = false
comparisonStatus = SIMILARITY_ONLY
```

비싼 후보는 가격만으로 제외하지 않는다. 비교 가능한 가격이 있으면 `priceScore=0`이며
유사도와 함께 최종 점수를 계산한다. 가격이 없거나 통화가 다른 후보도 제외하지 않고
similarity-only로 평가한다.

### 2.4 `matchPercentage`

- 요청 범위는 0~100이고 기본값은 Analysis 계약의 70이다.
- 최소 유사도 필터이며 Score V1의 70:30 가중치가 아니다.
- `similarityScore < matchPercentage`인 후보는 결과에서 제외한다.
- `valueMatch`의 절대 유사도 기준 60은 별도로 유지한다.

### 2.5 유사도 점수 경계

- 이 계약의 70:30은 **0~100으로 정규화된 similarityScore 이후의 최종 조합**을 뜻한다.
- 공급자 raw score를 그대로 사용하지 않는다.
- 공급자별 normalization과 태그 근거 fallback은 Phase 2 PoC로 calibration 근거를 확보하고
  Phase 6 구현 이슈에서 확정한다.
- 확정 전 Phase 1 fixture는 명시된 deterministic fixture score만 반환하며 운영 정확도를
  주장하지 않는다.
- Phase 6은 normalization/fallback 결정과 contract test 없이 완료할 수 없고, 산식 변경 시
  `scoreVersion`과 이 문서를 함께 갱신한다.

### 2.6 동점 정렬

```text
finalScore DESC
-> similarityScore DESC
-> candidateEffectivePrice ASC (가격 없음은 마지막)
-> sourceApi ASC
-> externalProductId ASC
-> candidateFingerprint ASC (externalProductId가 없는 요청 내 후보)
-> productId ASC
```

`candidateFingerprint`는 서버 내부 동점·중복 제거 값이며 API 응답 필드가 아니다.

### 2.7 상품 표시와 데이터 상태

`availability`는 상품의 판매·조회 상태를 나타낸다.

| 값 | 의미 |
| --- | --- |
| `AVAILABLE` | 현재 구매 가능 |
| `UNAVAILABLE` | 품절·판매 종료·외부 not found |
| `TEMPORARILY_UNRESOLVED` | timeout, 429, 5xx 등으로 현재 상태를 확인하지 못함 |
| `UNKNOWN` | 아직 조회하지 않았거나 공급자가 상태를 제공하지 않음 |

`dataStatus`는 응답 상품 데이터의 최신성 근거를 나타내며 상품 상세와 찜 목록에서 같은 enum을
사용한다.

| 값 | 의미 |
| --- | --- |
| `LIVE` | 현재 요청에서 공급자 live lookup으로 확인했거나 유효한 최신 snapshot을 사용함 |
| `STALE_SNAPSHOT` | live lookup 실패로 허용된 과거 snapshot 또는 저장된 최소 관계 데이터로 부분 응답함 |

### 2.8 추천 상태와 가격 비교 모드

| 구분 | 값 | 의미 |
| --- | --- | --- |
| `RecommendationStatus` | `NOT_GENERATED` | 현재 추천 세트가 없음 |
| `RecommendationStatus` | `CURRENT` | 현재 입력·원상품 revision으로 생성된 세트 |
| `RecommendationStatus` | `STALE` | 현재 입력 또는 원상품 revision과 다른 기존 세트 |
| `ComparisonStatus` | `COMPARABLE` | 같은 카테고리·통화의 검증 가격으로 가격 비교 가능 |
| `ComparisonStatus` | `SIMILARITY_ONLY` | 가격 비교 없이 유사도만 사용 |

`SIMILARITY_ONLY`는 추천 항목별 비교 모드이며 `recommendationStatus`로 사용하지 않는다.
`AnalysisReport`의 마지막 성공 result metadata로 상태를 계산하므로 추천 항목이 0개여도
`CURRENT`와 `NOT_GENERATED`를 구분한다.

### 2.9 Candidate token

- 외부 검색 후보를 DB에 자동 저장하지 않는다.
- 상세·찜을 지원할 수 있는 raw 후보에는 서버가 서명한 opaque `candidateToken`을 반환한다.
- token에는 공급자 identity, capability, 만료 시각, 서버 검증용 서명이 포함될 수 있지만
  클라이언트 계약은 문자열 하나뿐이다.
- token 유효시간은 기본 10분이며 운영 설정으로 조정할 수 있다.
- token은 발급 당시 인증 회원과 `allowedPurposes` 집합에 묶는다. 목적은
  `REFERENCE_SELECTION`, `PRODUCT_MATERIALIZATION` 중 하나 이상이다.
- 원상품 후보 token은 항상 `REFERENCE_SELECTION`을 포함하고, 상세·찜까지 지원하면
  `PRODUCT_MATERIALIZATION`도 포함한다. 상품 검색 token은 materialization 목적만 포함한다.
- 다른 회원 또는 허용되지 않은 목적에서 사용하면 invalid다.
- 같은 회원은 만료 전 허용된 목적에서 재사용할 수 있으며 materialization은 동일 Product를
  반환한다. 만료 뒤에는 새 검색이 필요하다.
- `SNAPSHOT_UUID` 후보 token에는 서명된 random materialization nonce가 들어간다. 서버는
  원문 token/nonce가 아니라 versioned SHA-256 `materializationKey`만 Product에 저장해 같은
  token 재시도를 동일 Product로 만든다.
- 새 검색에서 새 token으로 다시 발견한 unstable 상품의 전역 중복까지 보장하지는 않는다.
- token은 DB 저장이나 로그 원문 기록 대상이 아니다.
- 클라이언트가 token 대신 상품명, 가격, 이미지 URL을 보내 내부 Product를 만들 수 없다.

---

## 3. API 요약

| Method | Endpoint | 이름 | 인증 |
| --- | --- | --- | --- |
| POST | `/api/v1/analyses/{reportId}/reference-product-candidates` | 원상품 후보 탐색 | 필수 |
| PUT | `/api/v1/analyses/{reportId}/reference-product` | 원상품·기준 가격 확정 | 필수 |
| GET | `/api/v1/analyses/{reportId}/reference-product` | 원상품·기준 가격 조회 | 필수 |
| GET | `/api/v1/products` | 상품 검색 | 필수 |
| POST | `/api/v1/product-references` | 외부 후보 materialize | 필수 |
| GET | `/api/v1/products/{productId}` | 상품 상세 | 필수 |
| PATCH | `/api/v1/analyses/{reportId}/recommendations` | 태그 확정 및 추천 생성 | 필수 |
| GET | `/api/v1/analyses/{reportId}` | 분석 결과와 추천 조회 | 필수 |
| PUT | `/api/v1/members/me/saved-products/{productId}` | 상품 찜 | 필수 |
| GET | `/api/v1/members/me/saved-products` | 상품 찜 목록 | 필수 |
| DELETE | `/api/v1/members/me/saved-products/{productId}` | 상품 찜 해제 | 필수 |
| POST | `/api/v1/images/presigned-uploads` | 이미지 Presigned PUT URL 발급 | 필수 |
| POST | `/api/v1/images/{imageId}/complete` | 이미지 업로드 완료 검증 | 필수 |
| POST | `/api/v1/images/{imageId}/upload-request` | 이미지 업로드 URL 재발급 | 필수 |
| POST | `/api/v1/analyses` | 이미지 기반 분석 리포트 생성 | 필수 |
| GET | `/api/v1/analyses` | 내 분석 리포트 목록 | 필수 |
| DELETE | `/api/v1/analyses/{reportId}` | 분석 리포트 삭제 | 필수 |

---

## 4. 원상품 후보 탐색

### `POST /api/v1/analyses/{reportId}/reference-product-candidates`

분석 리포트의 업로드 이미지를 이용해 원상품 후보와 확인 가능한 가격 근거를 찾는다.
외부 결과는 후보일 뿐 정확한 SKU나 공식 출시가를 자동 확정하지 않는다.

### Request

body는 없다. 서버는 `AnalysisReport.imageUrl`과 확정된 태그를 사용한다.

### Response `200 OK`

```json
{
  "success": true,
  "code": "COMMON200_1",
  "message": "성공적으로 요청을 처리했습니다.",
  "data": {
    "reportId": 501,
    "items": [
      {
        "candidateToken": "opaque-signed-token",
        "name": "Designer Oversized Shirt",
        "brandName": "Example Brand",
        "sellerName": "Example Store",
        "category": "TOP",
        "imageUrl": "https://provider.example/items/123.jpg",
        "sourceUrl": "https://provider.example/items/123",
        "similarityScore": 91.40,
        "listPrice": {
          "amount": 380000.00,
          "currency": "KRW",
          "type": "LIST",
          "observedAt": "2026-07-18T03:00:00Z"
        },
        "currentPrice": {
          "amount": 342000.00,
          "currency": "KRW",
          "type": "CURRENT",
          "observedAt": "2026-07-18T03:00:00Z"
        },
        "priceVerificationType": "PROVIDER_VERIFIED",
        "detailSupported": true,
        "wishlistSupported": true,
        "expiresAt": "2026-07-18T03:10:00Z"
      }
    ],
    "partial": false,
    "warnings": []
  }
}
```

### 규칙

- 후보 탐색 요청 자체는 Product DB write를 수행하지 않는다.
- 공급자별 결과를 한 요청 안에서 fingerprint로 중복 제거한다.
- 안정 identity 또는 허용된 snapshot 전략이 없으면 `candidateToken=null`,
  `detailSupported=false`, `wishlistSupported=false`다.
- 한 공급자만 실패하고 다른 공급자 결과가 있으면 200 partial response를 허용한다.
- 모든 공급자가 실패하고 fixture fallback도 없으면 `PRODUCT503_1`을 반환한다.

---

## 5. 원상품·기준 가격 확정

### `PUT /api/v1/analyses/{reportId}/reference-product`

자동 후보 하나를 선택하거나 후보가 없을 때 원상품 정보를 직접 입력한다.

### Request — 후보 선택

```json
{
  "selectionType": "CANDIDATE",
  "candidateToken": "opaque-signed-token",
  "referencePriceType": "LIST"
}
```

### Request — 직접 입력

```json
{
  "selectionType": "MANUAL",
  "manualInput": {
    "name": "무대 착장 와이드 팬츠",
    "brandName": "Example Brand",
    "category": "BOTTOM",
    "sourceUrl": "https://brand.example/products/123",
    "imageUrl": null,
    "price": {
      "amount": 380000.00,
      "currency": "KRW",
      "type": "LIST",
      "observedAt": "2026-07-18T03:00:00Z"
    }
  }
}
```

직접 입력에서 `price`는 선택이다. 입력했으면 `amount > 0`, 통화, 유형, 관측 시각이
필수이며 `priceVerificationType=USER_ENTERED`로 저장한다.

- `CANDIDATE`는 현재 회원에게 발급된 `REFERENCE_SELECTION` token과 실제 존재하는
  `referencePriceType`을 요구한다. 가격을 선택하지 않으면 유형도 보내지 않는다.
- 선택한 비교 기준 가격은 0보다 커야 한다. 0원·음수는 비교 가격으로 확정하지 않는다.
- `MANUAL`은 `name`, 내부 `category`, `sourceUrl`이 필수다.
- `selectionType`과 맞지 않는 다른 variant 필드는 보내지 않는다.

### Response `200 OK`

```json
{
  "success": true,
  "code": "COMMON200_1",
  "message": "성공적으로 요청을 처리했습니다.",
  "data": {
    "reportId": 501,
    "selection": {
      "selectionId": 71,
      "revision": 2,
      "matchStatus": "USER_CONFIRMED",
      "name": "Designer Oversized Shirt",
      "brandName": "Example Brand",
      "category": "TOP",
      "sourceUrl": "https://provider.example/items/123",
      "imageUrl": "https://provider.example/items/123.jpg",
      "referencePrice": {
        "amount": 380000.00,
        "currency": "KRW",
        "type": "LIST",
        "verificationType": "PROVIDER_VERIFIED",
        "observedAt": "2026-07-18T03:00:00Z"
      }
    },
    "recommendationStatus": "NOT_GENERATED"
  }
}
```

### 규칙

- report당 현재 선택 하나를 upsert한다.
- 의미 있는 원상품·가격 변경 때 `revision`을 1 증가시킨다.
- 같은 내용을 다시 PUT하면 revision을 증가시키지 않는 멱등 요청이다.
- 현재 세트가 없으면 `NOT_GENERATED`, 변경 전 세트가 남아 있으면 `STALE`, idempotent PUT 뒤
  현재 revision 기반 세트가 있으면 `CURRENT`다.
- 변경 전 추천 결과는 삭제하지 않고 `STALE`로 판정하며 가성비 문구를 숨긴다.
- 후보 token은 live lookup과 저장 정책을 다시 검증한다.
- 직접 입력 정보를 외부 검증 가격처럼 표시하지 않는다.

---

## 6. 원상품·기준 가격 조회

### `GET /api/v1/analyses/{reportId}/reference-product`

### Response `200 OK`

응답 shape은 PUT과 동일하게 `selection`을 항상 사용한다. 아직 선택하지 않았으면 404로
만들지 않고 다음을 반환한다.

```json
{
  "success": true,
  "code": "COMMON200_1",
  "message": "성공적으로 요청을 처리했습니다.",
  "data": {
    "reportId": 501,
    "selection": null,
    "recommendationStatus": "NOT_GENERATED"
  }
}
```

원상품 선택이 없어도 similarity-only 현재 세트가 이미 있으면 `recommendationStatus=CURRENT`다.

---

## 7. 상품 검색

### `GET /api/v1/products`

### Query

| 이름 | 타입 | 필수 | 기본값 | 규칙 |
| --- | --- | --- | --- | --- |
| `keyword` | String | O | 없음 | trim 후 1~100자 |
| `category` | ProductCategory | X | 없음 | 지정 시 내부 카테고리로 필터 |
| `cursor` | String | X | 없음 | 서버가 발급한 opaque cursor |
| `pageSize` | Integer | X | 10 | 1~20 |

### Request 예시

```http
GET /api/v1/products?keyword=미니멀%20셔츠&category=TOP&pageSize=10
```

### Response `200 OK`

```json
{
  "success": true,
  "code": "COMMON200_1",
  "message": "성공적으로 요청을 처리했습니다.",
  "data": {
    "items": [
      {
        "productId": null,
        "candidateToken": "opaque-signed-token",
        "imageUrl": "https://provider.example/items/100.jpg",
        "name": "오버핏 셔츠",
        "brandName": null,
        "sellerName": "에이블리",
        "category": "TOP",
        "price": {
          "amount": 28900.00,
          "currency": "KRW",
          "type": "CURRENT",
          "observedAt": "2026-07-18T03:00:00Z"
        },
        "availability": "AVAILABLE",
        "detailSupported": true,
        "wishlistSupported": true
      }
    ],
    "nextCursor": "opaque-next-cursor",
    "hasNext": true,
    "pageSize": 10,
    "partial": false,
    "warnings": []
  }
}
```

### 규칙

- 검색 요청은 DB write를 수행하지 않는다.
- 이미 materialize된 동일 상품이면 `productId`를 함께 반환할 수 있다.
- raw 후보는 상세나 찜 전에 `/product-references`로 검증·materialize한다.
- 공급자가 주지 않은 브랜드·판매처·이미지를 추측하지 않고 null로 반환한다.
- 외부 가격은 공식 출시가로 추정하지 않는다.

---

## 8. 외부 후보 materialize

### `POST /api/v1/product-references`

### Request

```json
{
  "candidateToken": "opaque-signed-token"
}
```

### Response — 새 Product `201 Created`

```json
{
  "success": true,
  "code": "COMMON201_1",
  "message": "리소스가 생성되었습니다.",
  "data": {
    "productId": 100,
    "created": true,
    "availability": "AVAILABLE"
  }
}
```

이미 materialize된 identity면 `200 OK`, `created=false`, 같은 `productId`를 반환한다.

### 규칙

- candidate token 서명·만료·공급자 capability를 검증한다.
- `SNAPSHOT_UUID`는 token의 `materializationKey` unique로 재시도를 멱등 처리한다.
- 가능한 경우 live lookup으로 identity와 현재 상태를 재확인한다.
- `docs/ERD.md`가 허용하는 provider identity 또는 snapshot 최소 필드만 저장한다.
- 클라이언트가 상품명, 가격, 이미지, 구매 URL을 body로 보내지 않는다.
- 안정 identity도 없고 snapshot 저장도 허용되지 않으면 `PRODUCT422_2`다.
- 추천 생성 내부에서도 동일한 materialization service를 사용한다.

---

## 9. 상품 상세

### `GET /api/v1/products/{productId}`

### Response `200 OK`

```json
{
  "success": true,
  "code": "COMMON200_1",
  "message": "성공적으로 요청을 처리했습니다.",
  "data": {
    "productId": 100,
    "imageUrl": "https://provider.example/items/100.jpg",
    "name": "오버핏 셔츠",
    "brandName": null,
    "sellerName": "에이블리",
    "category": "TOP",
    "price": {
      "amount": 28900.00,
      "currency": "KRW",
      "type": "CURRENT",
      "observedAt": "2026-07-18T03:00:00Z"
    },
    "purchaseUrl": "https://mall.example/products/100",
    "affiliateUrl": null,
    "availability": "AVAILABLE",
    "dataStatus": "LIVE",
    "tags": ["미니멀", "와이드핏"],
    "isSaved": false
  }
}
```

### partial 정책

- identity-only Product는 응답 시 live lookup한다.
- provider timeout이고 표시 가능한 허용 snapshot이 있으면 200과
  `dataStatus=STALE_SNAPSHOT`, `availability=TEMPORARILY_UNRESOLVED`를 반환할 수 있다.
- 정상 live lookup 또는 유효한 최신 snapshot 응답은 `dataStatus=LIVE`를 사용한다.
- 표시 가능한 데이터가 전혀 없으면 `PRODUCT503_1`이다.
- provider not found는 Product를 hard delete하지 않고 `UNAVAILABLE`로 표시한다.
- 찜 해제는 상세 조회 성공 여부와 무관하게 동작한다.

---

## 10. 태그 확정 및 추천 생성

### `PATCH /api/v1/analyses/{reportId}/recommendations`

임시 명세와 같이 태그·매칭값 확정과 추천 생성을 하나의 공개 API로 유지한다.

### Request

```json
{
  "confirmedTagIds": [12, 21, 33],
  "matchPercentage": 70
}
```

| 필드 | 타입 | 필수 | 규칙 |
| --- | --- | --- | --- |
| `confirmedTagIds` | Array\<Long\> | O | 비어 있지 않은 중복 없는 존재하는 Tag ID |
| `matchPercentage` | Integer | X | 0~100 최소 유사도 필터. 생략하면 70 |

### Response `200 OK`

아래 예시는 TOP에만 결과가 있고 나머지 7개 그룹은 비어 있는 경우다.

```json
{
  "success": true,
  "code": "COMMON200_1",
  "message": "성공적으로 요청을 처리했습니다.",
  "data": {
    "reportId": 501,
    "tags": ["미니멀", "와이드핏", "베이지톤"],
    "matchPercentage": 70,
    "scoreVersion": "V1",
    "recommendationStatus": "CURRENT",
    "referenceRevision": 2,
    "recommendationGroups": [
      {"category": "OUTER", "items": []},
      {
        "category": "TOP",
        "items": [
          {
            "productId": 100,
            "rank": 1,
            "imageUrl": "https://provider.example/items/100.jpg",
            "name": "오버핏 셔츠",
            "sellerName": "에이블리",
            "price": {
              "amount": 28900.00,
              "currency": "KRW",
              "type": "CURRENT",
              "observedAt": "2026-07-18T03:00:00Z"
            },
            "purchaseUrl": "https://mall.example/products/100",
            "similarityScore": 82.00,
            "priceScore": 92.39,
            "finalScore": 85.12,
            "priceSavingRate": 0.923947,
            "valueMatch": true,
            "comparisonStatus": "COMPARABLE",
            "reasonCodes": ["HIGH_SIMILARITY", "LOWER_PRICE"],
            "availability": "AVAILABLE",
            "isSaved": false
          }
        ]
      },
      {"category": "BOTTOM", "items": []},
      {"category": "DRESS", "items": []},
      {"category": "SHOES", "items": []},
      {"category": "BAG", "items": []},
      {"category": "ACCESSORY", "items": []},
      {"category": "OTHER", "items": []}
    ],
    "partial": false,
    "warnings": []
  }
}
```

가격 비교를 할 수 없는 항목은 다음 필드를 사용한다.

```json
{
  "priceScore": null,
  "finalScore": 82.00,
  "priceSavingRate": null,
  "valueMatch": false,
  "comparisonStatus": "SIMILARITY_ONLY",
  "reasonCodes": ["HIGH_SIMILARITY", "REFERENCE_PRICE_UNAVAILABLE"]
}
```

기준·후보 통화가 다른 항목도 같은 comparison mode를 사용하되 reason을 구분한다.

```json
{
  "priceScore": null,
  "finalScore": 82.00,
  "priceSavingRate": null,
  "valueMatch": false,
  "comparisonStatus": "SIMILARITY_ONLY",
  "reasonCodes": ["HIGH_SIMILARITY", "CURRENCY_MISMATCH"]
}
```

기준 가격이 없으면 `REFERENCE_PRICE_UNAVAILABLE`, `USER_ENTERED` 가격만 있어 가격 비교에서
제외하면 `REFERENCE_PRICE_UNVERIFIED`를 사용한다. 사용자 입력 가격은 응답에 검증 가격이나
가성비 근거로 표시하지 않는다.

### 트랜잭션과 동시성

```text
1. 소유권·리포트 상태·Tag ID 검증
2. 짧은 transaction A
   - confirmedTagIds와 matchPercentage 저장
   - analysis recommendationInputRevision 증가
   - 현재 reference selection ID와 revision을 요청 snapshot으로 캡처
3. transaction 밖
   - 외부 후보 탐색, normalize, category mapping, dedupe, Score V1 계산
4. 짧은 transaction B
   - report row lock 또는 optimistic compare
   - 현재 recommendationInputRevision과 요청 revision 비교
   - 현재 reference selection ID/revision과 요청 snapshot 비교
   - 둘 다 같을 때만 기존 현재 세트를 새 세트로 원자적 교체
   - 빈 세트도 result input/reference revision, scoreVersion, generatedAt metadata 갱신
```

- 외부 호출을 DB transaction 안에서 수행하지 않는다.
- 새 세트 저장에 성공하기 전 기존 세트를 삭제하지 않는다.
- transaction B가 실패하면 전체 새 세트를 rollback하고 기존 세트를 유지한다.
- 외부 호출 중 입력 또는 reference selection/revision이 바뀌면 `RECOMMENDATION409_1`이며
  기존 세트를 유지한다.
- 외부 공급자가 모두 실패하면 `PRODUCT503_1`이며 기존 세트는 유지된다.
- provider identity 또는 허용 snapshot으로 materialize할 수 없는 후보는 현재 추천 세트에서
  제외하고 `MATERIALIZATION_UNSUPPORTED` warning을 남긴다.
- 공급자가 후보를 반환했지만 전부 저장정책상 materialize 불가하면 `PRODUCT503_3`이며
  요청 단위 ephemeral 추천은 만들지 않고 기존 세트를 유지한다.
- transaction A가 이미 반영됐으므로 기존 세트는 input revision 불일치로 `STALE`이다.
- 추천 세트 교체·삭제는 `SavedProduct`를 변경하지 않는다.

---

## 11. 분석 결과와 추천 조회 연동

### `GET /api/v1/analyses/{reportId}`

Analysis `#35`의 상세 응답에 아래 Recommendation fragment를 포함한다. 최종 병합 DTO가
달라지면 Analysis와 Recommendation 담당자가 한 PR에서 명세와 코드를 함께 동기화한다.

```json
{
  "success": true,
  "code": "COMMON200_1",
  "message": "성공적으로 요청을 처리했습니다.",
  "data": {
    "reportId": 501,
    "tags": ["미니멀", "와이드핏", "베이지톤"],
    "matchPercentage": 70,
    "recommendationStatus": "CURRENT",
    "scoreVersion": "V1",
    "recommendationGroups": [
      {"category": "OUTER", "items": []},
      {"category": "TOP", "items": []},
      {"category": "BOTTOM", "items": []},
      {"category": "DRESS", "items": []},
      {"category": "SHOES", "items": []},
      {"category": "BAG", "items": []},
      {"category": "ACCESSORY", "items": []},
      {"category": "OTHER", "items": []}
    ]
  }
}
```

### 조회 규칙

- 아직 생성하지 않았으면 `recommendationStatus=NOT_GENERATED`와 8개 빈 그룹을 반환한다.
- 입력 또는 reference revision이 다르면 `STALE`로 반환하고 가성비 문구를 숨긴다.
- 일부 live hydrate 실패는 가능한 항목을 반환하고 `partial=true`, `warnings`에 reason code를 둔다.
- 전체를 표시할 수 없으면 provider 오류를 반환하되 저장된 찜 관계나 현재 세트를 삭제하지 않는다.

---

## 12. 상품 찜

### 12.1 찜 생성

#### `PUT /api/v1/members/me/saved-products/{productId}`

body는 없다.

```json
{
  "success": true,
  "code": "COMMON201_1",
  "message": "리소스가 생성되었습니다.",
  "data": {
    "productId": 100,
    "isSaved": true,
    "savedAt": "2026-07-18T03:00:00Z"
  }
}
```

- 처음 생성하면 201, 이미 찜했다면 200과 기존 `savedAt`을 반환한다.
- `(member_id, product_id)` 복합 PK로 멱등성을 보장한다.
- materialize된 Product만 받을 수 있다.
- client가 외부 상품 snapshot 필드를 함께 보내지 않는다.

### 12.2 찜 목록

#### `GET /api/v1/members/me/saved-products?cursor=&pageSize=`

```json
{
  "success": true,
  "code": "COMMON200_1",
  "message": "성공적으로 요청을 처리했습니다.",
  "data": {
    "items": [
      {
        "productId": 100,
        "imageUrl": "https://provider.example/items/100.jpg",
        "name": "오버핏 셔츠",
        "sellerName": "에이블리",
        "category": "TOP",
        "price": {
          "amount": 28900.00,
          "currency": "KRW",
          "type": "CURRENT",
          "observedAt": "2026-07-18T03:00:00Z"
        },
        "availability": "AVAILABLE",
        "dataStatus": "LIVE",
        "savedAt": "2026-07-18T03:00:00Z"
      }
    ],
    "nextCursor": null,
    "hasNext": false,
    "pageSize": 10,
    "partial": false,
    "warnings": []
  }
}
```

- 정렬은 `savedAt DESC, productId DESC`다.
- 품절·not found·일시 장애여도 찜 관계는 유지한다.
- live hydrate 실패 시 허용된 snapshot과 `dataStatus=STALE_SNAPSHOT`,
  `availability=TEMPORARILY_UNRESOLVED` 상태를 반환한다.
- 정상 live hydrate 또는 유효한 최신 snapshot은 `dataStatus=LIVE`를 반환한다.
- 표시 가능한 데이터가 없는 항목도 관계를 숨기거나 삭제하지 않고 최소 `productId`,
  `dataStatus=STALE_SNAPSHOT`, `savedAt`을 반환한다.

### 12.3 찜 해제

#### `DELETE /api/v1/members/me/saved-products/{productId}`

```json
{
  "success": true,
  "code": "COMMON200_1",
  "message": "성공적으로 요청을 처리했습니다.",
  "data": {
    "productId": 100,
    "isSaved": false
  }
}
```

- 관계가 이미 없어도 같은 성공 응답을 반환한다.
- provider 장애와 상품 상세 조회 실패 중에도 동작한다.
- Product, RecommendedItem, 다른 회원의 SavedProduct를 삭제하지 않는다.

---

## 13. 오류 계약

| Java 식별자 | Wire code | HTTP | 적용 조건 |
| --- | --- | ---: | --- |
| `UNAUTHORIZED` | `COMMON401_1` | 401 | 인증이 없거나 유효하지 않음 |
| `VALIDATION_ERROR` | `COMMON400_2` | 400 | 필드 형식·범위·필수값 위반 |
| `ANALYSIS_REPORT_NOT_FOUND` | `ANALYSIS404_1` | 404 | 리포트가 없거나 현재 회원 소유가 아님 |
| `ANALYSIS_NOT_READY` | `ANALYSIS409_1` | 409 | 추천 입력으로 사용할 수 없는 분석 상태 |
| `TAG_NOT_FOUND` | `TAG404_1` | 404 | 요청 Tag ID 중 존재하지 않는 값이 있음 |
| `PRODUCT_NOT_FOUND` | `PRODUCT404_1` | 404 | 내부 Product가 없음 |
| `PRODUCT_REFERENCE_INVALID` | `PRODUCT422_1` | 422 | candidate token 서명·형식·만료 오류 |
| `PRODUCT_REFERENCE_UNSUPPORTED` | `PRODUCT422_2` | 422 | 안정 identity와 허용 snapshot 전략이 모두 없음 |
| `PRODUCT_PROVIDER_RESPONSE_INVALID` | `PRODUCT502_1` | 502 | 공급자 응답을 계약대로 해석할 수 없음 |
| `PRODUCT_PROVIDER_UNAVAILABLE` | `PRODUCT503_1` | 503 | timeout, 5xx, 연결 실패 |
| `PRODUCT_PROVIDER_QUOTA_EXCEEDED` | `PRODUCT503_2` | 503 | 429 또는 공급자 quota 초과 |
| `PRODUCT_PROVIDER_PERSISTENCE_UNSUPPORTED` | `PRODUCT503_3` | 503 | 반환 후보를 허용된 방식으로 하나도 materialize할 수 없음 |
| `RECOMMENDATION_INPUT_CHANGED` | `RECOMMENDATION409_1` | 409 | 외부 호출 중 태그·match 또는 reference revision이 변경됨 |
| `IMAGE_UNSUPPORTED_CONTENT_TYPE` | `IMAGE400_1` | 400 | JPEG, PNG, WebP 이외의 업로드 MIME type |

정책:

- 타인 리포트에 403을 반환하지 않는다.
- 가격 없음·통화 불일치는 요청 오류가 아니라 similarity-only 조건이다.
- 찜 DELETE는 관계가 없어도 성공하므로 saved-product not-found 오류가 없다.
- provider 오류 응답에는 API key, 원문 요청 URL, candidate token, 외부 원문 body를 넣지 않는다.
- domain ErrorCode 구현 전에는 이 표의 식별자와 wire code를 동시에 임의 변경하지 않는다.

---

## 14. 외부 공급자와 저장 정책

### 14.1 Port 경계

```text
ReferenceProductDiscoveryPort  # 이미지 기반 원상품 후보
ProductPriceVerificationPort   # 원상품 가격 검증
ProductCatalogPort             # 키워드 검색·상세·live lookup
AffiliateLinkPort              # 제휴 링크를 실제 채택할 때만
```

Controller와 Service는 Shopify, Lykdat, ADPICK 같은 공급자 이름을 DTO 계약에 노출하지 않는다.

### 14.2 검색·저장 분리

- 상품 검색과 원상품 후보 탐색은 DB write를 하지 않는다.
- `POST /product-references`, 사용자의 원상품 확정, 추천 결과 materialization만 명시적으로 저장한다.
- 공급자 정책이 snapshot 저장을 금지하면 provider identity만 저장하고 응답 시 live lookup한다.
- 안정 identity가 없지만 snapshot 저장이 명시적으로 허용되면 내부 UUID 전략을 사용할 수 있다.
- identity와 snapshot 모두 허용되지 않으면 상세·찜을 비활성화한다.
- MVP 추천 현재 세트에는 materialize 가능한 Product만 포함한다. 요청 단위 ephemeral 추천
  DTO는 만들지 않는다.
- 검색 결과·가격·이미지 URL의 허용 필드와 TTL은 Phase 2 ADR 확정 전 추측하지 않는다.

### 14.3 장애와 fallback

- 실제 API를 CI에서 호출하지 않고 fixture Adapter를 사용한다.
- 401/403 인증 오류, quota 403/429, 5xx, timeout, invalid body를 구분한다.
- retry는 멱등 read와 공급자가 허용한 오류만 대상으로 한다.
- provider 장애가 DB transaction을 길게 유지하지 않는다.
- feature flag off 상태에서도 fixture 또는 명시적 unavailable 응답으로 기동한다.

---

## 15. DTO 이름

| 역할 | 이름 |
| --- | --- |
| 원상품 후보 요청/응답 | `ReferenceProductCandidateSearchRequest`, `ReferenceProductCandidateListResponse` |
| 원상품 확정 요청/응답 | `ReferenceProductConfirmRequest`, `ReferenceProductResponse` |
| 상품 검색 응답 | `ProductSearchResponse` |
| 외부 후보 materialize | `ProductReferenceCreateRequest`, `ProductReferenceResponse` |
| 상품 상세 응답 | `ProductDetailResponse` |
| 추천 생성 요청/응답 | `RecommendationCreateRequest`, `RecommendationResultResponse` |
| 추천 그룹 | `RecommendationGroupResponse` |
| 찜 응답/목록 | `SavedProductResponse`, `SavedProductListResponse` |
| 이미지 업로드 URL 요청/응답 | `ImageUploadRequest`, `ImageUploadResponse` |

Controller는 Entity나 외부 provider response를 직접 반환하지 않는다.

---

## 16. Auth #20 / Analysis #35 연동 체크

### Auth `#20`

- PR `#34` 병합본의 principal 타입은 `AuthMember`이며 Controller에서
  `@AuthenticationPrincipal AuthMember`로 주입받는다.
- member ID는 `authMember.getMember().getId()`로 얻고 Request에 임시 member ID를 추가하지 않는다.
- 모든 Product/Recommendation endpoint에 인증 정책을 적용한다.
- 인증 실패 401과 리포트 존재 숨김 404를 contract test로 고정한다.

### Analysis `#35`

- 실제 병합된 Controller prefix가 `/api/v1/analyses`인지 확인한다.
- report 소유자 조회, 분석 완료 상태, 확정 ReportTag 교체 방식을 확인한다.
- `PATCH /{reportId}/recommendations`의 tag·match transaction과 외부 호출을 분리한다.
- 이미지 URL만으로 외부 공급자가 이미지를 읽을 수 있는지 확인하고, 필요하면 Analysis 또는
  storage 계층에 read stream/signed URL 계약을 별도 이슈로 추가한다.
- `GET /analyses/{reportId}`의 기존 필드를 유지하며 이 문서의 recommendation fragment를 합친다.

Auth `#20`의 병합 계약은 위와 같이 사용한다. Analysis `#35`가 병합되기 전에는 해당 브랜치
코드를 복제하거나 임시 호환 계층을 만들지 않는다.

---

## 17. 구현 Phase 검증 기준

- [ ] 모든 endpoint 인증과 소유권 404가 MockMvc로 검증됨
- [ ] 요청 DTO validation과 공통 응답 envelope가 검증됨
- [ ] 상품 검색 GET이 DB write를 하지 않음
- [ ] candidate token 변조·만료·재사용 정책이 검증됨
- [ ] Score V1 70:30과 matchPercentage 필터가 순수 단위 테스트로 검증됨
- [ ] 가격 없음·통화 불일치가 similarity-only이며 valueMatch가 false임
- [ ] 8개 그룹 순서, Top 5, 빈 그룹 포함이 검증됨
- [ ] 외부 호출이 DB transaction 밖에서 실행됨
- [ ] 저장 실패·동시 입력 변경 시 기존 현재 세트가 유지됨
- [ ] 찜 PUT/DELETE 멱등성과 추천 세트 독립성이 검증됨
- [ ] provider 장애 중 찜 해제와 partial 목록이 동작함
- [ ] API key, candidate token, 사용자 이미지 URL 원문이 로그에 노출되지 않음
- [ ] API 변경 PR에서 이 문서가 함께 갱신됨

---

## 18. 이미지 Presigned Upload

### `POST /api/v1/images/presigned-uploads`

인증 회원이 브라우저에서 private S3 버킷으로 이미지를 직접 업로드할 수 있도록 5분 유효한
Presigned PUT URL을 발급한다. Request의 회원 식별자는 받지 않으며 JWT principal의 회원을
소유자로 사용한다.

### Request

```json
{
  "purpose": "ANALYSIS_ORIGINAL",
  "contentType": "image/jpeg",
  "fileSize": 3145728
}
```

| 필드 | 필수 | 계약 |
| --- | --- | --- |
| `purpose` | 예 | `ANALYSIS_ORIGINAL`, `LOOKBOOK_ORIGINAL`, `LOOKBOOK_MATCHED`, `PROFILE` |
| `contentType` | 예 | `image/jpeg`, `image/png`, `image/webp`만 허용 |
| `fileSize` | 예 | 1 byte 이상 5 MiB(`5,242,880` byte) 이하 |

### Response `201 Created`

```json
{
  "success": true,
  "code": "COMMON201_1",
  "message": "리소스가 생성되었습니다.",
  "data": {
    "imageId": "5f8ca021-02fe-4fba-982f-8de356789abc",
    "uploadUrl": "https://fitback-prod-images.example/presigned-put",
    "uploadMethod": "PUT",
    "requiredHeaders": {
      "Content-Type": "image/jpeg"
    },
    "expiresAt": "2026-07-22T05:05:00Z",
    "imageUrl": "https://d1p2ierkew26r1.cloudfront.net/prod/images/analysis_original/2026/07/5f8ca021-02fe-4fba-982f-8de356789abc.jpg"
  }
}
```

클라이언트는 `uploadMethod`와 `requiredHeaders`를 그대로 사용해 `uploadUrl`로 파일 body를
전송한다. `uploadUrl`은 자격 증명 URL이므로 저장하거나 로그에 남기지 않는다. `imageUrl`은
영구 리소스 위치이며 private CloudFront 배포의 서명 없는 조회 URL이므로 그 자체로는 `403`을
반환한다. 업로드 직후 화면 미리보기는 브라우저의 local object URL을 사용한다.

Presigned PUT 서명에는 요청의 `fileSize`와 동일한 `Content-Length`가 포함된다. 브라우저가
파일 body 크기를 기준으로 이 헤더를 자동 생성하므로 클라이언트 JavaScript에서 직접 설정하지
않으며, 요청 크기와 실제 body 크기가 다르면 S3가 업로드를 거부한다.

### 상태와 검증

- 발급과 함께 `image.status=PENDING`, `visibility=PRIVATE` 행을 저장한다.
- 객체 key는 서버가 UUID로 생성하며 클라이언트 파일명은 사용하지 않는다.
- 이 API가 받은 `fileSize`는 발급 전 요청 검증용이다. 업로드 완료 API가 S3 metadata,
  실제 파일 시그니처와 크기를 다시 검증한다.
- 분석 리포트가 생성되면 `ANALYSIS_ORIGINAL` 이미지를 `ACTIVE`로 전환한다.
- 24시간 이상 도메인에 연결되지 않은 이미지는 정리 작업자가 S3 객체와 DB 상태를 정리한다.

### 오류

| 조건 | HTTP | code |
| --- | ---: | --- |
| 인증 없음 또는 유효하지 않은 토큰 | 401 | `COMMON401_1` |
| 필수값, enum, 파일 크기 위반 | 400 | `COMMON400_1` 또는 `COMMON400_2` |
| 지원하지 않는 MIME type | 400 | `IMAGE400_1` |

### `POST /api/v1/images/{imageId}/complete`

인증 회원이 Presigned PUT 업로드를 마친 뒤 호출한다. 서버는 S3 객체의 크기, MIME type과
파일 시그니처를 검증하고 성공하면 이미지 상태를 `READY`로 전환한다.

```json
{
  "success": true,
  "code": "COMMON200_1",
  "message": "성공적으로 요청을 처리했습니다.",
  "data": {
    "imageId": "5f8ca021-02fe-4fba-982f-8de356789abc",
    "status": "READY"
  }
}
```

### `POST /api/v1/images/{imageId}/upload-request`

아직 `PENDING`인 본인 이미지의 5분 유효 Presigned PUT URL을 재발급한다. 응답 계약은 최초
발급 응답과 동일하며 DB의 이미지 ID와 object key는 바꾸지 않는다.

---

## 19. 분석 리포트 생명주기

### `POST /api/v1/analyses`

`Content-Type: application/json` 요청은 인증 회원 본인이 소유하고 `status=READY`인
`ANALYSIS_ORIGINAL` 이미지 ID를 받는다.

```json
{
  "imageId": "5f8ca021-02fe-4fba-982f-8de356789abc"
}
```

성공 시 `201 Created`로 `reportId`, signed `imageUrl`, `matchPercentage`, `suggestedTags`를
반환한다. 기존 `multipart/form-data`의 `image` part 계약은 클라이언트 전환 기간에만 유지한다.

| 조건 | HTTP | code |
| --- | ---: | --- |
| 이미지가 없거나 요청 회원 소유가 아님 | 404 | `IMAGE404_1` |
| 이미지 목적이 `ANALYSIS_ORIGINAL`이 아니거나 상태가 `READY`가 아님 | 409 | `IMAGE409_1` |

### `GET /api/v1/analyses?cursor=&pageSize=20`

인증 회원의 삭제되지 않은 리포트를 `reportId` cursor 기준으로 조회한다. `pageSize`는 1~50이며
응답은 `items`, `nextCursor`, `hasNext`, `pageSize`를 포함한다.

### `GET /api/v1/analyses/{reportId}`

본인의 삭제되지 않은 리포트 상세와 확정 태그, 추천 그룹을 반환한다. private 이미지 URL은
10분 유효한 CloudFront signed URL이다.

### `PATCH /api/v1/analyses/{reportId}/recommendations`

`confirmedTagIds` 한 개 이상과 0~100의 `matchPercentage`를 받아 태그를 확정하고 추천 응답을
갱신한다.

### `DELETE /api/v1/analyses/{reportId}`

리포트를 soft delete 처리한다. 삭제된 리포트는 목록과 상세 조회에서 제외되며 연결된 이미지는
보존 기간 이후 정리 작업의 대상이 된다.
