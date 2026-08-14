# FIT-BACK Backend API 계약

## 0. 문서 정보

| 항목 | 값 |
| --- | --- |
| 최초 작성일 | 2026-07-26 |
| 최종 검증일 | 2026-08-13 |
| 검증 기준 | `develop` `00c433076262e2b884b91d03170d933cf1501bf0` 기반 issue #368 변경 |
| 적용 범위 | 현재 Controller가 제공하는 Auth, Member, Image, Analysis, Recommendation, Product, Lookbook, Trend, Tag, Content Search, Closet, Notification API |
| API prefix | `/api/v1` |
| 기준 응답 | `ApiResponse<T>`의 `success`, `code`, `message`, `data` |
| 연동 참고 | `AuthMember` principal과 현재 `AnalysisReport`의 분석 결과를 입력으로 사용 |
| 문서 성격 | 구현 코드와 함께 갱신하는 현재 API 계약. 세부 request/response schema의 최종 기준은 실행 중인 OpenAPI(`/swagger-ui/index.html`, `/v3/api-docs`)와 DTO다. |

이 문서는 현재 backend 구조와 확정된 API 정책을 설명한다. Recommendation/Product/Image/Analysis는
상세 계약을 유지하고, 나머지 도메인은 현재 endpoint와 핵심 DTO를 빠짐없이 찾을 수 있는 색인을
제공한다. 문서와 실행 중인 OpenAPI가 다르면 해당 `develop` 커밋의 Controller와 DTO를 우선한다.

### 요구사항 반영 범위

| 요구 | API 반영 |
| --- | --- |
| 쇼핑몰 파트너 미확정 | partner 전용 ID·URI를 공개 계약에 넣지 않고 provider-neutral token/Product 사용 |
| 비용 최소화 | pagination, Top 10, live lookup 최소화, fixture fallback을 전제로 함 |
| 추천 생성 | 기존 분석 태그 또는 요청에서 확정한 기본·직접 입력 태그와 매칭값으로 추천 결과 생성 |
| 추천 상품 저장(찜) | 추천 현재 세트와 분리된 `/members/me/saved-products` 정의 |
| 범위 제한 | 원상품 후보 탐색·기준 가격 검증 port는 fixture 구현만 존재하며 현재 공개 API 흐름에서는 활성화하지 않음 |
| 3D 가상 피팅 제외 | 이 문서와 Recommendation/Product MVP endpoint에서 제외 |
| 외부 데이터 정책 준수 | candidateToken, materialization, snapshot/identity-only 경계 정의 |

---

## 1. 공통 계약

### 1.1 인증과 소유권

- 기본 정책은 JWT 인증과 `ROLE_USER` 또는 `ROLE_ADMIN` 권한 필수이며,
  `SecurityConfig`에 명시된 공개 경로만 예외다.
- 인증 없이 허용되는 API는 회원가입·로그인·비밀번호 재설정·토큰 refresh/exchange,
  Kakao OAuth2 진입/콜백, health, 그리고 `GET /api/v1/trends/**`, `GET /api/v1/tags/**`,
  `GET /api/v1/content-search`, `GET /api/v1/lookbooks`, `GET /api/v1/lookbooks/{id}`다.
- 상품 검색·상세, 이미지, 분석, 추천, 저장, 회원, 알림과 모든 변경 API는 인증 필수다.
- `Authorization: Bearer {accessToken}`을 사용한다.
- Request body, path, query에서 `memberId`를 받지 않는다.
- 회원 ID는 현재 인증 principal인 `AuthMember`에서 얻는다.
- 권한이 없는 인증 객체의 비공개 API 접근은 `403 COMMON403_1`로 거부한다.
- 리포트 기반 API는 인증 회원이 소유한 `AnalysisReport`에만 접근한다.
- 이미지·알림·저장 상품 등 회원 리소스도 서비스 계층에서 소유권을 검사한다.
- 타인 소유 리포트와 존재하지 않는 리포트는 모두 404로 처리해 리소스 존재 여부를 숨긴다.
- 임시 회원 헤더나 임의의 `memberId` DTO를 만들지 않는다.
- 공개 조회 응답의 `isSaved`, `isLiked`, `isOwner`는 필드가 항상 직렬화되며 익명 요청에서는
  `false`다.

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
실패 응답의 `data`는 오류 종류와 관계없이 항상 `null`이다.
지원하지 않는 `Accept`는 `406 COMMON406_1`, 지원하지 않는 요청 `Content-Type`은
`415 COMMON415_1`로 응답하며 두 경우 모두 JSON 공통 실패 응답을 사용한다.

### 1.5 금액과 시간

- 금액은 JSON number, Java `BigDecimal`, DB `DECIMAL(19,2)`를 사용한다.
- 통화는 ISO 4217 대문자 3자리다. 예: `KRW`.
- 외부 가격의 의미를 추정하지 않고 `LIST`, `CURRENT`, `SALE`을 구분한다.
- `Instant` 기반 필드는 UTC ISO 8601(`2026-07-18T03:00:00Z`)로 반환한다.
- `LocalDateTime` 기반 분석 저장 시각, 룩북 작성 시각, 알림 시각은 offset 없는 ISO 8601
  문자열로 직렬화된다. 클라이언트는 이 필드에 임의로 `Z`를 붙이지 않는다.
- MVP는 환율, 배송비, 관세를 계산하지 않는다.

### 1.6 페이지네이션

| API | cursor | pageSize |
| --- | --- | --- |
| 상품 검색, 저장 상품 | 서버 발급 opaque `String` | 기본 10, 1~20 |
| 분석 저장 목록 | `ClosetSave.saveId` 기반 `Long` | 기본 20, 1~50 |
| 룩북 목록 | `lookbookId` 기반 `Long` | 기본 20, 1~20 |
| 알림 목록 | `notificationId` 기반 `Long` | 기본 20, 1~50 |
| 트렌드, 통합 클로젯 | `Long` | 고정 10 |

목록 응답은 `items`, `nextCursor`, `hasNext`, `pageSize`를 포함하며 `hasNext=false`이면
`nextCursor=null`이다. opaque cursor는 클라이언트가 해석하거나 직접 생성하지 않는다.

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
- 각 그룹은 최대 10개다.
- 항목이 없는 그룹도 `items: []`로 반환한다.
- 외부 카테고리는 Adapter에서 위 enum으로 매핑한다.
- 매핑할 수 없는 구매 가능 패션 상품은 `OTHER`다.

### 2.2 추천 입력

- Request body를 생략하면 기존 `AnalysisReport`의 표시 가능한 분석 태그와 현재 매칭값을 사용한다.
- 신규 `AnalysisReport`의 현재 매칭값은 50으로 시작한다. 사용자가 이후 명시적으로 변경한 값은
  해당 리포트의 현재 값으로 유지한다.
- Request body를 보내면 `confirmedTagIds`, `customTagNames`, `matchPercentage`를 하나의 추천 입력으로
  확정한다. `memberId`는 받지 않는다.
- 기본 태그와 직접 입력 태그는 중복 제거 후 합계 1~8개이며, 직접 입력 태그는 각 1~50자다.
- `matchPercentage`는 0~100 정수다.
- 같은 정규화 입력을 다시 보내면 리포트의 `recommendationInputRevision`을 증가시키지 않는다.
- 다른 입력을 확정하면 기본 태그, 직접 입력 태그, 매칭값을 함께 교체하고 revision을 한 번 증가시킨다.
- 분석 이미지 상태는 추천 생성 과정에서 변경하지 않는다.
- body를 생략한 요청은 표시 가능한 기존 분석 태그가 없으면 추천을 생성하지 않는다.
- 원상품 후보 탐색, 원상품 선택, 기준 가격 확정은 이번 Recommendation/Product 범위가 아니다.

### 2.3 백엔드 영속 임시 유사도 점수

이 절의 `similarityScore`와 `finalScore`는 서버 호환성과 추천 이력 저장을 위한 임시·내부
계약이다. 실제 사용자에게 노출하는 추천 순서는 7절의 `browserReranking` 후보를 브라우저가
Fashion-CLIP으로 재평가한 결과를 기준으로 한다.

- 모든 `similarityScore`는 0~100으로 정규화한다.
- `finalScore`는 이번 범위에서 `similarityScore`와 같다.
- 상품 가격은 검색·상세·찜 화면 표시용이며 추천 점수나 가성비 문구에 사용하지 않는다.
- 공급자 raw score를 그대로 내부 점수로 저장하지 않는다.
- `SILHOUETTE`, `MATERIAL`, `DETAIL`, `COLOR` 타입 분석 태그 중 상품명·브랜드·카테고리에
  포함된 태그로 0~100의 `tagMatchScore`를 계산한다. 백엔드 영속 점수에서 `COLOR`의 가중치는
  6이고 나머지 세 속성의 가중치는 각각 1이다.
