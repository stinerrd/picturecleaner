# Picture Cleaner

An Android app that finds the flood of forwarded junk in your messenger folders,
such as "Good morning" greeting cards, AI-generated art, memes, flyers and
screenshots, and lets you delete it in one go. Your real photos stay where they
are.

Everything runs on the phone. The app has no internet permission and sends
nothing anywhere.

## How it works

1. **Check.** The app scans the media folders of WhatsApp, Viber, Telegram and
   ChatGPT. Every image and video goes through an on-device
   [MobileCLIP2](https://huggingface.co/apple/MobileCLIP2-S0) image encoder.
   Videos are judged by three frames.
2. **Score.** The first scores come from zero-shot classification against
   text prompts ("a greeting card with flowers and text", "a photo taken with
   a smartphone camera", "a screenshot of a chat", …).
3. **Quarantine.** Candidates above a threshold you choose are moved to
   `Pictures/PictureCleaner`. They show up in your gallery as an album, and
   the original path of every file is remembered.
4. **Review.** You put back anything that isn't junk with one tap, and it
   returns to its original folder.
5. **Delete.** Everything left in quarantine is deleted permanently.
6. **Learn.** Every delete counts as a "junk" example and every restore as a
   "keep" example. A small logistic regression on top of the stored image
   embeddings is retrained on the phone after each decision, and all scores
   are updated instantly. Random photos from `DCIM/Camera` serve as built-in
   "keep" examples, so ordinary family photos are not mistaken for junk.

You can also mark files as junk straight from your gallery: select them, tap
**Share → Move to trash (Picture Cleaner)**. They go to quarantine and the model
learns from them.

### What is never touched

- `DCIM/Camera` and screenshots are not scanned. Camera photos are only used
  as "keep" examples for the model.
- WhatsApp **locked chats** (`Private`) and media **you sent** (`Sent`) are
  excluded in every WhatsApp section unless you turn on their switches. Files
  from locked chats then go to a hidden quarantine that the Gallery never shows.
  The locked chats switch appears only when the phone has locked chat media
  (WhatsApp and WhatsApp Business; other messengers keep it in app-private storage).
- Nothing is deleted without your review. The quarantine is always in between.

## Requirements

- Android 11 or newer, arm64 (practically every phone from the last years)
- "All files access" permission: WhatsApp media lives in `Android/media`,
  and moving or deleting it is impossible without this permission
- About 100 MB of storage for the app (the image model alone is 45 MB)

Languages: English, Russian, German. The app follows your phone's language and
falls back to English.

## Things to know

- **Deletion is permanent.** It bypasses the Samsung or Google recycle bin.
- Once a WhatsApp file is deleted, the image in the chat shows as unavailable,
  the same as when you delete it with any other app.
- The defaults were tuned on a phone full of Russian and German greeting cards.
  Your mix may differ. Start with a high threshold and let the model learn from
  your decisions.
- The scanned folders are fixed in `files/Sources.kt`.

## Building

There are no prebuilt APKs. Build it yourself with JDK 17 and the Android SDK
(platform 34, build-tools 34). Android Studio is not required.

```bash
echo "sdk.dir=/path/to/android-sdk" > local.properties
export JAVA_HOME=/path/to/jdk-17
./gradlew testDebugUnitTest assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The model files are committed, so torch is not needed for building.

### Regenerating the model

The image encoder (`clip_visual.onnx`) and the prompt embeddings
(`clip_text.json`) are produced by the scripts in `calibration/`:

```bash
cd calibration
uv venv .venv -p 3.10
VIRTUAL_ENV=.venv uv pip install --index-strategy unsafe-best-match \
  --extra-index-url https://download.pytorch.org/whl/cpu \
  torch torchvision open_clip_torch onnx onnxruntime pillow numpy
.venv/bin/python score.py <folder with sample images> out   # scores + contact sheets
.venv/bin/python export.py ../app/src/main/assets <any image>
```

The prompts live in `calibration/score.py`. If you change them, rerun both
scripts. Model input is RGB 0..1 without normalization, shortest side resized to
256, center crop 256×256. `ClipScorer.kt` mirrors the scoring formula of
`score.py`.

## Project layout

| Path | What |
|---|---|
| `app/.../classify/` | ONNX inference (`ClipClassifier`), zero-shot (`ClipScorer`), on-device learning (`LogisticRegression`, `Ranker`, `Scoring`) |
| `app/.../files/` | Scanned and excluded folders (`Sources`), moving, restoring and deleting (`Quarantine`) |
| `app/.../data/` | Room database: files with scores and embeddings, and your decisions (`labels`) |
| `app/.../ui/`, `MainActivity`, `ShareActivity` | Jetpack Compose UI, the "Share → trash" entry point |
| `calibration/` | Python scripts for prompt calibration and model export |

## License

The **code** in this repository is under the [MIT License](LICENSE).

The **model files** `app/src/main/assets/clip_visual.onnx` and
`app/src/main/assets/clip_text.json` are **not** under MIT. They are a Model
Derivative of Apple's MobileCLIP2-S0 (obtained through
[timm/MobileCLIP2-S0-OpenCLIP](https://huggingface.co/timm/MobileCLIP2-S0-OpenCLIP)).
The image encoder was reparameterized and converted to ONNX, and
`clip_text.json` contains text-encoder embeddings for the prompts in
`calibration/score.py`. No retraining or fine-tuning was done.

> Apple Machine Learning Research Model is licensed under the Apple Machine
> Learning Research Model License Agreement.

That license ([LICENSE-MODEL](LICENSE-MODEL)) permits use **for non-commercial
research purposes only**. Read it before you use or redistribute the app. If
you need different terms, swap the model in `calibration/` for an openly
licensed CLIP model and rerun the export. Apple is not affiliated with this
project and does not endorse it.
