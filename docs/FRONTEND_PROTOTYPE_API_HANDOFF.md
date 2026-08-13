# 프론트엔드 최소 프로토타입 API 전달서

> **문서 상태 (2026-08-13):** `develop` 구현 기준 프런트 연동 계약이다. 외부 운영 설정값은
> 별도 확인 시점의 스냅샷이며, 배포 전 AWS의 현재 값을 다시 확인한다.

## 1. 목적과 범위

프론트엔드는 이 문서를 기준으로 아래 세로 흐름을 연결한다.

```text
로그인
  → 이미지 업로드 요청
  → S3 Presigned POST
  → 업로드 완료
  → 분석 생성
  → 태그 확인
  → 추천 생성
  → 브라우저 Fashion-CLIP reranking
  → 구매 URL 이동
```

백엔드는 API와 운영 설정을 제공한다. 프론트의 `picsum.photos` 및 고정 상품 데이터 제거,
컴포넌트 상태 관리와 실제 API 응답 바인딩은 프론트 작업이다. 실제 의미 기반 AI 공급자 연동은
이 최소 프로토타입 범위에 포함하지 않는다.

## 2. 운영 환경

| 구분 | 값 |
| --- | --- |
| API base URL | `https://d1ra74et9h0ohu.cloudfront.net` |
| Swagger UI | `https://d1ra74et9h0ohu.cloudfront.net/swagger-ui.html` |
| 운영 프론트 Origin | `https://frontend-chi-one-35.vercel.app` |
| 인증 | `Authorization: Bearer {accessToken}` |
| API 응답 | `success`, `code`, `message`, `data` envelope |

UT 계정 이메일과 비밀번호는 Git에 저장하지 않으며 별도 보안 채널로 전달한다.

## 3. 프론트에서 백엔드로 확정해서 전달할 URL

AWS Systems Manager Parameter Store 값은 저장소 코드가 보장하는 상수가 아니다. 아래 값은
2026-07-30 확인 당시의 운영 스냅샷이며, 이름과 쿼리 형식은 연동 계약으로 사용하되 실제 값은
배포 전 Parameter Store에서 다시 확인한다.

| Parameter Store 이름 | 2026-07-30 확인 스냅샷 | 프론트 구현 계약 |
| --- | --- | --- |
| `/fitback/prod/front-redirect-uri` | `https://frontend-chi-one-35.vercel.app/oauth/kakao` | 카카오 로그인 성공과 실패를 모두 받는 화면 |
| `/fitback/prod/front-password-reset-url` | `https://frontend-chi-one-35.vercel.app/reset-password` | 메일의 비밀번호 재설정 토큰을 받는 화면 |

프론트팀은 두 경로가 최종 운영 라우트인지 확인하여 백엔드팀에 전달한다. 프론트 배포 Origin이
변경되면 아래 세 값을 함께 전달해야 한다.

1. 새 운영 Origin
2. 카카오 로그인 결과를 받을 전체 HTTPS URL
3. 비밀번호 재설정 화면의 전체 HTTPS URL

전달 요청 예시는 다음과 같다.

```text
운영 프론트 URL 확정을 요청드립니다.

- 운영 Origin:
- 카카오 로그인 결과 화면 전체 URL:
- 비밀번호 재설정 화면 전체 URL:

카카오 결과 화면은 tempToken 또는 error/message 쿼리를 처리해야 합니다.
비밀번호 재설정 화면은 resetToken 쿼리를 처리해야 합니다.
```

### 3.1 카카오 로그인 결과 화면

시작 URL:

```http
GET https://d1ra74et9h0ohu.cloudfront.net/api/v1/auth/oauth2/kakao
```

백엔드는 카카오 콜백 처리 후 Parameter Store의 `front-redirect-uri`에 다음 중 하나를 붙여
리다이렉트한다.

```text
성공: ?tempToken={oneTimeToken}
실패: ?error={wireCode}&message={urlEncodedMessage}
```

성공 시 프론트는 `tempToken`을 메모리에 읽은 직후 브라우저 주소에서 제거하고 다음 API로
교환한다.

```http
POST /api/v1/auth/token/exchange
Content-Type: application/json

{
  "tempToken": "{oneTimeToken}"
}
```

응답의 `data.accessToken`, `data.refreshToken`, `data.isNewMember`를 사용한다. 실제 JWT는
리다이렉트 URL에 포함되지 않는다.

