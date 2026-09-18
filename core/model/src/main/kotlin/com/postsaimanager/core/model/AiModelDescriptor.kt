package com.postsaimanager.core.model

import kotlinx.serialization.Serializable

/**
 * A model offered in the catalog — not yet installed.
 *
 * Descriptors arrive from the signed remote manifest (`:core:config`) with a bundled
 * fallback, so new models can appear without an app update.
 */
@Serializable
data class AiModelDescriptor(
    val id: String,
    val name: String,
    val family: String,
    val parameterCount: String,
    val quantization: String,
    val sizeBytes: Long,
    /** Working-set estimate once loaded — always larger than [sizeBytes]. */
    val minAvailableRamBytes: Long,
    val contextTokens: Int,
    val license: String,
    val downloadUrl: String? = null,
    /** Required before any download is allowed. See [isInstallable]. */
    val sha256: String? = null,
    val supportsTools: Boolean = false,
    /**
     * Suited to reading a document into structured fields, which is a different job from
     * holding a conversation.
     *
     * Measured, not assumed. On a real German letter Gemma 4 E2B identified the sender,
     * recipient, contact, both reference numbers, the deadline and the amount; Qwen3.5 2B
     * on the identical prompt found no sender and missed the deadline entirely. Reading is
     * a one-shot structured task where accuracy is everything and latency barely matters,
     * since it runs in the background after a scan — the opposite of chat.
     */
    val recommendedForExtraction: Boolean = false,
    val description: String? = null,
) {
    /**
     * A model may only be downloaded when both a URL **and** an integrity hash are known.
     *
     * The hash comes from the signed manifest, so an entry that reaches the device without
     * one cannot be installed — that is deliberate. Verifying the artefact is the second of
     * the two independent supply-chain checks (the first is the manifest signature).
     */
    val isInstallable: Boolean get() = !downloadUrl.isNullOrBlank() && !sha256.isNullOrBlank()
}

/** A model present on disk and usable. */
@Serializable
data class InstalledModel(
    val id: String,
    /** Catalog entry this came from; null when side-loaded by the user. */
    val descriptorId: String? = null,
    val name: String,
    val filePath: String,
    val sizeBytes: Long,
    val sha256: String,
    val contextTokens: Int,
    val source: ModelSource,
    val installedAt: Long,
)

@Serializable
enum class ModelSource {
    CATALOG,
    IMPORTED,
}

/** Coarse device class, used to curate the catalog rather than to gate features. */
@Serializable
enum class DeviceTier {
    /** No local generation. The document app is fully functional; embeddings still run. */
    TIER_0,
    TIER_1,
    TIER_2,
    TIER_3;

    companion object {
        private const val GB = 1024L * 1024L * 1024L

        fun ofTotalRam(totalRamBytes: Long): DeviceTier = when {
            totalRamBytes < 4 * GB -> TIER_0
            totalRamBytes < 6 * GB -> TIER_1
            totalRamBytes < 8 * GB -> TIER_2
            else -> TIER_3
        }
    }
}

/** A snapshot of what the device can currently afford. */
data class DeviceCapability(
    val totalRamBytes: Long,
    /** What is free *right now* — the number that actually decides whether a load succeeds. */
    val availableRamBytes: Long,
    val freeStorageBytes: Long,
    val supportedAbis: List<String>,
    val isLowMemory: Boolean = false,
) {
    val tier: DeviceTier get() = DeviceTier.ofTotalRam(totalRamBytes)

    /** llama.cpp ships arm64-v8a only (plus x86_64 for the emulator). */
    val hasSupportedAbi: Boolean
        get() = supportedAbis.any { it == "arm64-v8a" || it == "x86_64" }
}

/** Whether a specific model can be installed and loaded on this device, and why not. */
sealed interface ModelFit {
    data object Fits : ModelFit

    /** Installable, but not loadable at this moment — free memory and retry. */
    data class InsufficientAvailableMemory(
        val requiredBytes: Long,
        val availableBytes: Long,
    ) : ModelFit

    data class InsufficientStorage(
        val requiredBytes: Long,
        val freeBytes: Long,
    ) : ModelFit

    data class TooLargeForDevice(
        val requiredBytes: Long,
        val totalRamBytes: Long,
    ) : ModelFit

    data object UnsupportedAbi : ModelFit

    data object NotInstallable : ModelFit

    val canDownload: Boolean
        get() = this is Fits || this is InsufficientAvailableMemory

    companion object {
        /** Headroom kept free for the OS and the rest of the app. */
        private const val STORAGE_HEADROOM_BYTES = 512L * 1024 * 1024

        /**
         * Evaluates a model against a live device snapshot.
         *
         * The critical detail: **available** memory decides loadability, not total.
         * Measured on a Galaxy S23 Ultra, `MemTotal` was 11.3 GB while `MemAvailable`
         * was 2.5 GB — a flagship at its practical limit for a 3–4 B model. Tiering on
         * total RAM alone would have called that Tier 3 and then hit an OOM kill.
         */
        fun evaluate(
            descriptor: AiModelDescriptor,
            capability: DeviceCapability,
        ): ModelFit {
            if (!descriptor.isInstallable) return NotInstallable
            if (!capability.hasSupportedAbi) return UnsupportedAbi

            if (capability.freeStorageBytes < descriptor.sizeBytes + STORAGE_HEADROOM_BYTES) {
                return InsufficientStorage(
                    requiredBytes = descriptor.sizeBytes + STORAGE_HEADROOM_BYTES,
                    freeBytes = capability.freeStorageBytes,
                )
            }

            // Hard ceiling: the device can never run this, no matter what is closed.
            if (capability.totalRamBytes < descriptor.minAvailableRamBytes) {
                return TooLargeForDevice(
                    requiredBytes = descriptor.minAvailableRamBytes,
                    totalRamBytes = capability.totalRamBytes,
                )
            }

            // Soft ceiling: downloadable now, may need memory freed before it loads.
            if (capability.availableRamBytes < descriptor.minAvailableRamBytes) {
                return InsufficientAvailableMemory(
                    requiredBytes = descriptor.minAvailableRamBytes,
                    availableBytes = capability.availableRamBytes,
                )
            }

            return Fits
        }
    }
}
