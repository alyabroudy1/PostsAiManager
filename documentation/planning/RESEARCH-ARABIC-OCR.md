# Research: offline on-device Arabic OCR for PostsAiManager

Date of research: 2026-09-30. Web facts come from the sources at the end. Anything I could not verify is marked UNVERIFIED. No benchmark was run on the device, so every speed figure for the Galaxy S23 is an estimate until the spike runs.

## 1. Findings in one paragraph

- ML Kit has no Arabic recognizer. Text Recognition v2 covers Latin, Chinese, Devanagari, Japanese and Korean. The ML Kit release notes show no text-recognition update in 2025–2026. The last Android artifact is `text-recognition:16.0.1` (Aug 2024), and the repo already uses that version.
- The best fit is **PaddleOCR PP-OCRv5 mobile: shared text detector plus a per-script recognizer, run on ONNX Runtime**. The app already ships `onnxruntime-android` 1.24.3. The Arabic models are `PP-OCRv5_mobile_det` (about 4.8 MB) and `arabic_PP-OCRv5_mobile_rec` (about 8 MB). Both are Apache-2.0. A community ONNX export already exists.
- PP-OCRv6 (released 2026-06-11) does not help here. It advertises 50 languages (Chinese, English, Japanese and 46 Latin-script languages). Arabic is not listed, and a GitHub issue reports an empty Arabic dictionary. Stay on v5 for Arabic.
- Tesseract is the fallback. Its licence is fine, but its Arabic accuracy on real phone photos is weak and it is slow. PaddleOCR-VL and Qari are too heavy for a per-page OCR step. The VLM route is a later option for hard pages.

## 2. Comparison table

| Option | Licence (commercial) | Size | Printed Arabic accuracy | Speed per A4, flagship phone (estimate unless noted) | Line/word boxes and RTL | Mixed Arabic/Latin/digits | Integration effort |
|---|---|---|---|---|---|---|---|
| ML Kit Latin (today) | Google ML Kit terms, free on-device | bundled | Cannot read Arabic script | fast (current pipeline) | blocks/lines/elements with boxes | Latin only | already done |
| ML Kit Arabic | does not exist (verified against v2 docs and release notes) | n/a | n/a | n/a | n/a | n/a | not possible |
| Tesseract via tesseract4android (`ara` / `ara_best`) | Apache-2.0 library, Tesseract Apache-2.0, traineddata Apache-2.0 | `ara` fast about 1.5 MB, `ara_best` about 14 MB (UNVERIFIED exact), plus `eng` and `deu` | A published printed-Arabic benchmark measured CER 0.44 and WER 0.89 for Tesseract (a hard test set, but a warning). Sensitive to skew, blur and low contrast | about 3–10 s per page (UNVERIFIED; it is single-threaded per instance and `best` is slow) | Yes: word, line and block boxes via `ResultIterator`. RTL order is handled by the engine. Bidi with digits is sometimes scrambled | Can load `ara+eng+deu` together. Accuracy drops on mixed lines, and it is slowest that way | Low to medium. Native AAR, and a new dependency |
| **PaddleOCR PP-OCRv5 mobile, det + Arabic rec, ONNX Runtime** | Apache-2.0 (models and PaddleOCR code) | det about 4.8 MB, rec about 8.0 MB, dictionary 749 classes | Official: 81.27% line accuracy on the Arabic eval set (a line counts as wrong if any character is wrong). A Paddle-family VLM paper reports much better edit distance, but that is the VLM | det + rec mobile: about 1–3 s per A4 on a Snapdragon 8 Gen 2 CPU (estimate from mobile ONNX reports; must be measured) | Detector returns quadrilateral boxes for each text line. Reading order and RTL are ours to do, and we have a layout engine for that. Recognizer outputs logical-order text, so RTL strings are correct as stored | Arabic dictionary includes Latin letters, digits and punctuation, so mixed lines work. Example outputs show Arabic with English words. German umlauts are UNVERIFIED and must be tested | Medium. Needs pre/post-processing in Kotlin: DB detector postprocess, CTC decode. No new native dependency |
| PP-OCRv6 (tiny/small/medium) | Apache-2.0 expected (UNVERIFIED for v6) | 1.5M–34.5M params | Arabic not supported at release: not in the 50-language list, and a GitHub issue reports an empty dictionary | faster than v5 | same as v5 | n/a | Not usable for Arabic today. Re-check later. It could replace the Latin detector or recognizer for German and English |
| PaddleOCR-VL 1.5 / 1.6 (0.5B–0.9B, llama.cpp GGUF + mmproj) | Apache-2.0 | 0.5B–0.9B, several hundred MB to about 1 GB quantised | Claims 109 languages including Arabic. Reported Arabic edit distance 0.122, the best of the compared systems | Seconds to tens of seconds per page on a phone CPU or GPU (UNVERIFIED; run it on the S23 to find out). Needs about 1 MP of image tokens per call | Boxes come from the layout pipeline and "spotting" mode. Plain mode returns text only | Good | High. Needs llama.cpp multimodal support (mtmd). The app has llama.cpp for chat, but it is another model to download and keep loaded |
| Qari-OCR (Qwen2-VL-2B fine-tune) | Follows Qwen2-VL licence, to be checked for commercial use | about 2B params, 1.5–4 GB | Best published Arabic quality (CER 0.061 on diacritic-heavy text) | too slow for a page-per-scan flow | text only, no line boxes | good | High. Not recommended |
| EasyOCR Arabic | Apache-2.0 | about 50 MB PyTorch | Worse than Tesseract in the benchmark above (CER 0.79) | n/a | n/a | n/a | PyTorch only. Rejected |

