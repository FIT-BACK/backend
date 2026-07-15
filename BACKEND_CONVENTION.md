# Backend Convention

이 문서는 FIT-BACK Backend 개발 시 적용하는 코드, API, 예외 처리, 보안, 문서화, 협업 컨벤션을 정리한 문서다.
공식 협업 흐름과 AI 작업 규칙은 `AGENTS.md`를 우선 기준으로 하고, 이 문서는 백엔드 구현 세부 규칙을 보완한다.

---

## 1. 프로젝트 기본 기준

- 프로젝트명: FIT-BACK Backend
- 기본 패키지: `com.fitback.backend`
- Java 버전: Java 21
- Spring Boot 버전: 4.1.0
- 빌드 도구: Gradle
- 기본 실행 프로필: `local`
- 테스트 프로필: `test`
- 기본 API prefix: `/api/v1`

---

## 2. 코드 구조 및 네이밍

### 2.1 코드 스타일

- 코드 스타일은 Google Style Guide를 기준으로 한다.
- 불필요한 포맷 변경, 파일 이동, 대규모 리팩터링은 지양한다.
- 변수명과 메서드명은 `camelCase`를 사용한다.
  - 예: `userName`, `getUserProfile`
- 클래스명은 `PascalCase`를 사용한다.
  - 예: `UserService`, `RecommendationController`
- 상수명은 `UPPER_SNAKE_CASE`를 사용한다.
  - 예: `MAX_IMAGE_SIZE`, `DEFAULT_PAGE_SIZE`
- Java 필드명은 `camelCase`, DB 컬럼명은 `snake_case`를 사용한다.

### 2.2 패키지 구조

패키지는 도메인 중심으로 분리한다. 공통 설정, 예외 처리, 응답 형식, 보안 설정 등 여러 도메인에서 함께 사용하는 코드는 `global` 패키지에 둔다.

```text
com.fitback.backend
├── domain
│   ├── member
│   │   ├── controller
│   │   ├── service
│   │   ├── repository
│   │   ├── entity
│   │   └── dto
│   ├── closet
│   ├── lookbook
│   ├── trend
│   ├── tag
│   ├── analysis
│   ├── recommendation
│   └── product
└── global
    ├── config
    ├── entity
    ├── exception
    ├── response
    └── security
```

---

## 3. DTO 규칙

### 3.1 네이밍

요청 DTO는 `Request`, 응답 DTO는 `Response` 접미사를 사용한다.

```text
LoginRequest
LoginResponse
UserProfileResponse
RecommendationCreateRequest
RecommendationListResponse
```

### 3.2 작성 기준

- Entity를 API 응답에 직접 노출하지 않는다.
- Controller 응답에는 Response DTO를 사용한다.
- Request DTO에는 요청 검증에 필요한 Bean Validation 어노테이션을 작성한다.
- Response DTO는 클라이언트에 필요한 값만 포함한다.
- API 명세서의 필드명과 DTO 필드명을 일치시킨다.
- DTO는 도메인별 `dto` 패키지에 둔다.

---

## 4. Entity 및 JPA 규칙

### 4.1 Entity 작성 기준

- Entity에는 `Setter` 사용을 지양한다.
- Entity 생성은 생성자 또는 정적 팩토리 메서드를 사용한다.
- Entity 상태 변경은 비즈니스 메서드를 통해 처리한다.
- Entity 생성 파라미터가 10개 이상인 경우에만 제한적으로 Builder를 사용한다.
- `@Builder`는 클래스가 아니라 생성자에 적용한다.
- Builder 접근 제어는 `@Builder(access = AccessLevel.PRIVATE)`를 기본으로 한다.
- 외부 생성은 정적 팩토리 메서드를 통해서만 허용한다.
- `id`, `createdAt`, `updatedAt` 등 시스템 관리 필드는 Builder 대상에서 제외한다.
- 연관관계는 기본적으로 `LAZY` 로딩을 사용한다.
- 양방향 연관관계는 필요한 경우에만 사용한다.
- 컬렉션 필드는 외부에서 직접 교체하지 않도록 관리한다.

### 4.2 공통 시간 컬럼

