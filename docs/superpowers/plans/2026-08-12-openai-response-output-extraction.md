# OpenAI Responses Output Extraction Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Accept a valid structured-output message when the Responses API `output` array also contains non-message items, while preserving strict JSON/schema failure behavior.

**Architecture:** Keep the existing manual Responses API transport and staged parsing categories. Narrow the extraction change to `OpenAiTagModelClient.outputText`: skip explicitly typed non-message output items, inspect message content, and leave malformed message shape, missing text, JSON decoding, schema parsing, logging redaction, and retry ownership unchanged.

**Tech Stack:** Java 21, Spring Boot 4.1, Gradle, JUnit 5, AssertJ, Jackson 3, OpenAI Responses Structured Outputs.

## Global Constraints

- Do not call the OpenAI API, run the evaluation/offline gate, or deploy production.
- Do not log or persist raw response bodies, model output, credentials, Authorization, image bytes, or data URLs.
- Do not relax markdown fences, prose, truncated JSON, schema validation, prompt v2, catalog, acceptance thresholds, pacing, or retry semantics.
- Do not change production P1 retry ownership or behavior.

---

### Task 1: Reproduce stage/category boundaries

**Files:**
- Modify: `src/test/java/com/fitback/backend/external/aitag/openai/OpenAiTagModelClientTest.java`

**Interfaces:**
- Consumes: package-private fake `Transport`, `TransportResponse`, and `ProviderFailure.responseParsingCategory()`.
- Produces: regression coverage for valid multi-item Responses output and strict malformed-output categories.

- [ ] **Step 1: Add a failing valid multi-output regression test**

Construct a 200 envelope whose first output item is `{"type":"reasoning","summary":[]}` and whose second item is a valid `message` containing one `output_text`. Assert that `client.analyze(...)` succeeds and returns the expected single garment.

- [ ] **Step 2: Run the focused test and verify red**

Run: `GRADLE_USER_HOME=/tmp/fitback-openai-output-extraction-gradle ./gradlew --no-daemon --no-watch-fs test --tests 'com.fitback.backend.external.aitag.openai.OpenAiTagModelClientTest.acceptsStructuredOutputAfterNonMessageOutputItem'`

Expected: FAIL because the current extractor maps the leading reasoning item to `INVALID_RESPONSE_SHAPE`.

- [ ] **Step 3: Add strict malformed-output boundary tests**

Feed fake 200 Responses envelopes containing malformed JSON, markdown-fenced JSON, leading prose, trailing prose, and incomplete JSON as `output_text.text`. Assert every case remains `INVALID_MODEL_OUTPUT_JSON`. Add direct assertions that absent `output_text` remains `MISSING_OUTPUT_TEXT`, and keep existing valid/schema-invalid cases as corroborating coverage.

### Task 2: Implement the minimal extraction correction

**Files:**
- Modify: `src/main/java/com/fitback/backend/external/aitag/openai/OpenAiTagModelClient.java:421-442`
- Test: `src/test/java/com/fitback/backend/external/aitag/openai/OpenAiTagModelClientTest.java`

**Interfaces:**
- Consumes: Responses output item `type` and existing `ResponseParsingException` categories.
- Produces: `outputText(JsonNode)` that skips explicitly typed non-message items and returns the first nonblank message `output_text` exactly as received.

- [ ] **Step 1: Make the smallest implementation change**

Before validating `content`, read `output.type`. If it is present and is not `message`, continue to the next output item. Preserve support for existing untyped fixtures, preserve `INVALID_RESPONSE_SHAPE` for malformed message content, and do not strip or transform output text.

- [ ] **Step 2: Run focused tests and verify green**

Run: `GRADLE_USER_HOME=/tmp/fitback-openai-output-extraction-gradle ./gradlew --no-daemon --no-watch-fs test --tests 'com.fitback.backend.external.aitag.openai.OpenAiTagModelClientTest' --tests 'com.fitback.backend.external.aitag.AiTagResponseParserTest' --tests 'com.fitback.backend.external.aitag.OpenAiTagEvaluationMainTest'`

Expected: PASS with no network/OpenAI calls.

### Task 3: Repository verification and publication

**Files:**
- Verify only: repository worktree and GitHub Issue #328 / Draft PR.

**Interfaces:**
- Consumes: completed implementation and tests.
- Produces: one reviewed commit and a Draft PR targeting `develop`.

- [ ] **Step 1: Run clean build**

Run: `GRADLE_USER_HOME=/tmp/fitback-openai-output-extraction-gradle ./gradlew --no-daemon --no-watch-fs clean build`

Expected: PASS.

- [ ] **Step 2: Run MySQL migration gate**

Run: `bash scripts/ci/test_mysql_migrations.sh`

Expected: PASS; if Docker/environment blocks it, report `NOT_RUN` without claiming success.

- [ ] **Step 3: Run diff checks**

Run: `git diff --check` and inspect `git diff --stat` / `git status --short`.

Expected: only the client, focused test, and this plan are changed; no whitespace errors or sensitive data.

- [ ] **Step 4: Commit and push**

Commit message: `fix: Responses output message 추출 수정`

Push branch: `fix/#328-openai-response-output-extraction`.

- [ ] **Step 5: Open Draft PR and inspect CI**

Create a Draft PR into `develop` with `close #328`, the exact verification results, exclusions, production impact, and security boundary. Confirm current head SHA and report CI status without merging or deploying.