## 3. Recommendation

**Adopt PP-OCRv5 mobile on ONNX Runtime as the second engine, with Tesseract as the contingency.**

Reasons:

1. Licence is clean for a commercial app: Apache-2.0 for code and weights.
2. ONNX Runtime is already a dependency (`gradle/libs.versions.toml` line 2, `onnxruntime-android` 1.24.3, used in `core/ai/embed`). No new native library. Release builds are arm64-v8a only, so there is no size penalty from extra ABIs.
3. Small: about 13 MB for det plus Arabic rec. This fits the existing "model files" download pattern used for the embedding model (`EmbeddingModelFiles`), so the models can be downloaded on first need rather than bundled.
4. The same det model serves every script. That makes the "shared detector plus script-keyed recogniser" design natural: adding Cyrillic, Greek, Devanagari and so on means adding one recogniser file plus its dictionary.
5. It gives text lines with boxes, which is what `OcrBlock` and `DocumentLayout` need.

What I do not recommend as the first step: VLMs (too slow and too large for every page), and waiting for ML Kit (no sign of Arabic).

Honest caveat: 81% line accuracy on the official eval set is not great. Invoices with long numbers and dates will need a check. The pipeline already has an AI repair and verification stage (`OcrRepairVerifierTest`, extraction v2), which fits the "AI decides, code verifies" rule. The spike below decides whether PP-OCRv5 Arabic is good enough or whether Tesseract `ara_best` or a VLM pass is needed for low-confidence pages.

## 4. Choosing the recogniser per page (no static language rules)

Options considered:

| Option | Pros | Cons |
|---|---|---|
| A. Run Latin ML Kit first, route to Arabic on low coverage | no new model, cheap for German/English (the common case) | "low coverage" needs a threshold. ML Kit on Arabic often returns junk fragments with moderate confidence, so the signal is noisy. Double OCR cost for Arabic pages |
| B. Shared detector, then a script classifier per crop, then the matching recogniser | works per line, so mixed pages are handled. Extensible. Purely model-driven | needs a script classifier model (small CNN or a cheap trick below) |
| C. Whole-page script classifier | one decision per page | fails on bilingual letters, which are common (Arabic with German header) |
| D. Run every recogniser on every line, keep the higher-confidence result | simplest, no classifier | cost scales with the number of scripts. CTC confidences from different models are not comparable without calibration |

**Recommended: B with D as the bootstrap.**

- Stage 1: the shared PP-OCRv5 detector finds text lines on the page image (script-agnostic).
- Stage 2: a `ScriptClassifier` port labels each line crop with a script key (`LATIN`, `ARABIC`, later `CYRILLIC`, `HAN`, ...). Implementation, in order of effort:
  1. Use the existing PP-OCRv5 recognisers as the classifier: for a small sample of lines per page (for example the 6 largest lines), run the Latin and Arabic recognisers and compare mean CTC probability and the fraction of characters belonging to each recogniser's own script (Unicode script property of the output, which is data read from the output, not a language keyword list). The winning script is applied to the whole page, then corrected per line if a line's own score disagrees strongly. This needs no extra model.
  2. Later, replace it with a tiny script-ID CNN on line crops (about 1 MB) if the spike shows step 1 wastes time.