- `STYLE` 타입과 직접 입력 태그는 점수 계산의 분자와 분모에서 제외한다.
- 태그 점수 계산 대상이 없으면 `tagMatchScore`는 100점이다.
- 백엔드 영속 점수의 `temporaryImageSimilarityScore`는 70점으로 고정하며 실제 Fashion-CLIP
  이미지 유사도나 사용자 노출 순위로 해석하지 않는다.
- 최종 `similarityScore`는 `temporaryImageSimilarityScore * 0.7 + tagMatchScore * 0.3`이며,
  소수 둘째 자리에서 `HALF_UP`으로 저장한다.
- 계산 대상 태그가 모두 일치하면 `FULL_ATTRIBUTE_MATCH`, 일부만 일치하면
  `PARTIAL_ATTRIBUTE_MATCH`, 하나도 일치하지 않으면 `NO_ATTRIBUTE_MATCH`를 사용한다.
- 계산 대상 태그가 없으면 `NO_SCORABLE_TAGS`를 사용한다. 이때 태그 점수는 100점이지만
  실제 매칭 결과가 아니므로 `HIGH_SIMILARITY`를 추가하지 않는다.
- 계산 대상 태그가 있고 `tagMatchScore`가 80점 이상이면 `HIGH_SIMILARITY`를 추가한다.
- `IMAGE_TAG_WEIGHTED_V1`과 `IMAGE_TAG_WEIGHTED_THR_V1`로 새로 생성되는 모든 추천 상품은
  최소 하나의 reason code를 가지며 code 목록은 정렬해 저장한다.
- 레거시 추천 항목의 `reason_codes` 저장값이 빈 문자열이면 조회 응답의 `reasonCodes`는 빈 배열이다.

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
- 현재 검색 API는 안정적인 provider identity가 있는 후보에만 token을 발급한다.
- `SNAPSHOT_UUID`와 `materializationKey` 컬럼은 향후 snapshot 저장이 허용되는 공급자를 위한
  스키마 예약이다. 현재 API는 unstable 후보에 token을 발급하지 않고 materialization 요청도
  `PRODUCT422_2`로 거부한다.
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

### 3.2 Image/Analysis/Lookbook API

| Method | Endpoint | 이름 | 인증 |
| --- | --- | --- | --- |
| POST | `/api/v1/images/upload-requests` | 이미지 Presigned POST 정보 발급 | 필수 |
| POST | `/api/v1/images/{imageId}/complete` | 이미지 업로드 완료 검증 | 필수 |
| POST | `/api/v1/images/{imageId}/upload-request` | 이미지 업로드 URL 재발급 | 필수 |
| POST | `/api/v1/analyses` | 이미지 기반 분석 리포트 생성 | 필수 |
| GET | `/api/v1/analyses` | 마이 클로젯에 저장한 분석 리포트 목록 | 필수 |
| GET | `/api/v1/analyses/{reportId}` | 기존 분석 상세와 추천 fragment 조회 | 필수 |
| PUT | `/api/v1/analyses/{reportId}/save` | 선택 상품을 포함한 분석 리포트 저장 | 필수 |
| DELETE | `/api/v1/analyses/{reportId}/save` | 분석 리포트 저장 해제 | 필수 |
| DELETE | `/api/v1/analyses/{reportId}` | 분석 리포트 삭제 | 필수 |
| POST | `/api/v1/lookbooks` | 업로드 이미지 또는 분석 추천 상품으로 룩북 생성 | 필수 |
| PUT | `/api/v1/lookbooks/{lookbookId}` | 룩북 전체 수정 | 필수 |
| POST | `/api/v1/lookbooks/{lookbookId}/reports` | 룩북 신고 | 필수 |
| DELETE | `/api/v1/lookbooks/{lookbookId}` | 룩북 soft delete | 필수 |
| POST | `/api/v1/lookbooks/{lookbookId}/likes` | 룩북 좋아요 | 필수 |
| DELETE | `/api/v1/lookbooks/{lookbookId}/likes` | 룩북 좋아요 취소 | 필수 |
| GET | `/api/v1/lookbooks` | 룩북 목록 | 선택(익명 허용) |
| GET | `/api/v1/lookbooks/{lookbookId}` | 룩북 상세 | 선택(익명 허용) |

### 3.3 Auth/Member/Notification/Content API

| Method | Endpoint | 이름 | 인증 |
| --- | --- | --- | --- |
| POST | `/api/v1/auth/sign` | 이메일 회원가입 | 불필요 |
| POST | `/api/v1/auth/login` | 이메일 로그인 | 불필요 |
| POST | `/api/v1/auth/password-reset/request` | 비밀번호 재설정 메일 요청 | 불필요 |
| PATCH | `/api/v1/auth/password-reset` | 비밀번호 재설정 | 불필요 |
| POST | `/api/v1/auth/token/refresh` | access/refresh token 재발급 | 불필요 |
| POST | `/api/v1/auth/token/exchange` | Kakao 임시 token 교환 | 불필요 |
| POST | `/api/v1/auth/logout` | 로그아웃 | 필수 |
| PATCH | `/api/v1/members/me` | 내 프로필 부분 수정 | 필수 |
| GET | `/api/v1/members/me/nickname-availability` | 닉네임 사용 가능 확인 | 필수 |
| PATCH | `/api/v1/members/me/password` | 비밀번호 변경 | 필수 |
| GET | `/api/v1/members/me` | 마이페이지 | 필수 |
| DELETE | `/api/v1/members/me` | 회원 탈퇴 | 필수 |
| PUT | `/api/v1/members/me/onboarding` | 온보딩 프로필 설정 | 필수 |
| PUT | `/api/v1/members/me/tags` | 관심 태그 교체 | 필수 |
| GET/PATCH | `/api/v1/members/me/notification-settings` | 알림 설정 조회/부분 수정 | 필수 |
| GET | `/api/v1/notifications` | 알림 목록 | 필수 |
| PATCH | `/api/v1/notifications/{notificationId}/read` | 알림 읽음 | 필수 |
| PATCH | `/api/v1/notifications/read` | 전체 알림 읽음 | 필수 |
| DELETE | `/api/v1/notifications/{notificationId}` | 알림 삭제 | 필수 |
| GET | `/api/v1/content-search` | 트렌드·룩북 통합 검색 | 선택(익명 허용) |
| GET | `/api/v1/trends`, `/api/v1/trends/{trendId}` | 트렌드 목록·상세 | 선택(익명 허용) |
| GET | `/api/v1/trends/{trendId}/lookbooks` | 트렌드 관련 룩북 | 선택(익명 허용) |
| GET | `/api/v1/tags` | 태그 목록 | 불필요 |
| POST/GET/DELETE | `/api/v1/closet-saves...` | 통합 클로젯 저장·목록·삭제 | 필수 |

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

`candidateToken`은 공백일 수 없으며 최대 4096자다.

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
- 현재는 `ProductIdentityHasher`가 `provider`, `externalProductId`, 선택 `externalVariantId`,
  선택 `merchantId`를 NUL 구분자로 정규화해 만든 SHA-256 hex `providerIdentityKey`와
  `UNIQUE(source_api, provider_identity_key)` 계약으로 재시도를 멱등 처리한다.
- 가능한 경우 live lookup으로 identity와 현재 상태를 재확인한다.
- 현재 `IDENTITY_ONLY` 방식은 provider identity와 내부 카테고리만 저장하며 표시 필드는 live
  lookup으로 가져온다.
- 클라이언트가 상품명, 가격, 이미지, 구매 URL을 body로 보내지 않는다.
- 안정 identity가 없으면 현재 구현에서는 `PRODUCT422_2`다.
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
- identity-only Product의 live lookup이 not found여도 DB 상태를 임의로 바꾸거나 hard delete하지
  않고 현재 요청을 `PRODUCT503_1`로 실패 처리한다.
- 찜 해제는 상세 조회 성공 여부와 무관하게 동작한다.

기본 런타임은 `SHOPPING_PROVIDER=fixture`, `SHOPIFY_ENABLED=false`다.
`SHOPPING_PROVIDER=shopify`, `SHOPIFY_ENABLED=true`와 Shopify client 설정을 함께 제공하면
`ShopifyGlobalCatalogAdapter`를 선택할 수 있다. CI와 기본 로컬 실행은 실제 외부 API를
호출하지 않으며 fixture로 검색 GET의 무저장, 회원·목적·10분 만료 candidate token, 안정
provider identity의 멱등 materialize와 상세 live lookup 계약을 검증한다.

---

## 7. 추천 결과 생성

