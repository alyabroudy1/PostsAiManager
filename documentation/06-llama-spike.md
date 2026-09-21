# Phase 7.2 — llama.cpp Spike Plan

**Status:** ✅ **COMPLETE — all four questions answered on real hardware.**
Measured 2026-08-07 on Samsung SM-S918B (Galaxy S23 Ultra), Android 16, arm64-v8a,
Qwen2.5 0.5B Instruct Q4_0.

| Question | Answer |
|---|---|
| **Q1** Builds for arm64-v8a? | ✅ **Yes** — 53 s clean, ≈5.8 MB stripped |
| **Q2** Loads and generates? | ✅ **Yes** — 297 ms load, **173 tok/s**, coherent output |
| **Q3** Does a JNI crash kill the app? | ⚠️ **Yes** — SIGABRT killed the whole process |
| **Q4** Does GBNF constrain output? | ✅ **Yes** — including valid tool-call JSON |

**Verdict: llama.cpp + GGUF is confirmed as the runtime. Phase 8's Tier B tool strategy is
viable.**

**Follow-on (7.3):** `LocalAiEngine` now wraps this with streaming, cancellation and
lifecycle — **13 instrumented tests green on device**. Wiring it surfaced a fifth bug the
spike had not: the **KV cache was never cleared**, so every generation inherited the
previous one's context.

| | |
|---|---|
| NDK | ✅ `27.0.12077973` installed |
| CMake | ✅ `3.22.1` installed |
| llama.cpp | ✅ vendored at tag **`b10299`** |
| Native build (arm64-v8a) | ✅ **BUILD SUCCESSFUL**, 53 s clean |
| Device | ❌ phone disconnected, emulator unauthorized |

This is the largest unknown in the project and it gates roughly a third of the remaining
work to 1.0. It is deliberately scoped as a **spike** — the goal is to answer four
questions, not to ship a finished engine.

---

## 1. Toolchain — resolved 2026-08-07

Nothing was installed at the start of the session. All of it now is:

| Component | Version | How |
|---|---|---|
| `cmdline-tools` | 13114758 | downloaded, unpacked to `$SDK/cmdline-tools/latest` |
| NDK | `27.0.12077973` | `sdkmanager --install` |
| CMake | `3.22.1` | `sdkmanager --install` |
| llama.cpp | tag **`b10299`** | git submodule at `core/ai/local/src/main/cpp/llama.cpp` (`shallow = true`) |

To reproduce on another machine:

```bash
sdkmanager "ndk;27.0.12077973" "cmake;3.22.1"
git submodule update --init --recursive   # or: git clone --recurse-submodules <repo>
./gradlew :core:ai:local:assembleDebug
```

llama.cpp is a registered git submodule (task 7.2.10), pinned to `b10299` by the gitlink
in this repository. `.gitmodules` sets `shallow = true`, so the checkout fetches only the
pinned commit. It started life as a gitignored shallow clone during the spike and was
registered once the revision was proven good.

### Still blocked: a device

`adb devices` shows only an `unauthorized` emulator; the phone disconnected mid-session.
Q2–Q4 cannot be answered without real arm64 hardware — x86_64 emulator throughput says
nothing about phone performance, and Q3 (crash blast radius) is meaningless on an emulator.

## 2. What is already written

The integration risk — Gradle ↔ CMake ↔ NDK wiring — is the part that can be designed
without the toolchain, and it is done:

| File | Purpose |
|---|---|
| `core/ai/local/build.gradle.kts` | `externalNativeBuild`, pinned `ndkVersion`, `abiFilters = arm64-v8a`, `useLegacyPackaging = false` |
| `core/ai/local/src/main/cpp/CMakeLists.txt` | Vendored-submodule wiring, trims tests/examples/server/CURL, fails with an actionable message if the submodule is missing |

Three decisions baked in, each for a reason worth keeping:

- **`useLegacyPackaging = false`** — Android 15+ devices may use **16 KB memory pages**. A
  native library aligned for 4 KB pages simply refuses to load there. Easy to miss, and it
  fails only on newer hardware.
- **`GGML_NATIVE OFF`** — prefer llama.cpp's runtime CPU-feature dispatch over a baseline
  `-march` bump, so one binary serves every arm64 device instead of crashing on older cores.
- **Submodule, not fetch-at-build** — pins the exact revision, keeps builds reproducible
  and offline-capable.

**JNI bridge written and compiling** (`llama_jni.cpp`, `LlamaNative.kt`) — scoped to the
spike's questions: load, generate, a grammar hook for Q4, and a deliberate
`crashForTesting()` for Q3. `LlamaNative.ensureLoaded()` returns false rather than throwing
on `UnsatisfiedLinkError`, so a device whose ABI we do not ship degrades to "no local AI"
instead of crashing.

