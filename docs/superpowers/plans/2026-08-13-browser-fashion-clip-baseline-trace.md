# Browser Fashion-CLIP Baseline Trace Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Browser Fashion-CLIP 추천 E2E의 현재 latency를 최적화나 정책 변경 없이, backend와 browser에서 상관 가능한 안전한 structured JSON trace로 분리한다.

**Architecture:** `X-Fitback-Benchmark-Trace: baseline-v1`가 붙은 추천 POST만 서버 filter가 ThreadLocal trace scope로 감싼다. 서비스는 해당 scope에 stage·catalog call·candidate count만 기록하고 filter는 안전한 JSON 한 줄과 correlation ID header를 남긴다. Browser PoC는 같은 header를 보내고, 기존 run 결과의 숫자만 모아 token, raw URL query, candidateId, image bytes, embedding을 배제한 console JSON을 남긴다.

**Tech Stack:** Java 21, Spring Boot 4.1, MockMvc/JUnit 5, browser ES modules, Node test, Vite.

## Global Constraints

- 기준 commit은 `705c01c752ccd4eded81cd9e07b6d1f0f4462dc0`의 `develop`이다.
- score, candidate limit, candidate ordering, direct browser image fetch, fallback, persistence 정책을 변경하지 않는다.
- static model, preload, concurrency, K, alias/tag matcher, FP16/INT8, cache, Worker를 추가하지 않는다.
- 후속 범위의 Shopify `lookup_catalog` batch hydrate는 stable identity를 최대 50개씩 요청하고, provider response 순서 대신 요청 identity mapping으로만 결합한다. 이 경로는 response-time display snapshot만 반환하며 score·rank·browserReranking·저장 정책을 변경하지 않는다.
- trace는 request wall-clock과 stage/call cumulative time을 별도 key로 기록한다.
- trace에는 token, 원문 candidateId, image bytes, embedding vector, Shopify raw response body, access token, 전체 model URL query/hash를 기록하지 않는다.
- Browser UI는 변경하지 않고 `console.info` structured JSON과 기존 summary만 사용한다.

---

### Task 1: 서버 trace scope와 CORS correlation header

**Files:**

- Create: `src/main/java/com/fitback/backend/global/observability/RecommendationPerformanceTrace.java`
- Create: `src/main/java/com/fitback/backend/global/observability/RecommendationPerformanceTraceFilter.java`
- Test: `src/test/java/com/fitback/backend/global/observability/RecommendationPerformanceTraceTest.java`
- Modify: `src/main/java/com/fitback/backend/global/security/SecurityConfig.java:120-140`
- Test: `src/test/java/com/fitback/backend/global/security/SecurityCorsIntegrationTest.java`

**Interfaces:**

- Consumes: `X-Fitback-Benchmark-Trace: baseline-v1` on `POST /api/v1/analyses/{reportId}/recommendations`.
- Produces: `X-Fitback-Benchmark-Trace-Id` and a single `recommendation_performance_trace={...}` backend log only for enabled requests.
- Produces: `RecommendationPerformanceTrace.measureStage`, `measureSearchCatalog`, `measureLookupCatalog`, `recordCandidateCounts`, and `recordBrowserRerankingCandidateCount` for the existing synchronous call path.

- [x] **Step 1: Write the failing trace-scope tests**

```java
try (var scope = RecommendationPerformanceTrace.beginIfRequested("baseline-v1")) {
    RecommendationPerformanceTrace.measureSearchCatalog("SILHOUETTE", () -> "ok");
    RecommendationPerformanceTrace.measureLookupCatalog(1, () -> "ok");
    RecommendationPerformanceTrace.recordCandidateCounts(20, 14, 10);
    RecommendationPerformanceTrace.recordBrowserRerankingCandidateCount(10);

    var snapshot = scope.snapshot();
    assertThat(snapshot.requestWallClockMs()).isGreaterThanOrEqualTo(0);
    assertThat(snapshot.searchCatalogCalls()).hasSize(1);
    assertThat(snapshot.lookupCatalogCalls()).extracting(LookupCall::inputSize)
            .containsExactly(1);
    assertThat(snapshot.browserRerankingCandidateCount()).isEqualTo(10);
}
```

- [x] **Step 2: Run the focused test to verify it fails**

Run: `GRADLE_USER_HOME=/tmp/fitback-baseline-trace-gradle ./gradlew test --tests '*RecommendationPerformanceTraceTest' --no-daemon --no-watch-fs`

Expected: compilation failure because `RecommendationPerformanceTrace` does not exist.

- [x] **Step 3: Implement the null-safe scoped trace and request filter**

