# PostsAiManager — Documentation

Living documentation for the PostsAiManager Android application.

> ## Product thesis
> **An on-device AI assistant for physical mail. Privacy by construction.**
>
> Scan a letter; the app reads it, understands it, files it against the sender, tracks what
> it obliges you to do, and — through a tool layer — **acts on it**, entirely on your device.
>
> Online models exist only as a deliberate, per-query escalation when the local model isn't
> smart enough: the user is warned, shown the exact payload, allowed to edit it, and must
> approve it. Remote models may *propose* actions but never execute them, and tool results
> never flow back to them. No feature depends on the network.

| Document | Purpose | Update cadence |
|---|---|---|
| [01-findings-report.md](01-findings-report.md) | State-of-completion audit. What is real, what is fake, what is missing. | Re-run at each phase boundary |
| [02-architecture.md](02-architecture.md) | Current vs. target architecture, AI subsystem, tool layer, privacy boundary, error handling. | When structure changes |
| [03-implementation-plan.md](03-implementation-plan.md) | Phased roadmap to 1.0 and beyond. | When scope changes |
| [04-task-list.md](04-task-list.md) | **Stepwise checklist.** The single source of truth for progress. | Every completed task |
| [05-test-harness.md](05-test-harness.md) | Agent-driven device testing via adb — protocol, safety, command vocabulary. | When a new case class is added |
| [07-document-pipeline.md](07-document-pipeline.md) | **Ingest to indexed.** Stages, fingerprints, edit provenance and the reprocessing merge, PDF handling. | When the pipeline changes |
| [06-llama-spike.md](06-llama-spike.md) | The llama.cpp spike: what is blocked, what is wired, the four questions to answer. | When the spike runs |
| [testing/cases/](testing/cases/) | Executable test cases. | Per feature |

`04-task-list.md` is the working document. Everything else explains *why* the tasks are
ordered the way they are.

## Verified on real hardware

