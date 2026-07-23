# FIT-BACK 이미지 저장소 정책

> 문서 상태: 확정 목표 정책 및 현재 구현 차이 기록
>
> 기준: 2026-07-24 승인 정책, 기존 Presigned PUT 정책 대체
>
> 대상 범위: 사용자 업로드 이미지의 Presigned POST 발급, S3 저장, CloudFront 제공, 상태 관리 및 삭제 정책
> 작성일: 2026-07-24

## 1. 목적

FIT-BACK의 사용자 업로드 이미지를 안전하게 저장하고, 업로드 완료 전 이미지와 실제 도메인에서 사용 중인 이미지를 구분하여 관리한다.

기본 흐름은 다음과 같다.

```text
프론트엔드
  → 백엔드에 Presigned POST 정보 요청
  → S3에 이미지 직접 업로드
  → 백엔드 업로드 완료 API 호출
  → 분석·룩북·프로필 API에 imageId 또는 images[] 전달
  → CloudFront를 통해 이미지 표시
```

핵심 원칙은 다음과 같다.

- 사용자 업로드 이미지는 서버가 발급한 `imageId`로 관리한다.
- S3 object key에는 원본 파일명이나 사용자 입력값을 사용하지 않는다.
- S3 업로드 성공만으로 이미지를 사용 가능 상태로 보지 않는다.
- 완료 API의 서버 검증을 통과한 이미지만 도메인 API에 연결할 수 있다.
- 분석·룩북·프로필 등 도메인 데이터와 연결된 이미지만 `ACTIVE` 상태로 변경한다.
- 24시간 자동 정리는 `PENDING_UPLOAD`, `READY`, `REJECTED`이면서 실제 도메인 참조가 없는 이미지만 대상으로 한다.
- 외부 쇼핑 API의 상품 이미지는 사용자 업로드 이미지와 분리하며 FIT-BACK S3에 복사하지 않는다.

## 2. 확정 목표 정책 요약

| 항목 | 정책 |
|---|---|
| 업로드 방식 | Presigned POST, `FormData` 사용 |
| 업로드 정보 발급 API | `POST /api/v1/images/upload-requests` |
| 업로드 완료 API | `POST /api/v1/images/{imageId}/complete` |
| 재발급 API | `POST /api/v1/images/{imageId}/upload-request` |
| 업로드 응답 | `imageId`, `uploadUrl`, `uploadMethod=POST`, `uploadFields`, `expiresAt` |
| 이미지 용도 | `ANALYSIS`, `LOOKBOOK`, `PROFILE` |
| 허용 형식 | JPEG, PNG, WEBP |
| 최대 용량 | 파일당 5MB, `5 * 1024 * 1024` bytes |
| Presigned POST 유효시간 | 5분 |
| 초기 상태 | `PENDING_UPLOAD` |
| 완료 검증 성공 상태 | `READY` |
| 도메인 연결 성공 상태 | `ACTIVE` |
| 미사용 이미지 정리 | 24시간이 지난 `PENDING_UPLOAD`/`READY` 중 도메인 참조가 없는 항목 |
| 검증 실패 이미지 정리 | `REJECTED` 전환 후 24시간 정리 대상에 포함 |
| 정리 작업 주기 | 1시간마다 배치 실행 |
| 삭제 실패 | `DELETE_FAILED`로 전환 후 다음 스케줄에서 재시도 |
| 복수 업로드 | 프론트에서 최대 2~3개 병렬 업로드 권장 |
| S3 공개 여부 | 비공개 버킷, Block Public Access, CloudFront OAC 적용 |
| 비공개 이미지 제공 | CloudFront Signed URL, MVP 기본 10분 |
| 외부 상품 이미지 | 공급자 상품 참조와 CDN URL 사용, FIT-BACK S3 복사 금지 |

## 3. 현재 구현 상태와 반영 원칙

이 문서는 최종 목표 정책을 정의한다. 2026-07-24 기준 Issue #95는 expand/contract 호환 릴리스 A만 수행한다. 운영 S3 CORS 반영과 production smoke test는 별도 운영 이슈 `#83`에서 확인한다.

