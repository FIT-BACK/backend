# Browser Fashion-CLIP PoC

이 격리된 demo는 local query crop 1개와 local 후보 이미지 또는 최대 10개의 직접 HTTPS 후보 이미지 URL을 입력받는다. Browser에서 image encoder를 실행하고 raw embedding과 명시적으로 L2 normalization한 embedding 진단값, raw/normalized cosine similarity, model load latency, inference latency, 선택된 execution provider 상태를 표시한다.

추천 연동은 로컬에서 입력한 bearer token과 report ID를 사용해 설정된 FIT-BACK backend URL로 browser가 직접 POST를 1회 호출한다. 응답의 `data.browserReranking`은 browser에서 소비하며 local image byte, model output, embedding은 FIT-BACK backend나 Modal로 보내지 않는다.

직접 URL 연동에서는 browser가 credential 없이 CORS로 각 URL을 fetch하고 image content type을 검증한 뒤 decode와 preprocess를 수행한다. 성공한 이미지는 동일한 Fashion-CLIP batch 경로로 전달한다. Image proxy, persistent cache, backend upload, embedding upload는 사용하지 않는다. 결과 table에는 host 단위 URL 정보, fetch/decode/preprocess latency, normalized cosine을 표시하며 CORS 실패는 후보 실패로 명확히 남긴다.

## Runtime과 model

