# Browser Fashion-CLIP Display Contract Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Extend the existing recommendation POST `browserReranking` handoff with snapshot display metadata and make the browser select relevance top-K before price-ordering the selected results.

**Architecture:** The backend maps the already-selected `ExternalProductCandidate` objects once, reusing `ProductPriceResponse` and the existing member-bound opaque `CandidateTokenService` token. The browser validates and joins that snapshot metadata locally, computes the existing normalized Fashion-CLIP cosine and positive 70/30 score for the full handoff pool, selects `min(10, count)` by relevance, then sorts only that shortlist by comparable same-currency price while preserving relevance order for incomparable prices.

**Tech Stack:** Java 21, Spring Boot/Gradle, JUnit 5/Mockito, browser-native ES modules, Node test runner, Vite, ONNX Runtime Web.

## Global Constraints

- One recommendation POST response contains `browserReranking` metadata; no resolve API or follow-up metadata call.
- `candidateId` remains the existing member-bound opaque token; Shopify GID, merchant identity, provider internal ID, and persisted productId stay out of the browser contract.
- `finalScore = imageSimilarity * 0.70 + tagSimilarity * 0.30`; use the current normalized Fashion-CLIP cosine, no threshold, and no browser-score persistence.
- Backend handoff source is the existing `ExternalProductCandidate`; do not add Shopify lookup/API calls, migrations, Modal changes, AI-tag/OpenAI changes, or production deployment.
- Backend handoff maximum is 30. Browser relevance shortlist size is `min(10, rerankedCandidateCount)`.
- Browser fallback text is `browser-reranking unavailable` and the backend recommendation result remains visible for non-2xx, missing handoff, model failure, and image fetch/decode failure.
- Handoff metadata is a response-time snapshot and must not be described as a live Shopify price guarantee.

---

### Task 1: Add backend snapshot metadata to the handoff contract

**Files:**
- Create: `src/main/java/com/fitback/backend/domain/recommendation/dto/BrowserRerankingCandidate.java`
- Modify: `src/main/java/com/fitback/backend/domain/recommendation/service/BrowserRerankingHandoffService.java`
- Modify: `src/main/java/com/fitback/backend/domain/recommendation/dto/RecommendationCreateResponse.java`
- Modify: `src/main/java/com/fitback/backend/domain/recommendation/service/RecommendationService.java`
- Add/modify: `src/main/java/com/fitback/backend/domain/recommendation/service/RecommendationTagMatcher.java` and `RecommendationScorer.java`
- Modify: `docs/API_SPEC.md` and `README.md`

**Interfaces:**
- `BrowserRerankingCandidate` produces `candidateId`, `imageUrl`, `tagSimilarity`, `name`, nullable `sellerName`, nullable `ProductPriceResponse price`, and nullable `purchaseUrl`.
- `BrowserRerankingHandoffService.create(long, ProductCategory, List<TagInput>, List<ExternalProductCandidate>)` maps at most 30 candidates from the supplied search snapshot and never calls `ProductCatalogPort`.

- [ ] Preserve the existing token, image URL, and tag-similarity validations while adding the seven display fields. Map `sellerName` and `purchaseUrl` from `candidate.offer()` when present, and map price through `ProductCandidateMapper.price(offer)` so all-null prices produce `price: null`.
- [ ] Inject `ProductCandidateMapper` into the handoff service and call it from `RecommendationService` on the existing selected candidate list before materialization; keep persistence scoring and selection unchanged.
- [ ] Keep the tag matcher extraction semantically identical to the current scorer so the existing backend tag score and handoff `tagSimilarity` remain unchanged.
- [ ] Document the exact JSON shape and explicitly state that the metadata is a handoff-time snapshot, not live Shopify data; state that no browser resolve/lookup call exists.

### Task 2: Add backend mapping and regression coverage

**Files:**
- Modify: `src/test/java/com/fitback/backend/domain/recommendation/service/BrowserRerankingHandoffServiceTest.java`
- Modify: `src/test/java/com/fitback/backend/domain/recommendation/service/RecommendationServiceTest.java`
- Modify: `src/test/java/com/fitback/backend/domain/recommendation/controller/RecommendationControllerIntegrationTest.java`

- [ ] Test one candidate with complete metadata, a candidate with null seller/purchase URL/all prices, opaque token pass-through, and a 31-candidate input limited to 30.
- [ ] Assert the controller JSON contains metadata while existing persisted `similarityScore`/`finalScore` assertions remain unchanged.
- [ ] Run focused backend tests before continuing to browser changes.

### Task 3: Implement browser final relevance selection and price display ordering

**Files:**
- Modify: `scripts/poc/fashion-clip-browser/src/math.js`
- Modify: `scripts/poc/fashion-clip-browser/src/main.js`
- Modify: `scripts/poc/fashion-clip-browser/index.html`
- Modify: `scripts/poc/fashion-clip-browser/README.md`

**Interfaces:**
- `validateBrowserRerankingHandoff` returns validated snapshot metadata without interpreting `candidateId`.
- `sortRerankingResults(results)` remains finalScore DESC then original handoff index ASC.
- Add `sortDisplayResults(results)` that orders the shortlist by price ASC only when every candidate has a finite comparable amount and the same currency; otherwise preserve the entire shortlist's stable relevance order. Equal comparable prices use finalScore DESC then original index ASC.

- [ ] Validate nullable seller, price, and purchase URL; reject malformed non-null display values without constructing defaults or URLs.
- [ ] Run image acquisition/inference for the full validated handoff pool, compute normalized cosine and the unchanged positive 70/30 final score, rank by relevance, select the first `min(10, count)`, and display-sort only that shortlist.
- [ ] Render name, seller, image URL, nullable price, nullable purchase link, image similarity, tag similarity, and final score using text-safe DOM operations; do not render the opaque token as a required UI field.
- [ ] Keep one backend POST, no metadata API, no browser score submit, no persistence, and the existing unavailable fallback paths.
- [ ] Update browser tests for relevance top-K, ascending same-currency price, mixed-currency/missing-price fallback, equal-price relevance ties, final-score regression, opaque IDs, and all fallback classes.

### Task 4: Verify, review, and publish the draft PR

**Files:**
- No additional source files unless verification finds a scoped defect.

- [ ] Run backend clean build, browser `npm test`, `npm run build`, `npm audit`, and `git diff --check` with fresh output.
- [ ] Attempt one authorized local fixture/Shopify E2E using the existing environment only; record candidate count, metadata completeness, relevance top-10, price display order, and reranking latency, or report `NOT_RUN`/`USER_INPUT_REQUIRED` without fabrication.
- [ ] Review the final diff for forbidden APIs, identifiers, score persistence, and unrelated changes; request a code-review subagent and resolve important findings.
- [ ] Commit the minimal scoped diff with the repository commit convention, push the issue branch, and open a Draft PR targeting `develop` with `Closes #332`, verification evidence, snapshot wording, and explicit exclusions.
