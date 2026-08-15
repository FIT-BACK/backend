# Shopify Retrieval Query Planner Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace raw Korean per-tag Shopify recommendation searches with a deterministic, curated English retrieval query plan capped at five queries.

**Architecture:** Add a recommendation-domain planner that converts canonical `TagInput` values into provider search keywords while keeping category filtering and the provider port contract intact. Allow an empty keyword only for a category-scoped `ProductSearchQuery`, so the existing Shopify adapter can issue a true category-only fallback without duplicating its category anchor. Keep candidate ordering, browser handoff, scoring, persistence, and price behavior unchanged.

**Tech Stack:** Java 21, Spring Boot 4.1.0, Gradle, JUnit 5, AssertJ, Mockito

## Global Constraints

- Base all work on `origin/develop` SHA `8d2c89f8a52330ee2a05d9a73f6dee3d4ebc2cac`.
- Use only curated aliases for canonical tags present in the checked-in taxonomy; no automatic or LLM translation and no fuzzy or semantic expansion.
- Do not put a raw Korean canonical/custom tag into a Shopify query as a fallback.
- Keep STYLE excluded and use COLOR only as a refinement.
- Keep query ordering deterministic, remove duplicate queries, reserve the final slot for category-only fallback, and cap the plan at five queries.
- Do not change the selector, browserReranking 30 cap, Fashion-CLIP, imageSimilarity, tagSimilarity, 70/30 scoring, persistence, price/value ranking, FX, Pareto, image policy, batch hydrate, or API response.
- Do not run production recommendation, S3 upload, analysis generation, main merge, or deployment; production/manual validation remains `NOT_RUN`.

---

### Task 1: Define the query planner contract with tests

**Files:**
- Create: `src/main/java/com/fitback/backend/domain/recommendation/service/RecommendationRetrievalQueryPlanner.java`
- Create: `src/test/java/com/fitback/backend/domain/recommendation/service/RecommendationRetrievalQueryPlannerTest.java`

**Interfaces:**
- Consumes: `ProductCategory` and `List<RecommendationInputSnapshot.TagInput>`.
- Produces: `List<PlannedQuery>` from `plan(ProductCategory, List<TagInput>)`; each `PlannedQuery` exposes `keyword()` and `tagTypeComposition()`.

- [ ] **Step 1: Write failing planner tests**

Cover SILHOUETTE, DETAIL, MATERIAL, COLOR refinement, STYLE exclusion, unknown aliases, category-only fallback, duplicate elimination, deterministic ordering, five-query maximum, empty input, and absence of Hangul/raw canonical values in every emitted keyword.

- [ ] **Step 2: Run the planner test and confirm it fails because the planner does not exist**

Run: `./gradlew test --tests '*RecommendationRetrievalQueryPlannerTest' --no-daemon --no-watch-fs`

- [ ] **Step 3: Implement the minimal planner**

Use immutable type-specific maps, select the first mapped canonical tag of each type in input order, build up to four distinct semantic queries in this order, then append the category-only fallback:

```text
SILHOUETTE
SILHOUETTE+COLOR
DETAIL
DETAIL+COLOR
MATERIAL
MATERIAL+COLOR
CATEGORY
```

Only the first four distinct semantic queries are kept so `CATEGORY` is always the final fifth-or-earlier query. COLOR is never a standalone query; STYLE, custom tags, and unmapped canonical tags contribute no raw term.

- [ ] **Step 4: Run the planner test and confirm it passes**

Run: `./gradlew test --tests '*RecommendationRetrievalQueryPlannerTest' --no-daemon --no-watch-fs`

### Task 2: Integrate the plan into recommendation candidate collection

**Files:**
- Modify: `src/main/java/com/fitback/backend/domain/product/service/model/ProductSearchQuery.java`
- Modify: `src/main/java/com/fitback/backend/domain/product/service/ProductSearchService.java`
- Modify: `src/main/java/com/fitback/backend/domain/recommendation/service/RecommendationService.java`
- Modify: `src/main/java/com/fitback/backend/external/shopping/shopify/ShopifyGlobalCatalogAdapter.java`
- Create: `src/test/java/com/fitback/backend/domain/product/service/model/ProductSearchQueryTest.java`
- Modify: `src/test/java/com/fitback/backend/domain/recommendation/service/RecommendationServiceTest.java`
- Modify: `src/test/java/com/fitback/backend/external/shopping/shopify/ShopifyGlobalCatalogAdapterTest.java`

**Interfaces:**
- Consumes: planner output plus the existing `ProductCatalogPort.search(ProductSearchQuery)` contract.
- Produces: the same page size `20`, category, category filtering, candidate batches, trace counts, selector input, and response contract as before.

- [ ] **Step 1: Write failing integration/regression tests**