```java
public static Scope beginIfRequested(String value) {
    if (!REQUEST_VALUE.equals(value) || ACTIVE.get() != null) {
        return Scope.inactive();
    }
    ActiveTrace trace = new ActiveTrace(UUID.randomUUID().toString(), System.nanoTime());
    ACTIVE.set(trace);
    return new Scope(trace);
}

public static <T> T measureSearchCatalog(String tagKind, Supplier<T> action) {
    return measureCatalogCall(CatalogCallType.SEARCH, safeTagKind(tagKind), 1, action);
}

public static <T> T measureLookupCatalog(int inputSize, Supplier<T> action) {
    return measureCatalogCall(CatalogCallType.LOOKUP, null, inputSize, action);
}
```

`RecommendationPerformanceTraceFilter`은 정확한 POST path와 opt-in header만 감싸고, `finally`에서 trace를 finish/log한다. 모든 serializable field는 count, safe enum/string, duration, UUID만 허용한다. stage timing은 interval union으로 `wallClockMs`와 합계 `cumulativeMs`를 모두 계산한다.

`SecurityConfig`에는 request header를 `setAllowedHeaders`에, correlation response header를 `setExposedHeaders`에 추가한다.

- [x] **Step 4: Run focused trace and CORS tests**

Run: `GRADLE_USER_HOME=/tmp/fitback-baseline-trace-gradle ./gradlew test --tests '*RecommendationPerformanceTraceTest' --tests '*SecurityCorsIntegrationTest' --no-daemon --no-watch-fs`

Expected: all selected tests pass; configured preflight accepts the trace request header and exposes only the safe correlation header.

### Task 2: recommendation service와 lookup 경계 계측

**Files:**

- Modify: `src/main/java/com/fitback/backend/domain/recommendation/service/RecommendationService.java:80-223`
- Modify: `src/main/java/com/fitback/backend/domain/product/service/ProductDetailService.java:49-96`
- Test: `src/test/java/com/fitback/backend/domain/recommendation/service/RecommendationServiceTest.java`
- Test: `src/test/java/com/fitback/backend/domain/product/service/ProductDetailServiceTest.java`
- Test: `src/test/java/com/fitback/backend/domain/recommendation/controller/RecommendationControllerIntegrationTest.java`

**Interfaces:**

- Consumes: active global trace scope; no new API body field.
- Produces: safe `searchCatalogCalls[{tagKind, wallClockMs}]`, `lookupCatalogCalls[{inputSize, wallClockMs}]`, candidate input/category-filtered/selected counts, stage timing, and final handoff count.

- [x] **Step 1: Write failing behavior-preservation tests**

```java
try (var scope = RecommendationPerformanceTrace.beginIfRequested("baseline-v1")) {
    RecommendationCreateResponse response = recommendationService.generate(1L, 501L);
    var snapshot = scope.snapshot();

    assertThat(snapshot.searchCatalogCalls()).hasSize(1);
    assertThat(snapshot.stage("candidateMergeDedup").cumulativeMs()).isGreaterThanOrEqualTo(0);
    assertThat(snapshot.browserRerankingCandidateCount())
            .isEqualTo(response.browserReranking().candidates().size());
}
```

For `ProductDetailService`, wrap an existing successful lookup in the same scope and assert exactly one lookup call with `inputSize == 1`. For MockMvc, add the opt-in header and assert `X-Fitback-Benchmark-Trace-Id` exists while the existing JSON scoring/ordering assertions remain unchanged.

- [x] **Step 2: Run the focused tests to verify they fail**

Run: `GRADLE_USER_HOME=/tmp/fitback-baseline-trace-gradle ./gradlew test --tests '*RecommendationServiceTest' --tests '*ProductDetailServiceTest' --tests '*RecommendationControllerIntegrationTest' --no-daemon --no-watch-fs`

Expected: trace assertions fail before service instrumentation is added.

- [x] **Step 3: Add only wrappers around existing operations**

```java
ProductSearchResult searchResult = RecommendationPerformanceTrace.measureSearchCatalog(
        searchTag.traceKind(),
        () -> productCatalogPort.search(new ProductSearchQuery(
                searchTag.name(), category, null, SEARCH_PAGE_SIZE
        ))
);
List<ExternalProductCandidate> filtered = RecommendationPerformanceTrace.measureStage(
        "categoryFiltering",
        () -> searchResult.items().stream()
                .filter(candidate -> candidateMapper.category(candidate) == category)
                .toList()
);
```

