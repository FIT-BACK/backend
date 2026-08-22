# Recommendation Accuracy Retrieval, Metadata, and Taxonomy Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use executing-plans task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `origin/main`의 production 기본 동작을 유지하면서 bounded depth, Shopify semantic metadata, structured taxonomy 세 Track을 production/provider 호출 없이 offline experiment-ready 상태로 만든다.

**Architecture:** Track A만 기존 `RecommendationService`에 default-off 최대 2-page 경로를 복구하고 Spring public constructor는 1 page로 고정한다. Track B/C는 각각 Git-ignored `.local/` Node 모듈로 구현해 Shopify raw JSON을 transient input으로만 받고 normalized projection 또는 aggregate diagnostics만 반환한다. 공통 durable schema는 identity/title/URL/raw payload를 거절하며 `SAFE_SNAPSHOT -> PRIVACY_VALIDATION -> DURABLE_WRITE -> CLEANUP` 순서를 강제한다.

**Tech Stack:** Java 21, Spring Boot 4.1.0, Gradle, JUnit 5, Mockito, AssertJ, Node.js built-in test runner

## Global Constraints

- Baseline은 `78bcd4ffd5134a2eca6e8780d5c62ada1600291d`이며 production public constructor/runtime 기본 동작은 page 1 only다.
- source-local ordering, planner, family order, category filter, selector, scorer, Fashion-CLIP, R2, price/value ordering을 변경하지 않는다.
- provider/Shopify/recommendation/deploy/holdout/GitHub write는 모두 0이다.
- `depth_top02_20260821_01..03`과 모든 과거 ledger/report/identity artifact는 복사·수정·재사용하지 않는다.
- durable output에는 provider/merchant/product/candidate identity, title, URL, query, raw payload, image, embedding, price를 넣지 않는다.
- 실제 GOOD label join이 없으면 Track B/C policy는 `NOT_JUDGED`로 유지한다.

---

### Task 1: Restore bounded retrieval with tracked failure-parity tests

**Files:**
- Modify: `src/main/java/com/fitback/backend/domain/recommendation/service/RecommendationService.java`
- Modify: `src/test/java/com/fitback/backend/domain/recommendation/service/RecommendationServiceTest.java`

**Interfaces:**
- Consumes: `ProductSearchResult.hasNext()`, `ProductSearchResult.nextCursor()`
- Produces: `RecommendationService(..., int maxPagesPerQuery)` package-private experiment constructor; public constructor remains one-page

- [ ] **Step 1: Add red tests for the bounded contract and failure parity**

Use the real selector and capture `ProductSearchQuery` calls. The essential assertions are:

```java
assertThat(capturedQueries).extracting(ProductSearchQuery::cursor)
        .containsExactly(null, "page-2");
verify(productCatalogPort, times(2)).search(any());
assertThat(selectedIds).containsSubsequence(page1LastId, page2FirstId);
```

Add separate cases for page1 failure continuing to the next family, page2 failure preserving page1, page2 returning another cursor without page3, public-constructor one-page compatibility, max `2Q` calls with retry 0, and all-page1 failures attempting every family once before the terminal error.

- [ ] **Step 2: Run focused tests and confirm the new tests fail**

Run:

```bash
env GRADLE_USER_HOME=/tmp/fitback-accuracy-gradle ./gradlew test \
  --tests com.fitback.backend.domain.recommendation.service.RecommendationServiceTest \
  --no-daemon --no-watch-fs --offline --rerun-tasks
```

Expected before implementation: compile or assertion failure because the max-pages constructor and page traversal do not exist.

- [ ] **Step 3: Implement the minimum default-off traversal**

The public constructor delegates with `CURRENT_MAX_PAGES_PER_QUERY`; the package-private constructor accepts only 1 or 2. The family loop follows this shape:

```java
List<ExternalProductCandidate> categoryFilteredBatch = new ArrayList<>();
boolean familySearchSucceeded = false;
String cursor = null;
for (int page = 1; page <= maxPagesPerQuery; page++) {
    String pageCursor = cursor;
    ProductSearchResult result = productCatalogPort.search(
            new ProductSearchQuery(plannedQuery.keyword(), category, pageCursor, 20));
    familySearchSucceeded = true;
    categoryFilteredBatch.addAll(result.items().stream()
            .filter(candidate -> candidateMapper.category(candidate) == category)
            .toList());
    if (!result.hasNext()) break;
    cursor = result.nextCursor();
}
if (familySearchSucceeded) candidateBatches.add(List.copyOf(categoryFilteredBatch));
```

Keep the existing `ProductProviderException` catch outside the page loop so page1 failure skips the family and page2 failure preserves the accumulated page1 batch. Do not add retry or sorting.

- [ ] **Step 4: Run focused and recommendation-domain tests**

Run the focused command above, then:

