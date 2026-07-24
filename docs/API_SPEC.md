# Recommendation/Product 및 이미지 업로드 API 계약

## 0. 문서 정보

| 항목 | 값 |
| --- | --- |
| 기준일 | 2026-07-24 |
| 적용 범위 | Recommendation/Product MVP: 추천 결과 생성, 상품 검색·상세, 쇼핑 API 연동, 추천 상품 저장, 카테고리별 그룹핑. Image/Analysis는 기존 계약 참조 |
| API prefix | `/api/v1` |
| 기준 응답 | `ApiResponse<T>`의 `success`, `code`, `message`, `data` |
| 연동 참고 | Auth `#20`의 `AuthMember` principal과 현재 `AnalysisReport`의 분석 결과를 입력으로 사용 |
| 문서 성격 | Issue `#98` 기준 계약. 실제 Controller·Service·Entity 변경은 후속 기능 이슈에서 진행 |

이 문서는 제공된 임시 API 명세의 URI와 화면 흐름을 최대한 유지하면서 현재 backend 구조와
확정된 Recommendation/Product 정책을 구체화한다. 이 범위 밖 Auth, Member, Tag, Trend,
Lookbook, Closet API는 해당 도메인의 명세를 따른다.

### 요구사항 반영 범위

| 요구 | API 반영 |
| --- | --- |
| 쇼핑몰 파트너 미확정 | partner 전용 ID·URI를 공개 계약에 넣지 않고 provider-neutral token/Product 사용 |
| 비용 최소화 | pagination, Top 5, live lookup 최소화, fixture fallback을 전제로 함 |
| 추천 생성 | 기존 분석 결과를 읽어 similarity-only 추천 결과를 생성하며 분석 태그를 변경하지 않음 |
| 추천 상품 저장(찜) | 추천 현재 세트와 분리된 `/members/me/saved-products` 정의 |
| 범위 제한 | 원상품 후보 탐색·기준 가격 확정·공개 확정 태그 반영 API는 제외 |
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

### 2.2 추천 입력

- 추천 생성 API는 인증 회원이 소유한 기존 `AnalysisReport`의 분석 태그를 읽기만 한다.
- 추천 요청은 `confirmedTagIds`, `matchPercentage`, `memberId`를 받지 않는다.
- 추천 생성 과정에서 `ReportTag`, 분석 결과, 이미지 상태를 변경하지 않는다.
- 표시 가능한 분석 태그가 없거나 분석이 완료되지 않은 리포트는 추천을 생성하지 않는다.
- 원상품 후보 탐색, 원상품 선택, 기준 가격 확정은 이번 Recommendation/Product 범위가 아니다.

### 2.3 유사도 점수

- 모든 `similarityScore`는 0~100으로 정규화한다.
- `finalScore`는 이번 범위에서 `similarityScore`와 같다.
- 상품 가격은 검색·상세·찜 화면 표시용이며 추천 점수나 가성비 문구에 사용하지 않는다.
- 공급자 raw score를 그대로 내부 점수로 저장하지 않는다.
- 공급자별 normalization과 분석 태그 fallback은 쇼핑 API Adapter 구현 이슈에서 contract test로 고정한다.

### 2.4 동점 정렬

```text
similarityScore DESC
-> sourceApi ASC
-> externalProductId ASC
-> candidateFingerprint ASC (externalProductId가 없는 요청 내 후보)
-> productId ASC
```

`candidateFingerprint`는 서버 내부 동점·중복 제거 값이며 API 응답 필드가 아니다.

### 2.5 상품 표시와 데이터 상태

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

### 2.6 추천 상태

| 구분 | 값 | 의미 |
| --- | --- | --- |
| `RecommendationStatus` | `NOT_GENERATED` | 현재 추천 세트가 없음 |
| `RecommendationStatus` | `CURRENT` | 현재 분석 결과를 입력으로 생성된 세트 |
| `RecommendationStatus` | `STALE` | 현재 분석 결과 version과 다른 입력으로 생성된 기존 세트 |

`AnalysisReport`의 마지막 성공 result metadata로 상태를 계산하므로 추천 항목이 0개여도
`CURRENT`와 `NOT_GENERATED`를 구분한다.

### 2.7 Candidate token