- 생성 시간은 `created_at` 컬럼을 사용한다.
- 수정 시간은 `updated_at` 컬럼을 사용한다.
- 삭제 시간은 Soft Delete가 필요한 테이블에 한해 `deleted_at` 컬럼을 사용한다.
- `created_at`은 `NOT NULL`을 기본으로 한다.
- `updated_at`은 생성 직후 값이 없을 수 있으므로 `NULL`을 허용한다.
- Java Entity에서는 공통 Auditing 기반 처리를 우선 사용한다.

### 4.3 Soft Delete 기준

Soft Delete는 기본으로 적용하지 않는다. 다음 조건에 해당하는 경우에만 적용한다.

- 사용자 데이터 복구가 필요한 경우
- 삭제 이력 추적이 필요한 경우
- 법적/정책적 보존 요구사항이 있는 경우
- 물리 삭제 시 다른 도메인 데이터 정합성에 문제가 생기는 경우

Soft Delete를 사용하는 경우 다음 규칙을 따른다.

- `deleted_at` 컬럼을 추가한다.
- 삭제 여부 판단은 `deleted_at IS NULL`을 기준으로 한다.
- 단순 boolean 컬럼인 `is_deleted`만 단독으로 사용하지 않는다.

### 4.4 ENUM 처리

DB native enum은 사용하지 않는다. ENUM 성격의 값은 Java enum과 DB `VARCHAR` 컬럼으로 관리한다.

예시:

- `member.login_provider`: `EMAIL`, `KAKAO`
- `member.role`: `USER`, `ADMIN`
- `tag.tag_type`: `SILHOUETTE`, `COLOR`, `DETAIL`
- `closet_save.target_type`: `TREND`, `LOOKBOOK`, `ANALYSIS_REPORT`
- `report_tag.source`: `AI`, `USER`

JPA 매핑 시 다음 규칙을 따른다.

```java
@Enumerated(EnumType.STRING)
```

`EnumType.ORDINAL`은 사용하지 않는다.

### 4.5 연관관계 매핑

- N:1 관계는 `@ManyToOne(fetch = FetchType.LAZY)`를 기본으로 사용한다.
- FK를 가진 Entity가 연관관계의 주인이다.
- 1:N 단방향 매핑은 기본적으로 사용하지 않는다.
- 양방향 연관관계는 실제 비즈니스 로직에 필요하거나 Cascade/orphan removal이 명확한 경우에만 사용한다.
- 단순 조회 목적의 양방향 매핑은 지양한다.
- `@ManyToMany` 직접 매핑은 사용하지 않는다.
- 다대다 관계는 중간 Entity로 풀어서 매핑한다.
  - 예: `MemberTag`, `ProductTag`, `LookbookTag`, `TrendTag`

---

## 5. DB 네이밍 및 마이그레이션

### 5.1 테이블 및 컬럼 네이밍

- 테이블명과 컬럼명은 `snake_case`를 사용한다.
  - 예: `analysis_report`, `profile_image_url`
- 테이블명은 단수형을 기본으로 한다.
  - 예: `member`, `product`, `tag`, `lookbook`
- 조인 테이블은 `{주체}_{대상}` 형식으로 작성한다.
  - 예: `member_tag`, `product_tag`, `lookbook_tag`, `trend_tag`
- PK 컬럼은 `{테이블명}_id` 형식을 사용한다.
  - 예: `member_id`, `product_id`, `report_id`
- FK 컬럼은 참조 대상의 PK 컬럼명을 그대로 사용한다.
  - 예: `member_id`, `tag_id`, `product_id`

### 5.2 제약조건 네이밍

- PK 제약조건명은 `PK_{TABLE_NAME}` 형식을 사용한다.
  - 예: `PK_MEMBER`, `PK_PRODUCT`
- FK 제약조건명은 `FK_{FROM_TABLE}_{TO_TABLE}` 형식을 사용한다.
  - 예: `FK_CLOSET_SAVE_MEMBER`, `FK_PRODUCT_TAG_PRODUCT`
- Unique 제약조건명은 `UK_{TABLE_NAME}_{COLUMN_NAME}` 형식을 사용한다.
  - 예: `UK_MEMBER_EMAIL`, `UK_MEMBER_NICKNAME`
- 복합 Unique 제약조건은 `UK_{TABLE_NAME}_{COLUMN1}_{COLUMN2}` 형식을 사용한다.
  - 예: `UK_MEMBER_TAG_MEMBER_ID_TAG_ID`