### `POST /api/v1/analyses/{reportId}/recommendations`

Issue #119에서 인증 회원의 기존 분석 결과를 사용하거나 요청 body의 확정 입력을 먼저 반영한 뒤
현재 추천 세트를 생성하거나 교체한다. Request body는 선택 사항이다.

```json
{
  "confirmedTagIds": [11, 27],
  "customTagNames": ["출근룩"],
  "matchPercentage": 70
}
```

body를 보낼 때 세 필드는 모두 필수다. 기본 태그와 직접 입력 태그 합계는 중복 제거 후 1~8개다.
예시의 `70`은 사용자가 명시적으로 선택한 값이며 신규 분석의 서버 기본값이 아니다. 기존 body 없는
호출도 하위 호환으로 유지하며, 이 경우 리포트에 현재 저장된 `matchPercentage`를 응답하지만
임계값 필터는 적용하지 않는다. body에 기본값과 같은 `50`을 명시한 경우에는 50점 미만 후보를
제외하고 `IMAGE_TAG_WEIGHTED_THR_V1`로 저장한다.

상품 category는 클라이언트가 입력하지 않는다. 서버가 분석 리포트의 `garmentPiece`를 다음과
같이 내부 검색 category로 변환한다.

```text
TOP -> TOP
BOTTOM -> BOTTOM
DRESS -> DRESS
OUTER -> OUTER
```

변환된 category는 모든 상품 검색 요청에 전달되며, 공급자가 다른 category 후보를 반환해도
추천 후보와 저장 결과에서 제외한다. `garmentPiece`가 `NULL`인 기존 리포트는 body 유무와
관계없이 `ANALYSIS409_1`로 추천 생성을 거부한다.

Demo와 Prototype 분석기는 실제 AI 의류 분류 결과가 없는 흐름 검증용 구현이므로 신규 분석에
`GarmentPiece.TOP`을 고정 저장한다. Shopify 검색 요청의 category는 검색어 보강에만 사용하며,
상품 category 판정에는 공급자 categoryPath를 우선 사용하고 값이 없으면 상품명을 사용한다.

### Response `200 OK`

아래 예시는 TOP에만 결과가 있고 나머지 7개 그룹은 비어 있는 경우다.

```json
{
  "success": true,
  "code": "COMMON200_1",
  "message": "성공적으로 요청을 처리했습니다.",
  "data": {
    "reportId": 501,
    "analysisTags": ["미니멀", "와이드핏", "베이지"],
    "matchPercentage": 70,
    "scoreVersion": "IMAGE_TAG_WEIGHTED_THR_V1",
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
            "similarityScore": 79.00,
            "finalScore": 79.00,
            "reasonCodes": ["FULL_ATTRIBUTE_MATCH", "HIGH_SIMILARITY"],
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
    "browserReranking": {
      "category": "TOP",
      "candidates": [
        {
          "candidateId": "v1.opaque-member-bound-token",
          "imageUrl": "https://provider.example/items/100.jpg",
          "tagSimilarity": 0.50,
          "name": "오버핏 셔츠",
          "sellerName": "에이블리",
          "price": {
            "amount": 28900.00,
            "currency": "KRW",
            "type": "CURRENT",
            "observedAt": "2026-07-18T03:00:00Z"
          },
          "purchaseUrl": "https://mall.example/products/100"
        }
      ]
    },
    "partial": false,
    "warnings": []
  }
}
```

`browserReranking`은 추천 생성 POST 1회 응답에만 포함되는 browser handoff namespace다.
`candidates`는 `ImageComparisonCandidateSelector`가 선택한 기존
`ExternalProductCandidate`의 response-time snapshot이며 최대 30개다. `candidateId`는
기존 member-bound opaque candidate token이고, `imageUrl`, `tagSimilarity`, `name`,
`sellerName`, `price`(`ProductPriceResponse` 재사용), `purchaseUrl`만 표시용으로 전달한다.
Browser `tagSimilarity`는 eligible tag의 단순 일치 개수/전체 개수로 계산한 비가중 `[0,1]`
비율이다. 백엔드 영속 `tagMatchScore`에만 적용하는 `COLOR=6` 가중치를 사용하지 않는다.
판매자·가격·구매 URL이 없으면 해당 값은 `null`이며 임의의 기본값이나 URL을 만들지 않는다.
이 snapshot은 live Shopify 가격 보장이 아니며, browser는 candidate token resolve API나
추가 Shopify metadata lookup을 호출하지 않는다. Shopify GID, merchant identity, provider
internal ID, persisted productId는 이 contract에 노출하지 않는다.

실제 사용자에게 노출하는 추천 순서는 이 browser reranking 결과를 기준으로 한다. Browser는
이 응답의 후보 전체에 대해 현재 normalized Fashion-CLIP cosine과
`finalScore = imageSimilarity * 0.70 + tagSimilarity * 0.30`을 계산하고, threshold 없이
`finalScore DESC`로 `min(10, candidateCount)` relevance shortlist를 선택한다. 선택된
shortlist의 모든 후보가 유한하고 비교 가능한 `price.amount`를 가지며 동일 currency일 때만
`price.amount ASC`로 표시한다. 그 외에는 shortlist 전체의 기존 relevance 순서를 유지한다.
동일 가격은 `finalScore DESC`, 그 다음 original handoff index ASC다. Browser score는 backend에
저장하거나 submit하지 않는다.

### 생성·교체 규칙

```text
1. 리포트 소유권을 검증하고, body가 있으면 확정 태그와 매칭값을 write lock에서 멱등 반영한다.
2. 현재 입력 revision, 매칭값, 의류 category, 정렬된 기본·직접 입력 태그 key를 snapshot으로 캡처한다.
3. DB transaction 밖에서 의류 category를 포함한 쇼핑 API 후보 조회, category 재검증, 정규화, 중복 제거,
   similarity score 계산을 수행한다.
4. 짧은 write transaction에서 입력 version을 다시 비교한다.
5. 입력이 같을 때만 기존 현재 세트를 새 세트로 원자적으로 교체하고 결과 metadata를 갱신한다.
```

- 외부 호출을 DB transaction 안에서 수행하지 않는다.
- 새 세트 저장에 성공하기 전 기존 세트를 삭제하지 않는다.
- 입력 revision, 태그 key, 매칭값 또는 의류 category가 달라지면 `RECOMMENDATION409_1`을 반환하고
  기존 세트를 유지한다.
- body 요청은 `matchPercentage` 미만 후보를 materialization 전에 제외하고
  `IMAGE_TAG_WEIGHTED_THR_V1`로 저장한다. 필터 결과가 비어 있어도 정상적인 빈 `CURRENT` 결과다.
- body 없는 하위 호환 요청은 임계값 필터 없이 `IMAGE_TAG_WEIGHTED_V1`로 저장한다.
- 외부 공급자가 모두 실패하면 대표 실패 원인에 따라 malformed response는 `PRODUCT502_1`,
  timeout/auth/unavailable은 `PRODUCT503_1`, quota는 `PRODUCT503_2`이며 기존 세트를 유지한다.
- materialize할 수 없는 후보는 현재 세트에서 제외하고 warning을 남긴다.
- 후보가 모두 저장 정책상 materialize 불가하면 `PRODUCT503_3`이며 기존 세트를 유지한다.
- 각 그룹은 최대 10개이며 8개 그룹을 고정 순서로 반환한다.
- 추천 세트 교체는 `SavedProduct`를 변경하지 않는다.
- 일부 태그 query가 실패하면 `PROVIDER_PARTIAL_FAILURE`, 저장 불가 후보를 제외하면
  `MATERIALIZATION_SKIPPED` warning과 `partial=true`를 반환한다.
- 추천 응답과 상품 상세 응답의 `isSaved`는 인증 회원의 `SavedProduct` 관계를 기준으로 반환한다.

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
    "tags": ["미니멀", "와이드핏", "베이지"],
    "recommendationStatus": "CURRENT",
    "scoreVersion": "IMAGE_TAG_WEIGHTED_V1",
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
- 일부 또는 전체 identity-only live hydrate 실패는 해당 항목의 표시 필드를 `null`, availability를
  `TEMPORARILY_UNRESOLVED`로 두고 나머지 그룹과 함께 200으로 반환한다.
