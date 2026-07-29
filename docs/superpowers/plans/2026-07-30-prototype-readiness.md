# Prototype Readiness Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 운영형 S3 업로드 경로에서 명시적 프로토타입 태그 분석과 상품 추천을 끝까지 실행할 수 있게 한다.

**Architecture:** 운영 프로필의 multipart 로컬 저장을 별도 fail-closed 구현으로 교체하고, `fitback.ai.tag-analyzer=prototype`일 때만 기준 태그를 반환하는 프로토타입 분석기를 활성화한다. 상품 공급자는 기존 `ProductCatalogPort`와 Shopify ID-only/live lookup 구현을 유지하며, 배포 스크립트가 AI·상품 공급자 모드를 비민감 runtime 설정으로 전달한다.

**Tech Stack:** Java 21, Spring Boot 4.1, Spring Profiles/ConditionalOnProperty, Flyway/MySQL 8.4, Docker Compose, Bash

## Global Constraints

- 실제 AI 모델이 아닌 프로토타입 분석 결과를 운영 기본값으로 위장하지 않는다.
- 운영 multipart 분석은 로컬 파일을 쓰지 않고 S3 `imageId` 경로로 안내한다.
- Shopify 상품은 provider/product/variant/merchant ID만 저장하고 표시 정보는 live lookup한다.
- 비밀값을 저장소, 배포 `.env`, 로그에 추가하지 않는다.
- 검증 및 서브에이전트 사용을 최소화하되 완료 전 `./gradlew clean build`를 실행한다.

---

### Task 1: 운영 분석 경로를 fail-closed로 분리

**Files:**
- Modify: `src/main/java/com/fitback/backend/domain/analysis/service/LocalImageStorage.java`
- Create: `src/main/java/com/fitback/backend/domain/analysis/service/UnavailableMultipartImageStorage.java`
- Modify: `src/main/java/com/fitback/backend/global/exception/ErrorCode.java`
- Modify: `src/test/java/com/fitback/backend/global/health/HealthEndpointIntegrationTest.java`

**Interfaces:**
- Consumes: `ImageStorage.store(MultipartFile)`와 `prod` Spring profile
- Produces: 운영 multipart 요청의 `ANALYSIS400_3` 오류와 S3 `imageId` 경로 강제

- [x] **Step 1: 운영 프로필 bean 회귀 테스트 작성**

`HealthEndpointIntegrationTest`에서 주입된 `ImageStorage` 구현명이
`UnavailableMultipartImageStorage`인지 검증한다.

- [x] **Step 2: 테스트가 현재 `LocalImageStorage`로 실패하는지 확인**

Run: `./gradlew test --tests '*HealthEndpointIntegrationTest'`
Expected: FAIL because the production bean is `LocalImageStorage`.

- [x] **Step 3: 최소 구현**

`LocalImageStorage`를 `default/local/test` 프로필로 제한하고 운영 구현은
`ANALYSIS_IMAGE_UPLOAD_FLOW_REQUIRED`를 발생시킨다.

- [x] **Step 4: focused test 통과 확인**

Run: `./gradlew test --tests '*HealthEndpointIntegrationTest'`
Expected: PASS.

### Task 2: 명시적 프로토타입 분석 모드 추가

**Files:**
- Modify: `src/main/resources/application.properties`
- Modify: `src/main/java/com/fitback/backend/domain/analysis/service/UnavailableAiTagAnalyzer.java`
- Create: `src/main/java/com/fitback/backend/domain/analysis/service/PrototypeAiTagAnalyzer.java`
- Modify: `src/main/java/com/fitback/backend/domain/tag/repository/TagRepository.java`
- Create: `src/test/java/com/fitback/backend/domain/analysis/service/PrototypeAiTagAnalyzerTest.java`
- Create: `src/test/java/com/fitback/backend/global/health/PrototypeProductionProfileIntegrationTest.java`

**Interfaces:**
- Consumes: `FITBACK_AI_TAG_ANALYZER`, `TagRepository`
- Produces: `fitback.ai.tag-analyzer=prototype`에서 `미니멀`, `와이드핏`, `베이지톤` 순서의 분석 태그

- [x] **Step 1: 분석기 단위 테스트와 prod prototype bean 테스트 작성**