| 항목 | 공용 API 계약 | 릴리스 A 구현 | 후속 단계 |
|---|---|---|---|
| S3 업로드 방식 | Presigned POST | Presigned POST | 유지 |
| 발급 API | `POST /api/v1/images/upload-requests` | 동일 | 유지 |
| 업로드 응답 | `imageId`, `uploadUrl`, `uploadMethod=POST`, `uploadFields`, `expiresAt` | 동일, `requiredHeaders`/`imageUrl` 미포함 | 유지 |
| API 이미지 용도 | `ANALYSIS`, `LOOKBOOK`, `PROFILE` | request DTO에서 사용 | 유지 |
| DB purpose 저장 | API 값과 분리 | 신규 writer는 `ANALYSIS→ANALYSIS_ORIGINAL`, `LOOKBOOK→LOOKBOOK_ORIGINAL`, `PROFILE→PROFILE` 저장. 기존 `LOOKBOOK_MATCHED`는 보존 | B(`#96`)에서 new-write/backfill, C(`#97`)에서 legacy 제거 |
| API 논리 초기 상태 | `PENDING_UPLOAD` | API 명세·도메인 논리 상태로 사용 | 유지 |
| DB status 저장 | API 값과 분리 | 신규 writer는 rollback 호환을 위해 `PENDING` 저장. reader/domain check는 `PENDING`/`PENDING_UPLOAD` 모두 지원 | B(`#96`)에서 new-write/backfill, C(`#97`)에서 legacy 제거 |
| V4 migration | 호환 제약 확장 | 데이터 UPDATE 없이 check constraint만 old/new purpose와 `PENDING`/`PENDING_UPLOAD`를 모두 허용 | C(`#97`)에서 constraint 축소 |
| Object key | `images/{purpose}/{memberId}/{yyyy}/{MM}/{imageId}.{ext}` | API purpose 기반 신규 key 사용 | 유지 |
| 기존 S3 객체 | 기존 경로에 존재 | 이동하지 않음 | 조회 호환 유지 |
| `retryCount`, `nextRetryAt` | 저장 유지 | DB에 저장 | 유지 |
| 자동 삭제 조회 | 논리 미사용 이미지 정리 | `PENDING`/`PENDING_UPLOAD`는 `createdAt`, `READY`/`REJECTED`는 `COALESCE(uploadedAt, createdAt)`, `DELETE_FAILED`는 `nextRetryAt` 기준 | 유지 |
| multipart 분석 API | 전환 기간 유지 | 호환용으로 유지 | 후속 contract 이슈에서 정리 |
| JSON 분석 API 요청 | 현재 단일 `{ imageId }` | 유지 | 후속 contract 이슈에서 `images[]/role/sourceType` 전환 |
| `ACTIVE` 마지막 참조 해제 자동 삭제 | 후속 기능 | 완료로 표시하지 않음 | 후속 contract 이슈에서 구현 |

기존 운영 데이터와 S3 객체는 강제로 이동하지 않는다. 새 object key 정책은 신규 업로드부터 적용한다.

## 4. 이미지 용도

업로드 요청의 `purpose`는 저장 용도를 나타낸다.

| 화면 또는 기능 | `purpose` |
|---|---|
| AI 태그 분석 사진 | `ANALYSIS` |
| 룩북 원본 또는 매칭 사진 | `LOOKBOOK` |
| 프로필 사진 | `PROFILE` |

기존처럼 룩북 내부 역할을 별도 `purpose` 값으로 분리하지 않는다. 룩북 내부 역할은 도메인 API의 `images[].role`로 구분한다.

## 5. 이미지 생명주기 상태

### 5.1 상태 정의