- 현재 `AnalysisDetailResponse`는 `partial`, `warnings` 필드를 노출하지 않는다. 이 두 필드는
  추천 생성 응답 `RecommendationCreateResponse`에만 포함된다.

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
- `pageSize` 기본값은 10이며 허용 범위는 1~20이다.
- `cursor`는 마지막 항목의 저장 시각과 상품 ID를 서버가 인코딩한 opaque 문자열이다.
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
| `BAD_REQUEST` | `COMMON400_1` | 400 | 일반 잘못된 요청 |
| `VALIDATION_ERROR` | `COMMON400_2` | 400 | 필드 형식·범위·필수값 위반 |
| `UNAUTHORIZED` | `COMMON401_1` | 401 | 인증이 없거나 유효하지 않음 |
| `FORBIDDEN` | `COMMON403_1` | 403 | 권한 부족 |
| `NOT_FOUND` | `COMMON404_1` | 404 | 일반 리소스 없음 |
| `METHOD_NOT_ALLOWED` | `COMMON405_1` | 405 | 허용되지 않은 HTTP method |
| `NOT_ACCEPTABLE` | `COMMON406_1` | 406 | 지원하지 않는 응답 미디어 타입 요청 |
| `UNSUPPORTED_MEDIA_TYPE` | `COMMON415_1` | 415 | 지원하지 않는 요청 미디어 타입 |
| `INTERNAL_SERVER_ERROR` | `COMMON500_1` | 500 | 처리되지 않은 서버 오류 |
| `INVALID_ANALYSIS_IMAGE` | `ANALYSIS400_1` | 400 | 분석 multipart 이미지 형식·크기 위반 |
| `ANALYSIS_SELECTION_INVALID` | `ANALYSIS400_2` | 400 | 저장 선택 상품이 현재 추천과 불일치 |
| `ANALYSIS_IMAGE_UPLOAD_FLOW_REQUIRED` | `ANALYSIS400_3` | 400 | 운영에서 multipart 분석 요청 |
| `ANALYSIS_REPORT_NOT_FOUND` | `ANALYSIS404_1` | 404 | 리포트가 없거나 현재 회원 소유가 아님 |
| `ANALYSIS_NOT_READY` | `ANALYSIS409_1` | 409 | 분석 태그 또는 의류 category가 없어 추천 입력으로 사용할 수 없음. category가 없는 기존 리포트는 신규 분석 필요 |
| `ANALYSIS_IMAGE_STORAGE_ERROR` | `ANALYSIS500_1` | 500 | 분석 이미지 저장 실패 |
| `TAG_NOT_FOUND` | `TAG404_1` | 404 | 확정 요청의 기본 태그 ID가 존재하지 않음 |
| `TREND_NOT_FOUND` | `TREND404_1` | 404 | 트렌드 없음 |
| `LOOKBOOK_NOT_FOUND` | `LOOKBOOK404_1` | 404 | 통합 클로젯 저장 대상으로 지정한 룩북이 없거나 삭제됨 |
| `CLOSET_ALREADY_SAVED` | `CLOSET400_1` | 400 | 통합 클로젯에 이미 저장됨 |
| `CLOSET_NOT_FOUND` | `CLOSET404_1` | 404 | 저장 항목 없음 또는 소유권 없음 |
| `CLOSET_TARGET_UNSUPPORTED` | `CLOSET422_1` | 422 | 지원하지 않는 저장 대상 |
| `EMAIL_ALREADY_EXISTS` | `AUTH409_1` | 409 | 가입된 이메일 |
| `INVALID_CREDENTIALS` | `AUTH401_1` | 401 | 이메일 또는 비밀번호 불일치 |
| `LOGIN_ATTEMPT_LOCKED` | `AUTH429_1` | 429 | 로그인 시도 횟수 초과로 일시 잠금 |
| `INVALID_REFRESH_TOKEN` | `AUTH401_2` | 401 | refresh token 오류 |
| `INVALID_TEMP_TOKEN` | `AUTH401_3` | 401 | Kakao 임시 token 오류 |
| `INVALID_PASSWORD_RESET_TOKEN` | `AUTH401_4` | 401 | 비밀번호 재설정 token 오류·만료 |
| `SOCIAL_EMAIL_REQUIRED` | `AUTH400_1` | 400 | Kakao 이메일 동의 필요 |
| `SOCIAL_ID_REQUIRED` | `AUTH400_2` | 400 | Kakao 사용자 식별값 없음 |
| `PASSWORD_MISMATCH` | `MEMBER400_1` | 400 | 현재 비밀번호 불일치 |
| `PASSWORD_CHANGE_NOT_ALLOWED` | `MEMBER400_2` | 400 | 소셜 회원 비밀번호 변경 요청 |
| `MEMBER_TAG_NOT_FOUND` | `MEMBER400_3` | 400 | 존재하지 않는 관심 태그 포함 |
| `REJOIN_BLOCKED` | `MEMBER403_1` | 403 | 탈퇴 후 30일 이내 재가입 |
| `MEMBER_NOT_FOUND` | `MEMBER404_1` | 404 | 회원 없음 |
| `NICKNAME_ALREADY_EXISTS` | `MEMBER409_1` | 409 | 사용 중인 닉네임 |
| `NOTIFICATION_SETTING_EMPTY` | `NOTIFICATION400_1` | 400 | 알림 설정 PATCH에 변경 필드 없음 |
| `NOTIFICATION_NOT_FOUND` | `NOTIFICATION404_1` | 404 | 알림 없음 또는 소유권 없음 |
| `PRODUCT_NOT_FOUND` | `PRODUCT404_1` | 404 | 내부 Product가 없음 |
| `PRODUCT_REFERENCE_INVALID` | `PRODUCT422_1` | 422 | candidate token 서명·형식·만료 오류 |
| `PRODUCT_REFERENCE_UNSUPPORTED` | `PRODUCT422_2` | 422 | 안정 identity와 허용 snapshot 전략이 모두 없음 |
| `PRODUCT_PROVIDER_RESPONSE_INVALID` | `PRODUCT502_1` | 502 | 공급자 응답을 계약대로 해석할 수 없음 |
| `PRODUCT_PROVIDER_UNAVAILABLE` | `PRODUCT503_1` | 503 | timeout, 5xx, 연결 실패 |
| `PRODUCT_PROVIDER_QUOTA_EXCEEDED` | `PRODUCT503_2` | 503 | 429 또는 공급자 quota 초과 |
| `PRODUCT_PROVIDER_PERSISTENCE_UNSUPPORTED` | `PRODUCT503_3` | 503 | 후보를 허용된 방식으로 하나도 materialize할 수 없음 |
| `RECOMMENDATION_INPUT_CHANGED` | `RECOMMENDATION409_1` | 409 | 외부 호출 중 분석 결과 version이 변경됨 |
| `IMAGE_UNSUPPORTED_CONTENT_TYPE` | `IMAGE400_1` | 400 | JPEG, PNG, WebP 이외의 업로드 MIME type |
| `IMAGE_NOT_FOUND` | `IMAGE404_1` | 404 | 이미지 없음 또는 소유권 없음 |
| `IMAGE_OBJECT_NOT_FOUND` | `IMAGE404_2` | 404 | 업로드 완료 확인 시 S3 객체가 없음 |
| `IMAGE_INVALID_STATE` | `IMAGE409_1` | 409 | 현재 상태·목적으로 요청 수행 불가 |
| `IMAGE_UPLOAD_EXPIRED` | `IMAGE410_1` | 410 | 업로드 요청 만료 |
| `INVALID_IMAGE_CONTENT` | `IMAGE422_1` | 422 | 실제 object signature·내용 검증 실패 |
| `IMAGE_PRESIGN_ERROR` | `IMAGE500_1` | 500 | Presigned POST 발급 실패 |
| `IMAGE_STORAGE_ERROR` | `IMAGE500_2` | 500 | S3 권한 또는 서버 설정 오류 |
| `IMAGE_STORAGE_UNAVAILABLE` | `IMAGE503_1` | 503 | S3 timeout, 429, 5xx, `RequestTimeout`, `OperationAborted`, 연결 실패 |

정책:

- 타인 리포트에 403을 반환하지 않는다.
- 상품 가격은 표시 데이터이며 가격 없음·통화 차이는 추천 생성 오류가 아니다.
- 저장 상품 DELETE는 관계가 없어도 성공한다.
- provider 오류 응답에는 API key, 원문 요청 URL, candidate token, 외부 원문 body를 넣지 않는다.
- domain `ErrorCode`와 이 표의 식별자·wire code를 함께 동기화한다.

---

## 11. 외부 공급자와 저장 정책

### 11.1 Port 경계

```text
ProductCatalogPort              # 현재 공개 검색·추천 후보 조회·live lookup에 사용
ReferenceProductDiscoveryPort   # 원상품 후보 탐색 PoC 경계; fixture만 구현, 공개 API 미연결
ProductPriceVerificationPort    # 기준 가격 검증 PoC 경계; fixture만 구현, 공개 API 미연결
```