Refresh Token 원문은 프론트에만 반환되며 서버에는 HMAC-SHA256 해시만 저장된다. 재발급이
성공하면 access/refresh token을 모두 새 값으로 교체한다. V31 배포 전에 발급된 Refresh Token은
폐기되므로 재발급 API가 `AUTH401_2`를 반환하면 저장된 token을 제거하고 로그인 화면으로 보낸다.

### 3.2 비밀번호 재설정 화면

백엔드는 메일 링크를 다음 형식으로 만든다.

```text
https://frontend-chi-one-35.vercel.app/reset-password?resetToken={oneTimeToken}
```

프론트는 `resetToken`과 새 비밀번호를 다음 API에 전달한다.

```http
PATCH /api/v1/auth/password-reset
Content-Type: application/json

{
  "resetToken": "{oneTimeToken}",
  "newPassword": "{newPassword}"
}
```

재설정 토큰은 5분 동안 유효하며 한 번 사용하면 폐기된다.

## 4. 공통 응답과 인증

성공:

```json
{
  "success": true,
  "code": "COMMON200_1",
  "message": "성공적으로 요청을 처리했습니다.",
  "data": {}
}
```

실패:

```json
{
  "success": false,
  "code": "COMMON401_1",
  "message": "인증이 필요합니다.",
  "data": null
}
```

- 로그인 응답의 토큰은 항상 `data` 아래에 있다.
- 이후 FIT-BACK API에는 `Authorization: Bearer {accessToken}`을 보낸다.
- S3 `uploadUrl`에는 JWT를 보내지 않는다.
- 비밀번호, JWT, `uploadFields`, `tempToken`, `resetToken`을 로그나 분석 도구에 기록하지 않는다.

## 5. 업로드→분석→추천 호출 순서

### 5.1 이메일 로그인

```http
POST /api/v1/auth/login
Content-Type: application/json

{
  "email": "{email}",
  "password": "{password}"
}
```

프론트가 사용하는 응답 필드:

```text
data.accessToken
data.refreshToken
data.memberId
data.email
data.nickname
data.profileImageUrl
data.loginProvider
```

### 5.2 이미지 업로드 요청

```http
POST /api/v1/images/upload-requests
Authorization: Bearer {accessToken}
Content-Type: application/json

{
  "purpose": "ANALYSIS",
  "contentType": "image/jpeg",
  "fileSize": 2627144
}
```

- 지원 MIME type: `image/jpeg`, `image/png`, `image/webp`
- 최대 크기: 5 MiB
- 프론트는 응답의 `data.imageId`를 분석 생성까지 유지한다.
- `data.uploadUrl`, `data.uploadFields`, `data.expiresAt`은 일시 정보다.
- 업로드 요청은 5분 동안 유효하다.

### 5.3 S3 Presigned POST

```javascript
const formData = new FormData();

for (const [key, value] of Object.entries(uploadFields)) {
  formData.append(key, value);
}
formData.append("file", file);

await fetch(uploadUrl, {
  method: "POST",
  body: formData,
});
```

주의사항:

- `uploadFields`를 먼저 넣고 마지막에 실제 `file`을 넣는다.
- 브라우저가 multipart boundary를 설정하도록 `Content-Type` 헤더를 직접 지정하지 않는다.
- S3 요청에 FIT-BACK JWT를 넣지 않는다.
- 성공 기준은 S3 응답 HTTP `204`다.

### 5.4 업로드 완료

```http
POST /api/v1/images/{imageId}/complete
Authorization: Bearer {accessToken}
```

성공 시 `data.status`는 `READY`다. 만료되었으면 다음 API에서 새 Presigned POST를 받아 같은
`imageId`로 다시 업로드한다.

```http
POST /api/v1/images/{imageId}/upload-request
Authorization: Bearer {accessToken}
```

### 5.5 분석 생성

운영에서는 multipart 분석 API를 사용하지 않는다.

```http
POST /api/v1/analyses
Authorization: Bearer {accessToken}
Content-Type: application/json

{
  "imageId": "{imageId}"
}
```

프론트가 유지할 응답:

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

신규 분석 생성 응답의 기본 `data.matchPercentage`는 `50`이다. 프론트는 이 응답값을 매칭 강도
조절 UI의 초기값으로 사용하고, 상수 `70`을 별도로 적용하지 않는다.

