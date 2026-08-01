# Develop Documentation Sync Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `develop` 브랜치의 구현 코드, DB migration, 보안 설정, CI/CD 계약과 저장소의 모든 Markdown 문서를 일치시킨다.

**Architecture:** 현재 계약 문서는 실제 컨트롤러·DTO·설정·migration을 기준으로 수정한다. 과거 구현 계획과 ADR은 당시 기록을 바꾸지 않고 상단에 상태, 구현 결과, 현재 기준 문서 링크를 추가해 역사 문서임을 명확히 한다. 외부 인프라 상태는 확인일과 증거를 함께 기록하고 저장소만으로 확정할 수 없는 값을 현재 상태처럼 단정하지 않는다.

**Tech Stack:** Markdown, Spring Boot 4.1, Spring Security/JWT, Flyway V1~V21, MySQL 8.4 CI contract, AWS S3/CloudFront/ECR/SSM, GitHub Actions

## Global Constraints

- 기준 브랜치/커밋은 `develop`의 `9ddb4508089cb0ddb108d196e639c58036ebd12a`이다.
- 문서만 수정하며 Java, SQL, workflow, 배포 스크립트의 동작은 바꾸지 않는다.
- 현재 구현과 문서가 충돌하면 문서를 구현에 맞추고, 미구현 설계는 미래 제안으로 구분한다.
- 과거 계획과 ADR의 본문·체크박스는 소급 수정하지 않고 상태 배너만 추가한다.
- 비밀값, 실제 토큰, 개인식별정보를 문서에 추가하지 않는다.

---

## Task 1: 저장소 운영·개발 기준 동기화

**Files:**
- Modify: `README.md`
- Modify: `AGENTS.md`
- Modify: `BACKEND_CONVENTION.md`

- [x] 공개/인증 API 범위를 `SecurityConfig`와 일치시킨다.
- [x] JWT 발급·전달 방식, 현재 URI, 공통 응답·오류 코드를 현재 DTO와 enum에 맞춘다.
- [x] 로컬 프로필, Flyway, MySQL 8.4 migration CI, 브랜치 prefix, 추적 설정 파일 설명을 갱신한다.
- [x] 중복된 PR template 본문은 실제 `.github/pull_request_template.md`를 기준으로 정리한다.

## Task 2: API 계약 동기화

**Files:**
- Modify: `docs/API_SPEC.md`
- Modify: `docs/FRONTEND_PROTOTYPE_API_HANDOFF.md`
- Modify: `docs/PROTOTYPE_FRONTEND_VERTICAL_FLOW.md`

- [x] API별 cursor 타입·페이지 크기와 공개 조회 동작을 구분한다.
- [x] 현재 구현된 Auth, Member, Lookbook, Notification API와 V20 프로필 이미지 계약을 추가한다.
- [x] 추천/상품 DTO, 공급자 오류, identity-only, partial 응답, Shopify 선택 가능 상태를 실제 코드에 맞춘다.
- [x] 이미지 canonical enum과 운영 multipart 제한을 반영한다.

## Task 3: 이미지·스키마·배포 계약 동기화

**Files:**
- Modify: `docs/IMAGE_STORAGE_POLICY.md`
- Modify: `docs/ERD.md`
- Modify: `docs/DEPLOYMENT.md`

- [x] 모든 사용자 이미지가 `PRIVATE`으로 유지되고 10분 Signed URL을 사용한다는 현재 정책을 명시한다.
- [x] Release C canonical enum과 구현된 last-reference release/cleanup 흐름을 현재 계약으로 옮긴다.
- [x] ERD에 V20 profile image 및 V21 notification과 관련 설정·동의 이력을 추가한다.
- [x] 배포 판정 조건, V1~V21 migration 범위, 최신 검증 SHA/digest와 확인일을 갱신한다.

## Task 4: 역사 문서 상태 명시

**Files:**
- Modify: `docs/SHOPIFY_PRODUCT_PROVIDER_DECISION.md`
- Modify: `docs/trend-design.md`
- Modify: `docs/trend-implementation.md`
- Modify: `docs/superpowers/plans/2026-07-30-*.md`

- [x] ADR과 trend 문서에 historical/archived 상태 및 현재 기준 문서 링크를 추가한다.
- [x] 기존 구현 계획에 관련 이슈·PR·완료 결과를 기록하되 원래 계획 본문은 보존한다.

## Task 5: 문서 정합성 검증

- [x] `git diff --check`로 whitespace 오류를 검사한다.
- [x] 로컬 Markdown 링크와 참조 파일이 존재하는지 검사한다.
- [x] 문서의 endpoint, migration V1~V21, 공개 경로를 코드 목록과 대조한다.
- [x] 비밀값 패턴을 검사한다.
- [x] `./gradlew clean build --no-daemon`을 실행한다.

## Task 6: PR 및 리뷰 마감

- [ ] 문서 변경만 선별해 커밋하고 원격 브랜치에 푸시한다.
- [ ] Issue #204를 연결한 `develop` 대상 draft PR을 생성한다.
- [ ] 독립 자체 리뷰를 수행하고 적절한 지적을 반영한다.
- [ ] CodeRabbit이 사용 가능하면 완료까지 기다려 적절한 피드백을 반영한다. 한도 도달이 명시되면 자체 리뷰 결과로 마감한다.
- [ ] 최종 변경·검증·미반영 의견을 PR과 사용자에게 보고한다.
