from pathlib import Path

import modal

APP_NAME = "fitback-fashion-clip-poc"
MODEL_ID = "patrickjohncyh/fashion-clip"
MODEL_REVISION = "83cb9b65be402bbdb4d0e1b84bd53555028bfed8"
MODEL_DIR = "/models/fashion-clip"
MAX_BATCH_SIZE = 8


def download_model() -> None:
    from huggingface_hub import snapshot_download

    snapshot_download(
        repo_id=MODEL_ID,
        revision=MODEL_REVISION,
        local_dir=MODEL_DIR,
    )


core_file = Path(__file__).with_name("modal_fashion_clip_core.py")
image = (
    modal.Image.debian_slim(python_version="3.12")
    .uv_pip_install(
        "fastapi==0.141.1",
        "huggingface-hub==1.27.0",
        "Pillow==12.3.0",
        "torch==2.13.0",
        "transformers==5.14.1",
    )
    .run_function(download_model, timeout=20 * 60)
    .add_local_file(core_file, "/root/modal_fashion_clip_core.py")
)
app = modal.App(APP_NAME)


@app.cls(image=image, gpu="T4", timeout=5 * 60)
class FashionClipEndpoint:
    @modal.enter()
    def load_model(self) -> None:
        import torch
        from transformers import AutoProcessor, CLIPModel

        self.device = torch.device("cuda")
        self.processor = AutoProcessor.from_pretrained(MODEL_DIR, local_files_only=True)
        self.model = CLIPModel.from_pretrained(MODEL_DIR, local_files_only=True).to(
            self.device
        )
        self.model.eval()

    @modal.fastapi_endpoint(
        method="POST",
        requires_proxy_auth=True,
    )
    def embed(self, payload: dict[str, object]) -> dict[str, object]:
        import torch
        from fastapi import HTTPException
        from modal_fashion_clip_core import normalize_embeddings, parse_image_batch

        try:
            images = parse_image_batch(payload, max_batch_size=MAX_BATCH_SIZE)
        except (TypeError, ValueError) as error:
            raise HTTPException(status_code=400, detail=str(error)) from error

        inputs = self.processor(images=images, return_tensors="pt")
        pixel_values = inputs["pixel_values"].to(self.device)
        with torch.inference_mode():
            feature_output = self.model.get_image_features(pixel_values=pixel_values)

        feature_vectors = feature_output.pooler_output.detach().float().cpu().tolist()
        try:
            embeddings = normalize_embeddings(feature_vectors)
        except ValueError as error:
            raise HTTPException(status_code=500, detail=str(error)) from error

        if len(embeddings) != len(images):
            raise HTTPException(
                status_code=500,
                detail="model returned an unexpected embedding count",
            )

        return {"model": MODEL_ID, "embeddings": embeddings}