- Runtime: `onnxruntime-web@1.27.0`.
- 우선 execution provider: browser가 `navigator.gpu`를 제공하면 WebGPU.
- Fallback: WebGPU를 사용할 수 없거나 session 생성이 실패하면 ONNX Runtime WebAssembly(`wasm`).
- Model: [`Frapic/fashion-clip-onnx`](https://huggingface.co/Frapic/fashion-clip-onnx)의 `vision_model.onnx` export.
- 고정 model revision: `12eb79267363fd03b8983a25903cd9097b1ec76c`(검증 시점 `vision_model.onnx` 352,575,989 byte).
- 출처: model card는 이를 [`patrickjohncyh/fashion-clip`](https://huggingface.co/patrickjohncyh/fashion-clip)의 ONNX export로 설명하며 image output은 512차원이다. 이 demo는 범용 CLIP checkpoint로 대체하지 않는다.
- 약 353 MB의 model file은 runtime에 browser가 가져오며 의도적으로 Git에 저장하지 않는다.

Artifact의 model output은 L2 normalization되어 있지 않다. Demo는 normalized cosine 계산 전에 명시적인 L2 normalization helper를 적용하고, zero/non-finite embedding을 거부하며, normalized norm이 약 `1.0`인지 검증하고, raw cosine과 normalized cosine의 차이를 보고한다. 이 차이는 부동소수점 오차 수준이어야 한다.

## 실행

```bash
npm install
npm test
npm run dev
```

출력된 local URL을 열고 local JPEG/PNG/WEBP query image 1개와 후보 image를 하나 이상 선택한 뒤 **Load model and compare first candidate**를 누른다. Same-image invariant를 확인하려면 두 입력에 같은 local file을 선택한다. Runtime 부동소수점 동작을 고려할 때 raw cosine과 normalized cosine은 약 `1`이어야 한다.

### 발표/demo용 local model

Model artifact는 의도적으로 Git 외부에 둔다. 다음 절차는 ignore된 `.local/poc/fashion-clip-browser/model/vision_model.onnx` 경로를 사용하며 serve하기 전에 고정 byte 수를 검증한다.

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

Artifact directory에서 browser에 필요한 최소 CORS-enabled static server를 실행한다.

```bash
cd "$MODEL_DIR"
python3 -c 'from http.server import SimpleHTTPRequestHandler,ThreadingHTTPServer; H=type("H",(SimpleHTTPRequestHandler,),{"end_headers":lambda s:(s.send_header("Access-Control-Allow-Origin","*"),s.send_header("Cache-Control","public, max-age=3600"),SimpleHTTPRequestHandler.end_headers(s))[-1]}); ThreadingHTTPServer(("127.0.0.1",8765),H).serve_forever()'
```

다른 terminal에서 기존 PoC dev server를 실행하고 model URL override 주소를 연다.

```bash
cd scripts/poc/fashion-clip-browser
npm run dev -- --host 127.0.0.1
```

`http://127.0.0.1:5173/?modelUrl=http%3A%2F%2F127.0.0.1%3A8765%2Fvision_model.onnx`을 연다. `modelUrl` parameter가 없으면 고정 Hugging Face URL을 기본값으로 유지한다. Browser는 local model을 직접 가져오며 model byte, Shopify image byte, embedding을 FIT-BACK backend나 Modal로 보내지 않는다.

한 browser session에서 local serving을 검증했을 때 redirect 없이 `352,575,989` byte가 측정됐다.

| 실행 | URL resolve | WebGPU preflight | Header | Body download | Session 생성/readiness | 전체 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| Cold | 0.0 ms | 16.6 ms | 2.8 ms | 815.3 ms | 635.5 ms | 1,471.2 ms |
| Second | 0.1 ms | 0.9 ms | 4.2 ms | 603.5 ms | 283.4 ms | 893.0 ms |

두 번째 local 요청도 full GET으로 관찰됐으므로 이 PoC는 browser cache hit에 의존하지 않는다. Local static serving만으로 model readiness가 약 56~58초에서 약 0.9~1.5초로 줄었다. 두 실행 모두 WASM fallback을 준비한 상태에서 WebGPU를 사용했고 fallback/OOM은 없었다. 두 입력에 같은 승인된 local image를 사용했을 때 dimension은 `512`, finite는 `true`, normalized norm은 `1.000000`, cosine은 `1.00000000`이었다.

실제 Shopify 조회 이미지 URL을 확인하려면 직접 URL 입력에 HTTPS image URL을 최대 10개 붙여 넣고 **Run direct URL fetch and Fashion-CLIP batch**를 누른다. Browser가 URL을 직접 fetch하며 model load latency는 별도로 표시하고 전체 reranking latency에서는 제외한다.

Browser benchmark에서는 승인된 local 후보 image를 10개 이상 선택하고 **Run warm 1/3/5/10 benchmark**를 누른다. 후보 수 `1`, `3`, `5`, `10`을 한 번 warm-up한 뒤 각각 세 번 측정한다. Table에는 전체 embedding inference, image당 평균, query batch, candidate batch, cosine 계산의 median을 표시한다. Query는 1개 batch로 실행하고 각 candidate set은 하나의 `[candidateCount, 3, 224, 224]` tensor batch로 실행한다. Query batch와 candidate batch는 latency를 별도로 보고할 수 있도록 순차 실행한다. 재사용하거나 서로 다른 local image는 성능 입력일 뿐 accuracy evaluation이 아니다.

### Backend 추천과 browser reranking

기존 local query/crop image를 선택하고 local backend base URL, analysis report ID, bearer token을 입력한 뒤 **POST recommendation and rerank**를 누른다. Browser는 `POST /api/v1/analyses/{reportId}/recommendations`를 직접 호출하고 반환된 `data.browserReranking`을 검증한다. 후보는 1~30개이며 `candidateId`는 비어 있지 않고 서로 다른 opaque 값이어야 한다. `imageUrl`은 HTTP(S), `name`은 필수, seller/price/purchase metadata는 nullable, `tagSimilarity`는 유한한 `[0,1]` 값이어야 한다. Token은 현재 page에서만 유지하며 영속화하거나 product identifier로 표시하지 않는다. 승인된 scratch 전용 custom-tag baseline은 PoC URL에 `benchmarkCustomTag`를 붙여 사용한다(예: `?benchmarkCustomTag=black%20hoodie`). 공백이 아닌 최대 50자 값은 POST와 함께 일반 JSON request body로 직접 전달하며, 값이 없으면 기존 body 없는 요청을 유지한다.

각 handoff 후보 image URL은 이 browser tab에서 credential 없이 CORS와 `cache: 'no-store'`로 직접 fetch한다. 이 경로에는 browser-persistent image cache가 없다. 검증된 모든 handoff 후보는 다른 후보와 동시에 fetch→decode→preprocess pipeline을 실행하며, query crop preprocess도 후보 획득과 동시에 시작한다. UI는 단계별 wall-clock 구간과 후보별 duration 누적합을 모두 보고한다. 누적값은 CPU time이 아니며 작업이 겹치면 wall-clock보다 클 수 있다. Fetch, decode, preprocess 중 하나라도 실패하면 전체 handoff 실행을 중단하고 안전한 실패 class만 표시하며 backend 추천 결과는 유지한다. Partial ranking은 만들지 않는다. 성공한 후보는 하나의 Fashion-CLIP candidate tensor batch로 보낸다.

각 직접 candidate 요청에는 명시적인 PoC 상수 `CANDIDATE_IMAGE_FETCH_DEADLINE_MS = 30_000`을 적용한다. Deadline은 response와 image body 전송 전체를 포함하고 `AbortController`를 사용하며 retry를 추가하지 않는다. 30초는 느린 browser network 경로에서 cold 상태의 수 MiB Shopify CDN image를 허용하면서 무한 대기를 막기 위한 값이다. 기존 candidate pipeline은 병렬로 시작하므로 `candidateCount × 30초`가 아니라 요청별 상한이다. Structured baseline trace는 각 입력 index, 안전한 하나의 종료 사유(`success`, `http_error`, `timeout`, `network_error`, `aborted`, `decode_error`), 요청 시간, 집계 count, deadline, 가장 느린 요청만 기록한다. candidate ID, image URL, body, embedding은 기록하지 않는다. Timeout은 실제 요청 abort를 실행하고, blob은 decode 후 해제하며, fallback image decoder의 object URL revoke와 preprocessing cleanup의 `ImageBitmap.close()`를 유지한다. 성공이 아닌 결과는 모두 기존 fail-closed `browser-reranking unavailable` 경로를 유지하고 backend 추천 결과는 보존한다.

Backend가 성공이 아닌 응답, 잘못된 data, handoff 없음, 빈 handoff를 반환하거나 browser model/image 경로를 사용할 수 없으면 UI는 `browser-reranking unavailable`을 표시하고 간단한 backend 결과 요약을 유지한다. 따라서 handoff 누락은 추천 흐름을 깨뜨리는 browser 오류가 아니라 tag-only/backend-result 경로다.

일반 **POST recommendation and rerank browser top-10** 동작은 backend handoff pool을 변경하지 않는다. 전체 reranked 후보에 대해 `finalScore`를 계산한 뒤 `finalScore DESC`로 `min(10, rerankedCandidateCount)`를 선택한다. 선택한 relevance shortlist는 두 후보 모두 `price.amount`가 있고 통화가 같을 때만 가격 오름차순으로 표시한다. 가격 누락이나 통화 혼합은 기존 relevance 순서를 유지하며, 비교 가능한 동일 가격은 `finalScore DESC`, 다음으로 원래 handoff input index ASC를 사용한다. 별도 benchmark 동작은 동일 handoff 응답에 대해 전체 relevance/display 경로와 relevance top-10/price-display 경로를 측정한다. 두 경로 모두 전체 pool에서 relevance를 계산한다.

표시 점수는 현재 사용자 노출 추천 순위에 사용하는 browser 계산식이다.

```text
imageSimilarity = cosine(normalizedQueryEmbedding, normalizedCandidateEmbedding)
finalScore = imageSimilarity * 0.70 + tagSimilarity * 0.30
```

Cosine은 `[0,1]`로 다시 매핑하지 않고 model cosine scale을 유지한다. Relevance는 `finalScore` 내림차순으로 정렬하고 원래 handoff input index를 결정적 tie-breaker로 사용한다. 가격 정렬은 선택한 shortlist 안에서만 적용한다. Handoff 요약에는 backend HTTP status, 후보 수, metadata 완전성, image fetch 성공/실패, model readiness/runtime, Fashion-CLIP batch latency, 전체 browser reranking latency, score 범위, relevance 순서, 최종 가격 표시 순서를 기록한다. Threshold, score 제출, 영속화, 후보 tag 보강, server-side image fetch, Shopify lookup, Modal 호출은 수행하지 않는다.

Backend metadata는 기존 `ExternalProductCandidate`의 응답 시점 snapshot이며 `ProductPriceResponse`를 재사용한다. Live Shopify 가격을 보장하지 않는다. Browser는 candidate token resolve나 후속 metadata 요청을 호출하지 않는다. `purchaseUrl`이 null이면 link 없이 표시하고 URL을 합성하지 않는다.

아래 과거 local 30-vs-10 측정은 최종 점수 shortlist와 가격 표시 계약 이전에 PR #335에서 수집했다. 과거 성능 근거로만 보존하며 현재 정렬 동작의 근거가 아니다. 현재 계약 E2E 실행에서는 동일한 POST 1회 handoff 후보 수, metadata 완전성, finalScore relevance top-10, 최종 가격 표시 순서, browser reranking latency를 기록해야 한다.

| 경로 | 선택 수 | 선택 wall | Fetch 누적/wall | Decode 누적/wall | Preprocess 누적/wall | Query preprocess wall | Query inference | Candidate batch | Cosine | FinalScore | 정렬 | Render/update | 전체 reranking | Fetch 성공/실패 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| Full handoff | 30 | 0.0 ms | 7,672.3 / 421.4 ms | 904.9 / 477.6 ms | 55.9 / 55.9 ms | 60.5 ms | 37.1 ms | 226.9 ms | 0.1 ms | 0.0 ms | 0.0 ms | 1.2 ms | 869.4 ms | 30/0 |
| 과거 PR #335 tag-prefilter top 10 | 10 | 0.0 ms | 1,069.0 / 168.6 ms | 382.2 / 195.4 ms | 18.5 / 18.5 ms | 56.2 ms | 28.9 ms | 82.7 ms | 0.0 ms | 0.0 ms | 0.0 ms | 0.4 ms | 390.4 ms | 10/0 |

과거 full-path fetch wall 표본 3개는 `357.1/421.4/594.8 ms`, 과거 tag-prefilter top-10 표본은 `168.6/168.5/186.6 ms`였다. 이 값과 overlap/rank 관찰은 이전 prefilter 경로만 설명한다. 현재 최종 가격 표시 latency나 정렬 결과로 사용하면 안 된다. 현재 계약은 top-10 선택 전에 전체 pool의 새로운 finalScore rerank를 요구한다.

`npm run build`는 static app bundle만 생성한다. Model이나 image는 다운로드하지 않는다.

## Model load 진단

PoC는 URL resolve, WebGPU preflight, response header, response body download, ONNX Runtime session 생성/readiness를 각각 측정한다. 고정 model 요청은 Hugging Face resolve URL에서 최종 artifact host로 redirect되었고 동일한 `Content-Length`의 `352,575,989` byte를 반환했다.

한 browser session에서 측정한 cold load와 두 번째 load는 다음과 같다.

| 실행 | URL resolve | WebGPU preflight | Header | Body download | Session 생성/readiness | 전체 | Redirect 여부 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | --- |
| Cold | 0.0 ms | 14.1 ms | 687.3 ms | 56,002.5 ms | 888.4 ms | 57,597.0 ms | yes |
| Second | 0.1 ms | 3.4 ms | 544.4 ms | 55,083.3 ms | 569.3 ms | 56,204.9 ms | yes |

이 실행에서는 의미 있는 browser HTTP cache hit를 얻지 못했다. 두 번째 요청도 artifact 전체를 다시 다운로드했다. 근거는 WebGPU compile이나 ONNX session 생성이 아니라 redirect된 artifact network 경로/cache 동작을 가리킨다. Session 생성은 계속 1초 미만이었다. Demo 권장 방식은 일반 browser HTTP cache를 첫 번째 경로로 유지하고, 예측 가능한 최초 load latency가 필요하면 동일한 고정 artifact를 dev/demo local static server 또는 안정적인 static hosting/CDN에서 제공하는 것이다. Artifact는 Git 외부에 준비해야 하며 Service Worker, IndexedDB cache, Shopify image cache, model binary를 여기에 추가하지 않는다.

## 범위 제한

- 이 코드는 production frontend가 아니지만 handoff 검증과 70/30 reranking 동작은 사용자 노출 추천 순서의 기준 계약이다. 기존 backend 추천 endpoint를 호출하며 browser score endpoint는 추가하지 않는다.
- Query 비교 1회, backend handoff 실행 또는 제한된 `1/3/5/10` 후보 benchmark를 수행한다. Browser final score는 화면에만 표시하며 영속화하거나 backend에 제출하지 않는다.
- 실제 same-image/model 측정에는 사용자가 제공한 local image와 model에 접근할 수 있는 browser가 필요하다. 저장소가 image를 다운로드하거나 조작해 만들지 않는다.