One detail worth keeping: the grammar sampler is added to the chain **first**, so it filters
the candidate set before any probabilistic sampler runs. Added afterwards, the constraint
could be sampled around.

---

## 3. The four questions

Answer in order. Each has a defined consequence, so a failure changes the plan rather than
just costing time.

### Q1 — Does it build for arm64-v8a in this Gradle setup? ✅ **ANSWERED — YES**

Built 2026-08-07 with NDK 27.0.12077973, CMake 3.22.1, llama.cpp `b10299`. 203 CMake
targets, clean build 53 s. The trimmed configuration (no tests/examples/server/CURL, no
OpenMP, `c++_shared`) worked as written — llama.cpp itself compiled without a single
change.

**One fix was needed, and only in our own code:** `llama_model_params.use_mmap` no longer
exists in `b10299` — memory-mapping is now governed by a `load_mode` enum, and the default
already mmaps. Exactly the class of API drift that pinning the revision protects against.

**Shipped size, stripped, arm64-v8a:**

| Library | Size |
|---|---|
| `libllama.so` | 2.9 MB |
| `libc++_shared.so` | 1.2 MB |
| `libggml-cpu.so` | 0.8 MB |
| `libggml-base.so` | 0.8 MB |
| `libggml.so` + `libpam_llama.so` | 0.15 MB |
| **Total** | **≈ 5.8 MB** |

For context, ML Kit's `libmlkit_google_ocr_pipeline.so` — already in the app — is
**10.6 MB**, nearly twice as large. Adding a full LLM runtime did not move the APK
measurably (110.2 MB before and after). The APK-budget worry was misplaced; the **models**
are the weight, not the runtime, which is exactly why they download on demand.

### Q2 — Can it load a GGUF and emit tokens on a real device? ✅ **ANSWERED — YES**

| Measurement | Value |
|---|---|
| Model | Qwen2.5 0.5B Instruct Q4_0, 409 MB |
| Load time | **297 ms** (mmap) |
| Generation | 64 tokens in 369 ms → **173.4 tokens/sec** |
| CPU features | `NEON = 1, ARM_FMA = 1, LLAMAFILE = 1, REPACK = 1` |

Output for *"Name three colours."*:

> `Three colors are red, blue, and yellow.`

173 tok/s is far above reading speed, so streaming will feel instantaneous. A 297 ms load
means no progress indicator is needed for a model this size — though a 3–4 B model will be
several times slower and should still show one.

The `REPACK = 1` flag confirms llama.cpp's runtime weight repacking is active, which is
where much of the arm64 CPU performance comes from — and it validates the `GGML_NATIVE OFF`
choice: runtime dispatch found the right kernels without a baseline `-march` bump.

### Q3 — Does a JNI crash kill the app? ⚠️ **ANSWERED — YES, THE WHOLE PROCESS DIES**

Answered accidentally and convincingly: a genuine bug in the JNI bridge (see Q4) aborted
inside llama.cpp, and the result was unambiguous.

```
E libc++abi: terminating due to uncaught exception of type std::runtime_error
F libc    : Fatal signal 6 (SIGABRT), code -1 in tid 26283
I Zygote  : Process 26253 exited due to signal 6 (Aborted)
```

The **entire process** was killed. No Kotlin `try/catch` can intercept this — it is not a
Java exception, and by the time Zygote logs the exit the app is gone along with any unsaved
state.

**Consequence — `android:process=":inference"` is justified.** A C++ exception anywhere in
llama.cpp (bad grammar, malformed GGUF, OOM inside a kernel) otherwise takes the document
app down with it, losing whatever the user was doing. Isolation costs IPC complexity and
buys a failure mode where the answer is lost but the app is not.

Tracked as task **7.2.3**; the recommendation is now **yes, isolate**, on evidence rather
than caution.

### Q4 — Does GBNF grammar-constrained sampling work? 🔴

**The most consequential question, and the easiest to defer by mistake.**

The entire Phase 8 tool layer rests on Tier B: compile a tool's JSON schema into a GBNF
grammar so the sampler *cannot* emit invalid output. That is what makes tool calling work on
small models with no native tool support — and it is the single strongest reason llama.cpp
was chosen over ONNX Runtime GenAI and MediaPipe, neither of which offers it.

**✅ ANSWERED — YES. This is the most important result of the spike.**

Same prompt, same model, same greedy sampling — the only difference is the grammar.

*"Write a long paragraph about the ocean."*

| Grammar | Output |
|---|---|
| none | `The ocean is a vast and mysterious body of water that covers approximately 71% of the Earth's surface. It is the largest body of water in the` |
| `root ::= "red" \| "green" \| "blue"` | `red` |