세 기준 태그의 순서를 검증하고 태그 누락 시 `ANALYSIS_NOT_READY`를 검증한다.
prod+prototype 컨텍스트에서는 `PrototypeAiTagAnalyzer`가 유일한 `AiTagAnalyzer`인지 검증한다.

- [x] **Step 2: 새 분석기 부재로 테스트가 실패하는지 확인**

Run: `./gradlew test --tests '*PrototypeAiTagAnalyzerTest' --tests '*PrototypeProductionProfileIntegrationTest'`
Expected: FAIL because `PrototypeAiTagAnalyzer` does not exist.

- [x] **Step 3: 조건부 분석기 구현**

운영 기본 `unavailable` 분석기는 유지하고 명시적 `prototype` 값에서만 새 분석기를 활성화한다.
multipart 입력은 `ANALYSIS_IMAGE_UPLOAD_FLOW_REQUIRED`로 거절하고 S3 `Image` 입력만 처리한다.

- [x] **Step 4: focused test 통과 확인**

Run: `./gradlew test --tests '*PrototypeAiTagAnalyzerTest' --tests '*PrototypeProductionProfileIntegrationTest'`
Expected: PASS.

### Task 3: 프로토타입 기준 태그 migration

**Files:**
- Create: `src/main/resources/db/migration/V16__seed_prototype_analysis_tags.sql`
- Modify: `scripts/ci/test_mysql_migrations.sh`
- Modify: `docs/ERD.md`

**Interfaces:**
- Consumes: 기존 `tag(tag_name, tag_type)`와 `UK_TAG_NAME`
- Produces: 멱등 기준 태그 `미니멀/DETAIL`, `와이드핏/SILHOUETTE`, `베이지톤/COLOR`

- [x] **Step 1: MySQL migration 검증에 tag baseline과 결과 검증 추가**

기존 태그를 보존하고 누락된 기준 태그만 생성되는지 검증한다.

- [x] **Step 2: V16 migration 작성**

각 태그를 `INSERT ... SELECT ... WHERE NOT EXISTS`로 추가한다.

- [x] **Step 3: migration 검증**

Run: `bash scripts/ci/test_mysql_migrations.sh`
Expected: `MySQL migration tests passed.`

### Task 4: 배포 runtime 설정과 문서 동기화

**Files:**
- Modify: `compose.yaml`
- Modify: `scripts/deploy/remote_deploy.sh`
- Modify: `scripts/deploy/test_remote_deploy.sh`
- Modify: `.env.example`
- Modify: `README.md`
- Modify: `docs/API_SPEC.md`
- Modify: `docs/DEPLOYMENT.md`

**Interfaces:**
- Consumes: `FITBACK_AI_TAG_ANALYZER`, `SHOPPING_PROVIDER`, `SHOPIFY_ENABLED`
- Produces: Docker Compose 프로세스까지 전달되는 비민감 feature configuration

- [x] **Step 1: 배포 mock test에 runtime 설정 기대값 추가**

release `.env`와 Compose 환경에 세 설정이 전달되는지 검증한다.

- [x] **Step 2: 배포 스크립트와 Compose 설정 구현**

기본값은 안전하게 `unavailable/fixture/false`로 유지하고, 프로토타입 배포 시
`prototype/shopify/true`를 명시할 수 있게 한다.

- [x] **Step 3: 문서 동기화**

운영 multipart 종료, S3 `imageId` 필수, prototype 분석의 비-AI 성격,
Shopify ID-only/live lookup 활성화 조합을 기록한다.

- [x] **Step 4: 배포 script 검증**

Run: `bash scripts/deploy/test_remote_deploy.sh`
Expected: `remote_deploy.sh tests passed.`

### Task 5: 전체 검증 및 전달

**Files:**
- Verify all modified files

**Interfaces:**
- Consumes: Tasks 1-4
- Produces: PR #164에서 병합 가능한 검증 증거

- [x] **Step 1: 정적 diff 검증**

Run: `git diff --check`
Expected: exit 0.

- [x] **Step 2: 전체 빌드**

Run: `GRADLE_USER_HOME=/tmp/fitback-pr164-gradle ./gradlew clean build --no-daemon`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: 커밋·push·PR·CI 후 병합**

Issue #164와 관련 이슈 #88을 연결하고 `develop` 대상 PR을 생성한다. CI 성공 후 사용자 요청에
따라 별도 리뷰 승인 대기 없이 병합한다.
