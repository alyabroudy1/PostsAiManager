package com.postsaimanager.core.ai.catalog

import com.postsaimanager.core.model.Accelerator
import com.postsaimanager.core.model.AiModelDescriptor
import com.postsaimanager.core.model.BackendSpec
import com.postsaimanager.core.model.ModelRole
import com.postsaimanager.core.model.SpeedHint

/**
 * The models that ship knowable, compiled into the APK.
 *
 * ### Why these are pinned, when the catalog as a whole is not
 *
 * These entries used to carry no URL and no hash, on the reasoning that hard-coding them
 * would create a second, unsigned supply-chain path that could not be revoked after
 * release. Installation was to go only through the signed remote manifest.
 *
 * The consequence was that **nothing could be installed at all**: `TrustedKeys` ships empty,
 * so every remote manifest is refused, so the app fell back to a catalog whose every entry
 * reported `NotInstallable`. A model manager that cannot install a model is not a
 * conservative default, it is a broken feature.
 *
 * The revocation argument holds for a catalog that **grows and changes after release**, and
 * the manifest remains exactly that: it can add models, correct these, and override any
 * entry here. It does not hold for a small curated set fixed at build time, which changes
 * only when the app is rebuilt — the same release gate a manifest correction would pass.
 *
 * For that set, pinning is the *stronger* guarantee: [AiModelDescriptor.sha256] is verified
 * before the file is moved into place, and each URL names an **immutable revision** rather
 * than a branch, so the bytes cannot change under the hash. Every hash below is the Hugging
 * Face LFS `oid` — which is the SHA-256 of the file — read from the API rather than
 * transcribed, and every URL was checked to return 200.
 *
 * ### Why these five
 *
 * Current generation only: **Gemma 4** (July 2026) and **Qwen 3.5**. The Gemma entries are
 * Google's own **QAT** builds — quantisation-aware training, so the 4-bit weights were
 * learned rather than rounded afterwards, which shows most on exactly the small models a
 * phone can run.
 *
 * They span the device range rather than showing off. Reading a German letter accurately is
 * the job, and a device that can only hold 0.8B should still be able to do a worse version
 * of it rather than nothing. All five are ungated: no Hugging Face token, no licence
 * click-through, nothing between the user and a working assistant.
 */
object BundledCatalog {

    private const val MB = 1024L * 1024L
    private const val GB = 1024L * MB

    /** Gemma 4 is Apache 2.0 (https://ai.google.dev/gemma/docs/gemma_4_license), unlike Gemma 1-3's own terms of use. */
    private const val GEMMA4_LICENSE = "Apache-2.0"

    private const val QWEN35_08B_REV = "6ab461498e2023f6e3c1baea90a8f0fe38ab64d0"
    private const val QWEN35_2B_REV = "f6d5376be1edb4d416d56da11e5397a961aca8ae"
    private const val QWEN35_4B_REV = "e87f176479d0855a907a41277aca2f8ee7a09523"
    private const val GEMMA4_E2B_REV = "675cff42a74c774d6cb76f76d8eacb49b48c9b93"
    private const val GEMMA4_E4B_REV = "4b4a2c1d584be7264f87aac328a1bc739ce81b6c"

