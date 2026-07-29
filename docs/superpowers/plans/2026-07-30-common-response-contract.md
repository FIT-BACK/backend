# Common Response Contract Implementation Plan

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
