import json
import sys
from pathlib import Path

import numpy as np
import open_clip
import torch
from PIL import Image, ImageDraw, ImageOps

MODEL = "MobileCLIP2-S0"
PRETRAINED = "dfndr2b"

PHOTO_PROMPTS = [
    "a photo taken with a smartphone camera",
    "a casual snapshot of people",
    "a selfie",
    "a family photo",
    "a photo of a landscape",
    "a photo of food on a table",
    "a photo of a child",
    "a photo of a room",
    "a photo of a street",
    "a photo of a pet",
]
GENERATED_PROMPTS = [
    "an AI generated image",
    "a digital illustration",
    "a greeting card with flowers and text",
    "a good morning greeting image with text",
    "a religious greeting card",
    "a cartoon",
    "a meme with text",
    "fantasy digital art",
    "a 3D render",
    "a poster with text",
    "a festive holiday greeting picture",
    "a cute animal with flowers, decorative picture",
    "a beautiful still life with flowers and a cup, greeting picture",
    "a picture with a quote or wish written in decorative font",
]
DOCUMENT_PROMPTS = [
    "a screenshot of a phone app",
    "a screenshot of a chat conversation",
    "a scanned document or letter",
    "a photo of a printed page of text",
    "a table or schedule",
    "a map screenshot",
    "a website screenshot",
    "a receipt or invoice",
    "a QR code",
]


def main(data_dir: Path, out_dir: Path) -> None:
    out_dir.mkdir(parents=True, exist_ok=True)
    model, _, preprocess = open_clip.create_model_and_transforms(MODEL, pretrained=PRETRAINED)
    tokenizer = open_clip.get_tokenizer(MODEL)
    model.eval()

    with torch.no_grad():
        text = model.encode_text(tokenizer(PHOTO_PROMPTS + GENERATED_PROMPTS + DOCUMENT_PROMPTS))
        text = text / text.norm(dim=-1, keepdim=True)

    files = sorted(p for p in data_dir.iterdir() if p.is_file())
    rows = []
    embeddings = []
    for p in files:
        try:
            img = Image.open(p).convert("RGB")
        except Exception as e:
            print(f"skip {p.name}: {e}", file=sys.stderr)
            continue
        with torch.no_grad():
            emb = model.encode_image(preprocess(img).unsqueeze(0))
            emb = emb / emb.norm(dim=-1, keepdim=True)
        logits = (100.0 * emb @ text.T).softmax(dim=-1)[0].numpy()
        n, g = len(PHOTO_PROMPTS), len(GENERATED_PROMPTS)
        score = float(logits[n:n + g].sum())
        top = (PHOTO_PROMPTS + GENERATED_PROMPTS + DOCUMENT_PROMPTS)[int(logits.argmax())]
        rows.append({"file": p.name, "score": round(score, 3), "top": top})
        embeddings.append(emb[0].numpy())

    rows.sort(key=lambda r: r["score"], reverse=True)
    (out_dir / "scores.json").write_text(json.dumps(rows, ensure_ascii=False, indent=1))
    np.save(out_dir / "embeddings.npy", np.stack(embeddings))
    (out_dir / "embedding_files.json").write_text(json.dumps([p.name for p in files]))

    thumb, cols, per_sheet = 160, 10, 60
    for s in range(0, len(rows), per_sheet):
        chunk = rows[s:s + per_sheet]
        sheet = Image.new("RGB", (cols * thumb, ((len(chunk) + cols - 1) // cols) * (thumb + 18)), "white")
        draw = ImageDraw.Draw(sheet)
        for i, r in enumerate(chunk):
            im = ImageOps.fit(Image.open(data_dir / r["file"]).convert("RGB"), (thumb, thumb))
            x, y = (i % cols) * thumb, (i // cols) * (thumb + 18)
            sheet.paste(im, (x, y))
            draw.text((x + 2, y + thumb + 2), f"{s + i}:{r['score']:.2f} {r['file'][4:14]}", fill="black")
        sheet.save(out_dir / f"sheet_{s // per_sheet:02d}.jpg", quality=80)

    for r in rows:
        print(f"{r['score']:.3f}  {r['file']:40s}  {r['top']}")


if __name__ == "__main__":
    main(Path(sys.argv[1]), Path(sys.argv[2]))
