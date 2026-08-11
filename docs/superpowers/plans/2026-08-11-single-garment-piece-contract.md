# Single Garment Piece Contract Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Restrict crop-scoped AI output to exactly one `GarmentPiece` and preserve that piece with resolved canonical tags at the analysis service boundary.

**Architecture:** Keep the four required nullable structured-output keys and replace the 1–3 garment alternatives with four exactly-one branches. Enforce the same invariant in `AiTagResults`, then return a domain-facing `AiTagAnalysisResult` containing optional garment ownership plus canonical tags; canonical providers populate the piece while demo/prototype analyzers remain explicitly piece-less. `AnalysisService` consumes only `canonicalTags()` for current persistence, so public API, DB, recommendation, and category mapping contracts remain unchanged.

**Tech Stack:** Java 21, Spring Boot 4.1, Gradle, JUnit 5, AssertJ, Mockito, OpenAI strict Structured Outputs.

## Global Constraints

- Allowed pieces remain exactly `TOP`, `BOTTOM`, `DRESS`, `OUTER`.
- Do not change recommendation search/ranking, `ProductCategory` mapping, or analysis report category persistence.
- Do not change canonical resolution, fuzzy matching, taxonomy, evaluator gold/catalog, retry/timeout, Fashion-CLIP, CloudFormation, or deployment.
- Preserve existing API error codes and provider error ownership.
- Demo and prototype analyzers must not invent a garment piece.

---

### Task 1: Exactly-one model contract

**Files:**
- Modify: `src/test/java/com/fitback/backend/external/aitag/AiTagRequestFactoryTest.java`
- Modify: `src/test/java/com/fitback/backend/external/aitag/AiTagResponseParserTest.java`
- Modify: `src/main/java/com/fitback/backend/external/aitag/AiTagRequestFactory.java`
- Modify: `src/main/java/com/fitback/backend/external/aitag/AiTagResults.java`
- Modify: `src/main/java/com/fitback/backend/external/aitag/openai/OpenAiTagModelClient.java`
- Modify: `docs/AI_TAG_BLIND_EVALUATION.md`

**Interfaces:**
- Consumes: four required nullable garment keys and existing `AiTagGarment` validation.
- Produces: exactly one non-null schema branch and `AiTagResults.validateGarments(...)` accepting a list of size exactly one.

- [ ] **Step 1: Write failing schema and semantic tests**

  Assert `MAX_GARMENTS == 1`, four `anyOf` branches, each single-piece combination accepted, and every 0/2/3/4-piece combination rejected. Update parser tests so one garment passes while zero and two-or-more fail with `garments must contain exactly 1 item`; retain SHOES and duplicate-piece rejection.

- [ ] **Step 2: Run focused tests and confirm the old 1–3 contract fails**

  Run: `./gradlew test --tests '*AiTagRequestFactoryTest' --tests '*AiTagResponseParserTest'`

  Expected: FAIL on the old maximum, prompt, schema branch count, and multi-garment parser acceptance.

- [ ] **Step 3: Implement the minimal exactly-one contract**

  Set `MAX_GARMENTS = 1`. Change the prompt introduction to state exactly one cropped garment, exactly one classification, no multi-piece output, and no DRESS/OUTER folding. Build schema branches with one selected piece mapped to `garmentItem` and all other pieces mapped to `{ "type": "null" }`. Validate duplicates first, then require list size exactly one; keep the existing `GARMENT_COUNT_OUT_OF_RANGE` mapping for the new message.

- [ ] **Step 4: Re-run focused tests**

  Run: `./gradlew test --tests '*AiTagRequestFactoryTest' --tests '*AiTagResponseParserTest'`

  Expected: PASS.

### Task 2: Preserve garment ownership at the service boundary