| 상태 | 의미 | S3 객체 | 자동 삭제 대상 |
|---|---|---:|---:|
| `PENDING_UPLOAD` | Presigned POST 발급 후 완료 검증 전 | 없거나 있을 수 있음 | 예 |
| `READY` | 완료 API에서 객체 크기·MIME·시그니처 검증 완료, 도메인 연결 전 | 있음 | 예 |
| `ACTIVE` | 하나 이상의 도메인 데이터에서 참조 중 | 있음 | 아니오 |
| `DELETING` | 삭제 작업자가 삭제를 선점한 상태 | 있을 수 있음 | 처리 중 |
| `DELETE_FAILED` | S3 삭제에 실패하여 재시도가 필요한 상태 | 있음 | 재시도 대상 |
| `DELETED` | S3 객체 삭제가 완료된 상태 | 없음 | 아니오 |
| `REJECTED` | 파일 크기, 형식, 시그니처 또는 디코딩 검증 실패 | 있을 수 있음 | 예 |

`READY`는 S3 전송만 끝났다는 의미가 아니다. 완료 API의 서버 검증까지 통과해 도메인 연결이 가능한 상태다.

### 5.2 기본 상태 전이

```text
Presigned POST 발급
        ↓
 PENDING_UPLOAD
        ↓ S3 직접 업로드
        ↓ 완료 API에서 실제 파일 검증
      READY
        ↓ 도메인 연결 성공
      ACTIVE
```

삭제 상태 전이는 다음과 같다.

```text
PENDING_UPLOAD/READY/REJECTED ── 24시간 미사용 ──→ DELETING ── S3 삭제 성공 ──→ DELETED
                                           └── S3 삭제 실패 ──→ DELETE_FAILED
                                                                      │
                                                                      └── 재시도 시각 도달 ──→ DELETING

[최종 목표·후속 기능]
ACTIVE ── 모든 참조 해제 ──→ DELETING ── S3 삭제 성공 ──→ DELETED
                      └── S3 삭제 실패 ──→ DELETE_FAILED
```

`REJECTED`는 MVP에서 즉시 삭제하지 않고 기존 정책처럼 24시간 정리 대상에 포함한다.

## 6. Presigned POST 발급 정책

### 6.1 요청

```http
POST /api/v1/images/upload-requests
Content-Type: application/json
```

```json
{
  "purpose": "ANALYSIS",
  "contentType": "image/jpeg",
  "fileSize": 3145728
}
```

요청 규칙은 다음과 같다.

- `purpose`는 `ANALYSIS`, `LOOKBOOK`, `PROFILE` 중 하나다.
- `contentType`은 최종 업로드 파일의 MIME 타입이다.
- `fileSize`는 최종 업로드 파일의 바이트 크기다.
- 백엔드는 허용 MIME과 5MB 제한을 발급 단계에서 1차 검증한다.

### 6.2 응답

```json
{
  "imageId": "019c1234-abcd-7000-8000-123456789abc",
  "uploadUrl": "https://s3.example.com/fitback-bucket",
  "uploadMethod": "POST",
  "uploadFields": {
    "key": "images/analysis/42/2026/07/019c1234-abcd-7000-8000-123456789abc.jpg",
    "Content-Type": "image/jpeg",
    "success_action_status": "204",
    "policy": "...",
    "x-amz-algorithm": "AWS4-HMAC-SHA256",
    "x-amz-credential": "...",
    "x-amz-date": "20260724T000000Z",
    "x-amz-signature": "..."
  },
  "expiresAt": "2026-07-24T00:05:00+09:00"
}
```

응답 규칙은 다음과 같다.

- `uploadMethod`는 항상 `POST`다.
- `uploadFields`의 모든 값을 프론트가 `FormData`에 그대로 포함한다.
- POST policy는 bucket, object key, MIME, 성공 상태와 5분 만료를 제한하고,
  `content-length-range`의 최소·최대값을 요청 `fileSize`로 동일하게 설정해 실제 업로드 크기를 제한한다.
- 발급 후 다른 파일로 교체하거나 바이트 크기가 달라지면 S3 업로드 단계에서 실패한다. 파일을 바꾼 경우 새 업로드 요청을 발급한다.
- EC2 역할처럼 임시 자격 증명을 사용하면 `uploadFields`와 POST policy에 `x-amz-security-token`을 포함한다.
- `uploadUrl`은 S3 업로드 전용 주소이며 FIT-BACK API 주소가 아니다.
- `uploadUrl`, `uploadFields`, Presigned 서명 값은 DB에 영구 저장하지 않는다.
- Presigned 정보는 로그, 오류 수집 도구, 분석 도구에 기록하지 않는다.