- 외부 검색 후보를 DB에 자동 저장하지 않는다.
- 상세·찜을 지원할 수 있는 raw 후보에는 서버가 서명한 opaque `candidateToken`을 반환한다.
- token에는 공급자 identity, capability, 만료 시각, 서버 검증용 서명이 포함될 수 있지만
  클라이언트 계약은 문자열 하나뿐이다.
- token 유효시간은 기본 10분이며 운영 설정으로 조정할 수 있다.
- token은 발급 당시 인증 회원과 `PRODUCT_MATERIALIZATION` 목적에 묶는다.
- 상품 검색 token은 상세·찜이 가능한 후보에만 발급한다.
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

### 3.1 Recommendation/Product 현재 범위

| Method | Endpoint | 이름 | 인증 |
| --- | --- | --- | --- |
| GET | `/api/v1/products` | 상품 검색 | 필수 |
| POST | `/api/v1/product-references` | 외부 상품 후보 materialize | 필수 |
| GET | `/api/v1/products/{productId}` | 상품 상세 | 필수 |
| POST | `/api/v1/analyses/{reportId}/recommendations` | 기존 분석 결과 기반 추천 생성 | 필수 |
| PUT | `/api/v1/members/me/saved-products/{productId}` | 추천 상품 저장 | 필수 |
| GET | `/api/v1/members/me/saved-products` | 저장 상품 목록 | 필수 |
| DELETE | `/api/v1/members/me/saved-products/{productId}` | 저장 상품 해제 | 필수 |

`POST /product-references`는 외부 검색 결과를 상세·저장 가능한 내부 Product로 전환하기 위한
상품 검색·상세 지원 endpoint다. 별도의 원상품 선택 기능을 의미하지 않는다.

### 3.2 기존 Image/Analysis 기준 API

아래 API는 이 문서에 함께 기록된 기존 계약이며 Issue `#98`의 Recommendation/Product 구현
범위에는 포함하지 않는다.

| Method | Endpoint | 이름 | 인증 |
| --- | --- | --- | --- |
| POST | `/api/v1/images/upload-requests` | 이미지 Presigned POST 정보 발급 | 필수 |
| POST | `/api/v1/images/{imageId}/complete` | 이미지 업로드 완료 검증 | 필수 |
| POST | `/api/v1/images/{imageId}/upload-request` | 이미지 업로드 URL 재발급 | 필수 |
| POST | `/api/v1/analyses` | 이미지 기반 분석 리포트 생성 | 필수 |
| GET | `/api/v1/analyses` | 내 분석 리포트 목록 | 필수 |
| GET | `/api/v1/analyses/{reportId}` | 기존 분석 상세와 추천 fragment 조회 | 필수 |
| DELETE | `/api/v1/analyses/{reportId}` | 분석 리포트 삭제 | 필수 |

Issue `#98`은 `GET /analyses/{reportId}` 자체를 새로 구현하지 않고, 기존 상세 응답에
카테고리별 `recommendationGroups` fragment를 추가하는 계약만 정의한다.

---

## 4. 상품 검색

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
        "saveSupported": true
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

## 5. 외부 후보 materialize

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

## 6. 상품 상세

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

## 7. 추천 결과 생성

### 목표 계약 — `POST /api/v1/analyses/{reportId}/recommendations`

후속 기능 이슈에서 인증 회원의 기존 분석 결과를 읽어 현재 추천 세트를 생성하거나 교체한다.
목표 Request body는 없으며 분석 태그, `matchPercentage`, 이미지 상태를 변경하지 않는다.

이 절은 후속 기능 이슈에서 구현할 **목표 계약**이다. 현재 `develop`의 legacy
`PATCH /api/v1/analyses/{reportId}/recommendations`와 `ConfirmTagsRequest`는 이 문서만으로
변경되지 않으며, Controller·Service·테스트를 함께 전환한 뒤 이 POST 계약을 제공한다.

### Response `200 OK`

아래 예시는 TOP에만 결과가 있고 나머지 7개 그룹은 비어 있는 경우다.

```json
{
  "success": true,
  "code": "COMMON200_1",
  "message": "성공적으로 요청을 처리했습니다.",
  "data": {
    "reportId": 501,
    "analysisTags": ["미니멀", "와이드핏", "베이지톤"],
    "scoreVersion": "SIMILARITY_V1",
    "recommendationStatus": "CURRENT",
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
            "finalScore": 82.00,
            "reasonCodes": ["HIGH_SIMILARITY"],
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

### 생성·교체 규칙

```text
1. 리포트 소유권, 분석 완료 상태, 표시 가능한 분석 태그를 검증한다.
2. 현재 analysis result version과 정렬된 분석 태그 ID를 입력 snapshot으로 캡처한다.
3. DB transaction 밖에서 쇼핑 API 후보 조회, 정규화, category mapping, 중복 제거,
   similarity score 계산을 수행한다.
