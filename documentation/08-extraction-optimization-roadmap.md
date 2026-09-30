# 08: Extraction optimisation roadmap (after the first release)

Status: parked by the user on 2026-09-30. Get the app production-ready and released first; then improve result quality
using this list. Every idea below was researched or measured during Phase 1. Numbers are from the 16-letter benchmark
(`core/domain/src/test/resources/benchmark`: real phone-OCR fixtures + manifests; recorded model scores under
`benchmark/recordings`, replayable offline in the JVM). Other measurements come from local benchmark data that is not
part of the repository.

The pipeline these numbers describe is explained in [07-document-pipeline.md](07-document-pipeline.md), section 12.

## Where we are (the shipped approach)
OCR (ML Kit) → a language-neutral candidate extractor → geometry layout zones + templates (DIN 5008 A/B, invoice
table, receipt, form, RTL, generic) → **ZONES_SCORING**: for each slot in its zone, the LLM scores every candidate
label-free as P(Yes) − P(No) ("Is «X» the <slot>?") → the **joint decoder** (one value answers one question) →
**scored extras** (values no slot took that the model says yes to) → a separate writing session (language, extra names,
title, summary) → verifier → storage.

| Approach (Qwen3.5-0.8B, CPU, S23) | Fields | Roles | Halluc. | s/letter |
|---|---|---|---|---|
| One big JSON call | 51% | 40% | 20% | 72 |
| Image-only VLM (vision) | 44% | 20% | 17% | 149 |
| Short multiple-choice questions (+ restated options) | 64% | 40% | 12% | 73 |
| Zones, generated answers | 45% | 13% | 13% | 51 |
| Zones + label-free scoring, per-slot argmax, no decoder (earlier recordings) | 68% | 87% | 0% | 64 |
| Zones + scoring, Qwen3.5-2B (3 letters only) | ~73% | 67% | 4% | 163 |
| Zones + scoring, per-slot argmax (latest recordings: language, scored extras, writing session) | 80.2% | 86.7% | 1.5% | not re-timed |
| **Zones + scoring + joint decoder + scored extras (shipped, same recordings)** | **82.4%** | **86.7%** | **1.6%** | not re-timed |

The last two rows are scored on the newest recordings, with the extras and the writing session in the reading, so they
are not comparable with the rows above them (the set of facts the extractor finds and the questions asked differ).
Extras per letter: 2.13 with the argmax, 2.00 with the joint decoder (a value a slot took is no longer also an extra).
Before the document type depended on the direction the same two rows read 82.4% and 84.6% (extras 2.44 and 1.75):
the 2.2 points are not a regression. Three letters were typed `outgoing_letter`, whose slots (recipient, cited
references, sent date) happened to match two manifest facts; the slots of their real types were never asked in the
recording, so the fields of a correctly typed letter need a device re-record before the number is meaningful.
Document type, on the 13 letters with a known kind: 7/13 with every type offered, **9/13** once an incoming document is
offered only the types an incoming document can be (see "Parked findings").

Key lessons:
- Reading is not the bottleneck: candidate recall is 96.8% and the zones are 100%. The DECISION is.
- Small models have strong selection/label-token bias (PriDe); scoring each candidate on content removes it.
- Constrained output helps classification; don't add chain-of-thought for 0.8B.
- Grammar-first sampling was 2.5× slower; sample-then-check fixed it.
- A glimpse of the neighbouring zones did not help measurably (kept as an off option).
- What needs writing is asked in a separate session under an instruction that only says to write: in the scoring
  session's "answer Yes or No" instruction a 0.8B model answered "Yes" to the language, the title and the summary.

## Parked findings (measured, not yet acted on)
- **Title and summary**: the 0.8B model mostly copies a line of the letter as the title and the summary instead of
  writing one. The text is correct but not a summary. A larger writer, or a few-shot prompt, is the lever; nothing in
  code should rewrite the model's text.
- **Confidence**: the HIGH band is right 83% of the time held out (cuts fitted on the other half of the letters; 91%
  in-sample). LOW is the model's own score leaning No. The bands are cut points as data (`ScoreCuts`) and need more
  corrected letters before they are tightened.
- **Roles are capped by the candidates, not the decision**: the two missed addressees have no candidate to choose.
  "Max und Erika" is one line holding two people, and the guardian line is split across two lines. The fix is in the
  candidate extractor (split names joined by "und"/"and", join a name broken across lines), not in scoring.