Keep `searchTag.name()` internal only; trace tag kinds must be canonical tag type names or `CUSTOM_n`, never a raw custom tag. Measure the existing selector as `candidateMergeDedup`, scorer as `scoring`, materialization/set write as separate persistence stages, `findByReportId` as `responseHydrate`, and record `browserReranking.candidates().size()` after the existing handoff creation. Wrap the existing `productCatalogPort.lookup(providerRef)` in `measureLookupCatalog(1, ...)` without changing fallback behavior.

- [x] **Step 4: Run focused tests and inspect the diff**

Run: `GRADLE_USER_HOME=/tmp/fitback-baseline-trace-gradle ./gradlew test --tests '*RecommendationServiceTest' --tests '*ProductDetailServiceTest' --tests '*RecommendationControllerIntegrationTest' --no-daemon --no-watch-fs && git diff --check`

Expected: existing ordering/score/fallback tests stay green; trace test observes counts without any response body change.

### Task 3: Browser safe baseline trace

**Files:**

- Create: `scripts/poc/fashion-clip-browser/src/baseline-trace.js`
- Modify: `scripts/poc/fashion-clip-browser/src/backend.js:1-31`
- Modify: `scripts/poc/fashion-clip-browser/src/main.js:1-1064`
- Test: `scripts/poc/fashion-clip-browser/test/math.test.mjs`

**Interfaces:**

- Consumes: backend correlation header and existing handoff/run metrics.
- Produces: `console.info('[fashion-clip-baseline-trace]', JSON.stringify(report))` containing safe counts/durations only.
- Keeps: all existing scoring, selection, candidate acquisition, fallback, and display functions unchanged in behavior.

- [x] **Step 1: Write failing browser trace tests**

```js
const trace = createBrowserBaselineTrace({
  model: { source: 'pinned remote default', url: 'https://example.com/model.onnx?secret=nope' },
});
trace.recordBackendRequest({ status: 200, wallClockMs: 42, traceId: 'trace-1' });
trace.recordCandidateAcquisition({ candidateCount: 2, fetchSuccessCount: 2, fetchFailureCount: 0,
  fetchWallClockMs: 8, fetchCumulativeMs: 12, decodeWallClockMs: 3, decodeCumulativeMs: 5,
  preprocessWallClockMs: 2, preprocessCumulativeMs: 4 });
const report = trace.complete({ outcome: 'SUCCESS', totalE2EWallClockMs: 50 });
assert.equal(report.model.origin, 'https://example.com');
assert.equal(JSON.stringify(report).includes('secret=nope'), false);
assert.equal(JSON.stringify(report).includes('candidateId'), false);
```

Extend `fetchRecommendation` assertions to require the opt-in header and return the correlation header safely.

- [x] **Step 2: Run browser tests to verify they fail**

Run: `npm test`

Expected: module import failure because `baseline-trace.js` does not exist.

- [x] **Step 3: Implement report assembly and wire existing measured values**

```js
const request = await fetchRecommendation({
  baseUrl,
  reportId,
  accessToken,
  benchmarkTrace: true,
});
trace.recordBackendRequest({
  status: request.status,
  wallClockMs: request.latencyMs,
  traceId: request.benchmarkTraceId,
});
```

Add `imageToTensorDataWithMetrics` so query decode and preprocess are separately timed; preserve the existing `imageToTensorData` return type for all existing callers. Feed existing candidate wall/cumulative metrics, model transfer/session metrics, request provider/fallback state, inference, cosine/final score, sorting, render, and total reranking/E2E values into the trace. Record model origin/path without query/hash; record selected safe response headers as content type, content-length, cache-control, and redirect flag only. Never emit candidate result arrays, raw errors, model byte content, URLs with query/hash, or vectors.

- [x] **Step 4: Run browser verification**

Run: `npm test && npm run build && npm audit --audit-level=high`

Expected: all Node tests pass, Vite build exits 0, and audit reports no high-severity dependency finding.

### Task 4: local E2E, final verification, and publication

**Files:**

- Modify: `docs/superpowers/plans/2026-08-13-browser-fashion-clip-baseline-trace.md` only to mark execution state if needed.

- [x] **Step 1: Run one authorized local E2E attempt**

Run the existing local Browser PoC against the local backend using a local fixture/authorized input. Capture only the two structured trace lines and aggregate duration/count fields. If auth, local input, model, or image CORS prerequisites are absent, record exact `NOT_RUN` or the single observed blocker; do not replay historical values or call production Shopify.

