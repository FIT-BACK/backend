import base64
import binascii
import io
import math
from collections.abc import Mapping, Sequence
from typing import Any

from PIL import Image, UnidentifiedImageError

SUPPORTED_CONTENT_TYPES = frozenset({"image/jpeg", "image/png", "image/webp"})
CONTENT_TYPE_FORMATS = {
    "image/jpeg": "JPEG",
    "image/png": "PNG",
    "image/webp": "WEBP",
}
DEFAULT_MAX_IMAGE_BYTES = 5 * 1024 * 1024
DEFAULT_MAX_IMAGE_PIXELS = 25_000_000


def parse_image_batch(
    payload: object,
    *,
    max_batch_size: int,
    max_image_bytes: int = DEFAULT_MAX_IMAGE_BYTES,
    max_image_pixels: int = DEFAULT_MAX_IMAGE_PIXELS,
) -> list[Image.Image]:
    if max_batch_size < 1:
        raise ValueError("max_batch_size must be positive")
    if max_image_bytes < 1:
        raise ValueError("max_image_bytes must be positive")
    if max_image_pixels < 1:
        raise ValueError("max_image_pixels must be positive")
    if not isinstance(payload, Mapping):
        raise TypeError("request body must be a JSON object")

    images = payload.get("images")
    if not isinstance(images, list) or not images:
        raise ValueError("images must not be null or empty")
    if len(images) > max_batch_size:
        raise ValueError(
            f"images must contain at most {max_batch_size} "
            f"{'element' if max_batch_size == 1 else 'elements'}"
        )

    return [
        _decode_image(
            item,
            index,
            max_image_bytes=max_image_bytes,
            max_image_pixels=max_image_pixels,
        )
        for index, item in enumerate(images)
    ]


def _decode_image(
    item: object,
    index: int,
    *,
    max_image_bytes: int,
    max_image_pixels: int,
) -> Image.Image:
    if not isinstance(item, Mapping):
        raise TypeError(f"images[{index}] must be a JSON object")

    content_type = item.get("contentType")
    if not isinstance(content_type, str) or not content_type.strip():
        raise ValueError(f"images[{index}].contentType must not be null or blank")
    normalized_content_type = content_type.strip().lower()
    if normalized_content_type not in SUPPORTED_CONTENT_TYPES:
        raise ValueError(
            f"images[{index}].contentType must be image/jpeg, image/png, or image/webp"
        )

    data_base64 = item.get("dataBase64")
    if not isinstance(data_base64, str) or not data_base64.strip():
        raise ValueError(f"images[{index}].dataBase64 must not be null or blank")

    try:
        image_bytes = base64.b64decode(data_base64.strip(), validate=True)
    except (binascii.Error, ValueError) as error:
        raise ValueError(f"images[{index}].dataBase64 must be valid base64") from error
    if len(image_bytes) > max_image_bytes:
        raise ValueError(
            f"images[{index}].dataBase64 exceeds {max_image_bytes} decoded bytes"
        )

    try:
        image = Image.open(io.BytesIO(image_bytes))
    except (
        Image.DecompressionBombError,
        UnidentifiedImageError,
        OSError,
        ValueError,
    ) as error:
        raise ValueError(
            f"images[{index}].dataBase64 must decode to an image"
        ) from error

    with image:
        if image.format != CONTENT_TYPE_FORMATS[normalized_content_type]:
            raise ValueError(
                f"images[{index}].contentType does not match decoded image format"
            )
        if image.width * image.height > max_image_pixels:
            raise ValueError(
                f"images[{index}] exceeds {max_image_pixels} decoded pixels"
            )
        try:
            image.load()
            return image.convert("RGB")
        except (OSError, ValueError) as error:
            raise ValueError(
                f"images[{index}].dataBase64 must decode to an image"
            ) from error


def normalize_embeddings(
    embeddings: Sequence[Sequence[Any]],
) -> list[list[float]]:
    normalized_embeddings: list[list[float]] = []
    for index, embedding in enumerate(embeddings):
        if not embedding:
            raise ValueError(f"embedding at index {index} must not be empty")
        try:
            vector = [float(value) for value in embedding]
        except (TypeError, ValueError) as error:
            raise ValueError(
                f"embedding at index {index} contains non-numeric values"
            ) from error

        if not all(math.isfinite(value) for value in vector):
            raise ValueError(f"embedding at index {index} contains non-finite values")

        norm = math.sqrt(math.fsum(value * value for value in vector))
        if norm == 0.0:
            raise ValueError(f"embedding at index {index} has zero norm")
        if not math.isfinite(norm):
            raise ValueError(f"embedding at index {index} has non-finite norm")

        normalized = [value / norm for value in vector]
        if not all(math.isfinite(value) for value in normalized):
            raise ValueError(
                f"embedding at index {index} normalized to non-finite values"
            )
        normalized_embeddings.append(normalized)

    return normalized_embeddings
