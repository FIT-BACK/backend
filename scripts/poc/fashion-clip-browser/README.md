# Browser Fashion-CLIP PoC

This isolated demo accepts one local query image, local candidate images, or up to ten direct HTTPS candidate image URLs. It runs the image encoder in the browser, shows raw and explicitly L2-normalized embedding diagnostics, raw/normalized cosine similarity, model-load latency, inference latency, and the selected execution-provider state.

It does not call FIT-BACK backend, Shopify, Modal, or any application API. Local image bytes, model output, and embeddings remain in the browser tab.

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

### Backend browser-reranking handoff

The **Browser reranking handoff** section accepts pasted JSON without calling the backend. Paste the `browserReranking` object from the #330-compatible response, then select the existing local query/crop image and click **Run browser handoff reranking**. The browser validates 1–30 candidates, nonblank unique `candidateId` values, HTTP(S) `imageUrl` values, and finite `[0,1]` `tagSimilarity` values.

Each candidate image URL is fetched, decoded, and preprocessed directly in this browser tab. A fetch, decode, or preprocess failure blocks the whole handoff run and displays only the failed input index/candidate identifier and safe failure class; no partial ranking is emitted. Successful candidates are sent through one Fashion-CLIP candidate tensor batch. The local query remains the existing query file input and is embedded separately.

The displayed score is the demo-only hypothesis:

```text
imageSimilarity = cosine(normalizedQueryEmbedding, normalizedCandidateEmbedding)
finalScore = imageSimilarity * 0.70 + tagSimilarity * 0.30
```

The cosine is not remapped to `[0,1]`; it remains the model cosine scale. Results are sorted by descending `finalScore`, with original handoff input index as the deterministic tie-breaker. No threshold, score submission, persistence, price sort, candidate tag enrichment, backend call, server-side image fetch, or Modal call is performed.

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

- This is not production frontend code and has no backend endpoint.
- It performs one query comparison or a bounded `1/3/5/10` candidate benchmark; no threshold, tag similarity, or final score is included.
- A real same-image/model measurement requires a user-provided local image and a browser with model access. No image is downloaded or fabricated by this repository.