- Stage 3: the registry returns the recogniser for that script key and runs it on the line crops.
- Latin pages can keep using ML Kit Latin. It is proven and fast, and there is no reason to regress it. The registry therefore has two kinds of engine: a whole-page engine (ML Kit) and a detector-plus-recogniser engine (Paddle). The router sends a page to ML Kit unless the classifier sampling says a non-Latin script is present. To make that first decision cheaply, reuse the Latin result: if ML Kit returns few characters per detected line area, or the detector finds many more text lines than ML Kit returned, run the Paddle path. That is option A as a fast pre-check and option B as the real decision.

The decision logic uses signals from models (recogniser confidence, output script, detector line count). There are no German or Arabic keyword lists.

## 5. Integration design

### 5.1 Current code (read)

- `core/data/.../repository/OcrService.kt`: `recognizeText(imageUri): PamResult<OcrResult>`. It builds `InputImage.fromFilePath`, calls ML Kit Latin, and maps each **ML Kit text block** to `OcrBlock(text, bounds, confidence, language)`. Bounds are normalised 0..1 (`TextBounds`). It calls `DocumentLayout.plainText(blocks)` to get `fullText`.
- `OcrResult(fullText, confidence, blocks, detectedLanguage)` is a data-layer class defined in the same file.
- `OcrBlock` and `TextBounds` live in `core/model/.../PageLayout.kt`. `DocumentLayout.readingOrder` orders blocks by bands and then by `left` ascending. That is **left-to-right only**.
- `DocumentProcessingPipeline` (line about 203) calls `ocrService.recognizeText(page.imagePath)` per page with a semaphore, then stores `ocrText`, `ocrConfidence` and JSON `ocrBlocks`.
- Nothing in the model stores lines separately. Block granularity is what the rest of the code consumes.

### 5.2 Ports (domain layer, pure Kotlin)

```kotlin
// core/domain/.../ocr/
enum class ScriptKey { LATIN, ARABIC /* later: CYRILLIC, HAN, ... */ }   // or a value class over a string, so new scripts need no enum edit

data class RecognizedLine(
    val text: String,            // logical order, as the recogniser produced it
    val bounds: TextBounds,      // normalised
    val confidence: Float,       // 0..1
    val script: ScriptKey?,
    val rtl: Boolean,            // derived from the output text's dominant bidi class, not from a language name
)

interface PageTextRecognizer {           // the one thing OcrService depends on
    suspend fun recognize(image: PageImage): List<RecognizedLine>
}

interface TextLineDetector { suspend fun detect(image: PageImage): List<LineQuad> }
interface LineRecognizer   { val script: ScriptKey; suspend fun recognize(crops: List<LineCrop>): List<LineText> }
interface ScriptClassifier { suspend fun classify(image: PageImage, lines: List<LineQuad>): Map<LineQuad, ScriptKey> }
```

`PageImage` is a small wrapper over a decoded Bitmap, so `:core:domain` does not import Android types (the domain module is currently pure; keep it so by putting the Android-specific wrapper in `:core:data`).

### 5.3 Registry

```kotlin
class RecognizerRegistry(private val recognizers: Map<ScriptKey, LineRecognizer>) {
    fun forScript(key: ScriptKey): LineRecognizer? = recognizers[key]
    val scripts: Set<ScriptKey> get() = recognizers.keys
}
```

Hilt multibinding (`@IntoMap @ScriptKeyMapKey`) fills the map. Adding Cyrillic later means one new module or binding, one model file and one dictionary file. The download catalog (`core/ai/catalog`) lists the files and their checksums, the way the embedding model is handled. `OcrService` and the pipeline do not change.

### 5.4 Adapters (in `:core:data` or a new `:core:ocr` module)