### 5.3 인덱스 기준

인덱스는 조회 성능이 필요한 경우에만 추가한다. 불필요한 인덱스는 쓰기 성능과 저장 공간에 부담을 줄 수 있으므로 남용하지 않는다.

- FK 컬럼에는 인덱스 생성을 우선 검토한다.
- 검색 조건으로 자주 사용되는 컬럼에는 인덱스를 검토한다.
- 정렬이나 최신 목록 조회에 자주 사용되는 `created_at`은 필요 시 인덱스를 검토한다.
- 복합 조회 조건이 자주 사용되는 경우 복합 인덱스를 검토한다.
- Unique 조건이 필요한 컬럼에는 Unique 제약조건을 사용한다.

인덱스명은 다음 형식을 사용한다.

```text
IDX_{TABLE_NAME}_{COLUMN_NAME}
IDX_{TABLE_NAME}_{COLUMN1}_{COLUMN2}
```

### 5.4 마이그레이션

초기 단계에서는 ERD 기준 SQL을 문서화하고, 실제 운영 마이그레이션 도구는 Flyway를 우선 후보로 둔다.

Flyway 도입 기준은 다음과 같다.

- Entity와 Repository가 추가되어 실제 DB 스키마 변경이 발생하는 경우
- 여러 개발자가 같은 DB 스키마를 공유해야 하는 경우
- 배포 환경에서 스키마 변경 이력을 추적해야 하는 경우

Flyway를 도입하는 경우 다음 규칙을 따른다.

```text
src/main/resources/db/migration/V{version}__{description}.sql
```

한 번 merge된 마이그레이션 파일은 수정하지 않는다. 변경이 필요한 경우 새 버전의 마이그레이션 파일을 추가한다.

---

## 6. API 규칙

### 6.1 URI 네이밍

- URI는 리소스 중심으로 작성한다.
- URI에는 동사보다 명사를 사용한다.
- API 버전은 `/api/v1`을 기본 prefix로 사용한다.
- 컬렉션 리소스는 복수형을 사용한다.
- Path variable은 리소스 ID를 명확하게 표현한다.
- Query string은 검색, 필터, 정렬, 페이지네이션에 사용한다.

예시:

```text
POST /api/v1/auth/login
GET /api/v1/members/me
GET /api/v1/products/{productId}
GET /api/v1/products?keyword=미니멀
POST /api/v1/analysis-requests
```

### 6.2 공통 응답 포맷

API 응답은 API 명세서 기준에 맞춰 `success`, `code`, `message`, `data` 필드를 사용한다.
공통 응답 객체는 `global.response.ApiResponse`에 둔다.

성공 응답:

```json
{
  "success": true,
  "code": "COMMON200_1",
  "message": "성공적으로 요청을 처리했습니다.",
  "data": {}
}
```

생성 성공 응답:

```json
{
  "success": true,
  "code": "COMMON201_1",
  "message": "리소스가 생성되었습니다.",
  "data": {}
}
```

실패 응답:

```json
{
  "success": false,
  "code": "COMMON404_1",
  "message": "요청한 리소스를 찾을 수 없습니다.",
  "data": null
}
```

Controller에서는 다음 메서드를 사용한다.

```java
ApiResponse.onSuccess(data);
ApiResponse.onSuccess();
ApiResponse.onCreated(data);
ApiResponse.onCreated();
ApiResponse.onFailure(code, message, data);
```

### 6.3 페이지네이션 응답 형식

목록 조회 API는 커서 기반 페이지네이션을 우선 사용한다. API 명세서와 동일하게 `items`, `nextCursor`, `hasNext`, `pageSize` 필드를 사용한다.

```json
{
  "success": true,
  "code": "COMMON200_1",
  "message": "성공적으로 요청을 처리했습니다.",
  "data": {
    "items": [],
    "nextCursor": null,
    "hasNext": false,
    "pageSize": 10
  }
}
```

- `items`: 조회 결과 목록
- `nextCursor`: 다음 페이지 조회에 사용할 커서. 다음 페이지가 없으면 `null`
- `hasNext`: 다음 페이지 존재 여부
- `pageSize`: 요청 또는 응답 기준 페이지 크기

### 6.4 HTTP Status Code 기준