`FixtureShoppingProviderAdapter`는 세 port를 모두 구현하고, `ShopifyGlobalCatalogAdapter`는 현재
`ProductCatalogPort`만 구현한다. `AffiliateLinkPort`는 현재 코드에 없다. Controller와 Service는
Shopify 등 공급자 이름을 DTO 계약에 노출하지 않는다.

### 11.2 검색·저장 분리

- 상품 검색은 DB write를 하지 않는다.
- `/product-references`, 추천 결과 materialization, 사용자 저장 요청만 명시적으로 저장한다.
- 공급자 정책이 snapshot 저장을 금지하면 provider identity만 저장하고 응답 시 live lookup한다.
- Shopify Global Catalog 상품은 `IDENTITY_ONLY`로 저장하며 상품명·가격·이미지·구매 URL은
  상세·저장 상품 목록·추천 결과 응답 시 live lookup한다.
- 안정 identity가 없는 후보는 현재 상세·저장 대상에서 제외한다. snapshot 저장과 내부 UUID는
  schema 확장점일 뿐 현재 API가 지원하지 않는다.
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
| 추천 생성 요청 | `RecommendationGenerateRequest` |
| 추천 생성 응답 | `RecommendationCreateResponse` |
| 분석 상세 내부 추천 fragment | `RecommendationResultResponse` |
| 추천 그룹 | `RecommendationGroupResponse` |
| 저장 상품 응답/목록 | `SavedProductResponse`, `SavedProductListResponse` |
| 이미지 업로드 URL 요청/응답 | `ImageUploadRequest`, `ImageUploadResponse` |

추천 생성 body는 선택 사항이며 body를 보내는 경우 `RecommendationGenerateRequest`의 세 필드를
모두 제공한다. Controller는 Entity나 외부 provider response를 직접 반환하지 않는다.

---

## 13. Auth / Analysis 연동 체크

### Auth

- principal 타입은 `AuthMember`이며 `@AuthenticationPrincipal AuthMember`로 주입받는다.
- member ID는 `authMember.getMember().getId()`로 얻고 Request에 임시 member ID를 추가하지 않는다.
- 모든 Product/Recommendation endpoint에 인증 정책을 적용한다.
- 인증 실패 401과 리포트 존재 숨김 404를 contract test로 고정한다.
- BCrypt에 전달되는 비밀번호는 UTF-8 기준 72바이트 이하여야 하며 초과 입력은
  `400 COMMON400_2`로 거절한다. 회원가입과 비밀번호 변경·재설정의 새 비밀번호에 적용한다.
- JWT 형식·서명·만료·토큰 종류 오류만 필터에서 `401 COMMON401_1`로 변환한다.
  유효한 토큰의 회원이 존재하지 않는 경우에도 `401 COMMON401_1`로 응답한다. 회원 조회의
  시스템 예외는 공통 예외 처리기에 위임하고 인증 오류로 변환하지 않는다.

### Analysis

- 실제 Controller prefix와 소유자 조회 계약은 현재 Analysis 구현을 따른다.
- body 없는 Recommendation 요청은 기존 분석 결과와 ReportTag를 읽기 전용 입력으로 사용한다.
- body가 있는 추천 생성 API는 확정 기본 태그·직접 입력 태그·매칭값을 리포트 입력으로 멱등 교체한다.
- 분석 결과 version을 외부 호출 전후에 비교해 늦게 끝난 요청이 새 입력을 덮어쓰지 못하게 한다.
- 이미지 URL을 외부 공급자가 읽어야 한다면 storage 계층의 read stream 또는 짧은 signed URL
  계약을 쇼핑 API Adapter 구현 이슈에서 명시한다.
- `GET /analyses/{reportId}`의 기존 필드를 유지하며 recommendation fragment만 합친다.

---

## 14. 자동화 검증 현황

`develop` `85ecbc3` 기반 issue #224 변경의 테스트 기준이다. 체크되지 않은 항목은 정책이 없다는 뜻이 아니라 해당
속성을 직접 고정하는 전용 자동화 테스트를 확인하지 못했다는 뜻이다.

- [x] 요청 DTO validation과 공통 응답 envelope
- [x] 상품 검색 GET 무저장과 unstable 후보 token 미발급
- [x] candidate token 변조·만료·회원/목적 binding
- [x] 안정 provider identity materialization 멱등성
- [x] body 없는 추천 생성의 분석 입력 비변경
- [x] body 추천 생성의 기본·직접 입력 태그와 매칭값 멱등 확정
- [x] 임계값 경계와 임계값 미만 후보 materialization 제외
- [x] 유사도 정규화와 `finalScore=similarityScore`
- [x] 8개 그룹 순서, 그룹별 Top 10, 빈 그룹 포함
- [x] 저장 실패·분석 입력 변경 시 기존 현재 세트 유지
- [x] 저장 상품 PUT/DELETE 멱등성과 추천 세트 독립성
- [ ] 모든 공개/보호 endpoint의 Security filter-chain 계약
- [ ] 외부 호출이 DB transaction 밖에서 실행된다는 직접 검증
- [ ] API key, candidate token, 사용자 이미지 URL 원문 로그 비노출 검증

API 계약 변경 PR은 이 문서 또는 더 구체적인 도메인 계약 문서를 함께 갱신한다.

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
- 현재 허용 상태는 `PENDING_UPLOAD`, `READY`, `ACTIVE`, `DELETING`, `DELETE_FAILED`,
  `DELETED`, `REJECTED`다. V18/V19가 legacy `PENDING`을 backfill하고 DB check를 canonical 값으로
  축소했으므로 legacy 값은 현재 API 입력·저장 계약이 아니다.
- 객체 key는 `images/{purpose}/{memberId}/{yyyy}/{MM}/{imageId}.{ext}` 형식으로 서버가 생성하며 클라이언트 파일명은 사용하지 않는다.
- 이 API가 받은 `fileSize`는 발급 전 요청 검증용이다. 업로드 완료 API가 S3 metadata,
  실제 파일 시그니처와 크기를 다시 검증한다.
- 분석 리포트가 생성되면 `ANALYSIS` 이미지를 `ACTIVE`로 전환한다. 현재 허용 목적은
  `PROFILE`, `ANALYSIS`, `LOOKBOOK`뿐이다.
- 24시간 이상 도메인에 연결되지 않은 `PENDING_UPLOAD`/`READY`/`REJECTED` 이미지는 정리
  작업자가 S3 객체와 DB 상태를 정리한다.
- 모든 사용자 이미지는 `PRIVATE`을 유지한다. API에 포함되는 조회 URL은 CloudFront 10분 Signed
  URL이며, 공개 룩북 생성 시에도 visibility를 `PUBLIC`으로 바꾸지 않는다.

### 오류

| 조건 | HTTP | code |
| --- | ---: | --- |
| 인증 없음 또는 유효하지 않은 토큰 | 401 | `COMMON401_1` |
| 필수값, enum, 파일 크기 위반 | 400 | `COMMON400_1` 또는 `COMMON400_2` |
| 지원하지 않는 MIME type | 400 | `IMAGE400_1` |
| Presigned POST 정보 생성 실패 | 500 | `IMAGE500_1` |
| 이미지가 없거나 요청 회원 소유가 아님 | 404 | `IMAGE404_1` |
| 완료·재발급이 불가능한 상태 | 409 | `IMAGE409_1` |
| 업로드 요청 만료 | 410 | `IMAGE410_1` |
| 실제 파일 signature·크기·MIME 불일치 | 422 | `IMAGE422_1` |
| S3 metadata/object 처리 실패 | 500 | `IMAGE500_2` |

### `POST /api/v1/images/{imageId}/complete`

인증 회원이 Presigned POST 업로드를 마친 뒤 호출한다. 서버는 S3 객체의 크기, MIME type과
파일 시그니처를 검증하고 성공하면 이미지 상태를 `READY`로 전환한다.
S3 객체가 없으면 `404 IMAGE404_2`, S3 timeout·연결 실패·429·5xx 및 AWS 오류 코드
`RequestTimeout`·`OperationAborted`는 `503 IMAGE503_1`,
권한 또는 서버 설정 오류는 `500 IMAGE500_2`로 구분한다.

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

아직 `PENDING_UPLOAD`인 본인 이미지의 5분 유효 Presigned POST 정보를 재발급한다. 응답 계약은
최초 발급 응답과 동일하며 DB의 이미지 ID와 object key는 바꾸지 않는다.

---

## 16. 분석 리포트 생명주기

### `POST /api/v1/analyses`

`Content-Type: application/json` 요청은 인증 회원 본인이 소유하고 `status=READY`인
`ANALYSIS` 목적의 이미지 ID를 받는다.

```json
{
  "imageId": "5f8ca021-02fe-4fba-982f-8de356789abc"
}
```

