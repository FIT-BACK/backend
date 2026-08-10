# FIT-BACK Backend

FIT-BACK 서비스의 백엔드 레포지토리입니다.

## 기술 스택

- Java 21
- Spring Boot 4.1.0
- Gradle
- Spring Data JPA
- Spring Security
- Spring Validation
- MySQL
- H2 Database
- SpringDoc OpenAPI

## 로컬 실행 방법

### 1. 환경변수 파일 생성

`.env.example` 파일을 복사해 `.env` 파일을 생성합니다.

```bash
cp .env.example .env
```

`.env` 파일 예시는 다음과 같습니다.

```env
DB_URL=jdbc:mysql://localhost:3306/fitback?serverTimezone=Asia/Seoul&characterEncoding=UTF-8
DB_USER=your_mysql_user
DB_PASSWORD=your_mysql_password
JWT_SECRET_KEY=change-me-to-at-least-32-byte-random-secret
AWS_REGION=ap-northeast-2
IMAGE_BUCKET=fitback-prod-images-123209654535-ap-northeast-2
IMAGE_CDN_BASE_URL=https://d1p2ierkew26r1.cloudfront.net
CLOUDFRONT_KEY_PAIR_ID=K1XNJ3JDEDCVL3
CLOUDFRONT_PRIVATE_KEY_BASE64=bG9jYWwtcHJpdmF0ZS1rZXk=
IMAGE_S3_API_CALL_TIMEOUT=PT5S
IMAGE_S3_API_CALL_ATTEMPT_TIMEOUT=PT2S
HMAC_SECRET_KEY=change-me-to-a-stable-32-byte-random-secret
FITBACK_AI_TAG_ANALYZER=unavailable
FITBACK_AI_REQUEST_TIMEOUT=PT30S
FITBACK_AI_OPENAI_API_KEY=your-openai-api-key
FITBACK_AI_OPENAI_MODEL=
FITBACK_AI_BEDROCK_MODEL_ID=
SHOPIFY_ENABLED=false
SHOPIFY_GLOBAL_CATALOG_ENDPOINT=https://catalog.shopify.com/api/ucp/mcp
SHOPIFY_AGENT_PROFILE_URL=https://shopify.dev/ucp/agent-profiles/2026-04-08/valid-with-capabilities.json
SHOPIFY_CONNECT_TIMEOUT=PT3S
SHOPIFY_READ_TIMEOUT=PT10S
SHOPIFY_SNAPSHOT_TTL=PT15M
SHOPIFY_ADDRESS_COUNTRY=KR
SHOPIFY_LANGUAGE=ko
SHOPIFY_CURRENCY=KRW
SHOPPING_CANDIDATE_TOKEN_TTL=PT10M
SHOPPING_PROVIDER=fixture
RECOMMENDATION_IMAGE_COMPARISON_CANDIDATE_LIMIT=30
APP_CORS_ALLOWED_ORIGINS=https://frontend-chi-one-35.vercel.app,http://localhost:3000,http://localhost:5173,http://127.0.0.1:3000,http://127.0.0.1:5173
KAKAO_REST_API_KEY=team_kakao_rest_api_key
KAKAO_REST_API_SECRET=team_kakao_client_secret
FRONT_REDIRECT_URI=http://localhost:3000/oauth/success
MAIL_EMAIL=your-email@gmail.com
MAIL_APP_PASSWORD=your-google-app-password
FRONT_PASSWORD_RESET_URL=http://localhost:3000/reset-password
```

`RECOMMENDATION_IMAGE_COMPARISON_CANDIDATE_LIMIT`는 태그별 상품 검색 결과 중 이미지 비교 단계에 전달할
최대 후보 개수이며 기본값은 30입니다. 사용자 이미지 1장을 포함하면 총 31장이므로 요청당 최대
8장을 처리하는 현재 Fashion-CLIP PoC 기준 4개 배치로 나눌 수 있습니다. 이미지 비교 처리 시간과
API 비용에 따라 코드 수정 없이 조정할 수 있습니다. 선택된 후보만 이후 점수 계산과 추천 상품 저장
단계로 전달되므로 이 값을 낮추면 최종 추천 후보 수도 줄어들 수 있습니다.