- `MlKitLatinPageRecognizer : PageTextRecognizer`: today's ML Kit code, moved out of `OcrService`. Line-level output: ML Kit `TextBlock.lines`, each with `boundingBox` and `confidence`.
- `PaddleOnnxDetector : TextLineDetector`: ONNX session for `PP-OCRv5_mobile_det`. Resize to a multiple of 32 (long side about 960), normalise, run, threshold the probability map, find contours, unclip, return quads.
- `PaddleOnnxRecognizer(script, modelFile, dictFile) : LineRecognizer`: crop and rotate each quad, resize to height 48, batch, run, CTC-decode against the dictionary. Confidence is the mean of the max probabilities of the kept timesteps.
- `ScriptRoutingRecognizer : PageTextRecognizer`: implements 4 above (Latin pre-check, detector, sampling classifier, registry dispatch). It is the only class that knows the routing policy.
- ONNX Runtime `OrtEnvironment` should be shared with the embedding service (one environment, separate sessions). Load sessions lazily, since `OcrService` already avoids eager construction because of the `:inference` process. Keep the same rule: nothing in constructors.

### 5.5 Mapping to `OcrBlock` and the existing layout

- One `RecognizedLine` becomes one `OcrBlock` (block granularity = line for the Paddle path). `OcrBlock.language` stays a nullable string. Put the script key there (for example `"ar"` or the BCP-47 script tag `"ar-Arab"`) or keep it null. Do not invent a language from code.
- **Grouping into paragraphs:** merge consecutive lines into blocks when the vertical gap is small and the left/right edges align. The same merge also helps the ML Kit path, but it is optional for the spike. Lines as blocks already work with `DocumentLayout` because it groups by band.
- **RTL reading order:** `DocumentLayout.readingOrder` sorts a band by `left`. For an Arabic letter the right-hand column is read first, and the letterhead, address block and reference block swap sides. This needs a change. The clean way: add a `direction` per page (`LTR` / `RTL`), computed from the share of RTL characters across the recognised lines, weighted by line length (derived from text, so no language rule), and sort each band by `-right` when it is RTL. For mixed pages, sort bands per column cluster. This is a separate small task in the layout code, and it needs tests with an Arabic fixture.
- **Bidi in `fullText`:** store logical order (what the recogniser returns). Do not reorder characters for display inside the OCR layer. The chat and preview UI must render with `TextDirection.Content` or `BidiFormatter`. Digits and Latin inside Arabic lines must keep their order, which logical storage guarantees.
- **Confidence:** `OcrResult.confidence` averages line confidences, as today. Note that CTC confidence and ML Kit confidence are not on the same scale. Do not compare thresholds across engines. If any stage compares page confidence to a fixed cut, calibrate per engine first.
- **Page image decoding:** ML Kit reads the URI directly. The Paddle path needs a Bitmap. Decode with `inSampleSize` so the long side is about 1600–2000 px, and reuse the same decoded bitmap for the detector and the crops.
- **`OcrResult.detectedLanguage`:** today it is ML Kit's first block language. For the Paddle path set it from the page script only when you map script to a language tag from the model output (for example via the existing `mlkit.language.id` on the recognised Arabic text, which already ships in `core/data`). That keeps the language decision data-driven. Check that Language ID covers `ar` (it does in ML Kit, UNVERIFIED for the version pinned, 17.0.6).

### 5.6 Where it sits in the pipeline

`OcrService.recognizeText` becomes a thin wrapper: decode, call `PageTextRecognizer`, build `OcrResult`. `DocumentProcessingPipeline` does not change. `OCR_CONCURRENCY` should be checked: two parallel pages on the Paddle path double peak memory (the detector runs on a large bitmap). Start with 1 for the Paddle path.

## 6. Risks