| Fact | Value | Date |
|---|---|---|
| Build | `assembleDebug` **BUILD SUCCESSFUL** | 2026-08-07 |
| Debug APK — before | 300.4 MB (4 ABIs, 173.8 MB assets) | 2026-08-07 |
| Debug APK — after | **110.2 MB** — parser cut + `abiFilters` → **−190.2 MB (−63%)** | 2026-08-07 |
| Unit tests | **566 passing, 0 failing** | 2026-08-09 |
| Test capability | 18 / 18 modules via `pam.test-conventions` | 2026-08-07 |
| Defects found by tests | 7 fixed, 2 pinned open | 2026-08-07 |
| ✅ Reprocessing data loss | **Fixed** — provenance + merge + revision history, schema v3, migrated on-device with no loss | 2026-08-09 |
| ⚠️ Extractor quality | **Open** — regex extraction read a salutation as a name and a sentence fragment as an organisation, both at a hardcoded 0.80. Confidence is a per-field-type constant, not a measurement | 2026-08-09 |
| Test device | Samsung SM-S918B, Android 16 (API 36), arm64-v8a | 2026-08-07 |
| Device RAM | 11.3 GB total — 2.5–4.7 GB available depending on what else is running | 2026-08-07 |
| Smoke test T001 | **PASS**, 20/20 steps, zero crashes | 2026-08-07 |
| **llama.cpp on device** | **173 tok/s** (Qwen 0.5B Q4_0), 297 ms load, ≈5.8 MB libs | 2026-08-07 |
| **GBNF grammar constraint** | ✅ **works** — valid tool-call JSON from a 0.5B model | 2026-08-07 |
| Native crash blast radius | ⚠️ kills the whole process — process isolation justified | 2026-08-07 |
| **ONNX embeddings on device** | **41 ms** per text, 768 dims, 400 ms load (distiluse-multilingual-v2 fp16) | 2026-08-07 |
| **Semantic retrieval works** | related **0.423** vs unrelated **0.060** on German with no shared words | 2026-08-07 |
| **Cross-lingual retrieval** | DE↔EN **0.798** vs unrelated **−0.012** — ask in English about a German letter | 2026-08-07 |
| Batch embedding | 8 texts in **97 ms** (12 ms each) after batch-max padding — was 1554 ms | 2026-08-07 |
| Embedding device tests | **15 / 15 passing** on real hardware | 2026-08-07 |
| **Chat models installable** | 5 pinned by revision + SHA-256, all ungated, URLs verified 200 | 2026-08-09 |
| Chat model download | Qwen 1.5B and Gemma 4 E2B fetched through the app at their exact pinned sizes | 2026-08-09 |
| Catalog generation | **Gemma 4** (Jul 2026, Google QAT builds) + **Qwen 3.5** | 2026-08-09 |
| 🔴 Prompt >512 tokens | **Fixed** — `n_batch=512` with a single-batch prompt aborted the inference process. Would have killed grounded chat too | 2026-08-09 |
| Context window | Capped at **4096** — 8192 aborted on the reference phone with more memory free than 4096 succeeded with | 2026-08-09 |
| **Best on-device extractor** | **Gemma 4 E2B** — sender, recipient, contact, both references, deadline and amount all correct on a real letter. Now selectable separately from the chat model | 2026-08-09 |
| Extraction speed | Gemma 4 E2B **247 s**, Qwen3.5 2B **164 s** per letter — background work, not interactive | 2026-08-09 |
| Qwen3.5 + grammar | ⚠️ Reasoning model — emits `<think>`; forcing straight-to-JSON fights its training and it misses the deadline entirely | 2026-08-09 |
| Confidence | ✅ **Derived from the page, not self-reported** — models returned a constant 0.9, so the number is now computed by verifying each value against the source | 2026-08-09 |
| **OCR keeps layout** | Normalised box per block, persisted; schema v4 migrated on device, 3 documents / 12 fields intact | 2026-08-09 |
| Embedding model download | ✅ 258 MB fetched, hashes verified, both files in place | 2026-08-09 |
| Foreground download crash | ⚠️ Found on device: WorkManager's service declares no `foregroundServiceType` — **would have crashed every chat-model download too** | 2026-08-09 |
| Paragraph ranking accuracy | **4 / 5** questions rank the right paragraph first | 2026-08-07 |
| Question-vs-paragraph scores | correct **0.13 – 0.30**, unrelated **−0.04 – 0.04** | 2026-08-07 |

## Project at a glance

- **What it is:** a private document assistant. German DIN 5008 letter extraction,
  sender-to-profile matching, deadline tracking, local LLM chat and agentic actions over
  your own mail.
- **Stack:** Kotlin 2.1.0, Compose (BOM 2024.12.01), Hilt 2.53.1, Room 2.6.1, ML Kit,
  ONNX Runtime 1.24.3. Planned: llama.cpp + GGUF, WorkManager. minSdk 26 / targetSdk 35.
- **Today:** 21 Gradle modules + `build-logic`, 183 Kotlin files, **zero bundled ML assets** (models download on demand).

## Roadmap

| Phase | Focus | Ships |
|---|---|---|
| 0–5 | Foundation, scan pipeline, UI, extraction, profiles, PDF | ✅ Done |
| 6 | Foundation, boundaries & **subtraction** — tests, module split, delete parser | — |
| 7 | 🔒 **On-device AI core** — llama.cpp, model management, RAG, local chat | — |
| 8 | 🔧 **Tool & agent layer** — the model *acts* | — |
| 9 | Escalation architecture — consent gate, plugin contract, **zero providers** | — |
| 10 | Feature completeness — profile detail, reminders, localization | — |
| 11 | Production hardening | 🚀 **1.0 — fully private, no network AI path** |
| 12 | Provider rollout — Groq → NVIDIA Build → Ollama → Gemini | post-1.0 |

## Status summary

