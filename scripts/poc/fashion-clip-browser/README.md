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
stat -f '%z %N' "$MODEL_DIR/vision_model.onnx"
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

Select the existing local query/crop image, enter a local backend base URL, analysis report ID, and bearer token, then click **POST recommendation and rerank**. The browser calls `POST /api/v1/analyses/{reportId}/recommendations` directly and validates the returned `data.browserReranking` with 1–30 candidates, nonblank unique `candidateId` values, HTTP(S) `imageUrl` values, and finite `[0,1]` `tagSimilarity` values. The token is held only in the current page and is never persisted.

Each Shopify candidate image URL is fetched directly in this browser tab with CORS, no credentials, and `cache: 'no-store'`; there is no browser-persistent image cache in this path. Candidate fetch, decode, and preprocess work is issued in parallel stages; the UI reports both stage wall-clock and the cumulative sum of per-candidate durations. The cumulative value is not CPU time. A fetch, decode, or preprocess failure blocks the whole handoff run, displays only the safe failure class, and preserves the backend recommendation result; no partial ranking is emitted. Successful candidates are sent through one Fashion-CLIP candidate tensor batch. The local query crop is embedded separately.

If the backend returns a non-success response, malformed data, no handoff, an empty handoff, or the browser model/image path is unavailable, the UI reports `browser-reranking unavailable` and keeps a compact backend result summary. A missing handoff is therefore a tag-only/backend-result path, not a browser error that breaks the recommendation flow.

The regular **POST recommendation and rerank browser top-10** action keeps the backend handoff pool unchanged and selects `tagSimilarity DESC` top 10 in the browser before direct image fetch. Equal tag similarities use original handoff input index order. The separate benchmark action still measures all handoff candidates versus this browser-only top-10 path on one recommendation response.

The displayed score is the demo-only hypothesis:

```text
imageSimilarity = cosine(normalizedQueryEmbedding, normalizedCandidateEmbedding)
finalScore = imageSimilarity * 0.70 + tagSimilarity * 0.30
```

The cosine is not remapped to `[0,1]`; it remains the model cosine scale. Results are sorted by descending `finalScore`, with original handoff input index as the deterministic tie-breaker. The handoff summary reports backend HTTP status, candidate count, image fetch success/failure, model readiness/runtime, Fashion-CLIP batch latency, total browser reranking latency, score ranges, and final ordering. No threshold, score submission, persistence, price sort, candidate tag enrichment, server-side image fetch, or Modal call is performed.

The local 30-vs-10 demo measurement used the existing local query image, existing local Shopify configuration, and the authorized local Fashion-CLIP artifact. After one warm-up per path, three measured runs produced these medians:

| Path | Selected | Fetch wall | Decode cumulative / wall | Preprocess cumulative / wall | Query inference | Candidate batch | Total reranking | Fetch failures |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| Full handoff | 30 | 401.9 ms | 2,446.1 / 277.6 ms | 56.8 / 56.8 ms | 33.7 ms | 221.4 ms | 1,084.2 ms | 0/30 |
| Browser tag top 10 | 10 | 141.2 ms | 488.5 / 134.7 ms | 17.7 / 17.8 ms | 29.7 ms | 81.6 ms | 459.5 ms | 0/10 |

The same final run also measured backend HTTP `200`, handoff pool `30`, model readiness `1,592.5 ms`, fetch cumulative `6,122.3 ms` / `855.8 ms`, query-crop preprocess wall `59.9 ms` / `55.3 ms`, cosine `0.2 ms` / `0.0 ms`, finalScore calculation `0.0 ms` / `0.0 ms`, sort `0.0 ms` / `0.0 ms`, and render/update `1.6 ms` / `0.5 ms` for full/top-10 respectively. The three full-path fetch wall samples were `495.8/401.9/375.9 ms`; top-10 samples were `152.1/141.2/138.6 ms`. The measured ranges were imageSimilarity `0.30117880..0.55265682` / `0.36216948..0.55064899`, tagSimilarity `0.00000000..0.67000000` / `0.67000000..0.67000000`, and finalScore `0.26928713..0.58645429` / `0.45451864..0.58645429` for full/top-10 respectively. The top-10 path retained 2/3 full top-3, 4/5 full top-5, and 7/10 full top-10 candidates; 9/10 common candidates changed rank. One excluded candidate appeared in the full top-3, one in the full top-5, and three in the full top-10. WebGPU was ready, no WASM fallback or OOM occurred, and the demo default is therefore 10 for this browser-visible path only. This is a demo comparison, not a production candidate or accuracy policy.

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