`data.imageUrl`은 비공개 CloudFront 서명 URL이며 10분 동안 유효하다. 영구 저장하지 말고 화면을
다시 열거나 이미지 로딩이 만료되면 `GET /api/v1/analyses/{reportId}`로 새 URL을 받는다.

현재 운영 `prototype` 분석기는 흐름 검증용 결정적 태그를 반환한다. 서로 다른 이미지의 의미를
판별하는 실제 AI 결과로 해석하지 않는다.

### 5.6 추천 생성

사용자가 확정한 기본 태그 ID와 직접 입력 태그를 보낸다.

```http
POST /api/v1/analyses/{reportId}/recommendations
Authorization: Bearer {accessToken}
Content-Type: application/json

{
  "confirmedTagIds": [1, 2],
  "customTagNames": ["미니멀"],
  "matchPercentage": 70
}
```

- 기본 태그와 직접 입력 태그 합계는 1~8개다.
- `matchPercentage`는 0~100이다.
- 예시의 `70`은 사용자가 기본값 `50`에서 조정한 뒤 명시적으로 전송한 값이다.
- body를 생략하면 현재 리포트 값을 응답에 유지하되 임계값 필터는 적용하지 않는다.
- body에 분석 생성 응답값 `50`을 명시하면 50점 미만 후보를 제외하는 임계값 필터가 적용된다.
  태그나 매칭값을 확정하지 않고 분석 결과를 그대로 추천하려면 body를 생략한다.
- 상품 category는 요청 body에 포함하지 않는다. 서버가 분석 리포트의 의류 category를 모든
  상품 검색에 자동 적용하고 다른 category 후보를 제외한다.
- 의류 category가 없는 기존 분석 리포트는 `ANALYSIS409_1`로 추천 생성을 거부한다.
- Demo/Prototype 분석으로 새로 생성한 리포트는 흐름 검증을 위해 의류 category를 `TOP`으로
  고정 저장한다.
- 응답은 8개 고정 카테고리 그룹을 반환하며 결과가 없는 그룹의 `items`는 빈 배열이다.
- `partial=true`이면 `warnings`를 사용자에게 비차단 안내로 표시한다.

실제 사용자에게 노출하는 추천 카드는 `data.browserReranking.candidates` 전체를 브라우저에서
Fashion-CLIP으로 재평가한 결과를 사용한다. 브라우저는 사용자 분석 이미지를 query로 사용하고,
각 후보 이미지의 normalized cosine을 `imageSimilarity`로 계산한 뒤 다음 점수로 전체 후보의
relevance 순서를 만든다.

```text
finalScore = imageSimilarity * 0.70 + tagSimilarity * 0.30
```

`finalScore DESC` 상위 `min(10, candidateCount)`를 선택하고, 선택 후보 모두의 가격이 유한하고
동일 통화일 때만 가격 오름차순으로 표시한다. 이외에는 relevance 순서를 유지한다. 후보 이미지는
브라우저가 직접 가져오며, 후속 candidate resolve나 Shopify metadata API를 호출하지 않는다.
Browser score는 서버로 제출하거나 저장하지 않는다.

사용자 노출 추천 카드의 데이터 바인딩:

| UI | API 필드 |
| --- | --- |
| 불투명 후보 식별자 | `data.browserReranking.candidates[].candidateId` |
| 상품 이미지 | `data.browserReranking.candidates[].imageUrl` |
| 태그 점수 | `data.browserReranking.candidates[].tagSimilarity` |
| 상품명 | `data.browserReranking.candidates[].name` |
| 판매처 | `data.browserReranking.candidates[].sellerName` |
| 가격 | `data.browserReranking.candidates[].price.amount`, `.currency` |
| 구매 URL | `data.browserReranking.candidates[].purchaseUrl` |

`candidateId`는 member-bound opaque token이며 persisted `productId`나 Shopify 내부 ID로 해석하지
않는다. `data.recommendationGroups[].items[].similarityScore`와 해당 순위는 백엔드 호환성·추천
이력 저장용 임시 내부 결과다. 고정 이미지 점수 70을 사용하므로 실제 사용자 노출 추천 순위를
결정하지 않는다.

