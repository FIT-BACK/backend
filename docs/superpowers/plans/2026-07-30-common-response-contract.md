# Common Response Contract Implementation Plan

> **상태 (2026-08-01): 완료·병합됨.** [Issue #161](https://github.com/FIT-BACK/backend/issues/161)과 [PR #163](https://github.com/FIT-BACK/backend/pull/163)의 실행 기록이다. 아래 본문과 체크박스는 작성 당시 계획을 보존하며 현재 완료 여부를 나타내지 않는다. 최신 계약은 [API_SPEC.md](../../API_SPEC.md)와 구현 코드를 기준으로 한다.

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Align the closet-save creation HTTP status with its common response code and prevent failure responses from carrying payload data.

**Architecture:** Keep the existing `ApiResponse` envelope and controller flow. Return a `ResponseEntity` only where the HTTP status must be explicit, remove the failure factory overload that accepted data, and enforce the failure-data invariant in the record constructor.

**Tech Stack:** Java 21, Spring Boot 4.1, JUnit 5, AssertJ, Mockito

---

### Task 1: Enforce the common failure response invariant

**Files:**
- Modify: `src/main/java/com/fitback/backend/global/response/ApiResponse.java`
- Modify: `src/main/java/com/fitback/backend/global/security/exception/CustomEntryPoint.java`
- Modify: `src/main/java/com/fitback/backend/global/security/exception/CustomAccessDenied.java`
- Modify: `src/main/java/com/fitback/backend/global/security/filter/JwtAuthFilter.java`
- Test: `src/test/java/com/fitback/backend/global/response/ApiResponseTest.java`

- [ ] Reject non-null data when `success=false`.
- [ ] Remove the three-argument failure factory.
- [ ] Migrate security error writers to the two-argument failure factory.
- [ ] Test both the supported factory and direct-construction invariant.

### Task 2: Return HTTP 201 for closet-save creation

**Files:**
- Modify: `src/main/java/com/fitback/backend/domain/closet/controller/ClosetSaveController.java`
- Test: `src/test/java/com/fitback/backend/domain/closet/controller/ClosetSaveControllerTest.java`

- [ ] Return `ResponseEntity` with `HttpStatus.CREATED`.
- [ ] Assert both the HTTP status and `COMMON201_1` body contract.

### Task 3: Contract and completion

**Files:**
- Modify: `docs/API_SPEC.md`

- [ ] State that failure response data is always null.
- [ ] Run focused response/controller tests and `git diff --check`.
- [ ] Run `./gradlew clean build`.
- [ ] Commit, push, open PR to `develop`, and complete issue #161 through merge.