### 6.3 S3 업로드

Presigned PUT이 아니라 Presigned POST 방식이다. 프론트는 백엔드가 반환한 `uploadFields`를 모두 `FormData`에 넣고 파일을 마지막에 추가한다.

```ts
async function uploadToS3(
  file: File,
  uploadUrl: string,
  uploadFields: Record<string, string>,
  signal?: AbortSignal,
) {
  const formData = new FormData();

  Object.entries(uploadFields).forEach(([key, value]) => {
    formData.append(key, value);
  });

  formData.append("file", file);

  const response = await fetch(uploadUrl, {
    method: "POST",
    body: formData,
    signal,
  });

  if (!response.ok) {
    throw new Error(`S3_UPLOAD_FAILED:${response.status}`);
  }
}
```

주의사항은 다음과 같다.

- `Content-Type: multipart/form-data` 헤더를 직접 설정하지 않는다. 브라우저가 boundary를 포함해 자동 설정해야 한다.
- 백엔드 API용 `Authorization` 헤더를 S3 요청에 넣지 않는다.
- 백엔드 API 클라이언트의 공통 interceptor를 S3 요청에 적용하지 않는다.
- S3 응답은 FIT-BACK 공통 JSON 형식이 아니며 성공 시 본문이 비어 있을 수 있다.
- `uploadFields`의 필드를 누락하거나 수정하지 않는다.
- 파일은 가능한 한 FormData의 마지막 필드로 추가한다.

## 7. S3 object key 정책

신규 업로드의 object key 형식은 다음과 같다.

```text
images/{purpose}/{memberId}/{yyyy}/{MM}/{uuid}.{server-determined-extension}
```

예시는 다음과 같다.

```text
images/analysis/42/2026/07/019c1234-abcd-7000-8000-123456789abc.jpg
```

규칙은 다음과 같다.

- `purpose`는 소문자로 변환한다.
- `memberId`는 인증된 회원 ID를 사용한다.
- `uuid`는 서버가 생성한 `imageId`를 사용한다.
- 확장자는 백엔드가 허용 MIME 기준으로 결정한다.
- 원본 파일명과 사용자 입력값은 object key에 포함하지 않는다.
- 환경 구분은 버킷 또는 AWS 계정·설정으로 처리하고 신규 object key에는 `prod/` 같은 환경 prefix를 붙이지 않는다.
- 기존 `prod/images/...` 등의 객체는 이동하지 않고 기존 경로로 조회 호환성을 유지한다.

## 8. 업로드 완료와 재발급

### 8.1 완료 API

```http
POST /api/v1/images/{imageId}/complete
```

완료 API는 S3에 업로드된 실제 객체를 검증한다.

| 검증 항목 | 기준 |
|---|---|
| 객체 존재 | object key에 객체가 있어야 함 |
| 파일 크기 | 5MB 이하 |
| MIME | JPEG, PNG, WEBP 중 하나 |
| 파일 시그니처 | MIME과 실제 파일 시그니처 일치 |
| 소유권 | 요청 회원이 `imageId` 소유자 |
| 상태 | `PENDING_UPLOAD` 상태 |

검증 성공 시 `PENDING_UPLOAD → READY`로 전환한다. 검증 실패 시 `REJECTED`로 전환하고 24시간 정리 대상에 포함한다.
릴리스 A에서는 DB의 `PENDING`과 미래 저장값 `PENDING_UPLOAD`를 모두 논리
`PENDING_UPLOAD`로 처리하므로 완료와 재발급 조건이 같다.

### 8.2 재발급 API

```http
POST /api/v1/images/{imageId}/upload-request
```

재발급 규칙은 다음과 같다.

