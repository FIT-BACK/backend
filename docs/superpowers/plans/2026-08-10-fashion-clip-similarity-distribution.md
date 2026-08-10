# Fashion-CLIP PoC A Similarity Distribution Evaluation Plan

> **For agentic workers:** Execute the steps in this plan task-by-task and verify each checkpoint before publishing.

**Goal:** Extend the existing Fashion-CLIP batch evaluator so an authorized local dataset is sent to the protected Modal Fashion-CLIP endpoint and produces per-candidate cosine similarities plus relation summaries.

**Architecture:** Reuse the existing PR #289 provider-neutral dataset/evaluator and PR #292 Modal endpoint/smoke contract. Add a test-source Java Modal HTTP provider that sends local image bytes in ordered batches of at most eight, then let `FashionClipSimilarityEvaluationMain` calculate cosine values and summary statistics. Dataset images remain outside Git; missing local inputs stop with `USER_INPUT_REQUIRED`.

**Tech Stack:** Java 21, JDK `HttpClient`, existing Jackson/JUnit/AssertJ runtime, Python Modal endpoint assets, Gradle `JavaExec`.

## Global Constraints

- Shopify Catalog image/product URLs are never read or downloaded.
- No threshold, 70/30 finalScore, tag similarity, Pinecone, vector DB, or cache.
- No image bytes or credentials are committed or printed.
- Input relation labels remain human-supplied and are not inferred by code.
- Modal max batch size remains eight; batches are sent in input order and reassembled in input order.
- Missing dataset/images/endpoint credentials produce `USER_INPUT_REQUIRED`; no arbitrary image download is allowed.

## Task 1: Assemble and extend the existing PoC assets

**Files:**
- Reuse `src/test/java/com/fitback/backend/external/fashionclip/FashionClipSimilarityEvaluationMain.java`
- Reuse `FashionClipEmbeddingProvider.java`, `FashionClipImageInput.java`, `FashionClipSimilarity.java`
- Reuse `scripts/poc/fashion-clip/fashion-clip-evaluation.schema.json`, template, and Modal endpoint/smoke files
- Modify `build.gradle` only for the evaluation JavaExec task if needed

Implement `ModalFashionClipEmbeddingProvider` using `HttpClient`: request `POST` JSON `{images:[{contentType,dataBase64}]}`, send chunks of 8, require the response embedding count to match each chunk, validate finite/non-zero vectors through the existing contract, and concatenate results. The provider must never include response bodies, vectors, or proxy credentials in exceptions.

## Task 2: Write the real evaluation report

**Files:**
- Modify `FashionClipSimilarityEvaluationMain.java`
- Add `ModalFashionClipEmbeddingProvider.java`
- Add focused provider/evaluator tests
- Add `scripts/poc/fashion-clip/fashion-clip-similarity-output.schema.json`

`main` reads the dataset path, resolves image paths relative to the dataset directory (or explicit `FASHION_CLIP_EVALUATION_DATASET_DIR`), requires `MODAL_FASHION_CLIP_ENDPOINT_URL`, `MODAL_PROXY_TOKEN_ID`, and `MODAL_PROXY_TOKEN_SECRET`, and writes `fashion-clip-similarity-evaluation.json`. Each pair contains `queryId`, `queryPath`, `candidateId`, `candidatePath`, `relation`, and `cosineSimilarity`. The report contains all four relation keys with `count`, `min`, `max`, `mean`, and `median`; empty groups use `count: 0` and nullable statistics.

## Task 3: Document and verify the local-only flow

**Files:**
- Modify `scripts/poc/fashion-clip/README_MODAL_ENDPOINT.md`
- Add a `.gitignore` rule for the optional local dataset directory

Document a dataset directory outside the repository, the copyable JSON structure, env vars, command, output shape, and license/size responsibility. Do not add sample images. Run focused tests, the full test suite, `git diff --check`, and a no-image invocation that proves `USER_INPUT_REQUIRED` without making a network request.

## Task 4: Publish

Create one minimal commit containing only the PoC evaluation changes, push `feature/#300-fashion-clip-similarity-evaluation`, and open a Draft PR to `develop` with `close #300`, explicitly recording whether real cosine measurement was run or remains `USER_INPUT_REQUIRED`.