`picsum.photos`나 고정 상품 배열을 fallback으로 사용하지 않는다. 결과가 없으면 빈 상태 UI를
표시하고, `imageUrl`만 없으면 이미지 없음 UI를 표시한다.

### 5.7 백엔드 영속 추천 항목 상세와 구매 이동

이 절은 `recommendationGroups[].items[].productId`를 사용하는 기존 저장·상세 흐름이다.
사용자 노출 browser reranking 후보의 `candidateId`는 `productId`가 아니므로 이 상세 API에
전달하지 않는다. Browser 추천 카드는 handoff snapshot의 `purchaseUrl`로 직접 이동한다.

```http
GET /api/v1/products/{productId}
Authorization: Bearer {accessToken}
```

상세 화면은 추천 카드의 과거 값을 그대로 재사용하지 않고 상세 응답을 표시한다.

```text
data.productId
data.imageUrl
data.name
data.brandName
data.sellerName
data.category
data.price
data.purchaseUrl
data.affiliateUrl
data.availability
data.dataStatus
data.tags
data.isSaved
```

Shopify 상품은 provider/product/variant/merchant ID만 서버에 저장한다. 상품명·가격·이미지·구매
URL은 응답 시 live lookup하므로 프론트가 별도 snapshot을 영구 저장하지 않는다.

- `dataStatus=LIVE`: 현재 응답을 정상 표시한다.
- `dataStatus=STALE_SNAPSHOT`: 오래된 데이터임을 표시하고 구매 이동 전 상세를 다시 조회한다.
- `availability=TEMPORARILY_UNRESOLVED`: 구매 버튼을 비활성화한다.
- 구매 이동은 `affiliateUrl`이 있으면 우선 사용하고, 없으면 `purchaseUrl`을 사용한다.

### 5.8 프로필 이미지 업로드와 연결

프로필 이미지도 분석 이미지와 같은 Presigned POST 흐름을 사용하되 업로드 목적만 `PROFILE`로
지정한다.

```http
POST /api/v1/images/upload-requests
Authorization: Bearer {accessToken}
Content-Type: application/json

{
  "purpose": "PROFILE",
  "contentType": "image/jpeg",
  "fileSize": 1048576
}
```

S3 업로드와 `POST /api/v1/images/{imageId}/complete`가 끝난 뒤, 응답받은 `imageId`를 회원
수정이나 온보딩 요청의 `profileImageId`로 보낸다.

```http
PATCH /api/v1/members/me
Authorization: Bearer {accessToken}
Content-Type: application/json

{
  "profileImageId": "{imageId}"
}
```

신규 회원은 `PUT /api/v1/members/me/onboarding`의 `profileImageId` 필드에 같은 값을 넣는다.
백엔드는 업로드가 완료된 본인 소유 `PROFILE` 이미지만 연결하며 회원·로그인·룩북 응답에는
저장 식별자가 아닌 `profileImageUrl`을 반환한다. 이 URL은 비공개 이미지의 10분짜리 서명
URL이므로 영구 저장하지 않고 해당 회원 또는 룩북 API를 다시 조회해 갱신한다.

### 5.9 알림 API와 현재 구현 경계

다음 API는 모두 로그인이 필요하다.

| Method | Endpoint | 현재 구현 |
| --- | --- | --- |
| GET | `/api/v1/members/me/notification-settings` | 설정 조회, 없으면 기본값 생성 |
| PATCH | `/api/v1/members/me/notification-settings` | 전달된 설정만 부분 수정 |
| GET | `/api/v1/notifications?cursor={id}&pageSize={1..50}` | 알림 목록·전체 미읽음 수 조회, 기본 20건 |
| PATCH | `/api/v1/notifications/{notificationId}/read` | 본인 알림 단건 읽음 처리 |
| PATCH | `/api/v1/notifications/read` | 본인 알림 전체 읽음 처리 |
| DELETE | `/api/v1/notifications/{notificationId}` | 본인 알림 삭제 |

설정 수정 필드는 `analysisCompleteEnabled`, `lookbookLikedEnabled`, `trendUpdateEnabled`,
`marketingEnabled`이며 하나 이상을 보내야 한다. 목록 항목은 `notificationId`,
`notificationType`, `title`, `body`, `targetType`, `targetId`, `readAt`, `createdAt`을 반환한다.