- 아직 `PENDING_UPLOAD`인 본인 이미지에 대해서만 재발급한다.
- 같은 `imageId`와 object key를 유지한다.
- 기존 Presigned 정보를 연장하지 않고 `uploadUrl`, `uploadFields`, `expiresAt` 전체를 새로 발급한다.
- 프론트는 최초 `uploadFields`를 새 `uploadUrl`과 섞어 사용하지 않는다.
- URL 만료로 판단되는 경우 재발급 후 1회만 재시도한다.

## 9. 미사용 이미지 자동 삭제

### 9.1 삭제 대상

다음 조건을 모두 만족하는 이미지만 자동 삭제한다.

```text
(
  status IN (PENDING, PENDING_UPLOAD) AND createdAt < 현재 시각 - 24시간
  OR status = READY AND COALESCE(uploadedAt, createdAt) < 현재 시각 - 24시간
  OR status = REJECTED AND COALESCE(uploadedAt, createdAt) < 현재 시각 - 24시간
  OR status = DELETE_FAILED AND (nextRetryAt IS NULL OR nextRetryAt <= 현재 시각)
)
AND 분석·룩북·프로필 등 실제 도메인 참조가 존재하지 않음
```

정리 작업은 1시간마다 실행한다. 실제 보관 시간은 약 24~25시간이 될 수 있다.
`READY`/`REJECTED`의 `uploadedAt`이 없는 기존 데이터는 `createdAt`을 기준으로 한다. 신규 삭제 실패는 `nextRetryAt`을 반드시 저장하되, 값이 없는 기존 `DELETE_FAILED` 데이터가 영구 정체되지 않도록 즉시 재시도 대상으로 취급한다. `DELETE_FAILED`에는 24시간 경과 조건을 다시 적용하지 않는다.

### 9.2 삭제 처리 순서

1. 오래된 `PENDING`/`PENDING_UPLOAD`/`READY`/`REJECTED` 또는 재시도 시각에 도달한 `DELETE_FAILED` 중 도메인 참조가 없는 이미지를 일정 개수만큼 조회한다.
2. 같은 트랜잭션에서 참조 부재를 다시 확인한다.
3. DB 락 또는 조건부 상태 변경으로 `DELETING`을 선점한다.
4. 선점에 성공한 작업자만 S3 객체를 삭제한다.
5. S3 객체가 이미 없다면 삭제 성공으로 처리한다.
6. 삭제 성공 시 `DELETING → DELETED`로 변경한다.
7. 삭제 실패 시 `DELETING → DELETE_FAILED`로 변경한다.
8. `DELETE_FAILED`는 다음 1시간 스케줄에서 다시 조회한다.

`retryCount`, `nextRetryAt`은 현재 구현처럼 DB에 저장한다. 지수 백오프와 최대 재시도 정책은 후속 운영 개선 항목으로 둔다.

## 10. 사용 중 이미지 삭제 최종 목표(후속 기능)

`ACTIVE` 이미지는 분석 리포트, 룩북, 프로필 등에서 공유될 수 있다.

```text
분석 리포트 ─┐
             ├─ 동일한 imageId
룩북 게시물 ─┘
```

다음 흐름은 현재 MVP 완료 항목이 아니라 전체 도메인 연동 뒤 적용할 최종 목표다.

1. 삭제하는 도메인과 이미지 사이의 참조를 제거한다.
2. 남아 있는 이미지 참조 수를 확인한다.
3. 참조가 하나라도 있으면 `ACTIVE` 상태를 유지한다.
4. 참조가 0이면 `ACTIVE → DELETING`으로 변경한다.
5. 비동기 삭제 작업자가 S3 객체를 삭제한다.
6. 삭제 성공 시 `DELETED`, 실패 시 `DELETE_FAILED`로 변경한다.

DB 트랜잭션 안에서 S3 삭제를 직접 실행하지 않는다. 다만 `ACTIVE` 마지막 참조 해제 자동 삭제는 MVP 완료 항목으로 표시하지 않고 후속 기능으로 관리한다.

## 11. 공개 범위와 CloudFront

이미지 생명주기 상태와 공개 범위는 별도로 관리한다.