| Risk | Impact | Mitigation |
|---|---|---|
| Arabic accuracy is too low on real phone photos (81% line accuracy is an eval-set number, not a photo number) | wrong numbers in invoices | Spike with real scans (section 7). Fallback: Tesseract `ara_best` on low-confidence lines, or an optional VLM pass (PaddleOCR-VL) for hard pages. Keep the existing AI repair and verification stage for amounts and dates |
| Latin-side regression if routing misfires | German/English letters get worse | Keep ML Kit as the default for pages that look Latin. Require the Paddle path to win on a measured margin. Regression test on the existing German fixtures |
| Dictionary coverage: German umlauts, ß, € | broken German words in bilingual letters | Test the Arabic recogniser on German lines. If it fails, route by line, so German lines go to ML Kit |
| Bidi and digit order (amounts, IBANs, dates inside Arabic text) | reversed numbers | Keep logical order, add a unit test with Arabic + digits, render with content direction. Eastern Arabic-Indic digits (٠١٢) versus Western digits: decide normalisation in a downstream step, not in OCR |
| Reading order for RTL pages (`DocumentLayout` is LTR-only) and the zone and extraction prompts (`LetterLayout`, DIN 5008 assumptions) assume German letter layout | wrong sender/recipient | Plan the RTL ordering change. Extraction of Arabic letters is a second milestone. The OCR spike only claims correct text and order |
| Memory and latency on big photos | OOM or slow scans | Downscale, run one page at a time, release sessions after a batch. Measure peak RSS |
| PP-OCRv5 ONNX exports are community-made (for example `vladadu/pp-ocrv5-arabic-mobile-onnx`) | provenance and updates | Re-export ourselves with `paddle2onnx` from the official Hugging Face weights and pin SHA-256 in the catalog |
| PP-OCRv6 might add Arabic later, making v5 obsolete | rework | The registry hides the model. Swapping a model file does not touch callers |
| Apache-2.0 attribution | compliance | Add Paddle, ONNX Runtime and traineddata notices to the licences screen |
| Model download: offline-first app needs models on the device | first Arabic scan blocked | Offer the download at onboarding or when the language setting includes Arabic. Do not bundle to keep the APK small. Alternatively bundle the 13 MB, because it is small |
| ML Kit rejects or mis-reads Arabic silently instead of failing | wasted time | The router should not trust ML Kit output on pages where the detector finds many more lines than ML Kit |

## 7. Spike plan (time-boxed, about 3–4 days, separate branch, no pipeline change)

**Test set (build first):** 30 real pages photographed with the S23 camera or document scanner: 10 Arabic official letters, 10 Arabic or bilingual invoices, 5 mixed Arabic + German, 5 German letters (regression). Hand-transcribe the ground truth for at least 15 of them (5 per Arabic type) and mark the key fields: amounts, dates, IBAN or reference numbers.

**Steps:**

1. Run PP-OCRv5 mobile det plus Arabic rec in a standalone Android test (`androidTest`), using ONNX Runtime 1.24.3, CPU EP first, then NNAPI and XNNPACK if quick. Re-export the ONNX models with `paddle2onnx` from the official weights.
2. Run Tesseract (`ara`, `ara_best`, `ara+eng+deu`) on the same pages through tesseract4android as a comparison baseline.
3. Optionally run PaddleOCR-VL 1.6 GGUF through the app's llama.cpp on 5 pages to measure the upper bound and its latency.
4. Implement the routing sample (section 4, step 1) and run it over all 30 pages.
5. Map output to `OcrBlock` and run `DocumentLayout` with and without the RTL band fix on the Arabic pages.

**Acceptance criteria (all measured on the S23, release-like build, pages photographed at 12 MP, warm model):**

| Metric | Threshold |
|---|---|
| Arabic character error rate (CER), printed letters and invoices, Paddle path | at most 8% median, no page above 20% |
| Key-field recall (amount, date, reference) from OCR text alone, Arabic pages | at least 85% exact match (before AI repair) |
| Mixed Arabic + Latin + digits lines: digits and Latin tokens preserved in order | at least 95% of tokens |
| Script routing accuracy per page (Arabic vs Latin vs mixed) | at least 97% on the 30 pages, zero German pages misrouted |
| German regression: CER of the routed pipeline vs ML Kit alone | no worse than 0.5 percentage points |
| Latency per A4 page, Arabic path (detector + recogniser, excluding model load) | at most 4 s median, at most 8 s worst |
| Latency added to a Latin page by the routing pre-check | at most 300 ms |
| Model load time (cold) | at most 1.5 s |
| Peak additional memory during Arabic OCR | at most 400 MB |
| Reading order on Arabic letters (RTL fix): correct band and column order on the 10 letters | at least 9 of 10 |
| Model download size for Arabic | at most 20 MB |

**Decision after the spike:**