- **Document type**: with every type offered, the incoming letters N4, N8 and N10 were typed as `outgoing_letter`
  (and a payment confirmation type is offered the same way). Those types are for documents the user sends or pays
  and cannot describe a scanned incoming letter. The candidate set now depends on the document's direction
  (`DocType.directions`, `ExtractionSchema.typesFor`; incoming until the app stores a direction per document), and type
  accuracy on the recorded scores goes from 7/13 to 9/13. N10 becomes `health`, N4 becomes `other` (acceptable). The
  remaining errors are all `other` winning over a real type (N1, N2, N8, the long tax letter): the catch-all
  description was made neutral ("a document of a kind not listed here"), but its effect cannot be measured offline
  (the recorded scores are for the old wording) and needs a device re-record. The fields of a letter whose type
  changed were not asked in the recording (for example N10's appointment), so those fields also need a re-record.

## Ideas, ranked (expected gain is an estimate unless measured)
1. ✅ DONE (E1): the joint assignment (exact branch-and-bound, sharing rules as data, a secondary tier for
   care_of/contact/subject) is the default decoder for qwen3.5-0.8b. On the latest recordings fields 80.2% → **82.4%**
   (before the direction fix 82.4% → 84.6%), roles 86.7% (ceiling: the 2 misses have no candidate), halluc ~1.6%. Pair constraints and calibrations added
   nothing held out and stay off. Original idea: **Global joint assignment** (Hungarian/ILP over the slot×candidate
   score matrix; one value per slot, sender ≠ addressee, due ≥ letter date, net+VAT=gross) with soft penalties and a
   "none" column.
2. **Calibration**: a per-slot content-free baseline ("N/A" score; Calibrate Before Use, arXiv 2102.09690), z-score or
   rank normalisation. Z-score and rank were tried inside the decoder and did no better held out; the N/A baseline
   (one extra score per slot) is untested.
3. **Tiny learned ranker** (logistic regression / GBDT) fusing the LLM score, margin, rank, candidate kind, geometry,
   cross-slot scores and multilingual embedding similarity (the app already ships an ONNX embedding model). Train on
   generated letters, validate leave-one-letter-out on real OCR. +3–7; the most likely route to ≥85% fields.
4. **Margin-gated re-scoring** of only the uncertain slots: paraphrased/multilingual slot descriptions, domain-conditional
   PMI (Surface Form Competition, arXiv 2104.08315), and a reverse question. +2–4 at +10–15 s.
5. **Per-sender memory**: confirmed letters from the same sender give layout priors and few-shot examples. Large gains
   for recurring senders (banks, insurers, utilities); fits Phase 2 "Who".
6. **LoRA fine-tune / distillation** of Qwen3.5-0.8B on the yes/no scoring task, with teacher labels on thousands of
   generated letters (MLX on the Mac; llama.cpp LoRA GGUF adapter or merged model). The highest ceiling, +5–10.
   Keep the benchmark letters out of the training data.
7. **Bigger reader in the background**: Qwen3.5-2B with ZONES_SCORING (~73% fields on 3 letters, 163 s/letter,
   1.3 GB). Offer it as an opt-in "thorough reading" profile via the ModelProfile registry.
8. **GLiNER2.5-multi** (287M, Apache 2.0, mDeBERTa) as an extra scorer/feature or a candidate cross-check; needs an
   ONNX/Android port. +1–4 as a feature.
9. **NuExtract-2.0-2B** (MIT) as a verifier for low-confidence letters only (2B speed).
10. **Document VLM fallback** for pages where OCR fails (non-Latin scripts, bad scans): PaddleOCR-VL-1.5 (0.9B,
    Apache 2.0, llama.cpp-supported). Not for the main path. The measured 0.8B VLM was worse than OCR+LLM.
Rejected: DeepSeek-OCR / OCR 2 (3B MoE; it replaces OCR, which isn't our bottleneck; the compression idea doesn't help
short letters), LayoutLMv3 (non-commercial licence), 7B layout LLMs, NPU on the Snapdragon 8 Gen 2 (not supported by
LiteRT QNN per the Google post).

## Also parked
- Speed: KV-prefix batching for scoring (llama_memory_seq_cp), n-gram speculative decoding for generated parts.
- Arabic and other non-Latin scripts: ML Kit Latin can't read them. Route to a VLM fallback (idea 10) or ML Kit's other
  script recognizers.
- A benchmark with more real letters (redacted), and calibration of the AI confidence on real user corrections.
- Outgoing letters and payment proofs (phase P3): the document direction is passed into the reading already; what is
  missing is storing it per document and the capture flow that sets it.

## How to evaluate any idea
Run the JVM replay first (seconds) on the recorded score matrices. Device runs only when new scores are needed.
Gate: `BenchmarkGateTest` (real-OCR candidate recall and zones) + the oracle + the interpreter metrics on the recordings.
Accept only held-out (cross-fitted) gains ≥2 points without extra hallucination. The tools: `DecoderEvalTest`
(decoders, `$DECODER_OUT`), `FamilyAccuracyTest` (document family, `$FAMILY_OUT`), `ExtrasThresholdTest`, `ZoneScoringTuneTest`.