4. 짧은 write transaction에서 입력 version을 다시 비교한다.
5. 입력이 같을 때만 기존 현재 세트를 새 세트로 원자적으로 교체하고 결과 metadata를 갱신한다.
```

- 외부 호출을 DB transaction 안에서 수행하지 않는다.
- 새 세트 저장에 성공하기 전 기존 세트를 삭제하지 않는다.
- 입력 version이 달라지면 `RECOMMENDATION409_1`을 반환하고 기존 세트를 유지한다.
- 외부 공급자가 모두 실패하면 `PRODUCT503_1`이며 기존 세트를 유지한다.
- materialize할 수 없는 후보는 현재 세트에서 제외하고 warning을 남긴다.
- 후보가 모두 저장 정책상 materialize 불가하면 `PRODUCT503_3`이며 기존 세트를 유지한다.
- 각 그룹은 최대 5개이며 8개 그룹을 고정 순서로 반환한다.
- 추천 세트 교체는 `SavedProduct`를 변경하지 않는다.

---

## 8. 분석 결과와 추천 조회 연동

### `GET /api/v1/analyses/{reportId}`

기존 Analysis 상세 응답에 아래 Recommendation fragment를 포함한다. Analysis 상세 조회 자체의
소유권·soft delete 계약은 Analysis 도메인의 기존 명세를 따른다.

```json
{
  "success": true,
  "code": "COMMON200_1",
  "message": "성공적으로 요청을 처리했습니다.",
  "data": {
    "reportId": 501,
    "tags": ["미니멀", "와이드핏", "베이지톤"],
    "recommendationStatus": "CURRENT",
    "scoreVersion": "SIMILARITY_V1",
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
- 현재 분석 결과 version과 마지막 생성 입력 version이 다르면 `STALE`로 반환한다.
- 일부 live hydrate 실패는 가능한 항목을 반환하고 `partial=true`, `warnings`에 reason code를 둔다.
- 전체를 표시할 수 없으면 provider 오류를 반환하되 저장 상품 관계나 현재 세트를 삭제하지 않는다.

---

## 9. 추천 상품 저장

### 9.1 저장 생성

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

### 9.2 저장 목록

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

### 9.3 저장 해제

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

## 10. 오류 계약

| Java 식별자 | Wire code | HTTP | 적용 조건 |
| --- | --- | ---: | --- |
| `UNAUTHORIZED` | `COMMON401_1` | 401 | 인증이 없거나 유효하지 않음 |
| `VALIDATION_ERROR` | `COMMON400_2` | 400 | 필드 형식·범위·필수값 위반 |
| `ANALYSIS_REPORT_NOT_FOUND` | `ANALYSIS404_1` | 404 | 리포트가 없거나 현재 회원 소유가 아님 |
| `ANALYSIS_NOT_READY` | `ANALYSIS409_1` | 409 | 추천 입력으로 사용할 수 없는 분석 상태 |
| `PRODUCT_NOT_FOUND` | `PRODUCT404_1` | 404 | 내부 Product가 없음 |
| `PRODUCT_REFERENCE_INVALID` | `PRODUCT422_1` | 422 | candidate token 서명·형식·만료 오류 |
| `PRODUCT_REFERENCE_UNSUPPORTED` | `PRODUCT422_2` | 422 | 안정 identity와 허용 snapshot 전략이 모두 없음 |
| `PRODUCT_PROVIDER_RESPONSE_INVALID` | `PRODUCT502_1` | 502 | 공급자 응답을 계약대로 해석할 수 없음 |
| `PRODUCT_PROVIDER_UNAVAILABLE` | `PRODUCT503_1` | 503 | timeout, 5xx, 연결 실패 |
| `PRODUCT_PROVIDER_QUOTA_EXCEEDED` | `PRODUCT503_2` | 503 | 429 또는 공급자 quota 초과 |
| `PRODUCT_PROVIDER_PERSISTENCE_UNSUPPORTED` | `PRODUCT503_3` | 503 | 후보를 허용된 방식으로 하나도 materialize할 수 없음 |
| `RECOMMENDATION_INPUT_CHANGED` | `RECOMMENDATION409_1` | 409 | 외부 호출 중 분석 결과 version이 변경됨 |
| `IMAGE_UNSUPPORTED_CONTENT_TYPE` | `IMAGE400_1` | 400 | JPEG, PNG, WebP 이외의 업로드 MIME type |

정책:

- 타인 리포트에 403을 반환하지 않는다.
- 상품 가격은 표시 데이터이며 가격 없음·통화 차이는 추천 생성 오류가 아니다.
- 저장 상품 DELETE는 관계가 없어도 성공한다.
- provider 오류 응답에는 API key, 원문 요청 URL, candidate token, 외부 원문 body를 넣지 않는다.
- domain ErrorCode 구현 전에는 이 표의 식별자와 wire code를 동시에 임의 변경하지 않는다.

---

## 11. 외부 공급자와 저장 정책

### 11.1 Port 경계

```text
ProductCatalogPort             # 상품 검색·상세·추천 후보 조회·live lookup
AffiliateLinkPort              # 제휴 링크를 실제 채택할 때만
```

Controller와 Service는 Shopify 등 공급자 이름을 DTO 계약에 노출하지 않는다. 실제 운영 Adapter의
채택, 가격, quota는 공식 근거가 확정된 뒤 별도 운영 결정으로 기록한다.

### 11.2 검색·저장 분리

- 상품 검색은 DB write를 하지 않는다.
- `/product-references`, 추천 결과 materialization, 사용자 저장 요청만 명시적으로 저장한다.
- 공급자 정책이 snapshot 저장을 금지하면 provider identity만 저장하고 응답 시 live lookup한다.
- 안정 identity가 없지만 snapshot 저장이 명시적으로 허용되면 내부 UUID 전략을 사용할 수 있다.
- identity와 snapshot 모두 허용되지 않으면 상세·저장을 비활성화한다.
- 현재 추천 세트에는 materialize 가능한 Product만 포함하고 ephemeral 추천 행은 두지 않는다.
- 외부 가격은 상품 표시 데이터로만 취급하며 기준 가격이나 가성비 근거로 재해석하지 않는다.

### 11.3 장애와 fallback

- 실제 외부 API를 CI에서 호출하지 않고 fixture Adapter를 사용한다.
- 401/403 인증 오류, quota 403/429, 5xx, timeout, invalid body를 구분한다.
- retry는 멱등 read와 공급자가 허용한 오류만 대상으로 한다.
- provider 장애가 DB transaction을 길게 유지하지 않는다.
- feature flag off 상태에서도 fixture 또는 명시적 unavailable 응답으로 기동한다.

### 11.4 운영 채택 근거

- 비용·quota·저장 권한의 공식 근거가 확인되기 전에는 운영 Adapter 채택이나 장기 저장을
  허용하지 않는다.
- 지원 채널 답변 대기 시간과 escalation 절차는 로컬 로드맵과 공급자 PoC 이슈에서 관리하며
  API wire 계약에는 포함하지 않는다.

---

## 12. DTO 이름

| 역할 | 이름 |
| --- | --- |
| 상품 검색 응답 | `ProductSearchResponse` |
| 외부 후보 materialize | `ProductReferenceCreateRequest`, `ProductReferenceResponse` |
| 상품 상세 응답 | `ProductDetailResponse` |
| 추천 생성 응답 | `RecommendationResultResponse` |
| 추천 그룹 | `RecommendationGroupResponse` |
| 저장 상품 응답/목록 | `SavedProductResponse`, `SavedProductListResponse` |
| 이미지 업로드 URL 요청/응답 | `ImageUploadRequest`, `ImageUploadResponse` |

추천 생성은 body가 없으므로 별도 Request DTO를 만들지 않는다. Controller는 Entity나 외부 provider
response를 직접 반환하지 않는다.

---

## 13. Auth / Analysis 연동 체크

### Auth

- principal 타입은 `AuthMember`이며 `@AuthenticationPrincipal AuthMember`로 주입받는다.
- member ID는 `authMember.getMember().getId()`로 얻고 Request에 임시 member ID를 추가하지 않는다.
- 모든 Product/Recommendation endpoint에 인증 정책을 적용한다.
- 인증 실패 401과 리포트 존재 숨김 404를 contract test로 고정한다.

### Analysis

- 실제 Controller prefix와 소유자 조회 계약은 현재 Analysis 구현을 따른다.
- Recommendation은 완료된 기존 분석 결과와 ReportTag를 읽기 전용 입력으로 사용한다.
- 추천 생성 API는 태그·매칭값을 교체하지 않으며 Analysis 저장 책임을 가져오지 않는다.
- 분석 결과 version을 외부 호출 전후에 비교해 늦게 끝난 요청이 새 입력을 덮어쓰지 못하게 한다.
- 이미지 URL을 외부 공급자가 읽어야 한다면 storage 계층의 read stream 또는 짧은 signed URL
  계약을 쇼핑 API Adapter 구현 이슈에서 명시한다.
- `GET /analyses/{reportId}`의 기존 필드를 유지하며 recommendation fragment만 합친다.

---

## 14. 구현 Phase 검증 기준

- [ ] 모든 endpoint 인증과 소유권 404가 MockMvc로 검증됨
- [ ] 요청 DTO validation과 공통 응답 envelope가 검증됨
- [ ] 상품 검색 GET이 DB write를 하지 않음
- [ ] candidate token 변조·만료·재사용 정책이 검증됨
- [ ] 추천 생성이 AnalysisReport와 ReportTag를 변경하지 않음
- [ ] 유사도 정규화와 `finalScore=similarityScore`가 순수 단위 테스트로 검증됨
- [ ] 8개 그룹 순서, 그룹별 Top 5, 빈 그룹 포함이 검증됨
- [ ] 외부 호출이 DB transaction 밖에서 실행됨
- [ ] 저장 실패·분석 입력 변경 시 기존 현재 세트가 유지됨
- [ ] 저장 상품 PUT/DELETE 멱등성과 추천 세트 독립성이 검증됨
- [ ] provider 장애 중 저장 해제와 partial 목록이 동작함
- [ ] API key, candidate token, 사용자 이미지 URL 원문이 로그에 노출되지 않음
- [ ] API 변경 PR에서 이 문서가 함께 갱신됨

---

## 15. 이미지 Presigned Upload

### `POST /api/v1/images/upload-requests`

인증 회원이 브라우저에서 private S3 버킷으로 이미지를 직접 업로드할 수 있도록 5분 유효한
Presigned POST 정보를 발급한다. Request의 회원 식별자는 받지 않으며 JWT principal의 회원을
소유자로 사용한다.

### Request

```json
{
  "purpose": "ANALYSIS",
  "contentType": "image/jpeg",
  "fileSize": 3145728
}
```

| 필드 | 필수 | 계약 |
| --- | --- | --- |
| `purpose` | 예 | `ANALYSIS`, `LOOKBOOK`, `PROFILE` |
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
    "uploadUrl": "https://fitback-prod-images.example/",
    "uploadMethod": "POST",
    "uploadFields": {
      "key": "images/analysis/42/2026/07/5f8ca021-02fe-4fba-982f-8de356789abc.jpg",
      "Content-Type": "image/jpeg",
      "success_action_status": "204",
      "policy": "encoded-policy",
      "x-amz-algorithm": "AWS4-HMAC-SHA256",
      "x-amz-credential": "...",
      "x-amz-date": "20260724T000000Z",
      "x-amz-signature": "..."
    },
    "expiresAt": "2026-07-24T00:05:00Z"
  }
}
```

클라이언트는 응답의 `uploadUrl`과 `uploadFields`를 사용해 S3로 `multipart/form-data` POST를
전송한다. `uploadFields`의 모든 필드를 `FormData`에 넣고 파일은 마지막 `file` 필드로 추가한다.
브라우저가 boundary를 포함한 `Content-Type`을 자동 설정해야 하므로 S3 요청의
`Content-Type: multipart/form-data` 헤더를 직접 지정하지 않는다.

POST policy는 bucket, 정확한 object key, MIME, 성공 상태, 5분 만료를 제한한다.
`content-length-range`의 최소·최대값은 모두 요청 `fileSize`로 설정하므로 다른 크기의 파일로
교체하면 S3가 업로드를 거부한다. EC2 역할 같은 임시 자격 증명으로 발급할 때는
`uploadFields`에 `x-amz-security-token`이 추가되며 프론트는 이 필드도 그대로 전송한다.

`uploadUrl`, `uploadFields`, Presigned 서명 값은 일시적인 업로드 권한 정보이므로 저장하거나
로그에 남기지 않는다. 응답에는 `requiredHeaders`와 `imageUrl`을 포함하지 않는다. 업로드 직후
화면 미리보기는 브라우저의 local object URL을 사용한다.

### 상태와 검증

- API의 논리 초기 상태는 `PENDING_UPLOAD`이며 `visibility=PRIVATE`로 시작한다.
- Issue #95 호환 릴리스 A에서는 자동 rollback을 위해 DB에는 legacy `PENDING`을 기록하고, reader/domain check는 `PENDING`과 `PENDING_UPLOAD`를 모두 논리 `PENDING_UPLOAD`로 처리한다.
- 객체 key는 `images/{purpose}/{memberId}/{yyyy}/{MM}/{imageId}.{ext}` 형식으로 서버가 생성하며 클라이언트 파일명은 사용하지 않는다.
- 이 API가 받은 `fileSize`는 발급 전 요청 검증용이다. 업로드 완료 API가 S3 metadata,
  실제 파일 시그니처와 크기를 다시 검증한다.
- 분석 리포트가 생성되면 API 목적 `ANALYSIS` 또는 호환 저장값 `ANALYSIS_ORIGINAL` 이미지를 `ACTIVE`로 전환한다.
- 24시간 이상 도메인에 연결되지 않은 `PENDING`/`PENDING_UPLOAD`/`READY`/`REJECTED` 이미지는 정리 작업자가 S3 객체와 DB 상태를 정리한다.

### 오류

| 조건 | HTTP | code |
| --- | ---: | --- |
| 인증 없음 또는 유효하지 않은 토큰 | 401 | `COMMON401_1` |
| 필수값, enum, 파일 크기 위반 | 400 | `COMMON400_1` 또는 `COMMON400_2` |
| 지원하지 않는 MIME type | 400 | `IMAGE400_1` |
| Presigned POST 정보 생성 실패 | 500 | `IMAGE500_1` |

### `POST /api/v1/images/{imageId}/complete`

인증 회원이 Presigned POST 업로드를 마친 뒤 호출한다. 서버는 S3 객체의 크기, MIME type과
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

아직 논리 `PENDING_UPLOAD`인 본인 이미지의 5분 유효 Presigned POST 정보를 재발급한다. 호환 릴리스 A에서는 DB의 `PENDING`도 같은 상태로 처리한다. 응답 계약은
최초 발급 응답과 동일하며 DB의 이미지 ID와 object key는 바꾸지 않는다.

---

## 16. 분석 리포트 생명주기

### `POST /api/v1/analyses`

`Content-Type: application/json` 요청은 인증 회원 본인이 소유하고 `status=READY`인
`ANALYSIS` 목적의 이미지 ID를 받는다. 호환 저장값 `ANALYSIS_ORIGINAL`도 같은 분석 목적 이미지로 처리한다.

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
| 이미지 목적이 `ANALYSIS`가 아니거나 상태가 `READY`가 아님 | 409 | `IMAGE409_1` |

### `GET /api/v1/analyses?cursor=&pageSize=20`

인증 회원의 삭제되지 않은 리포트를 `reportId` cursor 기준으로 조회한다. `pageSize`는 1~50이며
응답은 `items`, `nextCursor`, `hasNext`, `pageSize`를 포함한다.

### `GET /api/v1/analyses/{reportId}`

본인의 삭제되지 않은 리포트 상세와 확정 태그, 추천 그룹을 반환한다. private 이미지 URL은
10분 유효한 CloudFront signed URL이다.

### 목표 계약 — `POST /api/v1/analyses/{reportId}/recommendations`

후속 기능 이슈의 목표 계약이다. 기존 분석 결과를 읽어 추천 현재 세트를 생성한다. Request
body는 없으며 분석 태그와 `matchPercentage`를 변경하지 않는다. 현재 legacy PATCH 구현과
전환 조건을 포함한 세부 계약은 7절을 따른다.

### `DELETE /api/v1/analyses/{reportId}`

리포트를 soft delete 처리한다. 삭제된 리포트는 목록과 상세 조회에서 제외되며 연결된 이미지는
보존 기간 이후 정리 작업의 대상이 된다.
