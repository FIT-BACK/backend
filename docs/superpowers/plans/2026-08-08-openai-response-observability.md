# OpenAI Response Observability Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use inline execution with verification checkpoints. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make `OpenAiTagModelClient` distinguish transport, HTTP, response-shape, output-extraction, and model-output parsing failures while logging only safe response metadata.

**Architecture:** Keep the existing `ANALYSIS_NOT_READY` business contract and transport payload unchanged. Parse the provider response in explicit stages, derive bounded metadata (`responseStatus`, `incomplete_details.reason`, output types, content types), and emit a single structured warning for each failure stage without logging response text, request data, API keys, image bytes, or exception messages.

**Tech Stack:** Java 21, Spring Boot 4.1.0, Gradle, SLF4J/Logback, JUnit 5, AssertJ, Jackson (`tools.jackson.databind`).

## Global Constraints

- Base all implementation decisions on `origin/develop` at `f979d511874db889e32478fa7b476bc9a1590147`.
- Preserve the existing OpenAI Responses API request payload and `ANALYSIS_NOT_READY` error contract.
- Never log response bodies, model output text, prompts, image data URLs/bytes, API keys, or exception messages.
- Log only response status, safe `incomplete_details.reason`, bounded output/content type lists, failure category, provider/model, and elapsed time.
- Do not commit or push.

---

### Task 1: Add staged safe response diagnostics

**Files:**
- Modify: `src/main/java/com/fitback/backend/external/aitag/openai/OpenAiTagModelClient.java`

**Interfaces:**
- Consumes: `TransportResponse.statusCode()` and provider response body.
- Produces: unchanged `AiTagModelResult` success path and unchanged `ANALYSIS_NOT_READY` failure path; warning fields `responseStatus`, `incompleteDetailsReason`, `outputTypes`, `contentTypes`, and stage-specific `responseParsingCategory`.

- [x] **Step 1: Separate response stages without changing the API contract.**

Keep transport errors and HTTP status handling in their existing branches. After a non-error status, handle these stages independently: response JSON decoding, root/output shape, `output_text` extraction, model-output JSON decoding, and schema parsing.

- [x] **Step 2: Derive bounded metadata without retaining or logging response content.**

Extract only `incomplete_details.reason`, `output[].type`, and `output[].content[].type`; limit each list to 20 entries, each token to 64 safe ASCII characters, and use `<redacted>` for non-token values. Use `UNKNOWN`/empty lists when the body cannot be decoded.

- [x] **Step 3: Emit safe category-specific warnings.**

Use categories `INVALID_RESPONSE_JSON`, `INVALID_RESPONSE_SHAPE`, `MISSING_OUTPUT`, `MISSING_OUTPUT_TEXT`, `EMPTY_OUTPUT_TEXT`, `INVALID_MODEL_OUTPUT_JSON`, and `INVALID_MODEL_OUTPUT_SCHEMA`. Do not pass the exception to the logger.

### Task 2: Add regression tests for observability and redaction

**Files:**
- Modify: `src/test/java/com/fitback/backend/external/aitag/openai/OpenAiTagModelClientTest.java`

**Interfaces:**
- Consumes: the package-private transport seam and Logback `ListAppender` pattern already used in this repository.
- Produces: assertions covering metadata, stage categories, HTTP status classification, and absence of sensitive values.

- [x] **Step 1: Cover safe metadata on a missing-output response.**

Return a 200 response with `incomplete_details.reason`, an output type, and a refusal content type. Assert the log contains the status, reason, both type lists, and `MISSING_OUTPUT_TEXT`, but not the response body, API key, or `data:image`.

- [x] **Step 2: Cover model-output redaction.**

Return a valid provider envelope whose `output_text.text` contains a secret marker but is not valid model JSON. Assert `INVALID_MODEL_OUTPUT_JSON` is logged with `output_text` in the content type list and the secret marker is absent.

- [x] **Step 3: Cover malformed provider JSON and preserve existing behavior.**

Assert malformed provider JSON is still translated to `ANALYSIS_NOT_READY` and logs `INVALID_RESPONSE_JSON` without the body.

### Task 3: Run focused and repository verification

**Files:**
- No additional source files.

- [x] **Step 1: Check the diff and sensitive-value patterns.**

Run `git diff --check` and inspect the diff for response-body, image, prompt, API-key, and exception-message logging.

- [x] **Step 2: Run the focused test.**

Run `./gradlew --no-daemon test --tests com.fitback.backend.external.aitag.openai.OpenAiTagModelClientTest`.

- [x] **Step 3: Run the full build test gate if the focused test passes.**

Run `./gradlew --no-daemon test` and report the exact result. Leave the checkout uncommitted and unpushed.

## Verification Checklist

- [x] `git diff --check` passes.
- [x] Focused `OpenAiTagModelClientTest` passes: 8 tests, 0 failures, 0 errors.
- [x] Full Gradle test suite passes: 700 tests, 0 failures, 0 errors.
- [x] `git status` shows only the intended source, test, and plan changes.
- [x] No commit or push was performed.