성공 시 `201 Created`로 `reportId`, signed `imageUrl`, `matchPercentage`, `suggestedTags`를
반환한다. 기존 `multipart/form-data`의 `image` part 계약은 로컬 개발 프로필에서만 유지한다.
신규 분석 리포트의 기본 `matchPercentage`는 50이며, 응답에도 같은 값이 포함된다.

```json
{
  "success": true,
  "code": "COMMON201_1",
  "message": "리소스가 생성되었습니다.",
  "data": {
    "reportId": 501,
    "imageUrl": "https://signed-cdn.example/image",
    "matchPercentage": 50,
    "suggestedTags": [
      {
        "tagId": 10,
        "tagName": "미니멀"
      }
    ]
  }
}
```

운영 프로필에서 multipart 분석을 요청하면 로컬 파일을 생성하지 않고 `ANALYSIS400_3`으로
거절하므로, 클라이언트는 Presigned POST 완료 후 `imageId` JSON 계약을 사용해야 한다.

`FITBACK_AI_TAG_ANALYZER=prototype`은 이미지 의미를 판별하는 실제 AI가 아니라 end-to-end
프로토타입용 결정적 fallback이다. `미니멀`, `와이드핏`, `베이지` 기준 태그를 반환하며,
기본값 `unavailable`은 실제 AI 공급자 연결 전까지 분석 생성을 fail-closed로 유지한다.

| 조건 | HTTP | code |
| --- | ---: | --- |
| 운영 프로필에서 multipart 이미지 part 사용 | 400 | `ANALYSIS400_3` |
| 이미지가 없거나 요청 회원 소유가 아님 | 404 | `IMAGE404_1` |
| 이미지 목적이 `ANALYSIS`가 아니거나 상태가 `READY`가 아님 | 409 | `IMAGE409_1` |
| prototype 기준 태그 migration이 적용되지 않음 | 409 | `ANALYSIS409_1` |

### `GET /api/v1/analyses?cursor=&pageSize=20`

인증 회원이 명시적으로 마이 클로젯에 저장한 리포트를 `ClosetSave.saveId` cursor 기준으로
최신 저장순 조회한다. 생성만 하고 저장하지 않은 분석 결과는 목록에 포함하지 않는다.
`pageSize`는 1~50이며 응답은 `items`, `nextCursor`, `hasNext`, `pageSize`를 포함한다.
각 item은 `reportId`, signed `imageUrl`, `tags`, `savedAt`을 반환한다.

### `GET /api/v1/analyses/{reportId}`

본인의 삭제되지 않은 리포트 상세와 확정 기본·직접 입력 태그, 추천 그룹을 반환한다. private
이미지 URL은 10분 유효한 CloudFront signed URL이다. 명시적 저장 여부인 `saved`, `savedAt`과
저장 시점의 카테고리별 `selectedItems`도 함께 반환한다. SCR-09 연동을 위해 S3 기반 분석은
`originalImageId`도 반환하며, `selectedItems`에는 카테고리별로 선택한 모든 상품 이미지가
포함된다.

### `PUT /api/v1/analyses/{reportId}/save`

SCR-08에서 현재 결과 리포트 전체를 마이 클로젯에 저장한다. 현재 추천 결과에 상품이 존재하는
각 카테고리마다 정확히 하나를 선택해야 하며, 타 리포트 상품·카테고리 누락·중복은
`ANALYSIS400_2`로 거부한다.

```json
{
  "selectedItems": [
    {"category": "TOP", "productId": 100},
    {"category": "BOTTOM", "productId": 205}
  ]
}
```

최초 저장은 `201 Created`, 같은 리포트 재저장은 기존 스냅샷을 유지하며 `200 OK`를 반환한다.
응답은 `reportId`, `saved`, `savedAt`, `selectedItems`를 포함한다. 선택 상품은 상품명, 판매처,
가격, 이미지, 구매 URL, 순위, 유사도와 최종 점수를 저장 시점 스냅샷으로 반환한다.

### `DELETE /api/v1/analyses/{reportId}/save`

분석 리포트의 클로젯 저장 관계와 선택 상품 스냅샷만 멱등 삭제한다. 원본 분석 리포트와 현재
추천 결과는 유지한다. 응답은 `saved=false`, `savedAt=null`, 빈 `selectedItems`를 반환한다.

### `POST /api/v1/analyses/{reportId}/recommendations`

Issue #119 구현은 body를 생략하면 기존 분석 결과를 읽고, body가 있으면 확정 기본·직접 입력
태그와 `matchPercentage`를 멱등 반영한 뒤 추천 현재 세트를 생성한다. legacy PATCH는 제거되었고
세부 생성·교체 계약은 7절을 따른다.

### `DELETE /api/v1/analyses/{reportId}`

리포트를 soft delete 처리한다. 삭제된 리포트는 목록과 상세 조회에서 제외되며 연결된 이미지는
transaction commit 후 참조 release 이벤트의 대상이 된다. cleanup은 분석·룩북·프로필 참조를
다시 확인하고 마지막 참조일 때만 삭제 claim을 수행한다. 저장된 리포트라면 클로젯 저장 관계와
선택 상품 스냅샷을 먼저 제거한다.

---

## 17. 분석 결과 기반 룩북 업로드

### `GET /api/v1/lookbooks?cursor=&pageSize=20&tag=`

룩북 목록은 Long ID cursor 기반으로 조회한다. `cursor`는 전달하는 경우 양수여야 하며,
`pageSize` 기본값은 20이고 허용 범위는 1~20이다. 범위를 벗어난 요청은
`400 COMMON400_2`로 응답한다.

### `POST /api/v1/lookbooks`

직접 진입한 SCR-09는 기존처럼 `originalImageId`, `matchedImageId`를 전송한다. SCR-08에서
진입한 경우에는 분석 원본 `originalImageId`를 재사용하고, 화면에서 대표로 정한 상품을
`matchedProductId`와 `sourceReportId`로 전송한다.
생성 성공 시 HTTP `201 Created`와 공통 응답 코드 `COMMON201_1`을 반환한다.

```json
{
  "originalImageId": "5f8ca021-02fe-4fba-982f-8de356789abc",
  "matchedImageId": null,
  "matchedProductId": 100,
  "sourceReportId": 501,
  "tagIds": [12, 21],
  "purchaseUrl": null,
  "comment": "분석 결과로 완성한 룩"
}
```

`matchedImageId`와 `matchedProductId`는 정확히 하나만 전달해야 한다. 상품 경로에서는
`sourceReportId`가 본인 소유의 삭제되지 않은 리포트인지, 해당 상품이 현재 추천 결과 또는
저장된 선택 상품인지 검증한다. 구매 링크를 생략하면 선택 상품의 구매 URL을 사용한다.
상품 이미지는 저장 방식에 따라 결정한다. `SNAPSHOT` 상품은 DB의 `product.image_url`을 사용하고,
`IDENTITY_ONLY` 상품은 저장된 공급자 식별자로 live lookup한 응답의 `imageUrl`을 사용한다.
`IDENTITY_ONLY`의 availability가 `TEMPORARILY_UNRESOLVED`여도 실제 조회 응답에 `imageUrl`이 있으면
사용할 수 있으며, live lookup 실패 또는 응답 이미지 누락 시 룩북을 저장하지 않는다. 외부 조회 후
DB 저장 트랜잭션에서 리포트 소유권, 원본 이미지 연결 및 선택 상품 포함 여부를 다시 검증한다.
결정된 URL은 `matched_product_image_url`에 게시 시점 snapshot으로 저장하므로 이후 공급자 이미지가
변경되어도 기존 룩북의 이미지는 바뀌지 않는다.
목록·상세 응답의 `matchedImageUrl`은 어느 경로로 생성했든 동일하게 표시 가능하며,
상품 경로인 경우 `matchedProductId`도 반환한다.

룩북에 연결된 원본·매칭·작성자 프로필 이미지는 모두 `PRIVATE` 상태를 유지한다. 공개 조회 API도
각 요청 시점에 10분 유효 CloudFront Signed URL을 만들어 반환하며, 룩북 생성·공개 조회 때문에
이미지를 `PUBLIC`으로 전환하지 않는다. Signed URL이 만료되면 목록·상세 API를 다시 호출해 새
URL을 받아야 한다.

### `PUT /api/v1/lookbooks/{lookbookId}`

작성자만 룩북을 수정할 수 있다. body는 생성 요청과 같은 전체 교체 계약이며, 새 이미지가
`READY`라면 `ACTIVE`로 전환하고 더 이상 참조하지 않는 기존 이미지 ID는 transaction commit 후
참조 재확인·cleanup 흐름으로 전달한다. 성공 응답은 `lookbookId`를 반환한다.
분석 추천 상품으로 교체할 때도 생성과 같은 저장 방식별 이미지 결정, 외부 조회와 DB 트랜잭션 분리,
저장 직전 권한·연결 관계 재검증 및 게시 시점 URL snapshot 정책을 적용한다.

