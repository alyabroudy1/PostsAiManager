package com.postsaimanager.core.model

import kotlinx.serialization.Serializable

/** What a catalog model does in the app. Data on the descriptor, so the reader is not named in code. */
@Serializable
enum class ModelRole {
    /** Offered as a chat model only. */
    CHAT,

    /**
     * Reads the scanned letters (the tuned extraction profile is measured on it) and may also chat. It is always installed, whatever chat
     * model the user picks.
     */
    READER_AND_CHAT,
}

/** What this phone offers, for deciding which chat models suit it. */
data class DeviceProfile(
    /** Total memory in GB (decimal, as Android reports it: a "12 GB" phone reads about 11.3). */
    val totalRamGb: Double,
    val availableStorageBytes: Long,
    /** Cores in the fastest clusters; the processor count when the frequencies cannot be read. */
    val bigCoreCount: Int,
    val is64Bit: Boolean,
)

/** How a model's answer speed compares with the reader's, as catalog data (shown as a localized note). */
@Serializable
enum class SpeedHint {
    NORMAL,
    SLOWER,
    MUCH_SLOWER,
}

/** Why a model is not recommended on this phone. */
enum class NotRecommendedReason {
    MEMORY,
    STORAGE,
    UNSUPPORTED_32_BIT,
}

/** How well a chat model suits this phone. */
sealed interface ChatModelFit {
    /** The phone has the memory the model is recommended for. */
    data object Recommended : ChatModelFit

    /** Enough memory to run it, below the recommended amount: works, may be slow. No badge. */
    data object Suitable : ChatModelFit

    /**
     * @param requiredRamGb the memory needed, for [NotRecommendedReason.MEMORY].
     * @param requiredStorageBytes the free space needed, for [NotRecommendedReason.STORAGE].
     */
    data class NotRecommended(
        val reason: NotRecommendedReason,
        val requiredRamGb: Double? = null,
        val requiredStorageBytes: Long? = null,
    ) : ChatModelFit
}

/**
 * One chat model on offer.
 *
 * @property downloadBytes everything that would be downloaded if this model is the chat model: the reader and this model when they
 *   differ, plus the search model, each only when not installed yet.
 */
data class ChatModelOption(
    val descriptor: AiModelDescriptor,
    val fit: ChatModelFit,
    val downloadBytes: Long,
) {
    val id: String get() = descriptor.id
}

/** The offer: every chat model with its fit, the one to check first, and the reader that is always installed. */
data class ChatModelRecommendation(
    val options: List<ChatModelOption>,
    val preselectedId: String,
    val reader: AiModelDescriptor,
) {
    fun option(id: String): ChatModelOption? = options.firstOrNull { it.id == id }
}