Result: baseline trace는 batch 적용 전 실측으로 보존한다. `lookup_catalog`은 input 1 기준 10회, cumulative `3094 ms`, response/hydrate `3172 ms`, backend total `4504 ms`, browser E2E `8828.2 ms`였다. 이 값은 batch 적용 후 결과가 아니다. batch hydrate E2E latency 감소폭은 Chrome file upload 문제로 `NOT_VERIFIED`이며, 코드와 regression test만으로 검증했다. Shopify 또는 production 요청으로 이를 재측정하지 않았다.

- [x] **Step 2: Run required full checks**

Run: `GRADLE_USER_HOME=/tmp/fitback-baseline-trace-gradle ./gradlew clean build --no-daemon --no-watch-fs`

Run: `npm test && npm run build && npm audit --audit-level=high`

Run: `git diff --check && git status --short`

Expected: each command exits 0; the only tracked changes are trace implementation, its tests, CORS header contract, and this plan.

- [ ] **Step 3: Review and publish**

Inspect `git diff origin/develop...HEAD` after tests. Commit only the listed files with `feat: Browser Fashion-CLIP baseline trace 계측 추가`, push `chore/#353-browser-fashion-clip-baseline-trace`, and create a Korean Draft PR to `develop` with `Related to #353`. Report test evidence and any `NOT_RUN` E2E blocker exactly.

### Task 5: Shopify `lookup_catalog` batch hydrate 후속 범위

**Files:**

- Create: `src/main/java/com/fitback/backend/domain/product/service/ProductDetailBatchResult.java`
- Create: `src/main/java/com/fitback/backend/domain/product/service/port/BatchProductCatalogPort.java`
- Create: `src/main/java/com/fitback/backend/external/shopping/shopify/ShopifyCatalogLookup.java`
- Modify: `src/main/java/com/fitback/backend/domain/product/service/ProductDetailService.java`
- Modify: `src/main/java/com/fitback/backend/domain/recommendation/service/RecommendationQueryService.java`
- Modify: `src/main/java/com/fitback/backend/external/shopping/shopify/ShopifyGlobalCatalogAdapter.java`
- Modify: `src/main/java/com/fitback/backend/external/shopping/shopify/ShopifyGlobalCatalogClient.java`
- Modify: `src/main/java/com/fitback/backend/external/shopping/shopify/ShopifyGlobalCatalogHttpClient.java`
- Test: `src/test/java/com/fitback/backend/domain/product/service/ProductDetailServiceTest.java`
- Test: `src/test/java/com/fitback/backend/domain/recommendation/service/RecommendationQueryServiceTest.java`
- Test: `src/test/java/com/fitback/backend/external/shopping/shopify/ShopifyGlobalCatalogAdapterTest.java`
- Test: `src/test/java/com/fitback/backend/external/shopping/shopify/ShopifyGlobalCatalogHttpClientTest.java`

**Interfaces:**

- Consumes: identity-only Shopify products with stable provider/product/variant/merchant identity.
- Produces: at most 50 distinct `lookup_catalog` IDs per provider request and a `ProviderProductRef`-keyed result; never uses catalog response order as identity.
- Keeps: absent or partial catalog results as `PRODUCT_PROVIDER_UNAVAILABLE`, provider errors as existing mapped per-item errors, live seller/price/purchaseUrl/imageUrl as response-time snapshots, and existing recommendation category/rank/score/browserReranking/persistence behavior.

- [x] **Step 1: Add contract tests for batch boundaries and mapping**

Verify one request for ten identity-only products, `50 + 1` chunking, reordered provider results, missing results, duplicate identities, provider failure mapping, and the original category/rank response order.

- [x] **Step 2: Add the optional batch capability without changing single lookup behavior**

`ProductDetailService` detects `BatchProductCatalogPort`, deduplicates stable identity references, chunks by the provider maximum, fans each mapped candidate back to its product IDs, and leaves non-batch providers on the existing single-lookup path. `ShopifyGlobalCatalogHttpClient` sends `lookup_catalog.ids` once per chunk and maps the returned products through `ShopifyCatalogLookup` input IDs.

- [ ] **Step 3: Run focused and full regression checks**

Run: `GRADLE_USER_HOME=/tmp/fitback-pr354-gradle ./gradlew test --tests '*ProductDetailServiceTest' --tests '*RecommendationQueryServiceTest' --tests '*ShopifyGlobalCatalogAdapterTest' --tests '*ShopifyGlobalCatalogHttpClientTest' --no-daemon --no-watch-fs`

Run: `GRADLE_USER_HOME=/tmp/fitback-pr354-gradle ./gradlew clean build --no-daemon --no-watch-fs`

Expected: batch contract tests and the full backend build pass. The actual browser E2E batch latency remains `NOT_VERIFIED`; do not label the pre-batch baseline as a batch improvement.