현재 `develop`에는 알림 설정과 목록·읽음·삭제 API 및 저장 테이블은 구현되어 있지만, 분석
완료·룩북 좋아요·트렌드 갱신 이벤트로 알림 레코드를 자동 생성하는 producer는 연결되어 있지
않다. 따라서 프론트는 빈 목록과 관리 동작을 연동할 수 있으나, 이벤트 발생 후 새 알림이
자동으로 생긴다고 가정하면 안 된다.

## 6. 화면 상태

```text
IDLE
  → REQUESTING_UPLOAD
  → UPLOADING_TO_S3
  → COMPLETING_UPLOAD
  → ANALYZING
  → EDITING_TAGS
  → GENERATING_RECOMMENDATIONS
  → READY
```

각 단계가 실패하면 이전에 성공한 `imageId` 또는 `reportId`를 유지하고 실패 단계부터 재시도한다.
추천 요청을 중복 실행해도 같은 입력이면 서버가 현재 세트를 반환한다.

## 7. 주요 오류 처리

| Wire code | 프론트 처리 |
| --- | --- |
| `COMMON401_1` | access token 재발급 후 한 번 재시도, 실패하면 로그인 |
| `COMMON400_2` | 입력 필드 오류 표시 |
| `AUTH401_1` | 이메일 또는 비밀번호 오류 |
| `AUTH401_2` | Refresh Token이 만료·폐기·불일치함. 저장된 token을 제거하고 재로그인 |
| `IMAGE400_1` | 지원 형식 안내 |
| `IMAGE409_1` | 현재 이미지 상태를 다시 확인 |
| `IMAGE410_1` | Presigned POST 재발급 후 재업로드 |
| `IMAGE422_1` | 실제 이미지 파일이 잘못됨 |
| `ANALYSIS400_3` | multipart 대신 `imageId` JSON 흐름 사용 |
| `ANALYSIS409_1` | 추천에 필요한 분석 결과가 없음. 의류 category가 없는 기존 리포트는 동일 요청을 재시도하지 말고, 신규 분석을 생성한 뒤 새로운 `reportId`로 추천 요청 |
| `PRODUCT503_1` | 상품 공급자 일시 장애 안내와 재시도 |
| `PRODUCT503_2` | 요청 한도 초과 안내, 즉시 반복 호출 금지 |
| `RECOMMENDATION409_1` | 최신 태그·매칭값으로 추천 재생성 |

## 8. 프론트 완료 기준

- [ ] 운영 Origin, 카카오 결과 URL, 비밀번호 재설정 URL을 백엔드팀에 확정 전달했다.
- [ ] 카카오 결과 화면이 `tempToken`과 `error/message`를 모두 처리한다.
- [ ] 비밀번호 재설정 화면이 `resetToken`을 처리한다.
- [ ] JWT는 FIT-BACK API에만 보내고 S3에는 보내지 않는다.
- [ ] JPEG와 PNG 각각 한 건의 `204 → READY → 분석 → 추천` 흐름을 확인했다.
- [ ] 업로드 원본은 분석 응답의 `imageUrl`을 표시한다.
- [ ] 추천 카드는 `browserReranking.candidates[]`를 Fashion-CLIP으로 재평가한 top-10을 표시한다.
- [ ] Browser 추천 카드의 handoff `purchaseUrl`로 이동하며 `candidateId`를 상품 ID로 사용하지 않는다.
- [ ] 백엔드 영속 추천 항목을 별도 표시하는 화면만 `productId`로 상품 상세를 다시 조회한다.
- [ ] `picsum.photos` 및 고정 상품 데이터가 API 연결 화면에서 제거됐다.
- [ ] 토큰과 Presigned 값을 브라우저 로그·분석 도구에 남기지 않는다.
- [ ] 프로필 이미지는 `purpose=PROFILE`로 업로드하고 `profileImageId`로 연결한다.
- [ ] 화면에는 응답의 `profileImageUrl`을 사용하고 만료 시 회원·룩북 API를 다시 조회한다.
- [ ] 알림 화면은 빈 목록을 처리하며 자동 알림 생성은 별도 백엔드 구현 전까지 가정하지 않는다.

전체 API 예시는 [API_SPEC.md](API_SPEC.md), 기존 단계별 예시는
[PROTOTYPE_FRONTEND_VERTICAL_FLOW.md](PROTOTYPE_FRONTEND_VERTICAL_FLOW.md)를 참고한다.
