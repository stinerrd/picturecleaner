"""Экспорт MobileCLIP2-S0 для телефона.

Пишет в app/src/main/assets:
- clip_visual.onnx — image encoder, вход [1,3,256,256] RGB 0..1, выход
  ненормированный эмбеддинг;
- clip_text.json — нормированные эмбеддинги промптов по группам и logit scale.
Промпты берутся из score.py, чтобы калибровка и приложение не разъехались.
"""
import json
import sys
from pathlib import Path

import numpy as np
import onnxruntime as ort
import open_clip
import torch
from PIL import Image
from timm.models import reparameterize_model

from score import DOCUMENT_PROMPTS, GENERATED_PROMPTS, MODEL, PHOTO_PROMPTS, PRETRAINED


def main(assets: Path, probe_image: Path) -> None:
    assets.mkdir(parents=True, exist_ok=True)
    model, _, preprocess = open_clip.create_model_and_transforms(MODEL, pretrained=PRETRAINED)
    model.eval()
    model = reparameterize_model(model)
    tokenizer = open_clip.get_tokenizer(MODEL)

    groups = {"photo": PHOTO_PROMPTS, "generated": GENERATED_PROMPTS, "document": DOCUMENT_PROMPTS}
    text_out = {"logitScale": 100.0, "groups": {}}
    with torch.no_grad():
        for name, prompts in groups.items():
            t = model.encode_text(tokenizer(prompts))
            t = t / t.norm(dim=-1, keepdim=True)
            text_out["groups"][name] = [[round(float(v), 6) for v in row] for row in t.numpy()]
    (assets / "clip_text.json").write_text(json.dumps(text_out))

    onnx_path = assets / "clip_visual.onnx"
    dummy = torch.rand(1, 3, 256, 256)
    torch.onnx.export(
        model.visual, dummy, str(onnx_path),
        input_names=["image"], output_names=["embedding"],
        opset_version=17, dynamo=False,
    )

    x = preprocess(Image.open(probe_image).convert("RGB")).unsqueeze(0)
    with torch.no_grad():
        ref = model.visual(x).numpy()
    got = ort.InferenceSession(str(onnx_path)).run(None, {"image": x.numpy()})[0]
    cos = float((ref @ got.T) / (np.linalg.norm(ref) * np.linalg.norm(got)))
    print(f"onnx {onnx_path.stat().st_size / 1e6:.1f} MB, cosine torch/onnx = {cos:.6f}")


if __name__ == "__main__":
    main(Path(sys.argv[1]), Path(sys.argv[2]))