```text
status(logical): PENDING_UPLOAD | READY | ACTIVE | DELETING | DELETE_FAILED | DELETED | REJECTED
status(persisted compatibility): PENDING | PENDING_UPLOAD | READY | ACTIVE | DELETING | DELETE_FAILED | DELETED | REJECTED
visibility: PRIVATE | PUBLIC
purpose(logical): ANALYSIS | LOOKBOOK | PROFILE
purpose(persisted compatibility): ANALYSIS_ORIGINAL | LOOKBOOK_ORIGINAL | LOOKBOOK_MATCHED | PROFILE | ANALYSIS | LOOKBOOK
```

| 이미지 종류 | MVP 공개 범위 | 제공 방법 |
|---|---|---|
| 분석 원본 | `PRIVATE` | CloudFront Signed URL |
| 프로필 이미지 | `PRIVATE` | CloudFront Signed URL |
| 공개 룩북 | `PUBLIC` | 일반 CloudFront URL |
| `PENDING_UPLOAD`/`READY` 이미지 | 비공개 | CloudFront 접근 차단 |
| 외부 상품 이미지 | 이미지 저장 정책 대상 아님 | 공급자 CDN URL |

MVP에서는 비공개 CloudFront Signed URL 유효시간을 기존 구현처럼 10분으로 유지한다. CloudFront 캐시 TTL, invalidation, Signed Cookie 적용 여부는 후속 운영 정책에서 확정한다.

룩북 이미지는 업로드 시점에는 `PRIVATE`로 시작한다. 공개 룩북 등록이 성공한 뒤 도메인 정책에 따라 `PUBLIC`으로 전환한다.

## 12. 단일 및 복수 이미지 전달 계약

이미지 한 장마다 하나의 `imageId`와 Presigned POST 정보를 사용한다. 복수 업로드는 프론트에서 최대 2~3개 병렬 처리를 권장하며, 실패한 이미지만 독립적으로 재시도한다.

분석·룩북 API에는 임의의 외부 `imageUrl` 대신 이미지 출처와 역할을 명시한 `images[]` 배열을 전달한다.

```json
{
  "images": [
    {
      "role": "ORIGINAL",
      "sourceType": "UPLOADED",
      "imageId": "019c1234-abcd-7000-8000-123456789abc"
    },
    {
      "role": "MATCHED",
      "sourceType": "PRODUCT",
      "productId": 123
    }
  ]
}
```

직접 업로드한 매칭 이미지라면 다음과 같이 전달한다.

```json
{
  "role": "MATCHED",
  "sourceType": "UPLOADED",
  "imageId": "019d1234-abcd-7000-8000-123456789abc"
}
```

전달 규칙은 다음과 같다.

- 분석 API는 `ORIGINAL + UPLOADED + imageId` 이미지 1장을 사용한다.
- 룩북 API는 `ORIGINAL`과 `MATCHED` 역할을 구분한다.
- 룩북의 `MATCHED` 이미지는 직접 업로드 이미지(`UPLOADED + imageId`) 또는 상품 이미지(`PRODUCT + productId`)일 수 있다.
- 프로필 API는 단일 `imageId`를 사용한다.
- 사용자 업로드 이미지는 `imageId`로 전달한다.
- 쇼핑 API 상품 이미지는 내부 `productId`로 전달한다.
- `imageUrl`은 응답과 화면 표시 용도로만 사용한다.
- 이미지 순서에 의미가 있으면 배열 위치가 아니라 `role`로 구분한다.
- 여러 이미지는 쉼표 문자열이 아닌 JSON 배열로 전달한다.

현재 구현에는 multipart 분석 API와 단일 `{ imageId }` JSON 분석 API가 모두 있다. 전환 기간에는 두 API를 호환용으로 유지하며, 목표 신규 도메인 계약은 위 `images[]/role/sourceType` 형식을 기준으로 별도 기능 이슈에서 반영한다.

## 13. 외부 쇼핑 API 이미지

외부 쇼핑 API가 제공한 상품 이미지는 FIT-BACK 사용자 업로드 이미지와 분리한다.

