package com.postsaimanager.core.ai.catalog

import com.postsaimanager.core.model.AiModelDescriptor

/**
 * Offline fallback catalog, compiled into the APK.
 *
 * Its job is to let a fresh install browse *something* before it has ever reached the
 * network. The authoritative catalog is the signed remote manifest (`:core:config`), which
 * supplies download URLs and SHA-256 hashes and can add models without an app update.
 *
 * ### Why these entries carry no URL or hash
 *
 * [AiModelDescriptor.isInstallable] requires both, and [com.postsaimanager.core.model.ModelFit]
 * reports `NotInstallable` without them. That is deliberate: hard-coding a download URL and
 * hash in the APK would create a second, unsigned supply-chain path that could not be
 * revoked or corrected after release. Installation always goes through the signed manifest.
 *
 * Sizes and memory requirements are approximate and used only to sort and pre-filter the
 * list; the manifest's values win once fetched.
 */
object BundledCatalog {

    private const val MB = 1024L * 1024L
    private const val GB = 1024L * MB

    val models: List<AiModelDescriptor> = listOf(
        AiModelDescriptor(
            id = "qwen2.5-0.5b-instruct-q4_k_m",
            name = "Qwen2.5 0.5B Instruct",
            family = "Qwen",
            parameterCount = "0.5B",
            quantization = "Q4_K_M",
            sizeBytes = 400 * MB,
            minAvailableRamBytes = 1 * GB,
            contextTokens = 32768,
            license = "Apache-2.0",
            supportsTools = true,
            description = "Smallest usable assistant. Fits low-memory devices; " +
                "weakest at multi-step tool use.",
        ),
        AiModelDescriptor(
            id = "qwen2.5-1.5b-instruct-q4_k_m",
            name = "Qwen2.5 1.5B Instruct",
            family = "Qwen",
            parameterCount = "1.5B",
            quantization = "Q4_K_M",
            sizeBytes = 1100 * MB,
            minAvailableRamBytes = 2 * GB,
            contextTokens = 32768,
            license = "Apache-2.0",
            supportsTools = true,
            description = "Good default. Handles summarisation and simple tool calls.",
        ),
        AiModelDescriptor(
            id = "qwen2.5-3b-instruct-q4_k_m",
            name = "Qwen2.5 3B Instruct",
            family = "Qwen",
            parameterCount = "3B",
            quantization = "Q4_K_M",
            sizeBytes = 2000 * MB,
            minAvailableRamBytes = 3 * GB,
            contextTokens = 32768,
            license = "Apache-2.0",
            supportsTools = true,
            description = "Noticeably better at multi-step tool use and German letters.",
        ),
        AiModelDescriptor(
            id = "gemma-2-2b-it-q4_k_m",
            name = "Gemma 2 2B Instruct",
            family = "Gemma",
            parameterCount = "2B",
            quantization = "Q4_K_M",
            sizeBytes = 1700 * MB,
            minAvailableRamBytes = 2500 * MB,
            contextTokens = 8192,
            license = "Gemma Terms of Use",
            supportsTools = false,
            description = "Strong general quality; shorter context and no native tool calling " +
                "(handled by grammar-constrained decoding).",
        ),
    )
}