### `POST /api/v1/lookbooks/{lookbookId}/reports`

로그인 회원이 신고 사유 하나를 전달한다. 사유는 `INAPPROPRIATE_IMAGE`,
`COPYRIGHT_INFRINGEMENT`, `FRAUD_OR_FALSE_INFORMATION`, `SPAM_OR_ADVERTISEMENT`, `OTHER`다.
동일 회원의 동일 룩북 중복 신고는 `COMMON400_1`이다. 성공 응답은 생성된 `reportId`를 반환한다.

### `DELETE /api/v1/lookbooks/{lookbookId}`

작성자 또는 `ADMIN`만 soft delete할 수 있다. 연결 이미지의 마지막 참조 여부는 commit 이후
재확인하며, 다른 분석·룩북·프로필이 참조하면 이미지를 삭제하지 않는다.

### `POST /api/v1/lookbooks/{lookbookId}/likes`

인증 회원이 룩북에 좋아요를 등록한다.

### `DELETE /api/v1/lookbooks/{lookbookId}/likes`

인증 회원이 등록한 룩북 좋아요를 취소한다.

좋아요 등록과 취소는 멱등이다. 두 응답 모두 현재 `isLiked`, `likeCount`를 반환한다.

### `GET /api/v1/lookbooks?cursor=&pageSize=20&tag=`

비로그인 조회를 허용한다. `lookbookId` 기반 `Long` cursor로 최신순 조회하며, `tag`가 있으면
정규화한 태그 이름으로 필터한다. 응답은 `items`, `nextCursor`, `hasNext`, `pageSize`다. 각 item의
`isLiked`는 익명 요청에서 `false`다.

### `GET /api/v1/lookbooks/{lookbookId}`

비로그인 조회를 허용한다. 원본·매칭 이미지, 작성자·프로필 이미지, 작성 시각, 구매 링크,
코멘트, 태그, 좋아요 수를 반환한다. 익명 요청의 `isLiked`, `isOwner`는 `false`다.
삭제되었거나 신고로 `AUTO_HIDDEN` 처리된 룩북은 존재하지 않는 리소스와 동일하게 404를 반환한다.

로그인 회원이 해당 룩북을 마이 클로젯에 저장한 경우 `saveId`에 저장 취소(`DELETE
/api/v1/closet-saves/{saveId}`)에 사용할 `ClosetSave` ID를 반환한다. 저장하지 않았거나
익명 요청인 경우 `saveId`는 `null`이다.

### 오류

| 조건 | HTTP | code |
| --- | ---: | --- |
| 매칭 이미지·상품을 모두 선택하거나 모두 생략한 경우 | 400 | `COMMON400_1` |
| 상품 경로에서 `sourceReportId`를 생략하거나 이미지 경로에 전달한 경우 | 400 | `COMMON400_1` |
| 원본 이미지가 해당 분석 리포트의 원본 이미지와 다른 경우 | 400 | `COMMON400_1` |
| 선택 상품이 현재 추천 결과 또는 저장된 선택 상품에 없는 경우 | 400 | `COMMON400_1` |
| 선택 상품에 표시할 이미지가 없는 경우 | 400 | `COMMON400_1` |
| `IDENTITY_ONLY` 상품 live lookup 응답을 해석할 수 없는 경우 | 502 | `PRODUCT502_1` |
| `IDENTITY_ONLY` 상품 live lookup이 실패하거나 결과가 없는 경우 | 503 | `PRODUCT503_1` |
| `IDENTITY_ONLY` 상품 live lookup이 rate limit 또는 quota를 초과한 경우 | 503 | `PRODUCT503_2` |
| 분석 리포트가 없거나 본인 소유가 아니거나 삭제된 경우 | 404 | `ANALYSIS404_1` |
| 이미지가 없거나 본인 소유가 아닌 경우 | 404 | `IMAGE404_1` |
| 상품이 존재하지 않는 경우 | 404 | `COMMON404_1` |
| 이미지 목적 또는 상태가 룩북에서 사용할 수 없는 경우 | 409 | `IMAGE409_1` |
| 룩북이 없거나 삭제된 경우(룩북 API) | 404 | `COMMON404_1` |
| 룩북 수정·삭제 권한이 없는 경우 | 403 | `COMMON403_1` |
| 본인 룩북 신고 | 403 | `COMMON403_1` |
| 동일 룩북 중복 신고 | 400 | `COMMON400_1` |

---

## 18. 트렌드·태그·통합 클로젯

### `GET /api/v1/content-search?keyword=`

비로그인 조회를 허용하는 SCR-16 통합 콘텐츠 검색이다. `keyword`는 공백 제거 후 1~100자이며,
대소문자를 구분하지 않는 부분 일치로 검색한다. 트렌드는 제목·설명·태그, 룩북은 코멘트·작성자
닉네임·태그가 검색 대상이다. 삭제되거나 숨김 처리된 룩북은 제외한다.

응답은 `trends`, `lookbooks` 두 그룹으로 구성하며 각 그룹은 최신순 최대 10개의 기존 목록 카드
DTO를 반환한다. `isSaved`, `isLiked` 필드는 항상 포함하며 익명 요청에서는 `false`다.

### `GET /api/v1/trends`

비로그인 조회를 허용하며 최대 10개의 트렌드를 커서 기반으로 반환한다. `cursor`와 `tag`를
선택적으로 전달할 수 있다. `tag`를 전달하면 해당 태그가 등록된 트렌드만 최신순으로 조회한다.
`tag` 없이 로그인한 회원은 관심 태그와 일치하는 트렌드를 우선 노출하고 각 그룹 안에서 최신순으로
정렬하며, 비로그인 또는 관심 태그 미설정 회원은 전체를 최신순으로 조회한다. `isSaved`는 항상
포함하며 익명 요청에서는 `false`다.

### `GET /api/v1/trends/{trendId}`

트렌드 제목, 이미지, 설명, 태그와 로그인 회원의 `isSaved`를 반환한다.

### `GET /api/v1/trends/{trendId}/lookbooks`

트렌드 태그와 룩북 태그의 관련도 점수를 기준으로 관련 룩북을 3개씩 커서 기반으로 반환한다.
비로그인 조회를 허용하며 삭제·숨김 룩북은 제외한다. 응답은 룩북 목록 카드 DTO이며 `isLiked`는
항상 포함하고 익명 요청에서는 `false`다. 존재하지 않는 `trendId`는 `TREND404_1`이다.
`cursor`가 존재하지 않거나 해당 트렌드와 관련 없는 룩북이면 `COMMON404_1`이다. 페이지 조회 사이에
커서 룩북이 삭제·숨김 처리되어도 해당 정렬 위치 다음부터 조회하며 응답 목록에서는 제외한다.

### `GET /api/v1/tags`

관심 태그, 분석 태그 수정, 룩북 업로드에 사용할 canonical 태그 마스터를 반환한다.
각 항목은 태그 분류 `tagType`과 적용 복종 `targetClothing`을 포함한다. `ALL`은 상의,
바지, 스커트, 원피스, 아우터에 공통 적용됨을 뜻하며 다른 복종 값과 함께 반환하지 않는다.

```json
{
  "success": true,
  "code": "COMMON200_1",
  "message": "성공적으로 요청을 처리했습니다.",
  "data": {
    "items": [
      {
        "tagId": 8,
        "tagName": "와이드핏",
        "tagType": "SILHOUETTE",
        "targetClothing": ["PANTS"]
      },
      {
        "tagId": 22,
        "tagName": "데님",
        "tagType": "MATERIAL",
        "targetClothing": ["ALL"]
      }
    ]
  }
}
```

`tagType`은 `STYLE`, `SILHOUETTE`, `MATERIAL`, `DETAIL`, `COLOR` 중 하나다.
`targetClothing` 값은 `TOP`, `PANTS`, `SKIRT`, `DRESS`, `OUTER`, `ALL`이다. 개별 복종 배열은
앞의 다섯 값 순서로 정렬하고, `ALL`은 개별 복종 값과 함께 반환하지 않는다. V27 적용 후
마스터는 STYLE 9개, SILHOUETTE 12개, MATERIAL 8개, DETAIL 10개, COLOR 8개로 총 47개다.