**Files:**
- Create: `src/main/java/com/fitback/backend/domain/analysis/service/AiTagAnalysisResult.java`
- Modify: `src/main/java/com/fitback/backend/domain/analysis/service/AiTagAnalyzer.java`
- Modify: `src/main/java/com/fitback/backend/domain/analysis/service/DemoAiTagAnalyzer.java`
- Modify: `src/main/java/com/fitback/backend/domain/analysis/service/PrototypeAiTagAnalyzer.java`
- Modify: `src/main/java/com/fitback/backend/domain/analysis/service/UnavailableAiTagAnalyzer.java`
- Modify: `src/main/java/com/fitback/backend/domain/analysis/service/AnalysisService.java`
- Modify: `src/main/java/com/fitback/backend/external/aitag/CanonicalAiTagAnalyzer.java`
- Modify: `src/test/java/com/fitback/backend/external/aitag/CanonicalAiTagAnalyzerTest.java`
- Modify: `src/test/java/com/fitback/backend/domain/analysis/service/PrototypeAiTagAnalyzerTest.java`
- Modify: `src/test/java/com/fitback/backend/domain/analysis/service/AnalysisServiceTest.java`

**Interfaces:**
- Consumes: exactly one `AiTagGarment` from `AiTagModelResult.garments()`.
- Produces: `AiTagAnalysisResult(Optional<GarmentPiece> garmentPiece, List<Tag> canonicalTags)`; `AiTagAnalyzer.analyze(...)` returns this type for both image inputs.

- [ ] **Step 1: Write failing analyzer and service tests**

  Parameterize canonical analyzer coverage across TOP/BOTTOM/DRESS/OUTER and assert both `garmentPiece()` and resolved `canonicalTags()`. Update service mocks to return `AiTagAnalysisResult` and assert existing report tag persistence/output remains unchanged. Assert prototype output has an empty piece and stable canonical tags.

- [ ] **Step 2: Run focused tests and confirm the old list-only boundary fails**

  Run: `./gradlew test --tests '*CanonicalAiTagAnalyzerTest' --tests '*PrototypeAiTagAnalyzerTest' --tests '*AnalysisServiceTest'`

  Expected: FAIL to compile until the new result contract exists.

- [ ] **Step 3: Implement the service result and ownership-preserving analyzer**

  Create an immutable record that null-checks the `Optional` and copies canonical tags. Add `withGarmentPiece(piece, tags)` and `withoutGarmentPiece(tags)` factories. Canonical analysis reads `result.garments().getFirst()`, resolves only that garment's tags, and returns the piece with tags. Demo/prototype return piece-less results; unavailable methods keep throwing. `AnalysisService` stores `analysisResult.canonicalTags()` only.

- [ ] **Step 4: Re-run focused tests**

  Run: `./gradlew test --tests '*CanonicalAiTagAnalyzerTest' --tests '*PrototypeAiTagAnalyzerTest' --tests '*AnalysisServiceTest'`

  Expected: PASS.

### Task 3: Regression and publication gate

**Files:**
- Modify only test fixtures that still construct multi-garment success outputs under the obsolete contract.
- Verify protected recommendation, product category, migration, and API DTO files remain untouched.

**Interfaces:**
- Consumes: Tasks 1–2.
- Produces: buildable branch ready for Draft PR #310 review.

- [ ] **Step 1: Run all AI-tag focused tests**

  Run: `./gradlew test --tests 'com.fitback.backend.external.aitag.*' --tests 'com.fitback.backend.external.aitag.openai.*' --tests 'com.fitback.backend.external.aitag.bedrock.*'`

- [ ] **Step 2: Run clean build**

  Run: `GRADLE_USER_HOME=/tmp/fitback-310-gradle ./gradlew clean build --no-daemon --no-watch-fs`

- [ ] **Step 3: Run migration and diff gates**

  Run: `bash scripts/ci/test_mysql_migrations.sh`

  Run: `git diff --check`

  Confirm `git diff --name-only origin/develop...HEAD` contains no recommendation, product category, DB migration, or API DTO changes.

- [ ] **Step 4: Commit, push, and open Draft PR**

  Commit only reviewed files with `fix: AI 태그 단일 의류 계약 적용`, push `fix/#310-single-garment-piece-contract`, and create a Draft PR to `develop` with `close #310`, exact validation results, and excluded scope.

- [ ] **Step 5: Verify CI without merge or deploy**

  Observe the Draft PR checks to a terminal state. Report failing checks accurately; do not merge, deploy, or manufacture provider/production failures.
