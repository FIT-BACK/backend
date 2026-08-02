# FIT-BACK 이미지 저장소 정책

> 문서 상태: `develop` 구현 기준 운영 계약
>
> 기준: 2026-08-02 `develop` (`7a84f9c`)
>
> 대상 범위: 사용자 업로드 이미지의 Presigned POST 발급, S3 저장, CloudFront 제공, 상태 관리 및 삭제 정책
>
> 최종 수정일: 2026-08-01
>
> **문서 표기:** `현재`는 위 기준 커밋의 코드·migration 계약, 날짜·Release 표기는 과거 배포 이력,
> `후속`은 아직 구현하지 않은 정책이다. AWS 인프라의 실제 상태는 배포 전 다시 확인한다.

## 1. 목적

FIT-BACK의 사용자 업로드 이미지를 안전하게 저장하고, 업로드 완료 전 이미지와 실제 도메인에서 사용 중인 이미지를 구분하여 관리한다.

기본 흐름은 다음과 같다.

```text
프론트엔드
  → 백엔드에 Presigned POST 정보 요청
  → S3에 이미지 직접 업로드
  → 백엔드 업로드 완료 API 호출
  → 분석·룩북·프로필 API에 용도별 imageId 전달
  → API 응답의 10분짜리 CloudFront Signed URL로 이미지 표시
```

핵심 원칙은 다음과 같다.

- 사용자 업로드 이미지는 서버가 발급한 `imageId`로 관리한다.
- S3 object key에는 원본 파일명이나 사용자 입력값을 사용하지 않는다.
- S3 업로드 성공만으로 이미지를 사용 가능 상태로 보지 않는다.
- 완료 API의 서버 검증을 통과한 이미지만 도메인 API에 연결할 수 있다.
- 분석·룩북·프로필 등 도메인 데이터와 연결된 이미지만 `ACTIVE` 상태로 변경한다.
- 24시간 자동 정리는 `PENDING_UPLOAD`, `READY`, `REJECTED`이면서 실제 도메인 참조가 없는 이미지만 대상으로 한다.
- 모든 사용자 업로드 이미지는 `PRIVATE`를 유지하며, 룩북을 공개해도 `PUBLIC`으로 전환하지 않는다.
- 이미지 조회는 로그인 여부와 관계없이 API가 응답 시점에 발급한 10분 만료 CloudFront Signed URL을 사용한다.
- trusted key group이 적용된 이미지 CloudFront의 서명 없는 URL은 `403`이다.
- 외부 쇼핑 API의 상품 이미지는 사용자 업로드 이미지와 분리하며 FIT-BACK S3에 복사하지 않는다.

## 2. 현재 정책 요약

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
| 이미지 visibility | 모든 신규 이미지 `PRIVATE`; 룩북 생성·공개 시 `PUBLIC` 전환 없음 |
| 이미지 제공 | CloudFront Signed URL, 10분; 서명 없는 URL은 `403` |
| 운영 multipart 분석 | 로컬 저장을 사용하지 않고 `ANALYSIS400_3`으로 거절 |
| 외부 상품 이미지 | 공급자 상품 참조와 CDN URL 사용, FIT-BACK S3 복사 금지 |

## 3. 현재 구현 상태와 반영 원칙

현재 코드와 DB 계약은 Issue #95~#97의 릴리스 A/B/C를 모두 반영한 상태다.
V18은 legacy 생명주기 값을 백필했고, V19는 zero gate 후 legacy `PENDING`과 이전
purpose 값을 제외한 canonical CHECK 제약을 적용했다. 운영 S3 CORS와 production
smoke test의 시점별 증적은 `docs/DEPLOYMENT.md`에 기록한다.

| 항목 | 현재 구현 | 비고 |
|---|---|---|
| S3 업로드 | Presigned POST | `uploadFields`를 포함한 `FormData` 직접 업로드 |
| 발급 API | `POST /api/v1/images/upload-requests` | 응답은 `imageId`, `uploadUrl`, `uploadMethod=POST`, `uploadFields`, `expiresAt` |
| 이미지 용도 | `ANALYSIS`, `LOOKBOOK`, `PROFILE` | API·DB에 같은 canonical 값 사용 |
| 생명주기 | `PENDING_UPLOAD` → `READY` → `ACTIVE` | legacy `PENDING` 코드·DB 값 없음 |
| 공개 범위 | 신규 row를 항상 `PRIVATE`로 생성 | 룩북 생성·조회가 visibility를 변경하지 않음 |
| 조회 URL | 모든 사용자 이미지에 10분 Signed URL 발급 | `PUBLIC` 분기는 잔존하지만 production writer가 없음 |
| Object key | `images/{purpose}/{memberId}/{yyyy}/{MM}/{imageId}.{ext}` | 신규 업로드부터 적용; 기존 객체는 이동하지 않음 |
| 임시 이미지 정리 | 1시간 주기, 최대 50개 선점 | 상태별 시간 기준과 도메인 참조 부재를 모두 확인 |
| `ACTIVE` 마지막 참조 해제 | 분석·룩북·프로필에 구현 | 커밋 후 참조를 다시 확인한 뒤 S3 삭제 |
| 분석 API | JSON `{ "imageId": "..." }` 경로가 운영 계약 | multipart 경로는 local/test에서만 저장하고 prod에서 `ANALYSIS400_3` |