The model *wanted* to write a paragraph. The grammar made every other token unreachable.

A JSON grammar shaped like a real tool call:

```gbnf
root ::= "{" ws "\"tool\"" ws ":" ws tool ws "}"
tool ::= "\"search_documents\"" | "\"create_profile\""
```

produced:

```json
{ "tool": "search_documents" }
```

**Valid, schema-conforming tool-call JSON from a 0.5 B model** — one small enough to run on
almost any modern phone, with no native tool-calling support whatsoever.

**Phase 8's Tier B is real.** Tool calling does not depend on model quality or prompt
engineering; it is a property of the sampler. This is the single strongest vindication of
choosing llama.cpp over ONNX Runtime GenAI and MediaPipe.

#### Bugs this uncovered

**One found by the test:**

The first attempt aborted with `Unexpected empty grammar stack after accepting piece: re`.
Cause was in **our** bridge, not llama.cpp: `llama_sampler_sample()` *samples **and
accepts*** (it calls `llama_sampler_accept` internally — see `llama.h`), so the explicit
`llama_sampler_accept()` afterwards advanced the grammar state **twice per token**.

Worth recording because it is invisible without a grammar in the chain: unconstrained
sampling ignores the extra accept entirely and looks perfectly healthy. Only a grammar
exposes it — by killing the process.

**Three more found by re-reading the bridge afterwards**, none of which any passing test
had objected to:

| Bug | Why the tests missed it |
|---|---|
| `static llama_token` holding the batch token | A function-scope static is shared across every call **and every thread**. Single-threaded tests never create the condition; two concurrent generations would silently corrupt each other's batch. |
| `tokenToPiece` returned empty when a token exceeded its 256-byte buffer | Silently drops the token. Common vocabulary never hits it; unusual entries corrupt output with no error. |
| An empty prompt produced a zero-length batch | `llama_decode` rejects it. No test passed an empty prompt. |

All three are fixed, and `GrammarRegressionTest` now covers them — including a check that
two generations from one handle produce **independent** results (`first=beta`,
`second=alpha`), that an unparseable grammar falls back to unconstrained sampling rather
than aborting, and that load/free cycles repeat cleanly.

The lesson is worth keeping: **passing tests told us the code worked; reading it told us
where it did not.** Q3 established that any of these would have killed the whole app.

---

## 4. Results summary

| Measurement | Value |
|---|---|
| Clean native build | 53 s, 203 CMake targets |
| Shipped native libs (arm64-v8a, stripped) | **≈5.8 MB** — half of ML Kit's OCR lib |
| APK impact | none measurable (110.2 MB before and after) |
| Model load, 409 MB Q4_0 | **297 ms** |
| Generation, 0.5 B Q4_0 | **83–173 tokens/sec** (see note) |
| CPU features engaged | NEON, ARM_FMA, LLAMAFILE, REPACK |
| Native crash blast radius | **entire process (SIGABRT)** |
| GBNF constraint | **works, including valid tool-call JSON** |
| Peak RSS while loaded | ⏳ not yet measured |

**On the throughput range:** 173.4 tok/s on a cold run, 83.2 tok/s when the same test ran
last in a six-test suite. Both figures are real; the spread is thermal and scheduling
contention, not measurement error. Quote the range, not the best number — even the low end
is far above reading speed, so streaming feels instant either way.

Peak RSS is the one gap. `AiModelDescriptor.minAvailableRamBytes` is still an estimate
(file size × 1.4 for Hub models); measuring real resident memory would replace guesswork in
the fit check that decides which models users are offered. Tracked as **7.2.9**.

---

## 5. Decisions this settles

| Decision | Outcome |
|---|---|
| Local runtime | ✅ **llama.cpp + GGUF confirmed.** Builds small, runs fast, supports GBNF. |
| Phase 8 tool strategy | ✅ **Tier B (grammar-constrained) is viable** — proven on a 0.5 B model with no native tool support. |
| Process isolation (7.2.3) | ⚠️ **Yes, isolate.** A native abort kills the whole app; evidence, not caution. |
| `GGML_NATIVE OFF` | ✅ Vindicated — runtime dispatch engaged REPACK and ARM_FMA without a baseline `-march` bump. |
| ONNX Runtime GenAI fallback | ❌ **Not needed.** Retained only for embeddings. |

---

## 6. Contingency (retained for the record)

If Q1 or Q2 fails outright, the fallback is ONNX Runtime GenAI — already a project
dependency for embeddings, so no new native stack. The cost is real and should be stated
plainly: **no arbitrary custom-model import, and no GBNF.** Phase 8's tool layer would drop
to parse-and-repair, and "import any GGUF" — shipped and working today — would be lost.

That is a materially different product, so it is a decision to bring back rather than take
unilaterally.