| 타입 | 태그와 적용 복종 |
| --- | --- |
| `STYLE` | 미니멀, 스트릿, 러블리, 캐주얼, 포멀, 뉴트럴, 페미닌, 데일리룩, 오피스룩 — 모두 `ALL` |
| `SILHOUETTE` | 와이드핏(`PANTS`), 슬림핏(`TOP/PANTS/SKIRT/DRESS/OUTER`), 오버사이즈(`TOP/DRESS/OUTER`), 레귤러핏(`TOP/PANTS/SKIRT/DRESS/OUTER`), A라인(`SKIRT/DRESS/OUTER`), H라인(`SKIRT/DRESS`), 크롭(`TOP/DRESS/OUTER`), 로우라이즈·하이라이즈·숏기장·미디기장·롱기장(`PANTS/SKIRT`) |
| `MATERIAL` | 데님, 니트, 코튼, 린넨, 가죽, 트위드, 시폰, 우븐/시어 — 모두 `ALL` |
| `DETAIL` | 브이넥·터틀넥·라운드넥(`TOP/DRESS/OUTER`), 러플/프릴·지퍼·벨트·포켓·슬릿·단추(`ALL`), 턱(`PANTS/SKIRT`) |
| `COLOR` | 화이트, 블랙, 베이지, 네이비, 그레이, 브라운, 카키, 파스텔/메탈릭 — 모두 `ALL` |

### `POST /api/v1/closet-saves`

인증 회원이 트렌드 또는 삭제되지 않은 룩북을 저장한다.

```json
{
  "targetType": "TREND",
  "targetId": 10
}
```

성공 시 `201 Created`로 삭제 식별자 `saveId`, `targetType`, `targetId`를 반환한다.
분석 리포트는 선택 상품 스냅샷 계약을 보존하기 위해 이 API로 저장할 수 없으며,
`PUT /api/v1/analyses/{reportId}/save`를 사용해야 한다.

### 오류

| 조건 | HTTP | code |
| --- | ---: | --- |
| 저장 대상이 트렌드/룩북이 아닌 경우 | 422 | `CLOSET422_1` |
| 이미 저장한 항목인 경우 | 400 | `CLOSET400_1` |
| 트렌드가 존재하지 않는 경우 | 404 | `TREND404_1` |
| 룩북이 존재하지 않거나 삭제된 경우 | 404 | `LOOKBOOK404_1` |
| 저장 항목이 없거나 본인 소유가 아닌 경우 | 404 | `CLOSET404_1` |

### `GET /api/v1/closet-saves?target_type=&cursor=`

인증 회원의 통합 저장 목록을 최신 저장순으로 최대 10개 반환한다. 각 항목은 삭제에 사용할
`saveId`, `targetType`, `targetId`와 표시용 `thumbnailUrl`, `matchedImageUrl`, `tags`를
포함한다. 저장 대상 타입별 표시 값은 다음과 같다.

| `targetType` | `thumbnailUrl` | `matchedImageUrl` | `tags` |
| --- | --- | --- | --- |
| `TREND` | 트렌드 이미지 | `null` | 트렌드 태그 |
| `LOOKBOOK` | 원본 이미지 | 매칭 이미지 또는 매칭 상품 이미지 | 룩북 태그 |
| `ANALYSIS_REPORT` | 분석 원본 이미지 | `null` | 기본 태그와 커스텀 태그 |

`matchedImageUrl`은 원본과 매칭 이미지를 나란히 표시하는 `LOOKBOOK`에만 값이 있으며 다른
타입에서는 키를 유지한 채 `null`이다. 저장 이후 대상이 삭제된 항목은 목록에서 제외한다.
룩북의 업로드 이미지와 `originalImage`가 연결된 분석 리포트는 응답 시점에 발급하는 signed
URL이고, 트렌드 이미지와 룩북의 매칭 상품 이미지는 외부 URL을 그대로 반환한다. 이미지 업로드
도입 전에 만들어진 분석 리포트는 저장된 `imageUrl`을 그대로 반환하므로 signed URL이 아니다.

### `DELETE /api/v1/closet-saves/{saveId}`

인증 회원 본인이 저장한 항목을 `saveId`로 삭제한다.

---

## 19. 인증·회원·프로필 이미지·알림

### 19.1 Auth

| Endpoint | 핵심 request | 핵심 response/동작 |
| --- | --- | --- |
| `POST /api/v1/auth/sign` | `email`, `password` | 201, access/refresh token과 회원 식별 정보 |
| `POST /api/v1/auth/login` | `email`, `password` | access/refresh token, 회원·프로필 정보 |
| `POST /api/v1/auth/password-reset/request` | `email` | 가입 여부를 노출하지 않고 동일한 200 응답 |
| `PATCH /api/v1/auth/password-reset` | `resetToken`, `newPassword` | 유효한 token이면 비밀번호 교체 |
| `POST /api/v1/auth/token/refresh` | `refreshToken` | 새 access/refresh token |
| `POST /api/v1/auth/token/exchange` | `tempToken` | Kakao access/refresh token, `isNewMember` |
| `POST /api/v1/auth/logout` | Bearer token | 현재 회원 refresh token 무효화 |

token은 가입·로그인·교환·refresh 응답 body로 발급한다. 이후 보호 API 요청은
`Authorization: Bearer {accessToken}` 헤더를 사용한다. 비밀번호는 가입 시 필수이고, 변경·재설정
비밀번호는 8~64자 validation을 적용한다.

서버는 Refresh Token 원문을 저장하지 않는다. 회원가입·로그인·Kakao token 교환·재발급 시
`refresh-token:` 용도 문자열을 포함한 HMAC-SHA256 소문자 64자리 hex만 저장하고, 원문은
응답 body로만 전달한다. 재발급은 요청 원문의 HMAC을 저장값과 비교한 뒤 access/refresh token을
함께 회전한다. 로그아웃과 비밀번호 재설정은 저장된 해시를 제거한다. V31은 기존 DB의 원문
Refresh Token을 폐기하므로 배포 전에 로그인한 사용자는 `AUTH401_2` 응답 후 재로그인해야 한다.

### 19.2 Member와 프로필 이미지

- `PATCH /api/v1/members/me`는 `nickname`, `profileImageId`, `tagIds` 중 전달된 필드만 바꾼다.
- `PUT /api/v1/members/me/onboarding`은 `nickname`, 선택 `profileImageId`, 최대 5개 `tagIds`를
  저장한다. `tagIds` 필드 자체는 필수이며 빈 배열은 허용한다.
- `PUT /api/v1/members/me/tags`는 관심 태그 전체를 교체한다.
- 프로필 이미지 업로드는 15절에서 `purpose=PROFILE`로 발급·완료한 뒤 `READY` image ID를
  `profileImageId`로 전달한다. 본인 소유가 아니거나 목적/상태가 다르면 연결할 수 없다.
- 로그인, 마이페이지, 온보딩, 회원 수정 응답의 `profileImageUrl`은 원본 URL이 아니라 10분
  유효한 CloudFront Signed URL이다. 이미지가 없으면 `null`이다.
- 프로필 교체와 회원 탈퇴는 기존 이미지 참조를 transaction commit 후 release하고 마지막
  참조인지 다시 확인한 뒤 cleanup한다.
- `GET /api/v1/members/me/nickname-availability?nickname=`은 2~16자 닉네임의 사용 가능 여부를
  반환한다. `PATCH /api/v1/members/me/password`는 이메일 회원만 허용한다.

### 19.3 Notification

알림 설정은 네 boolean 필드 `analysisCompleteEnabled`, `lookbookLikedEnabled`,
`trendUpdateEnabled`, `marketingEnabled`를 가진다. 설정 조회 시 행이 없으면 기본값으로 생성하며,
PATCH는 전달된 필드만 수정하고 모두 `null`이면 `NOTIFICATION400_1`이다.

`GET /api/v1/notifications?cursor=&pageSize=20`은 본인 알림을 `notificationId` 기반 최신순으로
조회하고 `items`, `unreadCount`, `nextCursor`, `hasNext`, `pageSize`를 반환한다. item에는 type,
title, body, 대상 type/ID, `readAt`, `createdAt`이 포함된다. type과 화면 대상은 다음과 같다.

| `notificationType` | `targetType` | target ID |
| --- | --- | --- |
| `ANALYSIS_COMPLETE` | `ANALYSIS_REPORT` | `reportId` |
| `LOOKBOOK_LIKED` | `LOOKBOOK` | `lookbookId` |
| `TREND_UPDATE` | `TREND` | `trendId` |
| `MARKETING` | `null` | `null` |

특정 읽음, 전체 읽음, 삭제는 모두 현재 회원 소유 범위에서만 처리한다. 현재 `develop`에는 알림
설정·목록·읽음·삭제 API와 V21 저장 스키마가 있지만, 분석 완료·룩북 좋아요·트렌드 업데이트에서
`Notification`을 자동 생성하는 producer는 연결되어 있지 않다. 따라서 이 문서는 자동 알림 발송을
완료 기능으로 보장하지 않는다.