기존 운영 데이터와 S3 객체는 강제로 이동하지 않는다. 새 object key 정책은 신규 업로드부터 적용한다.

## 4. 이미지 용도

업로드 요청의 `purpose`는 저장 용도를 나타낸다.

| 화면 또는 기능 | `purpose` |
|---|---|
| AI 태그 분석 사진 | `ANALYSIS` |
| 룩북 원본 또는 매칭 사진 | `LOOKBOOK` |
| 프로필 사진 | `PROFILE` |

룩북 내부 역할을 별도 `purpose` 값으로 분리하지 않는다. 현재 룩북
요청은 필드명 `originalImageId`와 `matchedImageId`로 역할을 구분하며, 분석 원본을
재사용할 때는 `ANALYSIS` purpose의 `originalImageId`도 허용한다.

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
신규 row는 `PENDING_UPLOAD`로 저장한다. V19는 Release B rollback window에서 생긴 legacy
`PENDING`을 최종 catch-up하고 zero gate를 통과한 후 신규값 전용 constraint를 적용했다.

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
  status = PENDING_UPLOAD AND createdAt < 현재 시각 - 24시간
  OR status = READY AND COALESCE(uploadedAt, createdAt) < 현재 시각 - 24시간
  OR status = REJECTED AND COALESCE(uploadedAt, createdAt) < 현재 시각 - 24시간
  OR status = DELETE_FAILED AND (nextRetryAt IS NULL OR nextRetryAt <= 현재 시각)
)
AND 분석·룩북·프로필 등 실제 도메인 참조가 존재하지 않음
```

정리 작업은 1시간마다 실행한다. 실제 보관 시간은 약 24~25시간이 될 수 있다.
`READY`/`REJECTED`의 `uploadedAt`이 없는 기존 데이터는 `createdAt`을 기준으로 한다. 신규 삭제 실패는 `nextRetryAt`을 반드시 저장하되, 값이 없는 기존 `DELETE_FAILED` 데이터가 영구 정체되지 않도록 즉시 재시도 대상으로 취급한다. `DELETE_FAILED`에는 24시간 경과 조건을 다시 적용하지 않는다.

### 9.2 삭제 처리 순서

1. 오래된 `PENDING_UPLOAD`/`READY`/`REJECTED` 또는 재시도 시각에 도달한 `DELETE_FAILED` 중 도메인 참조가 없는 이미지를 일정 개수만큼 조회한다.
2. 같은 트랜잭션에서 참조 부재를 다시 확인한다.
3. DB 락 또는 조건부 상태 변경으로 `DELETING`을 선점한다.
4. 선점에 성공한 작업자만 S3 객체를 삭제한다.
5. S3 객체가 이미 없다면 삭제 성공으로 처리한다.
6. 삭제 성공 시 `DELETING → DELETED`로 변경한다.
7. 삭제 실패 시 `DELETING → DELETE_FAILED`로 변경한다.
8. `DELETE_FAILED`는 다음 1시간 스케줄에서 다시 조회한다.

`retryCount`, `nextRetryAt`은 현재 구현처럼 DB에 저장한다. 지수 백오프와 최대 재시도 정책은 후속 운영 개선 항목으로 둔다.

## 10. 사용 중 이미지의 마지막 참조 해제

`ACTIVE` 이미지의 마지막 참조 해제 정리는 현재 분석 리포트, 룩북, 프로필에
구현되어 있다. 하나의 이미지는 여러 도메인에서 공유될 수 있다.

```text
분석 리포트 ─┐
             ├─ 동일한 imageId