쇼핑 공급자는 기본값으로 `fixture`를 사용합니다. Shopify Global Catalog를 사용하려면
`SHOPPING_PROVIDER=shopify`와 `SHOPIFY_ENABLED=true`를 함께 설정합니다. 익명 호출에는 API
키가 필요하지 않지만 agent profile URL이 필요하며, 기본 profile은 개발 검증용입니다.
Shopify 상품은 provider/product/variant/merchant ID만 저장하며 상품명·가격·이미지·구매 URL은
상세, 저장 상품 목록, 추천 결과 조회 시 Global Catalog에서 실시간 조회합니다.
상품 검색에서 발급하는 candidate token은 기본 10분 동안 유효하며
`SHOPPING_CANDIDATE_TOKEN_TTL`에 ISO-8601 Duration 형식으로 설정합니다.

배포형 최소 프로토타입에서는 다음 비민감 runtime 설정을 함께 전달합니다.

```env
FITBACK_AI_TAG_ANALYZER=prototype
SHOPPING_PROVIDER=shopify
SHOPIFY_ENABLED=true
```

`prototype` 분석기는 실제 이미지 의미를 판별하는 AI가 아니라 S3 업로드부터 분석·추천까지의
계약을 검증하기 위한 결정적 fallback입니다. 운영 기본값 `unavailable`은 실제 AI 공급자가
연결되기 전 데모 태그가 운영 데이터에 섞이지 않도록 fail-closed로 유지합니다.
실제 분석기는 `FITBACK_AI_TAG_ANALYZER=openai` 또는 `bedrock`으로 선택할 수 있으며,
두 공급자의 동일 조건 비교 절차는 [AI 태그 모델 블라인드 평가](docs/AI_TAG_BLIND_EVALUATION.md)를
따릅니다.

```env
# OpenAI
FITBACK_AI_TAG_ANALYZER=openai
FITBACK_AI_REQUEST_TIMEOUT=PT30S
FITBACK_AI_OPENAI_API_KEY=your-openai-api-key
FITBACK_AI_OPENAI_MODEL=gpt-5.6-luna

# 또는 Bedrock — 로컬은 AWS_PROFILE, 운영은 EC2 instance role 사용
FITBACK_AI_TAG_ANALYZER=bedrock
FITBACK_AI_REQUEST_TIMEOUT=PT30S
AWS_REGION=ap-northeast-2
FITBACK_AI_BEDROCK_MODEL_ID=global.anthropic.claude-haiku-4-5-20251001-v1:0
AWS_PROFILE=your-sso-profile
```

실제 값은 저장소에 커밋하지 않는다. 전체 변수 목록은 `.env.example`을 기준으로 한다.
Shopify를 사용할 때는 상품 식별자만 저장하며 표시 정보와 구매 URL은 `lookup_catalog`으로
실시간 조회합니다.

### 2. MySQL 데이터베이스 생성

로컬 MySQL에 사용할 데이터베이스를 생성합니다.

```sql
CREATE DATABASE fitback;
```

### 3. 애플리케이션 실행

```bash
./gradlew bootRun --args='--spring.profiles.active=local'
```

운영 설정이 실수로 로컬 설정으로 대체되지 않도록 실행 프로필을 명시합니다.

## 테스트 및 빌드

```bash
./gradlew clean build
```

