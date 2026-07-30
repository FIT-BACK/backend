# UT Data and Frontend Handoff Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 운영 UT 계정 2건의 업로드·분석·추천 데이터를 생성하고 프론트가 실제 API를 연결하는 데 필요한 계약을 전달한다.

**Architecture:** 기존 운영 준비 스크립트로 FIT-BACK API와 Presigned POST를 순서대로 호출하고, 결과에는 비밀값을 남기지 않는다. 프론트 전달 문서는 현재 Controller, DTO, 공통 응답 envelope, Shopify live lookup 계약을 기준으로 작성하며 프론트 코드 수정은 포함하지 않는다.

**Tech Stack:** Bash, curl, jq, Spring Boot REST API, S3 Presigned POST, Shopify Global Catalog, Markdown

## Global Constraints

- 실제 의미 기반 AI 공급자 연동은 범위에서 제외한다.
- 프론트 저장소와 프론트 코드는 수정하지 않는다.
- 비밀번호, JWT, Presigned POST 필드와 URL은 로그·문서·Git에 기록하지 않는다.
- AWS Parameter Store의 프론트 URL 파라미터는 이름, 현재 URL, 쿼리 계약만 기록한다.
- 검증은 스크립트 문법, 문서 계약 대조, `git diff --check`로 최소화한다.

---

### Task 1: 운영 UT 데이터 2건 생성

**Files:**
- Use: `scripts/ut/prepare_prototype_ut.sh`
- Read: `.local/ut/credentials.env`
- Write ignored result: `.local/ut/prepared-data.json`

**Interfaces:**
- Consumes: UT-A/UT-B 이메일·비밀번호와 JPEG/PNG 파일
- Produces: `imageId`, `reportId`, `recommendationCount`, `liveFieldCount`, `productDetailDataStatus`

- [ ] **Step 1: 입력 이미지 형식과 자격 증명 파일 존재를 확인한다.**
- [ ] **Step 2: 운영 준비 스크립트를 실행한다.**
- [ ] **Step 3: 비밀값을 제외한 결과 필드로 두 계정의 완료 여부를 확인한다.**

### Task 2: 프론트 API 전달 문서

**Files:**
- Create: `docs/FRONTEND_PROTOTYPE_API_HANDOFF.md`
- Reference: `docs/PROTOTYPE_FRONTEND_VERTICAL_FLOW.md`
- Reference: `src/main/java/com/fitback/backend/domain/*/controller/*.java`

**Interfaces:**
- Consumes: 현재 운영 API 요청·응답 계약
- Produces: 프론트 구현 순서, 상태 관리, 이미지 데이터 바인딩, 오류 처리, 완료 기준

- [ ] **Step 1: 실제 Controller와 DTO에서 경로와 필드를 확인한다.**
- [ ] **Step 2: AWS Parameter Store의 프론트 URL 두 개와 쿼리 계약을 확인한다.**
- [ ] **Step 3: 프론트 전달용 단일 Markdown 문서를 작성한다.**
- [ ] **Step 4: 하드코딩 이미지 제거 범위와 백엔드 제외 범위를 명시한다.**
- [ ] **Step 5: `git diff --check`와 민감정보 패턴 검색을 실행한다.**

### Task 3: PR #193 리뷰 스레드 종료

**Files:**
- Reference: PR #193 review threads
- Reference: PR #196 and PR #197 merged changes

**Interfaces:**
- Consumes: 9개 unresolved CodeRabbit 스레드와 반영 근거
- Produces: 근거 답변과 resolved 상태

- [ ] **Step 1: 각 최상위 리뷰 댓글 ID와 현재 스레드 상태를 조회한다.**
- [ ] **Step 2: 후속 PR 반영 또는 기존 잠금 보장 근거를 답변한다.**
- [ ] **Step 3: 9개 스레드를 resolve하고 unresolved 0개를 확인한다.**

### Task 4: 문서 PR 완료

**Files:**
- Commit: plan and handoff Markdown

**Interfaces:**
- Consumes: 검증된 문서 변경
- Produces: issue #195에 연결된 develop PR과 자체 피드백

- [ ] **Step 1: 문서 변경을 커밋하고 브랜치를 push한다.**
- [ ] **Step 2: develop 대상 PR을 생성하고 자체 피드백을 남긴다.**
- [ ] **Step 3: 최소 CI 확인 후 병합한다.**