- `200 OK`: 일반 조회, 수정, 삭제, 좋아요/취소 등 성공
- `201 Created`: 회원가입, 리소스 생성, 업로드, 저장 등 생성 성공
- `400 Bad Request`: 잘못된 요청, 파라미터 누락, Validation 실패
- `401 Unauthorized`: 인증이 필요하거나 인증 정보가 유효하지 않은 경우
- `403 Forbidden`: 인증은 되었지만 접근 권한이 없는 경우
- `404 Not Found`: 요청 리소스를 찾을 수 없는 경우
- `405 Method Not Allowed`: 지원하지 않는 HTTP Method 요청
- `500 Internal Server Error`: 서버 내부 오류

### 6.5 공통 에러 코드 규칙

공통 에러 코드는 `COMMON{HTTP_STATUS}_{순번}` 형식을 사용한다.

```text
COMMON400_1: 잘못된 요청입니다.
COMMON400_2: 요청 값이 올바르지 않습니다.
COMMON401_1: 인증이 필요합니다.
COMMON403_1: 접근 권한이 없습니다.
COMMON404_1: 요청한 리소스를 찾을 수 없습니다.
COMMON405_1: 허용되지 않은 HTTP 메서드입니다.
COMMON500_1: 서버 내부 오류가 발생했습니다.
```

도메인별 에러 코드는 실제 API 구현 시점에 도메인 prefix를 사용해 추가한다.

```text
AUTH401_1
MEMBER404_1
ANALYSIS404_1
TAG404_1
PRODUCT404_1
TREND404_1
LOOKBOOK404_1
CLOSET404_1
```

도메인별 에러 코드를 추가하거나 메시지를 변경하면 API 명세서의 예외 응답도 함께 업데이트한다.

---

## 7. 예외 처리 및 Validation

### 7.1 예외 처리 구조

공통 예외 처리는 `global.exception` 패키지에서 관리한다.

```text
global.exception.ErrorCode
global.exception.BusinessException
global.exception.GlobalExceptionHandler
```

기본 처리 대상은 다음과 같다.

- `BusinessException`: 비즈니스 규칙 위반
- `MethodArgumentNotValidException`: `@RequestBody` Validation 실패
- `ConstraintViolationException`: Path variable 또는 query parameter Validation 실패
- `MissingServletRequestParameterException`: 필수 query parameter 누락
- `HttpRequestMethodNotSupportedException`: 지원하지 않는 HTTP Method 요청
- `AuthenticationException`: 인증 실패
- `AccessDeniedException`: 인가 실패
- `NoHandlerFoundException`: 존재하지 않는 endpoint 요청
- `Exception`: 예상하지 못한 서버 오류

### 7.2 BusinessException 사용 기준

서비스 계층에서 비즈니스 규칙 위반이 발생하면 `BusinessException`을 사용한다.

```java
throw new BusinessException(ErrorCode.NOT_FOUND);
```

도메인별 에러 코드가 추가된 이후에는 공통 코드보다 도메인 코드를 우선 사용한다.

### 7.3 Validation 작성 기준

- Request DTO에는 필수값, 길이, 범위, 형식 검증을 명시한다.
- Controller에는 `@Valid` 또는 `@Validated`를 사용한다.
- Validation 실패 응답은 `COMMON400_2`를 기본으로 한다.
- 클라이언트에 전달할 메시지는 사용자 관점에서 이해 가능한 문장으로 작성한다.

---

## 8. 인증, 인가, Security 규칙

### 8.1 현재 Security 기본 정책

`global.security.SecurityConfig`에서 기본 보안 정책을 관리한다.

- JWT 인증 구현 전까지 Swagger/OpenAPI 경로와 `/api/v1/**` 경로만 임시 허용한다.
- 명시되지 않은 경로는 `denyAll`로 차단한다.
- ERROR dispatcher는 에러 응답 렌더링을 위해 허용한다.
- REST API 기준으로 CSRF, Form Login, HTTP Basic, Session은 비활성화한다.

### 8.2 공개 API와 인증 필요 API 구분

JWT 도입 전에는 구현 편의를 위해 `/api/v1/**`를 임시 허용한다. JWT 도입 후에는 다음 기준으로 전환한다.

공개 API 후보:

