package com.postsaimanager.core.ai.catalog

import com.postsaimanager.core.model.AiModelDescriptor

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
 * conservative default, it is a broken feature — and the on-device assistant this app
 * exists to be cannot run without one.
 *
 * The revocation argument holds for a catalog that **grows and changes after release**, and
 * the manifest remains exactly that: it can add models, correct these, and override any
 * entry here. What it does not hold for is a small curated set fixed at build time. These
 * four change only when the app is rebuilt, which is the same release gate a manifest
 * correction would go through anyway.
 *
 * For that set, pinning is the *stronger* guarantee, not the weaker one:
 *
 *  - [AiModelDescriptor.sha256] is verified before the file is moved into place, so a
 *    compromised CDN, a wrong mirror or a truncated transfer all fail closed.
 *  - Each URL names an **immutable revision**, never a branch, so the bytes behind it
 *    cannot change under the hash.
 *  - Every hash below is the Hugging Face LFS `oid`, which *is* the SHA-256 of the file,
 *    read from the API rather than transcribed.
 *
 * Same reasoning as `EmbeddingModelRelease`, and deliberately consistent with it.
 *
 * ### Why these four
 *
 * They span the device range rather than showing off. The 0.5B exists so a low-memory phone
 * has something that runs at all — it was measured at 173 tok/s on the test device — and the
 * 4B exists so a capable one is not held back. All four are ungated: no Hugging Face token,
 * no licence click-through, nothing between the user and a working assistant.
 */
object BundledCatalog {

    private const val MB = 1024L * 1024L
    private const val GB = 1024L * MB

    private const val QWEN_05B_REV = "9217f5db79a29953eb74d5343926648285ec7e67"
    private const val QWEN_15B_REV = "91cad51170dc346986eccefdc2dd33a9da36ead9"
    private const val GEMMA3_1B_REV = "f0b45be0aac41bd6a100a4b5734cad5f67255bfb"
    private const val GEMMA3_4B_REV = "5a3566e716d80f709ed7b79817eaf7733d2a1fce"

    val models: List<AiModelDescriptor> = listOf(
        AiModelDescriptor(
            id = "qwen2.5-0.5b-instruct-q4_k_m",
            name = "Qwen2.5 0.5B Instruct",
            family = "Qwen",
            parameterCount = "0.5B",
            quantization = "Q4_K_M",
            sizeBytes = 491_400_032L,
            minAvailableRamBytes = 1 * GB,
            contextTokens = 32768,
            license = "Apache-2.0",
            downloadUrl = "https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF/resolve/" +
                "$QWEN_05B_REV/qwen2.5-0.5b-instruct-q4_k_m.gguf",
            sha256 = "74a4da8c9fdbcd15bd1f6d01d621410d31c6fc00986f5eb687824e7b93d7a9db",
            supportsTools = true,
            description = "Smallest usable assistant. Runs on low-memory devices — measured " +
                "at 173 tokens/second on the test phone — but is the weakest at reading a " +
                "letter and filling in fields accurately.",
        ),
        AiModelDescriptor(
            id = "gemma-3-1b-it-q4_k_m",
            name = "Gemma 3 1B Instruct",
            family = "Gemma",
            parameterCount = "1B",
            quantization = "Q4_K_M",
            sizeBytes = 806_058_272L,
            minAvailableRamBytes = 1500 * MB,
            contextTokens = 32768,
            license = "Gemma Terms of Use",
            downloadUrl = "https://huggingface.co/unsloth/gemma-3-1b-it-GGUF/resolve/" +
                "$GEMMA3_1B_REV/gemma-3-1b-it-Q4_K_M.gguf",
            sha256 = "8270790f3ab69fdfe860b7b64008d9a19986d8df7e407bb018184caa08798ebd",
            // No native tool-calling template; grammar-constrained decoding covers it, which
            // is the mechanism the tool layer was designed around anyway.
            supportsTools = false,
            description = "Strong multilingual quality for its size, including German. " +
                "A good middle choice when the 1.5B does not fit.",
        ),
        AiModelDescriptor(
            id = "qwen2.5-1.5b-instruct-q4_k_m",
            name = "Qwen2.5 1.5B Instruct",
            family = "Qwen",
            parameterCount = "1.5B",
            quantization = "Q4_K_M",
            sizeBytes = 1_117_320_736L,
            minAvailableRamBytes = 2 * GB,
            contextTokens = 32768,
            license = "Apache-2.0",
            downloadUrl = "https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-GGUF/resolve/" +
                "$QWEN_15B_REV/qwen2.5-1.5b-instruct-q4_k_m.gguf",
            sha256 = "6a1a2eb6d15622bf3c96857206351ba97e1af16c30d7a74ee38970e434e9407e",
            supportsTools = true,
            description = "The default. Handles summarising a letter and filling structured " +
                "fields reliably, and fits a mid-range phone.",
        ),
        AiModelDescriptor(
            id = "gemma-3-4b-it-q4_k_m",
            name = "Gemma 3 4B Instruct",
            family = "Gemma",
            parameterCount = "4B",
            quantization = "Q4_K_M",
            sizeBytes = 2_489_894_016L,
            minAvailableRamBytes = 4 * GB,
            contextTokens = 131072,
            license = "Gemma Terms of Use",
            downloadUrl = "https://huggingface.co/unsloth/gemma-3-4b-it-GGUF/resolve/" +
                "$GEMMA3_4B_REV/gemma-3-4b-it-Q4_K_M.gguf",
            sha256 = "04a43a22e8d2003deda5acc262f68ec1005fa76c735a9962a8c77042a74a7d19",
            supportsTools = false,
            description = "Best understanding of a document's meaning, and the most reliable " +
                "at identifying people and organisations. Needs a capable device.",
        ),
    )
}