- 외부 상품 이미지를 FIT-BACK S3에 복사하지 않는다.
- 상품 공급자의 CDN URL은 화면 표시 용도로만 사용한다.
- 프론트가 임의의 외부 URL을 룩북 또는 추천 API에 전달하지 않는다.
- 프론트는 내부 `productId`를 전달하고 백엔드가 등록된 상품 정보를 조회한다.
- 장기간 유지해야 하는 룩북 이미지는 사용자가 직접 업로드한 매칭 사진 사용을 우선한다.
- 외부 상품 이미지의 만료나 삭제에 대비해 플레이스홀더를 제공한다.

외부 상품 이미지는 `PENDING_UPLOAD`, `READY`, `ACTIVE`, `DELETING` 등의 이미지 생명주기 상태와 24시간 미사용 이미지 정리 대상에 포함하지 않는다.

## 14. 프론트엔드 업로드 UX 기준

이미지별 상태는 독립적으로 관리한다.

```text
IDLE → VALIDATING → REQUESTING_URL → UPLOADING → COMPLETING → SUCCESS
                                                └────────────→ FAILED
```

권장 정책은 다음과 같다.

- 이미지 선택 직후 로컬 미리보기를 표시한다.
- 이미지별 업로드 진행률과 실패 상태를 관리한다.
- 필수 이미지가 모두 `SUCCESS`일 때만 등록 버튼을 활성화한다.
- 업로드 중에는 이미지 교체와 중복 등록을 방지한다.
- 네트워크 오류와 S3 5xx는 동일 URL이 유효한 동안 최대 2회 재시도한다.
- URL 만료 시 새 Presigned POST 정보를 발급해 최대 1회 재시도한다.
- S3 `403`을 무조건 URL 만료로 간주하지 않는다.
- `uploadUrl`을 이미지 표시용 `src`로 사용하지 않는다.
- S3 object URL을 프론트에서 조합해 화면에 표시하지 않는다.
- 생성한 `URL.createObjectURL`은 파일 교체나 컴포넌트 unmount 시 해제한다.

## 15. 보안 및 개인정보

- Presigned URL과 `uploadFields`를 콘솔에 출력하지 않는다.
- Presigned 정보를 오류 수집 도구의 request body로 전송하지 않는다.
- S3 object key를 사용자가 수정할 수 있는 상태로 노출하지 않는다.
- 분석 원본 이미지 URL을 공개 링크처럼 공유하거나 장기 저장하지 않는다.
- `imageId`만으로 다른 사용자의 이미지를 사용할 수 있다고 가정하지 않는다.
- 소유권이 없는 이미지와 존재하지 않는 이미지는 보안을 위해 동일한 오류로 응답할 수 있다.
- 파일의 EXIF 정보가 제거된다고 가정하지 않는다. EXIF 제거는 후속 정책에서 확정한다.
- 원본 파일명은 개인정보를 포함할 수 있으므로 서버 전송이나 로그 기록을 최소화한다.

## 16. 에러 코드 권고안

### 16.1 백엔드 API

| HTTP | 코드 | 의미 |
|---:|---|---|
| 400 | `IMAGE400_1` | 지원하지 않는 이미지 형식 |
| 400 | `COMMON400_1` 또는 `COMMON400_2` | 필수값, enum 또는 파일 크기 범위 위반 |
| 404 | `IMAGE404_1` | 이미지가 없거나 소유권이 없음 |
| 409 | `IMAGE409_1` | 업로드되지 않았거나 사용할 수 없는 상태 |
| 410 | `IMAGE410_1` | Presigned 업로드 정보 만료 |
| 422 | `IMAGE422_1` | MIME 또는 파일 시그니처 불일치 |
| 500 | `IMAGE500_1` | Presigned URL 발급 실패 |
| 500 | `IMAGE500_2` | 저장소 처리 실패 |

### 16.2 S3 직접 업로드 오류

S3 직접 업로드는 FIT-BACK 공통 API 응답을 반환하지 않는다. 프론트는 S3 오류를 다음 상태로 정규화한다.

