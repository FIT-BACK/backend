# 최소 프로토타입 프론트엔드 세로 흐름

> **문서 상태 (2026-08-01): 역사적·한정 시나리오.** 2026-07-30 당시 업로드→분석→추천
> UT 흐름을 재현하기 위한 기록이며 전체 프런트 계약이 아니다. 현재 계약은
> [FRONTEND_PROTOTYPE_API_HANDOFF.md](FRONTEND_PROTOTYPE_API_HANDOFF.md)와
> [API_SPEC.md](API_SPEC.md)를 우선한다.

## 1. 범위

이 문서는 실제 프론트엔드가 다음 흐름을 연결할 때 필요한 요청, 응답, 클라이언트 상태를
정의한다.

```text
로그인
  → Presigned POST 발급
  → S3 직접 업로드
  → 업로드 완료 확인
  → 분석 생성
  → 추천 생성
  → 상품 상세
  → 외부 구매 URL 이동
```

실제 의미 기반 AI 공급자 연동은 이 흐름의 범위가 아니다. 운영의 `prototype` 분석기는 현재
이미지와 무관한 결정적 태그를 반환하므로, 프론트는 이를 사용자에게 프로토타입 결과로
표시해야 한다.

모든 FIT-BACK API 응답은 다음 envelope를 사용한다.

```json
{
  "success": true,
  "code": "COMMON200_1",
  "message": "성공적으로 요청을 처리했습니다.",
  "data": {}
}
```

## 2. 프론트가 유지할 식별자

| 식별자 | 받은 API | 다음 사용처 | 영속 저장 권장 |
| --- | --- | --- | --- |
| `imageId` | 이미지 업로드 요청 | 완료 확인, 분석 생성 | 분석 생성 전까지 |
| `reportId` | 분석 생성 | 추천 생성, 분석 상세 | 예 |
| `productId` | 추천 결과 | 상품 상세, 저장 상품 | 예 |

`uploadUrl`, `uploadFields`, JWT는 일시적인 권한 정보이므로 로그, 브라우저 영속 저장소,
분석 도구에 기록하지 않는다.

## 3. 단계별 계약

### 3.1 로그인

```http
POST /api/v1/auth/login
Content-Type: application/json

{
  "email": "ut-account@example.com",
  "password": "temporary-password"
}
```

프론트는 응답의 `data.accessToken`을 이후 FIT-BACK API의
`Authorization: Bearer {accessToken}` 헤더에 사용한다.

### 3.2 업로드 요청

프론트가 전달하는 값은 파일 본문이 아니라 업로드 메타데이터다.

```http
POST /api/v1/images/upload-requests
Authorization: Bearer {accessToken}
Content-Type: application/json

{
  "purpose": "ANALYSIS",
  "contentType": "image/jpeg",
  "fileSize": 1048576
}
```

```json
{
  "success": true,
  "code": "COMMON200_1",
  "message": "성공적으로 요청을 처리했습니다.",
  "data": {
    "imageId": "019c1234-abcd-7000-8000-123456789abc",
    "uploadUrl": "https://s3.example.com/bucket",
    "uploadMethod": "POST",
    "uploadFields": {
      "key": "images/analysis/1/2026/07/image-id.jpg",
      "policy": "...",
      "x-amz-signature": "..."
    },
    "expiresAt": "2026-07-30T12:05:00Z"
  }
}
```

지원 형식은 JPEG, PNG, WebP이며 최대 크기는 5 MiB다.

### 3.3 S3 직접 업로드

`uploadFields`의 모든 항목을 `FormData`에 먼저 넣고 파일을 마지막 `file` 필드로 추가한다.

```javascript
const form = new FormData();
Object.entries(uploadFields).forEach(([key, value]) => form.append(key, value));
form.append("file", file);

const response = await fetch(uploadUrl, {
  method: "POST",
  body: form,
});

if (!response.ok) {
  throw new Error("S3 upload failed");
}
```

`uploadUrl`은 이미지 표시 URL이 아니다. 만료되었지만 업로드가 시작되지 않았다면
`POST /api/v1/images/{imageId}/upload-request`로 전체 Presigned 정보를 다시 받아야 한다.

### 3.4 업로드 완료 확인

```http
POST /api/v1/images/{imageId}/complete
Authorization: Bearer {accessToken}
```

```json
{
  "success": true,
  "code": "COMMON200_1",
  "message": "성공적으로 요청을 처리했습니다.",
  "data": {
    "imageId": "019c1234-abcd-7000-8000-123456789abc",
    "status": "READY"
  }
}
```

S3 응답이 성공했더라도 이 API가 `READY`를 반환하기 전에는 분석을 호출하지 않는다.

### 3.5 분석 생성

```http
POST /api/v1/analyses
Authorization: Bearer {accessToken}
Content-Type: application/json

{
  "imageId": "019c1234-abcd-7000-8000-123456789abc"
}
```