- Swagger/OpenAPI 경로
- `POST /api/v1/auth/login`
- `POST /api/v1/auth/sign`
- `POST /api/v1/auth/kakao`
- `POST /api/v1/auth/token/refresh`

인증 필요 API 후보:

- `POST /api/v1/auth/logout`
- `/api/v1/members/me/**`
- `/api/v1/analyses/**`
- `/api/v1/lookbooks/**` 중 작성, 삭제, 좋아요 등 사용자 상태 변경 API
- `/api/v1/closet-saves/**`

읽기 전용 공개 여부가 필요한 API는 API 명세서와 기획 기준에 따라 별도 결정한다.

### 8.3 JWT 도입 후 기준

JWT 도입 시 다음 내용을 추가한다.

- Access Token 인증 필터
- Refresh Token 재발급 정책
- 토큰 만료 및 위변조 에러 코드
- SecurityContext member 식별 방식
- 인증 실패와 인가 실패 응답 포맷
- 로그아웃 시 Refresh Token 무효화 방식

---

## 9. Swagger / OpenAPI 작성 규칙

### 9.1 기본 설정

- SpringDoc OpenAPI를 사용한다.
- 의존성은 `org.springdoc:springdoc-openapi-starter-webmvc-ui:3.0.3`을 사용한다.
- `global.config.OpenApiConfig`에서 API 문서 기본 정보와 v1 그룹을 관리한다.
- Swagger UI는 `/swagger-ui.html`, OpenAPI JSON은 `/v3/api-docs`에서 확인한다.

### 9.2 API 문서 작성 기준

Controller 또는 DTO에 Swagger 설명을 추가할 때 다음 내용을 API 명세서와 일치시킨다.

- API 요약과 설명
- Request Body 필드 설명
- Query parameter 설명
- Path variable 설명
- Response DTO 필드 설명
- 성공 응답 코드
- 주요 실패 응답 코드
- 인증 필요 여부

API 명세서와 Swagger 내용이 충돌하면 API 명세서를 먼저 수정한 뒤 코드 문서를 맞춘다.

---

## 10. 설정 파일 및 환경변수

### 10.1 프로필 기준

- 로컬 실행 기본 프로필은 `local`이다.
- 테스트 프로필은 `test`이다.
- 테스트는 H2 인메모리 DB를 사용한다.

### 10.2 커밋 대상

```text
.env.example
src/main/resources/application-local.yml
src/main/resources/application.properties
src/test/resources/application-test.yml
```

### 10.3 커밋 금지

```text
.env
.env.*
application-secret.yml
application-secret.properties
```

- `.env`에는 실제 비밀값을 둔다.
- `.env.example`에는 필요한 환경변수 이름과 예시값만 작성한다.
- 민감정보는 코드, 문서, PR, 이슈에 포함하지 않는다.

---

## 11. 테스트 및 검증 기준

### 11.1 기본 검증 명령

작업 완료 전 반드시 다음 명령을 실행한다.

```bash
./gradlew clean build
```

로컬 Gradle 캐시 문제가 있으면 다음 명령을 사용할 수 있다.

```bash
GRADLE_USER_HOME=/tmp/fitback-gradle-home ./gradlew clean build
```

### 11.2 테스트 작성 기준

구현 시작 후 다음 기준으로 테스트를 추가한다.

- Service 테스트는 비즈니스 규칙과 예외 케이스를 검증한다.
- Controller 테스트는 요청 검증, 응답 포맷, HTTP Status Code를 검증한다.
- Repository 테스트는 실제 조회 조건이나 DB 제약조건이 필요한 경우에 작성한다.
- 에러 핸들러 테스트는 공통 응답 포맷과 에러 코드를 검증한다.
- 단순 getter/setter 또는 프레임워크 기본 동작만 검증하는 테스트는 지양한다.

---

## 12. 로깅 규칙

- 예상 가능한 비즈니스 예외는 필요한 수준에서만 기록한다.
- 예상하지 못한 서버 예외는 `error` 레벨로 stack trace를 남긴다.
- 디버깅용 로그는 작업 완료 전 제거하거나 적절한 레벨로 조정한다.
- 비밀번호, 토큰, 인증 코드, 개인정보 등 민감정보는 로그에 남기지 않는다.
- 로그 메시지는 원인 파악에 필요한 컨텍스트를 포함하되, 민감정보는 제외한다.

