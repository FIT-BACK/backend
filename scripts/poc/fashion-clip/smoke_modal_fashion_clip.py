import argparse
import base64
import json
import math
import mimetypes
import os
import urllib.request
from pathlib import Path

SUPPORTED_CONTENT_TYPES = {"image/jpeg", "image/png", "image/webp"}


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Call the protected Modal Fashion-CLIP PoC endpoint."
    )
    parser.add_argument("endpoint_url")
    parser.add_argument("images", nargs="+", type=Path)
    args = parser.parse_args()

    token_id = required_environment("MODAL_PROXY_TOKEN_ID")
    token_secret = required_environment("MODAL_PROXY_TOKEN_SECRET")
    request_body = json.dumps(
        {"images": [encode_image(path) for path in args.images]}
    ).encode("utf-8")
    request = urllib.request.Request(
        args.endpoint_url,
        data=request_body,
        headers={
            "Content-Type": "application/json",
            "Modal-Key": token_id,
            "Modal-Secret": token_secret,
        },
        method="POST",
    )

    with urllib.request.urlopen(request, timeout=300) as response:
        payload = json.load(response)

    embeddings = payload.get("embeddings")
    if not isinstance(embeddings, list) or len(embeddings) != len(args.images):
        raise RuntimeError(
            "response embedding count does not match request image count"
        )
    dimensions = []
    norms = []
    for index, embedding in enumerate(embeddings):
        if not isinstance(embedding, list) or not embedding:
            raise RuntimeError(f"embedding at index {index} is not a numeric vector")
        vector = [float(value) for value in embedding]
        if not all(math.isfinite(value) for value in vector):
            raise RuntimeError(f"embedding at index {index} is non-finite")
        dimensions.append(len(vector))
        norms.append(math.sqrt(math.fsum(value * value for value in vector)))

    if len(set(dimensions)) != 1:
        raise RuntimeError("response embeddings have inconsistent dimensions")
    if not all(math.isclose(norm, 1.0, rel_tol=1e-5, abs_tol=1e-5) for norm in norms):
        raise RuntimeError("response embeddings are not L2-normalized")

    print(
        json.dumps(
            {
                "model": payload.get("model"),
                "embeddingCount": len(embeddings),
                "embeddingDimension": dimensions[0],
                "finite": True,
                "l2Norms": norms,
            },
            indent=2,
        )
    )


def required_environment(name: str) -> str:
    value = os.environ.get(name)
    if not value:
        raise RuntimeError(f"{name} must be set")
    return value


def encode_image(path: Path) -> dict[str, str]:
    content_type, _ = mimetypes.guess_type(path.name)
    if content_type not in SUPPORTED_CONTENT_TYPES:
        raise ValueError(f"unsupported image content type for {path}")
    return {
        "contentType": content_type,
        "dataBase64": base64.b64encode(path.read_bytes()).decode("ascii"),
    }


if __name__ == "__main__":
    main()
