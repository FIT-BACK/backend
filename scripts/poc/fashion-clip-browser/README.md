# Browser Fashion-CLIP PoC

This isolated demo accepts exactly two local files: one query image and one candidate image. It runs the image encoder in the browser, shows the two embedding dimensions, finite status, L2 norms, cosine similarity, model-load latency, inference latency, and the selected execution-provider order.

It does not call FIT-BACK backend, Shopify, Modal, or any application API. Local image bytes, model output, and embeddings remain in the browser tab.

## Runtime and model

- Runtime: `onnxruntime-web@1.27.0`.
- Preferred execution provider: WebGPU when the browser exposes `navigator.gpu`.
- Fallback: ONNX Runtime WebAssembly (`wasm`) if WebGPU is unavailable or session creation fails.
- Model: [`Frapic/fashion-clip-onnx`](https://huggingface.co/Frapic/fashion-clip-onnx), specifically its `vision_model.onnx` export.
- Pinned model revision: `12eb79267363fd03b8983a25903cd9097b1ec76c` (`vision_model.onnx`, 352,575,989 bytes at verification time).
- Provenance: the model card identifies this as an ONNX export of [`patrickjohncyh/fashion-clip`](https://huggingface.co/patrickjohncyh/fashion-clip), with a 512-dimensional image output. The demo does not substitute a generic CLIP checkpoint.
- The approximately 353 MB model file is fetched at runtime by the browser and is intentionally not stored in Git.

The model output is not L2-normalized by the artifact. The demo reports the raw output norm and computes cosine from the raw embeddings, which is mathematically equivalent to comparing normalized vectors.

## Run

```bash
npm install
npm test
npm run dev
```

Open the printed local URL, select two local JPEG/PNG/WEBP images, and click **Load model and compare**. To check the same-image invariant, select the same local file in both inputs; the cosine should be approximately `1` subject to runtime floating-point behavior.

`npm run build` creates only the static app bundle. It does not download the model or any image.

## Scope limits

- This is not production frontend code and has no backend endpoint.
- It performs one query plus one candidate comparison only; no candidate benchmark, threshold, tag similarity, or final score is included.
- A real same-image/model measurement requires a user-provided local image and a browser with model access. No image is downloaded or fabricated by this repository.