| Area | State |
|---|---|
| Architecture & module split | ✅ `:core:ai` split; privacy guarantees are compile-time facts. One boundary violation left (`feature:documents` → `core:data`) |
| Scan → OCR → extract → persist pipeline | Working end to end, **now covered by 44 tests** |
| Document management (list, detail, search, PDF, profiles) | Working |
| Test infrastructure | ✅ `build-logic` convention plugin, `:core:testing` fakes + fixtures, **532 unit tests + device suites** |
| Error handling | ⚠️ Systemic gaps — empty catches, raw debug strings, no logging abstraction, **ViewModels discard write failures** |
| **On-device LLM** | ✅ **Working on device** — llama.cpp b10299, five installable models, grammar-constrained extraction reading real letters |
| **Model management** | ✅ **Complete and now usable** — 4 chat models pinned and installable; previously every catalog entry was `NotInstallable`, so no model could be downloaded at all |
| **Tool / agent layer** | **Not started** |
| **RAG / semantic search** | ✅ **Working on device** — ONNX encoder, hybrid RRF retrieval, indexed on scan. Thresholds calibrated from measurement (4/5 paragraph ranking). Model delivery to a real install is the remaining gap |
| **Escalation & consent gate** | **Not started** |
| **Chat** | Wired to the local engine; needs a model installed |
| **Entity understanding** | **Next** — AI reads the document, identifies people and organisations, creates or links profiles. See [08](08-entity-understanding.md) |
| **Tests** | ✅ **75 passing** across 5 modules; all 17 test-capable. Still no Room or instrumented tests |
| **Localization** | **`stringResource` used zero times** |
| `feature:parser` (Arabic) | ✅ Removed — archived at git tag `archive/arabic-parser` |

See [01-findings-report.md](01-findings-report.md) for the evidence behind each row.

## Key decisions on record

| Decision | Choice |
|---|---|
| Priority | On-device first; cloud after 1.0 |
| Local runtime | **llama.cpp + GGUF** — ✅ **confirmed by spike**: 173 tok/s, GBNF verified |
| ABI | arm64-v8a (+ x86_64 for debug) |
| Embeddings | ONNX Runtime, **retargeted** from the cut parser to RAG — `distiluse-base-multilingual-cased-v2`, 768 dims, chosen for shipping a WordPiece vocab |
| Vector store | None — `BLOB` + brute-force cosine |
| Online consent | Per-query: warn → preview → edit → approve. **No "always allow."** |
| Remote tool calls | **Proposal only** — executed locally after user confirmation, results never returned |
| Tool protocols | Four tiers, best available. **GBNF is the answer for models without tool calling.** |
| Plugin mechanism | Declarative descriptors + one OpenAI-compatible adapter |
| Config delivery | Signed remote manifest (**ECDSA P-256** — Ed25519 needs API 33, minSdk is 26) for anything that changes after release; a small curated set pinned by revision + SHA-256 in the APK so the app works before any manifest exists |
| 1.0 provider count | **Zero** |
| First provider | Groq (Phase 12) |

## The two structural guarantees

Not promises — **module dependency facts**, checked by Konsist in CI:

1. **An online provider cannot read a document.** `:core:ai:online` has no dependency on
   `:core:data`.
2. **An online provider cannot execute a tool.** `ToolSpec` (inert) lives in `:core:model`;
   `AiTool` (executable) lives in `:core:domain`, and `:core:ai:online` depends on neither
   `:core:domain` nor `:core:data`.

## Guiding principles

- **The LLM is a client of the domain layer, exactly like the UI is.** `AiTool` is to the
  model what `ViewModel` is to Compose — an adapter over use cases, never business logic.
- **Errors are values that cross layer boundaries; exceptions never do.** Every error state
  answers: what happened, why, what can I do.
- **Degradation is announced, never silent.** The document app works at every rung of the
  ladder — nothing non-AI ever gates on a model.
