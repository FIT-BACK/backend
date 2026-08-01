# UT 준비 및 ACTIVE 이미지 참조 해제 구현 계획

> **상태 (2026-08-01): 완료·후속 보완까지 병합됨.** [Issue #192](https://github.com/FIT-BACK/backend/issues/192), 구현 [PR #193](https://github.com/FIT-BACK/backend/pull/193), 피드백 반영 [PR #196](https://github.com/FIT-BACK/backend/pull/196)의 실행 기록이다. 아래 본문과 체크박스는 작성 당시 계획을 보존하며 현재 완료 여부를 나타내지 않는다. 최신 이미지 계약은 [IMAGE_STORAGE_POLICY.md](../../IMAGE_STORAGE_POLICY.md)와 구현 코드를 기준으로 한다.
>
> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 최소 프로토타입의 프론트엔드 연동 계약과 UT 사전 데이터를 준비하고, ACTIVE 이미지의 마지막 논리 참조가 해제되면 커밋 후 객체를 자동 삭제한다.

**Architecture:** 분석과 룩북 서비스는 삭제·교체된 이미지 ID를 도메인 이벤트로 발행한다. 커밋 후 리스너가 분석/룩북의 활성 참조를 다시 확인하고 참조가 없을 때만 이미지를 `DELETING`으로 선점한 뒤 기존 객체 삭제기를 호출한다. UT 준비 스크립트는 인증, Presigned POST 업로드, 분석, Shopify 추천을 공개 API만으로 재현한다.

**Tech Stack:** Java 21, Spring Boot 4.1, Spring Data JPA, JUnit 5, Mockito, Bash, curl, jq

## Global Constraints

- 실제 의미 기반 AI 공급자 연동은 이번 범위에서 제외한다.
- 상품명·가격·이미지·구매 URL은 저장하지 않고 Shopify live lookup 응답을 사용한다.
- S3 삭제는 도메인 트랜잭션 안에서 실행하지 않는다.
- 비밀번호, JWT, Presigned POST 필드는 저장소·로그·Notion에 기록하지 않는다.
- 변경 관련 단위 테스트와 스크립트 문법 검사를 먼저 실행하고, 완료 전
  `./gradlew clean build` 전체 빌드를 반드시 실행한다.
- 빌드·테스트 실패를 무시하거나 검증 없이 완료 처리하지 않는다.

---

### Task 1: 마지막 이미지 참조 해제 처리

**Files:**
- Create: `src/main/java/com/fitback/backend/domain/image/event/ImageReferencesReleasedEvent.java`
- Create: `src/main/java/com/fitback/backend/domain/image/service/ImageReferenceReleaseListener.java`
- Create: `src/main/java/com/fitback/backend/domain/lookbook/service/LookbookImageReferenceProbe.java`
- Modify: `src/main/java/com/fitback/backend/domain/image/entity/Image.java`
- Modify: `src/main/java/com/fitback/backend/domain/image/repository/ImageRepository.java`
- Modify: `src/main/java/com/fitback/backend/domain/image/service/ImageCleanupService.java`
- Modify: `src/main/java/com/fitback/backend/domain/analysis/repository/AnalysisReportRepository.java`
- Modify: `src/main/java/com/fitback/backend/domain/analysis/service/AnalysisImageReferenceProbe.java`
- Modify: `src/main/java/com/fitback/backend/domain/analysis/service/AnalysisService.java`
- Modify: `src/main/java/com/fitback/backend/domain/lookbook/repository/LookbookRepository.java`
- Modify: `src/main/java/com/fitback/backend/domain/lookbook/service/LookbookService.java`
- Test: `src/test/java/com/fitback/backend/domain/image/service/ImageCleanupServiceTest.java`
- Test: `src/test/java/com/fitback/backend/domain/analysis/service/AnalysisServiceTest.java`
- Test: `src/test/java/com/fitback/backend/domain/lookbook/service/LookbookServiceTest.java`

**Interfaces:**
- Consumes: 삭제 또는 교체 전 `Collection<String> imageIds`
- Produces: `ImageCleanupService.claimReleasedActiveImages(Collection<String>)`

- [x] **Step 1: 참조가 남은 ACTIVE 이미지는 선점하지 않고 마지막 참조 해제 이미지만 DELETING으로 바꾸는 테스트를 추가한다.**
- [x] **Step 2: 테스트를 실행해 현재 ACTIVE 상태가 삭제 후보가 아님을 확인한다.**
- [x] **Step 3: ACTIVE 전용 잠금 조회, 분석/룩북 활성 참조 probe, 커밋 후 리스너를 구현한다.**
- [x] **Step 4: 분석 삭제, 룩북 삭제, 룩북 이미지 교체에서 해제 이벤트를 발행한다.**
- [x] **Step 5: 변경 관련 단위 테스트를 실행한다.**

### Task 2: 프론트엔드 세로 흐름 및 UT 자동 준비

**Files:**
- Create: `docs/PROTOTYPE_FRONTEND_VERTICAL_FLOW.md`
- Create: `scripts/ut/prepare_prototype_ut.sh`
- Modify: `docs/IMAGE_STORAGE_POLICY.md`

**Interfaces:**
- Consumes: `FITBACK_UT_BASE_URL`, UT-A/UT-B 이메일·비밀번호, JPEG/PNG 경로
- Produces: 계정별 `memberId`, `imageId`, `reportId`, 추천 상품 수를 담은 민감정보 없는 JSON 요약

- [x] **Step 1: `imageId → reportId → productId` 요청/응답과 프론트 상태 처리를 실제 DTO 기준으로 문서화한다.**
- [x] **Step 2: 회원가입/로그인, 온보딩, S3 POST, 완료, 분석, 추천을 순서대로 호출하는 스크립트를 작성한다.**
- [x] **Step 3: 스크립트가 JWT, 비밀번호, Presigned 값을 출력하지 않는지 자체 검토한다.**
- [x] **Step 4: `bash -n scripts/ut/prepare_prototype_ut.sh`를 실행한다.**

### Task 3: 운영 UT 데이터와 기록

**Files:**
- Runtime only: `.local/ut/credentials.env` (Git 제외, 권한 600)
- Runtime only: `.local/ut/prepared-data.json` (Git 제외)
- External: Notion `FIT-BACK 최소 프로토타입 UT 운영 기록 (2026-07-30)`

**Interfaces:**
- Consumes: 운영 API와 `top-01.jpeg`, `ai-dress-01.png`
- Produces: UT-A/UT-B 사전 분석·추천 데이터와 Notion 준비 기록

- [x] **Step 1: 강한 임시 비밀번호를 생성해 Git 제외 로컬 파일에만 저장한다.**
- [ ] **Step 2: UT-A는 JPEG, UT-B는 PNG로 운영 데이터를 생성한다.**
- [ ] **Step 3: 민감정보 없는 결과만 `.local/ut/prepared-data.json`과 Notion에 기록한다.**
- [ ] **Step 4: PR diff를 자체 검토하고 Issue #192 체크리스트를 갱신한다.**
- [ ] **Step 5: 커밋, push, PR, 자체 피드백, 병합을 완료한다.**