---

## 13. CI 규칙

GitHub Actions는 다음 브랜치에서 실행된다.

- `main`
- `develop`

CI는 다음 명령을 수행한다.

```bash
./gradlew clean build
```

현재 CI에서는 MySQL service를 띄우지 않는다. 테스트는 H2 기반 `application-test.yml`을 사용한다.

MySQL service CI는 다음 시점에 별도 이슈로 추가한다.

- Entity와 Repository가 추가된 이후
- MySQL 고유 제약조건 검증이 필요해진 경우
- Flyway 또는 Liquibase가 도입된 경우
- H2와 MySQL 차이로 인한 검증 필요성이 생긴 경우

GitHub Actions 외부 액션은 full commit SHA로 고정한다.
`actions/checkout`에는 다음 설정을 사용한다.

```yaml
with:
  persist-credentials: false
```

GitHub Actions 업데이트는 Dependabot PR을 통해 관리한다.

---

## 14. 문서 동기화 규칙

ERD와 API 명세서를 기준으로 다음 상황에서는 관련 문서를 함께 업데이트한다.

- Entity 또는 DB 컬럼이 변경된 경우 ERD/DDL 문서를 업데이트한다.
- DB 제약조건, 인덱스, 마이그레이션 파일이 변경된 경우 ERD/DDL 문서를 업데이트한다.
- API URI, 요청값, 응답값이 변경된 경우 API 명세서를 업데이트한다.
- 에러 코드가 추가되거나 변경된 경우 API 명세서의 예외 응답을 업데이트한다.
- 인증 필요 여부가 변경된 경우 API 명세서와 Swagger 설명을 업데이트한다.
- 실행 방법 또는 환경변수가 변경된 경우 README, `.env.example`, application 설정 파일을 업데이트한다.
- PR 설명에 ERD/API 명세서 변경 여부를 작성한다.

---

## 15. Git 및 협업 규칙

### 15.1 커밋 컨벤션

커밋 메시지는 다음 형식을 따른다.

```text
태그: 한국어 설명
```

사용 가능한 태그는 다음과 같다.

```text
feat: 새로운 기능 추가 및 기능 업데이트 커밋
refactor: 리팩터링 커밋
fix: 버그 수정 커밋
style: 코드 포맷팅 등 스타일 변경 커밋
docs: 문서 커밋
chore: 오타 수정 등 기타 커밋
test: 테스트 관련 커밋
build: 배포 커밋
ci: CI 설정 파일 및 스크립트 변경, GitHub Actions 설정 추가
```

예시:

```text
feat: 회원가입 API 추가
fix: 로그인 예외 처리 수정
docs: README 실행 방법 정리
test: 회원가입 서비스 테스트 추가
ci: 백엔드 CI 실행 조건 정리
```

### 15.2 브랜치 전략

```text
main
develop
feature/#{issue-number}-{feature-name}
docs/{document-name}
```

브랜치 역할은 다음과 같다.

```text
main: 최종 제출 및 배포 기준 브랜치
develop: 백엔드 통합 개발 브랜치
feature/#{issue-number}-{feature-name}: 이슈 기반 기능 작업 브랜치
docs/{document-name}: 문서 작업 브랜치
```

예시:

```bash
git checkout develop
git pull --ff-only origin develop
git checkout -b feature/#12-auth
```

PR 흐름은 다음을 따른다.

```text
feature/#{issue-number}-{feature-name}
        ↓ PR
develop
        ↓ 최종 PR
main
```

### 15.3 이슈 규칙

이슈 제목은 다음 형식을 따른다.

```text
Feat: 회원가입 API 구현
Fix: 로그인 예외 처리 수정
Docs: API 명세서 수정
```

이슈 본문은 다음 형식을 따른다.

```md
## Issue Overview

- 이슈 개요:

## Issue Description

- 세부 설명:
  -

## To do

- [ ]
```

### 15.4 PR 규칙

PR은 기본적으로 `feature` 브랜치에서 `develop` 브랜치로 생성한다.