```bash
env GRADLE_USER_HOME=/tmp/fitback-accuracy-gradle ./gradlew test \
  --tests 'com.fitback.backend.domain.recommendation.**' \
  --no-daemon --no-watch-fs --offline --rerun-tasks
```

Expected: all selected tests pass with provider/network calls 0.

---

### Task 2: Add an exact aggregate depth evidence gate

**Files:**
- Create: `.local/track-a-depth/package.json`
- Create: `.local/track-a-depth/src/depth-report.mjs`
- Create: `.local/track-a-depth/src/finalizer.mjs`
- Create: `.local/track-a-depth/test/depth-report.test.mjs`
- Create: `.local/track-a-depth/test/finalizer.test.mjs`

**Interfaces:**
- Consumes: aggregate-only `fitback-dev-query-aggregate-depth-v1`
- Produces: normalized CURRENT/DEPTH metrics and `SUPPORTED | NOT_SUPPORTED | NOT_JUDGED`

- [ ] **Step 1: Write red schema and finalization tests**

The exact root keys are:

```js
const ROOT_KEYS = [
  "schemaVersion", "queryKey", "category", "coverage", "privacy",
  "deterministic", "queryBudget", "current", "depth", "depthDiagnostics",
];
```

Each arm must contain `rawLabelCounts`, `selectedLabelCounts`, `top10LabelCounts`, `selectedGoodRateAt30`, `goodRetentionAt30`, `goodCountAt10`, `highCountAt30`, and `queryBudget`. Diagnostics must contain page2 candidate/GOOD/selected counts, duplicates, calls, latency, partial/unavailable family counts, retry count, and latency-budget state. Tests must accept aggregate names such as `invalidProviderReferenceDropCount` but reject any extra provider-reference value field, URL, title, raw query, or identity.

Finalization test order:

```js
assert.deepEqual(order, [
  "SAFE_SNAPSHOT", "PRIVACY_VALIDATION", "DURABLE_WRITE", "CLEANUP",
]);
```

If durable write fails, cleanup must not destroy transient state; the caller receives the primary write error.

- [ ] **Step 2: Implement exact-key validation and the frozen gate**

Use exact allowlists, not forbidden substrings. The policy gate returns `NOT_JUDGED` when coverage is not `JUDGED`, any family is partial/unavailable, privacy/determinism/retry/call/latency gates fail, or required metrics are null. It returns `SUPPORTED` only when retention improves, selected rate and overall HIGH do not worsen, at least two categories are non-worsening at macro evaluation, and page2 GOOD selected contribution is positive. A clear measured regression returns `NOT_SUPPORTED`.

- [ ] **Step 3: Implement recoverable finalization**

```js
export async function publish({ snapshot, validate, write, cleanup }) {
  const frozen = deepFreeze(structuredClone(snapshot));
  validate(frozen);
  await write(frozen);       // no cleanup on failed durable write
  await cleanup();
  return frozen;
}
```

- [ ] **Step 4: Run Node tests**

```bash
npm --prefix .local/track-a-depth test
```

Expected: all schema, privacy, gate, determinism, and fault-injection tests pass.

---

### Task 3: Build the local-only Shopify semantic sidecar

**Files:**
- Create: `.local/track-b-metadata/package.json`
- Create: `.local/track-b-metadata/src/semantic-sidecar.mjs`
- Create: `.local/track-b-metadata/src/usefulness.mjs`
- Create: `.local/track-b-metadata/test/semantic-sidecar.test.mjs`
- Create: `.local/track-b-metadata/test/usefulness.test.mjs`
- Create: `.local/track-b-metadata/README.md`

**Interfaces:**
- Consumes: one transient Shopify product JSON object plus a session-local `safeOrdinal`
- Produces: transient normalized token sets and durable aggregate counts only

- [ ] **Step 1: Write red extractor tests**

Fixture coverage must include missing/null/malformed fields, duplicated attributes, conflicting taxonomy, absent inferred fields, and extra unknown fields. The projection has exact keys:

```js
{
  semanticTokens: {
    material: [], style: [], occasion: [], silhouette: [],
    detail: [], color: [], categoryTaxonomy: [],
  },
  diagnostics: {
    missingFieldCount: 0, malformedFieldCount: 0,
    duplicateTokenCount: 0, conflictingTaxonomyCount: 0,
  },
}
```

Inputs may read `description`, `options`, `metadata.attributes`, variant options/tags, categories, and media alt text. The output must never contain description text, title, IDs, URLs, seller, price, or raw attribute values outside the normalized allowlist.

- [ ] **Step 2: Implement bounded normalization**

Use fixed per-dimension allowlists and lowercase Unicode token normalization. Optional fields contribute nothing when absent. Unknown values are ignored and counted, duplicates are deduplicated, and conflicting category tokens increment the conflict counter without selecting a winner.

