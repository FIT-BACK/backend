# Browser Fashion-CLIP PoC

This isolated demo accepts one local query crop, local candidate images, or up to ten direct HTTPS candidate image URLs. It runs the image encoder in the browser, shows raw and explicitly L2-normalized embedding diagnostics, raw/normalized cosine similarity, model-load latency, inference latency, and the selected execution-provider state.

The recommendation integration makes one direct browser POST to a configured FIT-BACK backend URL, using a locally entered bearer token and report ID. The response's `data.browserReranking` is consumed in the browser; local image bytes, model output, and embeddings are not sent to FIT-BACK backend or Modal.

For direct URL integration, the browser fetches each URL with CORS and no credentials, validates the image content type, decodes and preprocesses the image, and then sends the successful images through the same Fashion-CLIP batch path. No image proxy, persistent cache, backend upload, or embedding upload is used. The result table reports host-level URL information, fetch/decode/preprocess latency, and normalized cosine; a CORS failure remains a visible candidate failure.

## Runtime and model

- Runtime: `onnxruntime-web@1.27.0`.
- Preferred execution provider: WebGPU when the browser exposes `navigator.gpu`.
- Fallback: ONNX Runtime WebAssembly (`wasm`) if WebGPU is unavailable or session creation fails.
- Model: [`Frapic/fashion-clip-onnx`](https://huggingface.co/Frapic/fashion-clip-onnx), specifically its `vision_model.onnx` export.
- Pinned model revision: `12eb79267363fd03b8983a25903cd9097b1ec76c` (`vision_model.onnx`, 352,575,989 bytes at verification time).
- Provenance: the model card identifies this as an ONNX export of [`patrickjohncyh/fashion-clip`](https://huggingface.co/patrickjohncyh/fashion-clip), with a 512-dimensional image output. The demo does not substitute a generic CLIP checkpoint.
- The approximately 353 MB model file is fetched at runtime by the browser and is intentionally not stored in Git.

The model output is not L2-normalized by the artifact. The demo now applies an explicit L2 normalization helper before its normalized cosine calculation, rejects zero/non-finite embeddings, verifies normalized norm approximately `1.0`, and reports raw-vs-normalized cosine difference. The difference is expected to be floating-point noise.

## Run

```bash
npm install
npm test
npm run dev
```

Open the printed local URL, select one local JPEG/PNG/WEBP query image and one or more candidate images, and click **Load model and compare first candidate**. To check the same-image invariant, select the same local file in both inputs; the raw and normalized cosine should be approximately `1` subject to runtime floating-point behavior.

### Presentation/demo local model

The model artifact is intentionally Git-external. The following procedure uses the ignored path `.local/poc/fashion-clip-browser/model/vision_model.onnx` and verifies the pinned byte count before serving it:

```bash
MODEL_DIR="$PWD/.local/poc/fashion-clip-browser/model"
mkdir -p "$MODEL_DIR"
curl -L --fail --retry 2 \
  --output "$MODEL_DIR/vision_model.onnx" \
  "https://huggingface.co/Frapic/fashion-clip-onnx/resolve/12eb79267363fd03b8983a25903cd9097b1ec76c/vision_model.onnx"
EXPECTED_BYTES=352575989
ACTUAL_BYTES="$(wc -c < "$MODEL_DIR/vision_model.onnx" | tr -d '[:space:]')"
if [ "$ACTUAL_BYTES" -ne "$EXPECTED_BYTES" ]; then
  echo "Unexpected model size: $ACTUAL_BYTES (expected $EXPECTED_BYTES)" >&2
  exit 1
fi
```

From the artifact directory, run the smallest CORS-enabled static server needed by the browser:

```bash
cd "$MODEL_DIR"
python3 -c 'from http.server import SimpleHTTPRequestHandler,ThreadingHTTPServer; H=type("H",(SimpleHTTPRequestHandler,),{"end_headers":lambda s:(s.send_header("Access-Control-Allow-Origin","*"),s.send_header("Cache-Control","public, max-age=3600"),SimpleHTTPRequestHandler.end_headers(s))[-1]}); ThreadingHTTPServer(("127.0.0.1",8765),H).serve_forever()'
```

In another terminal, run the existing PoC dev server and open the model URL override:

```bash
cd scripts/poc/fashion-clip-browser
npm run dev -- --host 127.0.0.1
```

Open `http://127.0.0.1:5173/?modelUrl=http%3A%2F%2F127.0.0.1%3A8765%2Fvision_model.onnx`. The missing `modelUrl` parameter keeps the pinned Hugging Face URL as the default. The browser fetches the local model directly; no model bytes, Shopify image bytes, or embeddings go to FIT-BACK backend or Modal.

Local serving verification in one browser session measured `352,575,989` bytes with no redirect:

| Run | URL resolve | WebGPU preflight | Headers | Body download | Session creation/readiness | Total |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| Cold | 0.0 ms | 16.6 ms | 2.8 ms | 815.3 ms | 635.5 ms | 1,471.2 ms |
| Second | 0.1 ms | 0.9 ms | 4.2 ms | 603.5 ms | 283.4 ms | 893.0 ms |

The second local request was still observed as a full GET, so this PoC does not rely on a browser cache hit; local static serving alone reduced model readiness from roughly 56–58 seconds to roughly 0.9–1.5 seconds. Both runs used WebGPU with the WASM fallback armed and no fallback/OOM. With the same approved local image in both inputs, dimension was `512`, finite was `true`, normalized norm was `1.000000`, and cosine was `1.00000000`.

To check real Shopify retrieval image URLs, paste at most ten HTTPS image URLs into the direct URL input and click **Run direct URL fetch and Fashion-CLIP batch**. The browser fetches those URLs directly; the model-load latency is shown separately and total reranking latency excludes model load.

For the browser benchmark, select at least ten approved local candidate images and click **Run warm 1/3/5/10 benchmark**. Candidate sizes `1`, `3`, `5`, and `10` are warmed once and then measured three times; the table reports medians for total embedding inference, per-image average, query batch, candidate batch, and cosine calculation. The query is run as a batch of one and each candidate set is run as one `[candidateCount, 3, 224, 224]` tensor batch. Query and candidate batch runs are sequential so their latency can be reported separately. Reused or heterogeneous local images are performance inputs only and are not an accuracy evaluation.

### Backend recommendation and browser reranking

Select the existing local query/crop image, enter a local backend base URL, analysis report ID, and bearer token, then click **POST recommendation and rerank**. The browser calls `POST /api/v1/analyses/{reportId}/recommendations` directly and validates the returned `data.browserReranking` with 1–30 candidates, nonblank unique opaque `candidateId` values, HTTP(S) `imageUrl` values, required `name`, nullable seller/price/purchase metadata, and finite `[0,1]` `tagSimilarity` values. The token is held only in the current page and is never persisted or displayed as a product identifier. For the approved scratch-only custom-tag baseline, append `benchmarkCustomTag` to the PoC URL (for example, `?benchmarkCustomTag=black%20hoodie`): a nonblank value up to 50 characters sends the normal JSON request body directly with the POST; without it, the existing bodyless request remains unchanged.

Each handoff candidate image URL is fetched directly in this browser tab with CORS, no credentials, and `cache: 'no-store'`; there is no browser-persistent image cache in this path. Every validated handoff candidate runs a direct fetch→decode→preprocess pipeline concurrently with the other candidates, and query-crop preprocessing starts at the same time as candidate acquisition. The UI reports both stage wall-clock spans and the cumulative sum of per-candidate durations. The cumulative value is not CPU time and can exceed wall-clock when work overlaps. A fetch, decode, or preprocess failure blocks the whole handoff run, displays only the safe failure class, and preserves the backend recommendation result; no partial ranking is emitted. Successful candidates are sent through one Fashion-CLIP candidate tensor batch.

Each direct candidate request has the explicit PoC constant `CANDIDATE_IMAGE_FETCH_DEADLINE_MS = 30_000`. The deadline covers both the response and image-body transfer, uses `AbortController`, and does not add a retry. Thirty seconds deliberately leaves room for a cold, multi-megabyte Shopify CDN image over a slow browser network path while preventing an unbounded wait; because the existing candidate pipelines still start in parallel, it is a per-request bound rather than `candidateCount × 30 seconds`. The structured baseline trace records only each input index, one safe terminal reason (`success`, `http_error`, `timeout`, `network_error`, `aborted`, or `decode_error`), request timing, aggregate counts, the deadline, and the slowest request. It never records candidate IDs, image URLs, bodies, or embeddings. A timeout issues the real request abort; blobs are released after decode, object URLs are revoked by the fallback image decoder, and `ImageBitmap.close()` remains in the preprocessing cleanup path. Any non-success remains the existing fail-closed `browser-reranking unavailable` path with the backend recommendation retained.

If the backend returns a non-success response, malformed data, no handoff, an empty handoff, or the browser model/image path is unavailable, the UI reports `browser-reranking unavailable` and keeps a compact backend result summary. A missing handoff is therefore a tag-only/backend-result path, not a browser error that breaks the recommendation flow.

The regular **POST recommendation and rerank browser top-10** action keeps the backend handoff pool unchanged and computes `finalScore` for the full reranked candidate count before selecting `min(10, rerankedCandidateCount)` by `finalScore DESC`. The selected relevance shortlist is then displayed by ascending price only when both candidates have `price.amount` and the same currency. Missing prices or mixed currencies preserve the existing relevance order; equal comparable prices use `finalScore DESC` and original handoff input index ASC. The separate benchmark action measures the same handoff response's full relevance/display path and relevance top-10/price-display path; both paths compute relevance from the full pool.

The displayed score is the demo-only hypothesis:

```text
imageSimilarity = cosine(normalizedQueryEmbedding, normalizedCandidateEmbedding)
finalScore = imageSimilarity * 0.70 + tagSimilarity * 0.30
```

The cosine is not remapped to `[0,1]`; it remains the model cosine scale. Relevance is sorted by descending `finalScore`, with original handoff input index as the deterministic tie-breaker, and price ordering is applied only inside the selected shortlist. The handoff summary reports backend HTTP status, candidate count, metadata completeness, image fetch success/failure, model readiness/runtime, Fashion-CLIP batch latency, total browser reranking latency, score ranges, relevance ordering, and final price display ordering. No threshold, score submission, persistence, candidate tag enrichment, server-side image fetch, Shopify lookup, or Modal call is performed.

The backend metadata is a response-time snapshot from the existing `ExternalProductCandidate` and reuses `ProductPriceResponse`. It is not a live Shopify price guarantee. The browser makes no candidate-token resolve call and no follow-up metadata request. A null `purchaseUrl` renders without a link, and no URL is synthesized.

The historical local 30-vs-10 measurement below was collected by PR #335 before this final-score shortlist and price-display contract. It is retained as prior performance evidence only; it is not evidence for the current ordering behavior. A current-contract E2E run must record the same one-POST handoff candidate count, metadata completeness, finalScore relevance top-10, final price display order, and browser reranking latency.

| Path | Selected | Selection wall | Fetch cumulative / wall | Decode cumulative / wall | Preprocess cumulative / wall | Query preprocess wall | Query inference | Candidate batch | Cosine | FinalScore | Sort | Render/update | Total reranking | Fetch success/failure |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| Full handoff | 30 | 0.0 ms | 7,672.3 / 421.4 ms | 904.9 / 477.6 ms | 55.9 / 55.9 ms | 60.5 ms | 37.1 ms | 226.9 ms | 0.1 ms | 0.0 ms | 0.0 ms | 1.2 ms | 869.4 ms | 30/0 |
| Historical PR #335 tag-prefilter top 10 | 10 | 0.0 ms | 1,069.0 / 168.6 ms | 382.2 / 195.4 ms | 18.5 / 18.5 ms | 56.2 ms | 28.9 ms | 82.7 ms | 0.0 ms | 0.0 ms | 0.0 ms | 0.4 ms | 390.4 ms | 10/0 |

The three historical full-path fetch wall samples were `357.1/421.4/594.8 ms`; the historical tag-prefilter top-10 samples were `168.6/168.5/186.6 ms`. Those values and their overlap/rank observations describe the old prefilter path only. They must not be used as the current final price display latency or ordering result; the current contract requires a fresh full-pool finalScore rerank before top-10 selection.

`npm run build` creates only the static app bundle. It does not download the model or any image.

## Model-load diagnostics

The PoC measures URL resolution, WebGPU preflight, response headers, response-body download, and ONNX Runtime session creation/readiness separately. The pinned model request redirected from the Hugging Face resolve URL to its final artifact host and returned `352,575,989` bytes with the same `Content-Length`.

In one browser session, the measured cold and second loads were:

| Run | URL resolve | WebGPU preflight | Headers | Body download | Session creation/readiness | Total | Redirected |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | --- |
| Cold | 0.0 ms | 14.1 ms | 687.3 ms | 56,002.5 ms | 888.4 ms | 57,597.0 ms | yes |
| Second | 0.1 ms | 3.4 ms | 544.4 ms | 55,083.3 ms | 569.3 ms | 56,204.9 ms | yes |

This run did not obtain a meaningful browser HTTP-cache hit: the second request downloaded the full artifact again. The evidence points to the redirected artifact network path/cache behavior, not WebGPU compilation or ONNX session creation; session creation remained below one second. The demo recommendation is to retain normal browser HTTP caching as the first path, and provide the same pinned artifact from a dev/demo local static server (or stable static hosting/CDN) when predictable first-load latency is required. The artifact must be provisioned outside Git; no Service Worker, IndexedDB cache, Shopify-image cache, or model binary is added here.

## Scope limits

- This is not production frontend code; it calls the existing backend recommendation endpoint but does not add a browser score endpoint.
- It performs one query comparison, a backend handoff run, or a bounded `1/3/5/10` candidate benchmark. Browser final scores remain visible-only.
- A real same-image/model measurement requires a user-provided local image and a browser with model access. No image is downloaded or fabricated by this repository.
