# Content Search Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a public SCR-16 API that searches visible trends and lookbooks by text.

**Architecture:** A `contentsearch` application service validates one keyword and composes existing trend/lookbook card DTOs. Domain repositories perform literal, case-insensitive substring matching over each content type's searchable fields, while existing services retain responsibility for tags, signed image URLs, and member-specific saved/liked state.

**Tech Stack:** Java 21, Spring Boot 4.1, Spring Data JPA, Spring Security, JUnit 5, Mockito

## Global Constraints

- Endpoint: `GET /api/v1/content-search?keyword=`
- Keyword: trim, 1-100 characters
- Search: trend title/description/tags; lookbook comment/author nickname/tags
- Visibility: exclude deleted or moderated lookbooks
- Result cap: latest 10 trends and latest 10 lookbooks
- Authentication: anonymous allowed; authenticated state fields preserved
- Verification: focused tests followed by `./gradlew clean build`

---

### Task 1: Search repositories and domain mappings

**Files:**
- Modify: `src/main/java/com/fitback/backend/domain/trend/repository/TrendContentRepository.java`
- Modify: `src/main/java/com/fitback/backend/domain/trend/service/TrendService.java`
- Modify: `src/main/java/com/fitback/backend/domain/lookbook/repository/LookbookRepository.java`
- Modify: `src/main/java/com/fitback/backend/domain/lookbook/service/LookbookService.java`
- Test: `src/test/java/com/fitback/backend/domain/trend/service/TrendServiceTest.java`
- Test: `src/test/java/com/fitback/backend/domain/lookbook/service/LookbookServiceTest.java`

**Interfaces:**
- Produces: `TrendService.searchTrends(String keyword, Member member)`
- Produces: `LookbookService.searchLookbooks(String keyword, Member member)`

- [ ] Add repository queries using `LOCATE(:keyword, LOWER(...))` and latest-first ordering.
- [ ] Add service methods that return at most 10 existing card DTOs.
- [ ] Reuse bulk tag and saved/liked queries and signed image URL mapping.
- [ ] Add focused service tests for mapping and member-state behavior.

### Task 2: Public integrated search API

**Files:**
- Create: `src/main/java/com/fitback/backend/domain/contentsearch/dto/ContentSearchResponse.java`
- Create: `src/main/java/com/fitback/backend/domain/contentsearch/service/ContentSearchService.java`
- Create: `src/main/java/com/fitback/backend/domain/contentsearch/controller/ContentSearchController.java`
- Modify: `src/main/java/com/fitback/backend/global/security/SecurityConfig.java`
- Test: `src/test/java/com/fitback/backend/domain/contentsearch/service/ContentSearchServiceTest.java`
- Test: `src/test/java/com/fitback/backend/domain/contentsearch/controller/ContentSearchControllerTest.java`
- Test: `src/test/java/com/fitback/backend/global/security/PublicContentSearchIntegrationTest.java`

**Interfaces:**
- Consumes: `TrendService.searchTrends(String, Member)`
- Consumes: `LookbookService.searchLookbooks(String, Member)`
- Produces: `ContentSearchResponse(List<TrendItem>, List<LookbookItem>)`

- [ ] Validate and lowercase the trimmed keyword in `ContentSearchService`.
- [ ] Return both result groups through the common `ApiResponse` envelope.
- [ ] Permit anonymous GET requests only for the content-search endpoint.
- [ ] Test blank/oversized keywords, composition, and anonymous security access.

### Task 3: Contract and completion

**Files:**
- Modify: `docs/API_SPEC.md`

- [ ] Document request validation, searchable fields, visibility, limits, and response groups.
- [ ] Run focused content-search tests and `git diff --check`.
- [ ] Run `./gradlew clean build`.
- [ ] Commit, push, open PR to `develop`, and complete issue #160 through merge.
