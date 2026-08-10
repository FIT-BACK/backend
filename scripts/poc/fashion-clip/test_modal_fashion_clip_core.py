import base64
import io
import math
import unittest
from typing import cast

from modal_fashion_clip_core import normalize_embeddings, parse_image_batch
from PIL import Image


def image_base64(image_format: str, color: tuple[int, int, int]) -> str:
    buffer = io.BytesIO()
    Image.new("RGB", (2, 2), color).save(buffer, format=image_format)
    return base64.b64encode(buffer.getvalue()).decode("ascii")


class ParseImageBatchTest(unittest.TestCase):
    def test_parses_images_in_request_order(self) -> None:
        payload = {
            "images": [
                {
                    "contentType": "image/png",
                    "dataBase64": image_base64("PNG", (255, 0, 0)),
                },
                {
                    "contentType": "image/jpeg",
                    "dataBase64": image_base64("JPEG", (0, 0, 255)),
                },
            ]
        }

        images = parse_image_batch(payload, max_batch_size=8)

        self.assertEqual(len(images), 2)
        red_pixel = cast(tuple[int, int, int], images[0].getpixel((0, 0)))
        blue_pixel = cast(tuple[int, int, int], images[1].getpixel((0, 0)))
        self.assertGreater(red_pixel[0], 240)
        self.assertGreater(blue_pixel[2], 240)

    def test_rejects_null_or_empty_images(self) -> None:
        payloads: list[dict[str, object]] = [
            {},
            {"images": None},
            {"images": []},
        ]
        for payload in payloads:
            with (
                self.subTest(payload=payload),
                self.assertRaisesRegex(ValueError, "images must not be null or empty"),
            ):
                parse_image_batch(payload, max_batch_size=8)

    def test_rejects_batch_above_limit(self) -> None:
        image = {
            "contentType": "image/png",
            "dataBase64": image_base64("PNG", (1, 2, 3)),
        }

        with self.assertRaisesRegex(
            ValueError, "images must contain at most 1 element"
        ):
            parse_image_batch({"images": [image, image]}, max_batch_size=1)

    def test_rejects_unsupported_content_type(self) -> None:
        payload = {
            "images": [
                {
                    "contentType": "image/gif",
                    "dataBase64": image_base64("PNG", (1, 2, 3)),
                }
            ]
        }

        with self.assertRaisesRegex(
            ValueError,
            r"images\[0\]\.contentType must be image/jpeg, image/png, or image/webp",
        ):
            parse_image_batch(payload, max_batch_size=8)

    def test_rejects_declared_content_type_that_does_not_match_image(self) -> None:
        payload = {
            "images": [
                {
                    "contentType": "image/png",
                    "dataBase64": image_base64("GIF", (1, 2, 3)),
                }
            ]
        }

        with self.assertRaisesRegex(
            ValueError,
            r"images\[0\]\.contentType does not match decoded image format",
        ):
            parse_image_batch(payload, max_batch_size=8)

    def test_rejects_invalid_base64(self) -> None:
        payload = {
            "images": [{"contentType": "image/png", "dataBase64": "not-base64%%%"}]
        }

        with self.assertRaisesRegex(
            ValueError, r"images\[0\]\.dataBase64 must be valid base64"
        ):
            parse_image_batch(payload, max_batch_size=8)

    def test_rejects_bytes_that_are_not_decodable_as_image(self) -> None:
        payload = {
            "images": [
                {
                    "contentType": "image/png",
                    "dataBase64": base64.b64encode(b"not an image").decode("ascii"),
                }
            ]
        }

        with self.assertRaisesRegex(
            ValueError, r"images\[0\]\.dataBase64 must decode to an image"
        ):
            parse_image_batch(payload, max_batch_size=8)

    def test_rejects_image_file_above_byte_limit(self) -> None:
        payload = {
            "images": [
                {
                    "contentType": "image/png",
                    "dataBase64": image_base64("PNG", (1, 2, 3)),
                }
            ]
        }

        with self.assertRaisesRegex(
            ValueError, r"images\[0\]\.dataBase64 exceeds 1 decoded bytes"
        ):
            parse_image_batch(payload, max_batch_size=8, max_image_bytes=1)

    def test_rejects_image_above_pixel_limit(self) -> None:
        payload = {
            "images": [
                {
                    "contentType": "image/png",
                    "dataBase64": image_base64("PNG", (1, 2, 3)),
                }
            ]
        }

        with self.assertRaisesRegex(
            ValueError, r"images\[0\] exceeds 3 decoded pixels"
        ):
            parse_image_batch(payload, max_batch_size=8, max_image_pixels=3)


class NormalizeEmbeddingsTest(unittest.TestCase):
    def test_normalizes_finite_vectors_and_preserves_order(self) -> None:
        normalized = normalize_embeddings([[3.0, 4.0], [0.0, -2.0]])

        self.assertEqual(normalized[0], [0.6, 0.8])
        self.assertEqual(normalized[1], [0.0, -1.0])
        self.assertTrue(
            all(
                math.isclose(math.dist(vector, [0.0] * len(vector)), 1.0)
                for vector in normalized
            )
        )

    def test_rejects_zero_norm_vector(self) -> None:
        with self.assertRaisesRegex(ValueError, "embedding at index 0 has zero norm"):
            normalize_embeddings([[0.0, 0.0]])

    def test_rejects_non_finite_vector(self) -> None:
        for value in (math.nan, math.inf, -math.inf):
            with (
                self.subTest(value=value),
                self.assertRaisesRegex(
                    ValueError, "embedding at index 0 contains non-finite values"
                ),
            ):
                normalize_embeddings([[1.0, value]])


if __name__ == "__main__":
    unittest.main()