```json
{
  "success": true,
  "code": "COMMON200_1",
  "message": "성공적으로 요청을 처리했습니다.",
  "data": {
    "reportId": 501,
    "imageUrl": "https://signed-cdn.example/image",
    "matchPercentage": 70,
    "suggestedTags": [
      {
        "tagId": 10,
        "tagName": "미니멀"
      }
    ]
  }
}
```

`imageUrl`은 조회 시점의 서명 URL이므로 만료 후에는
`GET /api/v1/analyses/{reportId}`를 다시 조회한다.

### 3.6 추천 생성

분석 결과를 그대로 사용할 때는 body를 생략한다.

```http
POST /api/v1/analyses/{reportId}/recommendations
Authorization: Bearer {accessToken}
```

사용자가 태그나 매칭값을 확정·수정한 경우에만 다음 body를 보낸다.

```json
{
  "confirmedTagIds": [10, 11],
  "customTagNames": ["출근룩"],
  "matchPercentage": 75
}
```

응답의 핵심 필드는 다음과 같다.

```json
{
  "success": true,
  "code": "COMMON200_1",
  "message": "성공적으로 요청을 처리했습니다.",
  "data": {
    "reportId": 501,
    "analysisTags": ["미니멀", "출근룩"],
    "matchPercentage": 75,
    "recommendationStatus": "CURRENT",
    "recommendationGroups": [
      {
        "category": "TOP",
        "items": [
          {
            "productId": 101,
            "rank": 1,
            "imageUrl": "https://...",
            "name": "상품명",
            "sellerName": "판매자",
            "price": {
              "amount": 34000,
              "currency": "KRW",
              "type": "CURRENT"
            },
            "purchaseUrl": "https://...",
            "availability": "AVAILABLE",
            "isSaved": false
          }
        ]
      }
    ],
    "partial": false,
    "warnings": []
  }
}
```

8개 카테고리 그룹은 항목이 없어도 반환된다. `partial=true`이면 사용 가능한 상품은 표시하되
`warnings`를 비차단 안내로 노출한다.

### 3.7 상품 상세와 구매 이동

```http
GET /api/v1/products/{productId}
Authorization: Bearer {accessToken}
```

```json
{
  "success": true,
  "code": "COMMON200_1",
  "message": "성공적으로 요청을 처리했습니다.",
  "data": {
    "productId": 101,
    "imageUrl": "https://...",
    "name": "상품명",
    "brandName": "브랜드",
    "sellerName": "판매자",
    "category": "TOP",
    "price": {
      "amount": 34000,
      "currency": "KRW",
      "type": "CURRENT"
    },
    "purchaseUrl": "https://...",
    "availability": "AVAILABLE",
    "dataStatus": "LIVE",
    "tags": [],
    "isSaved": false
  }
}
```

이 기록의 Shopify 활성화 시나리오에서는 상품명, 가격, 이미지, 구매 URL을 Shopify
`lookup_catalog`을 통해 실시간 조회한다. 현재 기본 runtime은 `fixture`이며 Shopify는
`SHOPPING_PROVIDER=shopify`, `SHOPIFY_ENABLED=true`를 함께 설정한 경우에만 선택된다.
`purchaseUrl`을 여는 것만으로 결제가 발생하지 않으며, 실제 결제는 외부 판매처에서 사용자가
확정할 때 발생한다. 프론트는 외부 이동임을 버튼 또는 안내 문구로 명확히 표시한다.

## 4. 화면 상태

| 상태 | 진입 조건 | 사용자 동작 |
| --- | --- | --- |
| `IDLE` | 파일 미선택 | 이미지 선택 |
| `PRESIGNING` | 업로드 요청 중 | 중복 클릭 방지 |
| `UPLOADING` | S3 전송 중 | 진행률·취소 표시 |
| `COMPLETING` | 완료 확인 중 | 대기 |
| `ANALYZING` | 분석 생성 중 | 프로토타입 분석 안내 |
| `RECOMMENDING` | 추천 생성 중 | 카테고리 skeleton |
| `READY` | 추천 표시 가능 | 상세·구매 이동 |
| `ERROR` | 단계 실패 | 실패 단계만 재시도 |

새 업로드를 시작하면 이전 `imageId`와 Presigned 정보를 재사용하지 않는다. 분석 생성 이후
새로고침 복구는 `reportId`로 `GET /api/v1/analyses/{reportId}`를 호출해 처리한다.

## 5. UT 사전 준비

다음 명령은 계정 생성/로그인, 온보딩, JPEG/PNG 업로드, 분석, Shopify 추천을 한 번에
준비한다. 자격 증명은 Git 제외 파일에서 환경변수로 읽고 출력하지 않는다.

```bash
set -a
source .local/ut/credentials.env
set +a

FITBACK_UT_A_IMAGE=/absolute/path/top-01.jpeg \
FITBACK_UT_B_IMAGE=/absolute/path/ai-dress-01.png \
scripts/ut/prepare_prototype_ut.sh
```

민감정보 없는 결과는 기본적으로 `.local/ut/prepared-data.json`에 저장한다.