    val models: List<AiModelDescriptor> = listOf(
        AiModelDescriptor(
            id = "qwen3.5-0.8b-q4_k_m",
            name = "Qwen3.5 0.8B",
            family = "Qwen",
            parameterCount = "0.8B",
            quantization = "Q4_K_M",
            sizeBytes = 532_517_120L,
            minAvailableRamBytes = 1 * GB,
            contextTokens = 32768,
            license = "Apache-2.0",
            downloadUrl = "https://huggingface.co/unsloth/Qwen3.5-0.8B-GGUF/resolve/" +
                "$QWEN35_08B_REV/Qwen3.5-0.8B-Q4_K_M.gguf",
            sha256 = "bd258782e35f7f458f8aced1adc053e6e92e89bc735ba3be89d38a06121dc517",
            supportsTools = true,
            description = "Recommended default. Small and fast, fits any supported phone and " +
                "reads letters well for its size; a larger model catches more details.",
            backendSpec = BackendSpec(accelerators = listOf(Accelerator.GPU, Accelerator.CPU)),
            // Reads every letter (the tuned profile is measured on it), so it is always installed.
            role = ModelRole.READER_AND_CHAT,
            // Total memory as Android reports it (decimal GB): a "4 GB" phone reads about 3.7.
            minRamGb = 2.5,
            recommendedRamGb = 3.0,
            approxRamUseGb = 0.6,
            preselectable = true,
        ),
        AiModelDescriptor(
            id = "qwen3.5-2b-q4_k_m",
            name = "Qwen3.5 2B",
            family = "Qwen",
            parameterCount = "2B",
            quantization = "Q4_K_M",
            sizeBytes = 1_280_835_840L,
            minAvailableRamBytes = 2 * GB,
            contextTokens = 32768,
            license = "Apache-2.0",
            downloadUrl = "https://huggingface.co/unsloth/Qwen3.5-2B-GGUF/resolve/" +
                "$QWEN35_2B_REV/Qwen3.5-2B-Q4_K_M.gguf",
            sha256 = "aaf42c8b7c3cab2bf3d69c355048d4a0ee9973d48f16c731c0520ee914699223",
            supportsTools = true,
            // Not "used for form filling": that feature is hidden in release builds, so the line would be untrue there.
            description = "Better answers than the recommended 0.8B, but slower and needs more " +
                "memory.",
            backendSpec = BackendSpec(accelerators = listOf(Accelerator.GPU, Accelerator.CPU)),
            minRamGb = 4.5,
            recommendedRamGb = 5.0,
            approxRamUseGb = 1.6,
            // Measured 2-3x slower than the 0.8B on the CPU engine.
            preselectable = true,
            speedHint = SpeedHint.SLOWER,
        ),
        AiModelDescriptor(
            id = "qwen3.5-4b-q4_k_m",
            name = "Qwen3.5 4B",
            family = "Qwen",
            parameterCount = "4B",
            quantization = "Q4_K_M",
            sizeBytes = 2_740_937_888L,
            minAvailableRamBytes = 4 * GB,
            contextTokens = 32768,
            license = "Apache-2.0",
            downloadUrl = "https://huggingface.co/unsloth/Qwen3.5-4B-GGUF/resolve/" +
                "$QWEN35_4B_REV/Qwen3.5-4B-Q4_K_M.gguf",
            sha256 = "00fe7986ff5f6b463e62455821146049db6f9313603938a70800d1fb69ef11a4",
            supportsTools = true,
            description = "Noticeably better at multi-step reasoning and at picking the " +
                "right entity out of a crowded page.",
            backendSpec = BackendSpec(accelerators = listOf(Accelerator.GPU, Accelerator.CPU)),
            // A flagship with 11.3 GB total had 2.5 GB free in practice: a 4B model is that phone's limit.
            minRamGb = 8.0,
            recommendedRamGb = 10.0,
            approxRamUseGb = 3.3,
            speedHint = SpeedHint.MUCH_SLOWER,
        ),
        AiModelDescriptor(
            id = "gemma-4-e2b-it-qat-q4_0",
            name = "Gemma 4 E2B",
            family = "Gemma",
            parameterCount = "E2B",
            quantization = "Q4_0 (QAT)",
            sizeBytes = 3_349_516_256L,
            // The E-series is a nested architecture: the file is far larger than the
            // "effective" parameter count suggests, and the whole of it is loaded.
            minAvailableRamBytes = 5 * GB,
            contextTokens = 32768,
            license = GEMMA4_LICENSE,
            downloadUrl = "https://huggingface.co/google/gemma-4-E2B-it-qat-q4_0-gguf/" +
                "resolve/$GEMMA4_E2B_REV/gemma-4-E2B_q4_0-it.gguf",
            sha256 = "fa401b55b07ee70a54c6dae3903c783a6e65064312529ea57175cb5f8dec6634",
            recommendedForExtraction = true,
            // No native tool-calling template; grammar-constrained decoding covers it, which
            // is what the tool layer was designed around anyway.
            supportsTools = false,
            description = "Google's own quantisation-aware build — the 4-bit weights were " +
                "trained, not rounded afterwards. Strong multilingual reading, including " +
                "German.",
            backendSpec = BackendSpec(accelerators = listOf(Accelerator.GPU, Accelerator.CPU)),
            minRamGb = 8.0,
            recommendedRamGb = 12.0,
            approxRamUseGb = 4.2,
            speedHint = SpeedHint.MUCH_SLOWER,
        ),
        AiModelDescriptor(
            id = "gemma-4-e4b-it-qat-q4_0",
            name = "Gemma 4 E4B",
            family = "Gemma",
            parameterCount = "E4B",
            quantization = "Q4_0 (QAT)",
            sizeBytes = 5_154_941_280L,
            minAvailableRamBytes = 7 * GB,
            contextTokens = 32768,
            license = GEMMA4_LICENSE,
            downloadUrl = "https://huggingface.co/google/gemma-4-E4B-it-qat-q4_0-gguf/" +
                "resolve/$GEMMA4_E4B_REV/gemma-4-E4B_q4_0-it.gguf",
            sha256 = "676c35070db6dbe52f93e9c864ee0fba4eddea94b9c875d9cb10daff453fbaee",
            recommendedForExtraction = true,
            supportsTools = false,
            description = "The best understanding of a document available on-device, and the " +
                "most reliable at identifying people and organisations. Needs a high-end " +
                "phone with memory to spare.",
            backendSpec = BackendSpec(accelerators = listOf(Accelerator.GPU, Accelerator.CPU)),
            minRamGb = 12.0,
            recommendedRamGb = 16.0,
            approxRamUseGb = 6.0,
            speedHint = SpeedHint.MUCH_SLOWER,
        ),
    )

    /**
     * The reader model (flagged [ModelRole.READER_AND_CHAT] above, which a test pins): setup always installs it, and extraction runs
     * on it whenever it is installed. The id is only for code that has no descriptor list at hand.
     */
    const val READER_MODEL_ID = "qwen3.5-0.8b-q4_k_m"

    val readerModel: AiModelDescriptor
        get() = models.first { it.role == ModelRole.READER_AND_CHAT }
}