테스트 환경에서는 `application-test.yml`을 통해 H2 인메모리 DB를 사용합니다.
쇼핑 공급자 contract test도 외부 네트워크 없이 fixture Adapter를 기준으로 실행합니다.
GitHub Actions의 Backend CI는 이 Gradle 빌드와 함께
`bash scripts/ci/test_mysql_migrations.sh`를 실행하여 MySQL 8.4 컨테이너에서
현재 Flyway `V1`~`V25` 마이그레이션과 주요 제약조건을 검증합니다.
ECR 이미지 발행, Nginx 공개 진입점, 원격 배포 payload 계약까지 포함한 전체 CI 명령은
[AGENTS.md의 CI 규칙](AGENTS.md#15-ci-규칙)과
[workflow 정의](.github/workflows/backend-ci.yml)를 기준으로 합니다.

## Swagger

애플리케이션 실행 후 아래 주소에서 Swagger UI를 확인할 수 있습니다.

```text
http://localhost:8080/swagger-ui.html
http://localhost:8080/v3/api-docs
```

## 운영 배포

운영 이미지는 GitHub Actions에서 ECR에 발행하고, AWS Systems Manager Run Command를 통해 EC2의 Docker Compose stack으로 배포합니다.

필요한 GitHub 변수, IAM 최소 권한, Parameter Store 경로, EC2 runtime 및 rollback 절차는 [운영 배포 문서](docs/DEPLOYMENT.md)를 참고합니다.

현재 운영 배포는 `main` push 또는 수동 `workflow_dispatch`로 실행됩니다. Git SHA 기반 ECR 태그가 이미 있으면 기존 불변 태그를 재사용하고 digest로 배포하므로 같은 commit을 안전하게 다시 실행할 수 있습니다. 운영 프로필의 구조와 기본 정책은 저장소의 `application-prod.yml`로 추적하고, 비민감 기능 설정은 GitHub Repository Variable, 비밀값은 Parameter Store SecureString으로 분리합니다. `.env.example`은 로컬 실행에 필요한 변수 계약이며 운영 비밀값의 저장소가 아닙니다.

운영 확인 경로는 다음과 같습니다.

```text
https://d1ra74et9h0ohu.cloudfront.net
https://d1ra74et9h0ohu.cloudfront.net/swagger-ui.html
https://d1ra74et9h0ohu.cloudfront.net/nginx-health
https://d1ra74et9h0ohu.cloudfront.net/actuator/health/readiness
```

CloudFront 기본 도메인의 루트는 `200 OK` 안내 페이지를 반환하며 Swagger UI와 readiness로 이동할 수 있는 링크를 제공합니다. EC2의 HTTP 80은 CloudFront 원본 요청에만 허용하고 Spring Boot의 8080 포트는 외부에 공개하지 않습니다. 운영 비밀값은 GitHub 변수나 저장소가 아니라 EC2 instance role이 Parameter Store SecureString에서 직접 읽습니다.

사용자 업로드 이미지는 비공개 S3 버킷에 저장하며, `https://d1p2ierkew26r1.cloudfront.net`에서 서명된 URL로만 조회합니다. S3 직접 접근과 서명 없는 CloudFront 접근은 허용하지 않습니다.

인증된 사용자는 `POST /api/v1/images/upload-requests`에서 5분 유효한 Presigned POST 정보를
발급받아 JPEG, PNG, WebP 이미지를 최대 5 MiB까지 S3로 직접 업로드할 수 있습니다. S3 업로드는
응답의 `uploadUrl`과 `uploadFields`를 `FormData`로 전송한 뒤 완료 API를 호출하는 방식입니다. 자세한
계약은 [API 명세](docs/API_SPEC.md)를 참고합니다.

운영 프로필의 분석 생성은 완료된 S3 이미지의 `imageId`를 JSON body로 전달해야 합니다.
기존 multipart 분석 요청은 로컬 프로필에서만 파일을 저장하며 운영에서는 `ANALYSIS400_3`으로
거절합니다.

## Security

JWT 기반 인증을 사용합니다. `SecurityConfig`에서 다음 경로를 인증 없이 허용합니다.

- Swagger/OpenAPI와 Actuator health 경로
- 회원가입·로그인·토큰 재발급/교환·비밀번호 재설정 경로
- 카카오 로그인 경로(`/api/v1/auth/oauth2/**`, `/api/v1/auth/callback/**`)
- 읽기 전용 공개 API의 `GET`: `/api/v1/trends/**`, `/api/v1/tags/**`, `/api/v1/content-search`, `/api/v1/lookbooks`, `/api/v1/lookbooks/*`

명시되지 않은 요청은 인증이 필요합니다. 룩북의 생성·수정·삭제·좋아요처럼 상태를 변경하는 요청도 인증 대상입니다.
요청의 `Authorization: Bearer {accessToken}` 헤더는 `JwtAuthFilter`가 검증하여 인증 정보를 설정합니다.
REST API 기준으로 CSRF, Form Login, HTTP Basic은 비활성화되어 있으며, 세션은 `STATELESS`로 사용합니다.

로컬 프론트엔드 QA에서는 다음 Origin만 백엔드 CORS allowlist에 포함합니다.

```text
http://localhost:3000
http://localhost:5173
http://127.0.0.1:3000
http://127.0.0.1:5173
```

회원가입·로그인·토큰 재발급·카카오 임시 토큰 교환에서 access/refresh JWT는 응답 본문의
`data`에 발급합니다. 이후 인증 요청은 access token을 `Authorization: Bearer {accessToken}`
헤더로 보내며, refresh token은 재발급 API의 JSON body로 전달합니다. credential cookie는
허용하지 않습니다.
운영 프로필의 Spring CORS allowlist는 comma-separated `APP_CORS_ALLOWED_ORIGINS` 환경변수로
주입합니다. GitHub Repository Variable을 변경한 경우 새 production 배포가 필요합니다.
배포 후에는 allowlist에 포함된 로컬 프론트엔드 Origin으로 로그인 OPTIONS preflight와 POST 응답의
`Access-Control-Allow-Origin`을 확인합니다. Spring 애플리케이션 직접 경로는 성공하지만
CloudFront 경유 요청만 실패하면 CloudFront의 Origin 요청 헤더 전달 및 OPTIONS 캐시 정책을
별도로 확인합니다. 운영 프론트엔드 Origin 추가는 이 로컬 QA 허용 범위에 포함하지 않습니다.
이 설정은 S3 이미지 업로드 CORS 또는 EC2 보안 그룹 설정과는 무관합니다.

카카오 소셜 로그인은 Spring OAuth2 Client 기반의 백엔드 주도 리다이렉트 방식입니다. `GET /api/v1/auth/oauth2/kakao`로 시작하면 카카오 인증 페이지로 리다이렉트되고, 콜백(`/api/v1/auth/callback/kakao`) 처리 후 프론트 URL(`FRONT_REDIRECT_URI`)로 일회용 임시 토큰 또는 에러 코드를 전달합니다. access/refresh JWT는 임시 토큰을 `POST /api/v1/auth/token/exchange`로 교환할 때 발급됩니다.

## 브랜치 컨벤션

```text
main
develop
feature/#{issue-number}-{feature-name}
fix/#{issue-number}-{fix-name}
chore/#{issue-number}-{task-name}
docs/#{issue-number}-{document-name}
```

예시:

```text
feature/#12-auth
fix/#34-login-error
chore/#56-ci-cleanup
docs/#78-api-spec
```

## 커밋 컨벤션

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

## 협업 규칙

1. 이슈 템플릿을 활용해서 GitHub Issue를 먼저 생성합니다.
2. `develop` 브랜치에서 이슈 번호에 맞게 작업 브랜치를 생성합니다.
3. 이슈 범위 안에서만 작업하고 커밋은 의미 단위로 나눕니다.
4. 작업 완료 전 `./gradlew clean build`로 검증합니다.
5. [PR 템플릿](.github/pull_request_template.md)을 활용해서 `develop` 브랜치로 PR을 생성합니다.
6. 리뷰어를 지정하고 테스트 결과를 공유한 뒤 승인 후 merge합니다.

자세한 컨벤션은 `AGENTS.md`를 참고합니다.