```md
## 관련된 이슈

close #

## 작업 내용

-

## 공유 사항

-

## 체크리스트

- [ ] Reviewer에 팀원들을 선택 했나요?
- [ ] Assignees에 본인을 선택 했나요?
- [ ] Merge 하려는 브랜치가 올바르게 설정되어 있나요?
- [ ] 컨벤션을 지키고 있나요?
- [ ] 로컬에서 실행했을 때 에러가 발생하지 않나요?
- [ ] 불필요한 주석이 제거되었나요?
- [ ] 코드 스타일이 일관적인가요?
- [ ] Entity 또는 DB 변경 시 ERD/DDL 문서를 업데이트했나요?
- [ ] API 변경 시 API 명세서를 업데이트했나요?
```

PR 작성 시 다음을 지킨다.

- 관련 이슈 번호를 반드시 연결한다.
- 이슈를 닫는 PR이면 `close #이슈번호`를 사용한다.
- 후속 작업이거나 참고만 하는 경우 `Related to #이슈번호`를 사용한다.
- 작업 내용은 실제 변경사항 기준으로 작성한다.
- 공유 사항에는 리뷰어가 알아야 할 제한사항, 후속 작업, 제외 범위를 적는다.
- 테스트 결과를 명시한다.
- API, DB, 인증 정책 변경 시 명세서/ERD/Swagger 업데이트 여부를 명시한다.

### 15.5 리뷰 코멘트 규칙

- 리뷰 코멘트는 명확하고 실행 가능한 형태로 작성한다.
- 코멘트 유형을 구분한다.
  - `[필수]`: merge 전 반드시 수정해야 하는 사항
  - `[제안]`: 개선하면 좋은 사항
  - `[질문]`: 의도 확인이 필요한 사항
  - `[참고]`: 공유 목적의 정보
- 작성자는 코멘트 반영 후 답글로 반영 내용을 남긴다.
- 반영하지 않는 경우 이유를 설명한다.
- 리뷰어는 수정 확인 후 conversation을 resolve한다.

---

## 16. 기술 스택

### Language

- Java 21

### Framework

- Spring Boot 4.1.0

### Database

- MySQL 8.0
- H2: 테스트 프로필에서 사용

### ORM

- Spring Data JPA

### Security

- Spring Security
- JWT는 인증/인가 구현 시 도입

### API Documentation

- SpringDoc OpenAPI
- Swagger UI

### Infra / DevOps

- GitHub Actions
- Dependabot

### Collaboration

- GitHub Issues
- GitHub Pull Requests
- Discord
- Notion

---

## 17. 운영 배포 컨벤션

### 17.1 배포 기준

- `main`을 production 배포 기준 브랜치로 사용한다.
- 이미지는 Git SHA 태그로 발행하고 ECR에서 조회한 digest로만 배포한다.
- ECR tag immutability를 유지하며 동일 SHA 재실행은 기존 태그를 재사용한다.
- 배포 concurrency로 같은 production 환경의 중복 실행을 직렬화한다.

### 17.2 AWS 인증과 비밀정보

- GitHub Actions는 OIDC 역할 위임을 사용하며 장기 AWS Access Key를 사용하지 않는다.
- EC2는 instance profile로 ECR, SSM, Parameter Store에 접근한다.
- DB URL, 사용자, 비밀번호는 Parameter Store SecureString으로 관리한다.
- 운영 비밀값은 GitHub Repository Variable, workflow payload, Compose `.env`, 저장소, 문서, 로그에 기록하지 않는다.
- EC2 운영 접속은 SSM으로 제한하고 SSH key pair와 22 포트를 사용하지 않는다.

### 17.3 배포 검증과 장애 대응

- 정상 배포 후 `/nginx-health`와 `/actuator/health/readiness`를 확인한다.
- Spring Boot 8080 포트가 외부에서 직접 접근되지 않는지 확인한다.
- 실패 시 직전 release 전체와 `current` symlink가 함께 복원되어야 한다.
- 이미지 pull, readiness timeout, 중복 실행, rollback 실패, 신호 종료 경로를 자동화된 테스트로 유지한다.
- 실제 production에 장애를 주지 않은 검증은 실제 AWS 통합 검증과 결정적 mock 검증을 구분해 기록한다.
- 장애 발생 시 GitHub Actions의 SSM command ID와 EC2의 `/opt/fitback/current` 및 release 상태를 먼저 확인하고, 비밀값을 로그로 출력하지 않는다.