| 프론트 오류 분류 | 처리 |
|---|---|
| `IMAGE_UPLOAD_URL_EXPIRED` | 새 Presigned POST 정보 발급 후 1회 재시도 |
| `IMAGE_UPLOAD_FAILED` | 네트워크 및 S3 오류 안내 후 재시도 제공 |

AWS 오류 원문, S3 object key, 서명 값 또는 Presigned URL을 사용자에게 그대로 노출하지 않는다.

## 17. 운영 및 인프라 확인 항목

Presigned POST 전환 시 운영 환경에서 다음 항목을 확인한다.

- S3 CORS가 브라우저의 POST 업로드를 허용하는지 확인한다.
- 허용 Origin에 실제 프론트엔드 주소가 포함되어 있는지 확인한다.
- 운영에서 와일드카드 Origin과 credential 조합을 사용하지 않는다.
- S3 직접 업로드 실패를 백엔드 API 오류 파서로 처리하지 않는다.
- IAM 권한과 bucket policy 조건이 Presigned POST 정책과 충돌하지 않는지 확인한다.
- CloudFront OAC와 S3 Block Public Access는 유지한다.

S3 객체 수명 주기 자동 만료는 `ACTIVE`와 미사용 이미지를 구분할 수 없으므로 적용하지 않는다. 미사용 이미지 정리는 DB 상태와 도메인 참조를 기준으로 애플리케이션 작업자가 수행한다.

## 18. 후속 정책 및 구현 항목

다음 항목은 이번 확정 목표 정책에서 완료로 표시하지 않는다.

- 최대 해상도 또는 픽셀 수 제한
- 실제 이미지 디코딩 공통 모듈
- EXIF 제거
- 체크섬 저장 및 검증
- WebP 강제 변환
- CloudFront 캐시 TTL과 invalidation 세부 정책
- Signed Cookie 적용 여부
- `ACTIVE` 마지막 참조 해제 자동 삭제의 전체 도메인 연동
- 룩북·프로필 이미지 참조 확인용 `ImageReferenceProbe` 구현
- 지수 백오프와 최대 삭제 재시도 횟수

## 19. 향후 구현 API 범위와 이미지 연계 메모

기존 구현 여부와 관계없이 앞으로의 기능 이슈와 계약 검토는 다음 API 범위를 기준으로 한다. 이 문서는 범위를 기록할 뿐 해당 API 구현 완료를 의미하지 않는다.

- 이미지 업로드
- AI 태그 분석 결과 저장
- 분석 리포트 목록, 상세, 삭제
- 추천 결과 생성
- 상품 검색, 상품 상세
- 쇼핑 API 연동
- 추천 상품 저장
- 카테고리별 그룹핑

`확정 태그 반영`은 별도 신규 API 범위로 잡지 않고, 사용자가 확정한 분석 결과를 입력으로 사용하는 **추천 결과 생성** 계약으로 설계한다. 이미지 업로드와 분석 관련 기존 API의 전환은 호환성을 고려해 각각의 기능 이슈에서 수행한다.

추천·룩북에서 외부 상품 이미지를 사용할 때는 외부 `imageUrl`을 요청값으로 직접 받지 않고 내부 `productId`를 기준으로 백엔드가 상품 정보를 조회한다.

## 20. 참고 자료

- [Amazon S3 Presigned URL](https://docs.aws.amazon.com/AmazonS3/latest/userguide/using-presigned-url.html)
- [Amazon S3 POST Policy](https://docs.aws.amazon.com/AmazonS3/latest/developerguide/sigv4-HTTPPOSTConstructPolicy.html)
- [CloudFront에서 S3 Origin 접근 제한](https://docs.aws.amazon.com/AmazonCloudFront/latest/DeveloperGuide/private-content-restricting-access-to-s3.html)
- [CloudFront 비공개 콘텐츠 제한](https://docs.aws.amazon.com/AmazonCloudFront/latest/DeveloperGuide/private-content-overview.html)
- [Amazon S3 Lifecycle 객체 만료](https://docs.aws.amazon.com/AmazonS3/latest/userguide/lifecycle-expire-general-considerations.html)