룩북 게시물 ─┤
회원 프로필 ─┘
```

분석 리포트 삭제, 룩북 삭제, 룩북 이미지 교체, 프로필 이미지 교체에는 다음 흐름을 적용한다.

1. 삭제하는 도메인과 이미지 사이의 참조를 논리적으로 비활성화한다. 분석 리포트는
   관계 행을 물리 삭제하지 않고 `deletedAt`을 설정하며, 활성 참조 조회에서 제외한다.
2. 남아 있는 이미지 참조 수를 확인한다.
3. 참조가 하나라도 있으면 `ACTIVE` 상태를 유지한다.
4. 참조가 0이면 `ACTIVE → DELETING`으로 변경한다.
5. 커밋 후 삭제 작업자가 S3 객체를 삭제한다.
6. 삭제 성공 시 `DELETED`, 실패 시 `DELETE_FAILED`로 변경한다.

DB 트랜잭션 안에서 S3 삭제를 직접 실행하지 않는다. 삭제·교체 트랜잭션이 커밋된 뒤 분석,
삭제되지 않은 룩북, 회원 프로필 참조를 다시 확인하고 마지막 논리 참조가 없을 때만 객체를
삭제한다. `claimReleasedActiveImages()`는 같은 DB 트랜잭션에서 후보 `ACTIVE` row를
비관 잠금하고 모든 참조 probe를 다시 확인한 다음 `ACTIVE → DELETING`을 선점한다. 선점에
성공한 ID만 커밋 후 S3 삭제 후보가 된다. 어느 한 도메인에서라도 같은 `imageId`를 참조하면
`ACTIVE`를 유지하며, `DELETING` 상태는 새 룩북 연결의 허용 상태(`READY` 또는 `ACTIVE`)가 아니다.

현재 `deleteClaimedImage()`는 `DELETING` 상태만 확인하고 S3 객체를 삭제하며 도메인 참조를
다시 조회하지 않는다. 후속 구현에서는 신규 연결과 삭제 선점이 같은 이미지 row lock 순서를
따르도록 하고, 삭제 직전 참조 재검증과 `DELETING` 이미지 연결 거부를 함께 보장해야 한다.

회원 프로필 교체는 새 `PROFILE + READY` 이미지를 `ACTIVE`로 전환하고
`member.profile_image_id`를 변경한 뒤 이전 이미지의 참조 해제 이벤트를 발행한다.
회원 탈퇴 시에도 프로필 참조를 먼저 해제한 후 동일한 이벤트를 발행한다.

## 11. 공개 범위와 CloudFront

이미지 생명주기 상태와 공개 범위는 별도로 관리한다.

```text
status(logical): PENDING_UPLOAD | READY | ACTIVE | DELETING | DELETE_FAILED | DELETED | REJECTED
status(persisted): PENDING_UPLOAD | READY | ACTIVE | DELETING | DELETE_FAILED | DELETED | REJECTED
visibility: PRIVATE | PUBLIC
purpose(logical): ANALYSIS | LOOKBOOK | PROFILE
purpose(persisted): ANALYSIS | LOOKBOOK | PROFILE
```

| 이미지 종류 | 현재 visibility | 제공 방법 |
|---|---|---|
| 분석 원본 | `PRIVATE` | CloudFront Signed URL |
| 프로필 이미지 | `PRIVATE` | CloudFront Signed URL |
| 공개 룩북의 원본·매칭 업로드 이미지 | `PRIVATE` | CloudFront Signed URL |
| `PENDING_UPLOAD`/`READY` 이미지 | 비공개 | 도메인 조회 URL 미발급 |
| 외부 상품 이미지 | 이미지 저장 정책 대상 아님 | 공급자 CDN URL |

현재 생성 코드는 모든 `image` row의 `visibility`를 `PRIVATE`로 저장하고, 분석·룩북·
프로필 연결 후에도 이 값을 변경하지 않는다. `PUBLIC` enum과 일반 URL을 만드는
코드 분기는 잔존하지만, production에서 `PUBLIC`으로 전환하는 writer는 없다.
또한 이미지 CloudFront에 trusted key group이 적용되어 있으므로 서명 없는 일반 URL은
`403`이다. 따라서 현재 운영 계약에서 `PUBLIC` 값을 사용하면 안 된다.

CloudFront Signed URL의 유효시간은 10분이다. 비로그인 사용자의 공개 룩북 조회도
백엔드가 발급한 Signed URL을 받으므로 만료 전에는 이미지를 볼 수 있다. 만료한
URL 자체가 자동 연장되지는 않으며, 룩북 API를 새로 요청하면 응답 생성 시점을
기준으로 새 Signed URL을 발급한다. Signed Cookie는 현재 구현하지 않았다.

## 12. 단일 및 복수 이미지 전달 계약

이미지 한 장마다 하나의 `imageId`와 Presigned POST 정보를 사용한다. 복수 업로드는 프론트에서 최대 2~3개 병렬 처리를 권장하며, 실패한 이미지만 독립적으로 재시도한다.

현재 도메인 API는 이미지 한 장마다 발급된 ID를 각 요청 필드에 직접 전달한다.
임의의 외부 `imageUrl`을 사용자 업로드 이미지 대신 받지 않는다.

```json
{
  "imageId": "019c1234-abcd-7000-8000-123456789abc"
}
```

위 형식은 JSON 분석 API `POST /api/v1/analyses`의 요청이다. 룩북 생성·수정은
다음처럼 원본과 매칭 출처를 별도 필드로 구분한다.

```json
{
  "originalImageId": "019c1234-abcd-7000-8000-123456789abc",
  "matchedImageId": null,
  "matchedProductId": 123,
  "sourceReportId": 45,
  "tagIds": [1, 2],
  "comment": "오늘의 코디"
}
```

전달 규칙은 다음과 같다.

- 분석 JSON API는 `ANALYSIS + READY`인 본인 이미지 ID 하나를 사용한다.
- 운영의 multipart 분석 API는 `ANALYSIS400_3`으로 거절하고 Presigned POST → 완료 → JSON 경로를 요구한다.
- 룩북 `originalImageId`는 본인 소유의 `LOOKBOOK` 또는 `ANALYSIS` 이미지이며 상태는 `READY` 또는 `ACTIVE`여야 한다.
- 룩북의 매칭 출처는 `matchedImageId` 또는 `matchedProductId` 중 하나다.
- `matchedImageId`는 본인 소유의 `LOOKBOOK + READY|ACTIVE` 이미지다.
- `matchedProductId`를 사용하면 상품 이미지 URL을 룩북에 snapshot하고 FIT-BACK S3에 복사하지 않는다.
- 프로필 API는 단일 `profileImageId`를 사용한다.
- 회원 수정·온보딩 요청은 `profileImageId`, 조회 응답은 표시용 `profileImageUrl`을 사용한다.
- 사용자 업로드 이미지는 `imageId`로 전달한다.
- 쇼핑 API 상품 이미지는 내부 `productId`로 전달한다.
- `imageUrl`은 응답과 화면 표시 용도로만 사용한다.

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
| 404 | `IMAGE404_2` | 업로드 완료 확인 시 S3 객체 없음 |
| 500 | `IMAGE500_2` | S3 권한 또는 서버 설정 오류 |
| 503 | `IMAGE503_1` | S3 timeout, 429, 5xx, `RequestTimeout`, `OperationAborted` 또는 연결 실패 |

서버의 S3 호출 제한은 기본적으로 전체 요청 5초, 시도별 2초이며
`IMAGE_S3_API_CALL_TIMEOUT`, `IMAGE_S3_API_CALL_ATTEMPT_TIMEOUT`으로 조정한다.
장애 로그에는 작업 종류, HTTP 상태, AWS 오류 코드, AWS request ID를 기록한다. SDK
클라이언트 예외는 재시도 가능 여부가 아니라 네트워크·타임아웃 원인 존재 여부를 기록하며,
object key, 예외 메시지, 서명 URL과 Presigned 필드는 기록하지 않는다.

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
- 지수 백오프와 최대 삭제 재시도 횟수
- 마지막 참조 해제와 신규 연결이 경쟁할 때 삭제 안전성을 고정하는 동시성 통합 테스트

## 19. 현재 도메인 API 연계 현황

현재 `develop` 기준 이미지 연계 경로는 다음과 같다.

- 이미지 API가 Presigned POST 발급, 재발급, 업로드 완료 검증을 담당한다.
- 분석 JSON API가 `ANALYSIS` 이미지를 `ACTIVE`로 바꾸고 리포트에 연결한다.
- 룩북 생성·수정은 원본과 직접 업로드한 매칭 이미지를 `ACTIVE`로 바꾸되 visibility는 `PRIVATE`를 유지한다.
- 회원 온보딩·수정은 `PROFILE` 이미지를 `ACTIVE`로 바꾸고 `member.profile_image_id`에 연결한다.
- 분석 삭제, 룩북 수정·삭제, 프로필 교체·회원 탈퇴는 참조 해제 이벤트를 발행한다.
- 이벤트 listener는 트랜잭션 커밋 후 분석·룩북·프로필 참조를 다시 확인하고, 마지막 참조가 없는 `ACTIVE` 이미지만 삭제한다.

상품 이미지는 외부 `imageUrl`을 룩북 요청으로 직접 받지 않고 내부
`productId`를 기준으로 백엔드가 검증한다.

## 20. 참고 자료

- [Amazon S3 Presigned URL](https://docs.aws.amazon.com/AmazonS3/latest/userguide/using-presigned-url.html)
- [Amazon S3 POST Policy](https://docs.aws.amazon.com/AmazonS3/latest/developerguide/sigv4-HTTPPOSTConstructPolicy.html)
- [CloudFront에서 S3 Origin 접근 제한](https://docs.aws.amazon.com/AmazonCloudFront/latest/DeveloperGuide/private-content-restricting-access-to-s3.html)
- [CloudFront 비공개 콘텐츠 제한](https://docs.aws.amazon.com/AmazonCloudFront/latest/DeveloperGuide/private-content-overview.html)
- [Amazon S3 Lifecycle 객체 만료](https://docs.aws.amazon.com/AmazonS3/latest/userguide/lifecycle-expire-general-considerations.html)