Assert planned English keywords and composition ordering, category/pageSize preservation, true category-only Shopify anchor generation, safe fingerprinting without raw query logging, provider partial failure behavior, selector handoff limit, and absence of raw Korean/custom terms.

- [ ] **Step 2: Run focused service and adapter tests to confirm the old per-tag behavior fails the new assertions**

Run: `./gradlew test --tests '*RecommendationServiceTest' --tests '*ShopifyGlobalCatalogAdapterTest' --no-daemon --no-watch-fs`

- [ ] **Step 3: Implement minimal integration**

Inject `RecommendationRetrievalQueryPlanner`, iterate `PlannedQuery` values instead of raw tags/custom tags, pass `tagTypeComposition()` to the existing safe trace input, and include category in the HMAC fingerprint input. Permit `keyword=""` only when `category` is non-null; make Shopify's adapter return only its existing category anchor for that case, while `ProductSearchService` continues rejecting blank public search keywords. Do not edit selector, handoff, scorer, price, or persistence classes.

- [ ] **Step 4: Run focused service, adapter, selector, and browser handoff tests**

Run: `./gradlew test --tests '*RecommendationRetrievalQueryPlannerTest' --tests '*RecommendationServiceTest' --tests '*ShopifyGlobalCatalogAdapterTest' --tests '*MultiTagPriorityImageComparisonCandidateOrderingPolicyTest' --tests '*ImageComparisonCandidateSelectorTest' --tests '*BrowserRerankingHandoffServiceTest' --no-daemon --no-watch-fs`

### Task 3: Document the manual candidate-quality contract

**Files:**
- Create: `docs/SHOPIFY_CANDIDATE_QUALITY_EVALUATION.md`

**Interfaces:**
- Consumes: manually labelled selector order and separately labelled Fashion-CLIP image-only order.
- Produces: definitions for `highCount30`, `highMediumCount30`, `firstHighSelectorOrdinal`, `top10HighCount`, and `top10HighMediumCount` without calling them formal recall.

- [ ] **Step 1: Write the document**

Include the TOP/BOTTOM/OUTER/DRESS baseline, distinguish selector-source order from image-only rank, state that missing HIGH makes `firstHighSelectorOrdinal` null, and state production/manual R1 validation is `NOT_RUN`.

- [ ] **Step 2: Check the document for forbidden claims and raw sensitive data**

Run: `rg -n 'Recall|production validation = (PASS|SUCCESS)|imageUrl|merchantId|token' docs/SHOPIFY_CANDIDATE_QUALITY_EVALUATION.md`

Expected: only the explicit warning that the metrics are not formal Recall; no production success or sensitive raw fields.

### Task 4: Verify and publish the Draft PR

**Files:**
- Verify all files in the final diff; do not add unrelated files.

**Interfaces:**
- Consumes: Issue #381, the verified branch diff, and the repository PR template.
- Produces: one Korean commit, pushed `feature/#381-shopify-retrieval-query-planner`, and a Korean Draft PR targeting `develop`.

- [ ] **Step 1: Run full verification**

Run: `./gradlew clean build --no-daemon --no-watch-fs`

Run: `git diff --check`

- [ ] **Step 2: Review scope**

Run: `git status -sb`

Run: `git diff --stat`

Run: `git diff -- src/main/java src/test/java docs`

- [ ] **Step 3: Commit only intended files**

```bash
git add docs/superpowers/plans/2026-08-16-shopify-retrieval-query-planner.md docs/SHOPIFY_CANDIDATE_QUALITY_EVALUATION.md src/main/java/com/fitback/backend/domain/product/service/ProductSearchService.java src/main/java/com/fitback/backend/domain/product/service/model/ProductSearchQuery.java src/main/java/com/fitback/backend/domain/recommendation/service/RecommendationRetrievalQueryPlanner.java src/main/java/com/fitback/backend/domain/recommendation/service/RecommendationService.java src/main/java/com/fitback/backend/external/shopping/shopify/ShopifyGlobalCatalogAdapter.java src/test/java/com/fitback/backend/domain/product/service/model/ProductSearchQueryTest.java src/test/java/com/fitback/backend/domain/recommendation/service/RecommendationRetrievalQueryPlannerTest.java src/test/java/com/fitback/backend/domain/recommendation/service/RecommendationServiceTest.java src/test/java/com/fitback/backend/external/shopping/shopify/ShopifyGlobalCatalogAdapterTest.java
git commit -m "feat: Shopify 추천 후보 retrieval query 개선"
```

- [ ] **Step 4: Push and open a Draft PR**

Push the branch, then open a Draft PR to `develop` whose Korean body includes the manual baseline, current problem, planner contract, curated alias policy, excluded scope, exact tests, and `production/manual R1 validation = NOT_RUN`.