- Paddle meets CER and key-field targets: proceed to implementation (ports, registry, RTL ordering) as in section 5.
- Paddle misses accuracy but Tesseract `ara_best` passes: add Tesseract as the Arabic `LineRecognizer` (or a whole-page engine) behind the same registry. Nothing else changes.
- Both miss: evaluate the VLM as an opt-in "enhanced Arabic scan" for low-confidence pages, and record the latency cost honestly to the user.

## 8. Sources

- ML Kit Text Recognition v2 (scripts): https://developers.google.com/ml-kit/vision/text-recognition/v2
- ML Kit supported languages: https://developers.google.com/ml-kit/vision/text-recognition/v2/languages
- ML Kit release notes (no text-recognition update in 2025–2026; Android 16.0.1 from Aug 2024): https://developers.google.com/ml-kit/release-notes
- PaddleOCR arabic_PP-OCRv5_mobile_rec (Apache-2.0, 81.27%): https://huggingface.co/PaddlePaddle/arabic_PP-OCRv5_mobile_rec
- Community ONNX export (det 4.8 MB, rec 8.0 MB, 749 classes, opset 17): https://huggingface.co/vladadu/pp-ocrv5-arabic-mobile-onnx
- PP-OCRv5 paper: https://arxiv.org/html/2603.24373v1
- PP-OCRv6 paper: https://arxiv.org/pdf/2606.13108
- PP-OCRv6 docs (50 languages, no Arabic listed): https://www.paddleocr.ai/main/en/version3.x/algorithm/PP-OCRv6/PP-OCRv6.html
- PP-OCRv6 Hugging Face blog: https://huggingface.co/blog/PaddlePaddle/pp-ocrv6
- PP-OCRv6 Arabic issue (empty dictionary): https://github.com/PaddlePaddle/PaddleOCR/issues/18180
- PaddleOCR-VL-1.6 GGUF: https://huggingface.co/PaddlePaddle/PaddleOCR-VL-1.6-GGUF
- PaddleOCR-VL-1.5 GGUF: https://huggingface.co/PaddlePaddle/PaddleOCR-VL-1.5-GGUF
- PaddleOCR-VL paper (109 languages, Arabic edit distance): https://arxiv.org/html/2510.14528v1
- llama.cpp OCR models overview: https://blog.ngxson.com/using-ocr-models-with-llama-cpp
- Tesseract4Android (Apache-2.0, Tesseract 5.3.4): https://github.com/adaptech-cz/Tesseract4Android
- Tesseract4Android speed discussion: https://github.com/adaptech-cz/Tesseract4Android/discussions/20
- Qari-OCR paper: https://arxiv.org/abs/2506.02295
- Qari-OCR model card (licence follows Qwen2-VL): https://huggingface.co/NAMAA-Space/Qari-OCR-0.1-VL-2B-Instruct
- KITAB-Bench (Arabic OCR benchmark; VLMs beat EasyOCR and PaddleOCR by about 60% CER): https://arxiv.org/pdf/2502.14949
- PaddleOCR with ONNX Runtime on-device examples: https://github.com/mywaspapp/flutter-paddle-ocr and https://github.com/iFleey/PPOCRv5-Android

Repo evidence: `gradle/libs.versions.toml` (onnxruntime 1.24.3, mlkit text-recognition 16.0.1, language-id 17.0.6, minSdk 26), `core/ai/embed/build.gradle.kts` (uses `onnxruntime-android`), `app/build.gradle.kts` (arm64-v8a only in release), `core/data/.../repository/OcrService.kt`, `core/model/.../PageLayout.kt` (`OcrBlock`, `TextBounds`), `core/domain/.../usecase/DocumentLayout.kt` (LTR reading order), `core/data/.../repository/DocumentProcessingPipeline.kt` (around line 203).

## 9. Unverified items to settle in the spike

- Exact `ara` and `ara_best` traineddata sizes, and Tesseract speed per page on the S23.
- Paddle ONNX latency on the S23 (my 1–3 s estimate is not from a measured source).
- Whether the Arabic dictionary handles German umlauts, ß and the euro sign.
- Whether PP-OCRv6 will publish an Arabic recogniser.
- PaddleOCR-VL on-phone latency and its exact GGUF sizes (the model card lists 0.5B parameters for v1.6 and files `PaddleOCR-VL-1.6.gguf` plus `-mmproj.gguf`, no sizes).
- Commercial terms for Qari (inherits Qwen2-VL licence).
