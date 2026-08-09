# Modal Fashion-CLIP image embedding PoC

This PoC deploys a protected Modal GPU Web Function for ordered image batch
embedding with `patrickjohncyh/fashion-clip`. It does not connect to the Java
backend.

## Contract

`POST` request, with 1 to 8 JPEG, PNG, or WebP images. Each declared content
type must match the decoded image format; each decoded file is limited to 5 MiB
and 25 million pixels:

```json
{
  "images": [
    {
      "contentType": "image/jpeg",
      "dataBase64": "..."
    }
  ]
}
```

Response:

```json
{
  "model": "patrickjohncyh/fashion-clip",
  "embeddings": [
    [0.01, -0.02]
  ]
}
```

Embedding index `i` corresponds to request image index `i`. Every vector is
checked for finite values and non-zero norm, then explicitly L2-normalized for
direct cosine similarity use.

## Why explicit normalization

The Transformers `CLIPModel.get_image_features()` API applies the learned image
projection but does not L2-normalize its result. The full CLIP forward path
normalizes image and text projections before computing similarity. This endpoint
uses `get_image_features()` for image-only inference and therefore performs the
same L2 step explicitly, rejecting zero-norm or non-finite output rather than
returning it.

References:

- [Transformers CLIP API](https://huggingface.co/docs/transformers/model_doc/clip)
- [Fashion-CLIP model](https://huggingface.co/patrickjohncyh/fashion-clip)

## Modal runtime choices

- `@modal.fastapi_endpoint`, `@modal.enter`, `Image.run_function`, and
  `Image.add_local_file` are the current Modal APIs; deprecated
  `@modal.web_endpoint` and `__enter__` are not used.
- Model revision `83cb9b65be402bbdb4d0e1b84bd53555028bfed8` is downloaded into
  the Modal Image during build. The processor and model are loaded once per
  container in `@modal.enter` and reused for later requests.
- One processor call and one `get_image_features()` call receive the full
  logical request batch. The conservative PoC maximum is 8; there is no retry,
  automatic sub-batching, or 50/100/200 benchmark policy.
- A single T4 is the starting GPU. Modal currently supports T4, and its 16 GB
  memory is ample for this approximately 0.2B-parameter, 605 MB checkpoint and
  the bounded batch while remaining Modal's lowest-cost listed GPU option.
- `requires_proxy_auth=True` keeps the Web Function from being public without
  authentication. Proxy credentials are supplied only at call time.

References:

- [Modal Web Functions](https://modal.com/docs/guide/webhooks)
- [Modal container lifecycle](https://modal.com/docs/guide/lifecycle-functions)
- [Modal GPU support](https://modal.com/docs/guide/gpu)
- [Modal Proxy Tokens](https://modal.com/docs/guide/webhook-proxy-auth)

## Local tests

```bash
python3 -m venv /tmp/fitback-modal-fashion-clip-venv
/tmp/fitback-modal-fashion-clip-venv/bin/pip install \
  -r scripts/poc/fashion-clip/requirements-modal-endpoint.txt
/tmp/fitback-modal-fashion-clip-venv/bin/python -m unittest discover \
  -s scripts/poc/fashion-clip -p 'test_modal_fashion_clip_core.py' -v
```

## Deploy and smoke

Authenticate the Modal CLI for the target workspace, create a Proxy Token, and
deploy. Do not commit the generated API token or Proxy Token values.

```bash
/tmp/fitback-modal-fashion-clip-venv/bin/modal setup
/tmp/fitback-modal-fashion-clip-venv/bin/modal workspace proxy-tokens create
/tmp/fitback-modal-fashion-clip-venv/bin/modal deploy \
  scripts/poc/fashion-clip/modal_fashion_clip_endpoint.py
```

Copy the deployed Web Function URL from the deploy output. Export the Proxy
Token pair only in the calling shell, then run the smoke command with one or two
local images:

```bash
export MODAL_PROXY_TOKEN_ID='wk-...'
export MODAL_PROXY_TOKEN_SECRET='ws-...'
/tmp/fitback-modal-fashion-clip-venv/bin/python \
  scripts/poc/fashion-clip/smoke_modal_fashion_clip.py \
  'https://YOUR-WEB-FUNCTION.modal.run' image-1.jpg image-2.webp
```

The smoke command reports only model name, embedding count, dimension, finite
status, and L2 norms. It does not print the vectors or credentials.