- [ ] **Step 3: Implement aggregate-only usefulness evaluation**

The evaluator accepts only exact session-local safeOrdinal joins. With no joined labels it returns:

```js
{ policy: "NOT_JUDGED", exactJoinCount: 0, reason: "LABEL_EVIDENCE_ABSENT" }
```

With labels it reports only per-label token-presence counts and comparison counts; it never emits row identity or raw tokens from a product.

- [ ] **Step 4: Run tests**

```bash
npm --prefix .local/track-b-metadata test
```

Expected: all fail-safe, privacy, determinism, and absent-evidence tests pass.

---

### Task 4: Build the local-only taxonomy mapper and false-negative diagnostics

**Files:**
- Create: `.local/track-c-category/package.json`
- Create: `.local/track-c-category/src/current-mapper.mjs`
- Create: `.local/track-c-category/src/taxonomy-mapper.mjs`
- Create: `.local/track-c-category/src/diagnostics.mjs`
- Create: `.local/track-c-category/test/mapper.test.mjs`
- Create: `.local/track-c-category/test/diagnostics.test.mjs`
- Create: `.local/track-c-category/README.md`

**Interfaces:**
- Consumes: transient taxonomy/value pairs and optional title tokens
- Produces: paired CURRENT/TAXONOMY enum outcomes and `CategoryDiagnosticV1` aggregates

- [ ] **Step 1: Freeze CURRENT precedence tests**

Encode the repository behavior `DRESS > OUTER > BOTTOM > TOP` and verify at least:

```js
[
  ["denim shirt", "BOTTOM"], ["shirt jacket", "OUTER"],
  ["jacket dress", "DRESS"], ["dress pants", "DRESS"],
  ["skirt top", "BOTTOM"],
]
```

- [ ] **Step 2: Implement conservative structured taxonomy mapping**

Merchant hierarchy segments are evaluated leaf-to-root. Multiple recognized categories produce `CONFLICT`; missing/malformed/unrecognized structured values produce `UNMAPPED`. Numeric taxonomy IDs such as `212` remain `UNMAPPED` unless an official versioned mapping is explicitly supplied; do not infer their meaning.

- [ ] **Step 3: Implement exact aggregate diagnostics**

```js
{
  mapperVariant, family, queryIndex, preFilterCount, postFilterCount,
  mappedSameCategory, mappedDifferentCategory, mappedOther,
  mappedEnumCounts, ambiguousTitleFallbackCount,
  structuredSignalAbsentCount, structuredSignalConflictCount,
  labelJoinState, goodPreFilterCount, goodPostFilterCount, goodRetention,
}
```

Enforce `preFilterCount == sum(mappedEnumCounts)`, `postFilterCount == mappedSameCategory`, and null GOOD fields when `labelJoinState=NOT_JOINED`. Optional diagnostic samples may contain only `{family, queryIndex, safeOrdinal, currentMappedEnum, taxonomyMappedEnum, outcomeEnum}`.

- [ ] **Step 4: Run tests**

```bash
npm --prefix .local/track-c-category test
```

Expected: precedence, conflict, missing-field, aggregate, privacy, and determinism tests pass.

---

### Task 5: Integrated verification and final evidence report

**Files:**
- Create: `outputs/FITBACK_RECOMMENDATION_ACCURACY_OFFLINE_READINESS_2026-08-22.md` outside the repository worktree
- Inspect only: all tracked and ignored experiment files

**Interfaces:**
- Consumes: fresh test outputs and diffs
- Produces: Korean completion report in the exact order requested by the user

- [ ] **Step 1: Run all offline suites**

Run Track A Gradle tests and all three `npm --prefix ... test` commands. If the Gradle environment blocks execution, record the exact environmental failure and do not claim PASS.

- [ ] **Step 2: Audit forbidden tracked components and diff**

```bash
git diff --check
git status --short
git diff --name-only
shasum -a 256 \
  src/main/java/com/fitback/backend/domain/recommendation/service/RecommendationRetrievalQueryPlanner.java \
  src/main/java/com/fitback/backend/domain/recommendation/service/ImageComparisonCandidateSelector.java \
  src/main/java/com/fitback/backend/domain/recommendation/service/MultiTagPriorityImageComparisonCandidateOrderingPolicy.java \
  src/main/java/com/fitback/backend/domain/recommendation/service/RecommendationScorer.java
```

Expected: only the plan plus Track A service/test are tracked changes; forbidden component hashes remain baseline values.

- [ ] **Step 3: Produce the Korean report**

Report baseline, source-local closure, A/B/C results, ranked next policy, accuracy/cost/privacy/cleanup/request counts/diff, final flags, and the next minimum step. Since no live calls are authorized, `DEPTH_POLICY=NOT_JUDGED`, all production/holdout counts are 0, and `PRODUCTION_APPLICATION_ALLOWED=NO`.
