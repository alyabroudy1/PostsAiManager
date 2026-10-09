package com.postsaimanager.core.domain.setup

import com.postsaimanager.core.model.AiModelDescriptor
import com.postsaimanager.core.model.ChatModelFit
import com.postsaimanager.core.model.ChatModelOption
import com.postsaimanager.core.model.ChatModelRecommendation
import com.postsaimanager.core.model.DeviceProfile
import com.postsaimanager.core.model.ModelRole
import com.postsaimanager.core.model.NotRecommendedReason
import com.postsaimanager.core.model.isItsOwnReader
import javax.inject.Inject

/**
 * Which chat models suit this phone, and the one to check first.
 *
 * Letters are read by the reader model ([ModelRole.READER_AND_CHAT]) unless the chosen chat model reads them itself
 * ([AiModelDescriptor.readsDocuments], the Gemma models): then the download is that model and the search model only. The choice is
 * the chat model. All thresholds and the order are on the descriptors ([AiModelDescriptor.minRamGb],
 * [AiModelDescriptor.recommendedRamGb], [AiModelDescriptor.setupRank]), none here.
 *
 * The options are listed by [AiModelDescriptor.setupRank] (highest first), those not recommended for this phone after the others.
 * The preselected model is the highest-ranked (then the largest) [AiModelDescriptor.preselectable] one whose recommended memory the
 * phone has and whose whole download fits in the free space; the reader when none does. Bigger models are slow on this engine, so
 * memory alone never makes them the default.
 */
class RecommendChatModelUseCase @Inject constructor() {

    /**
     * @param catalog every model on offer; must contain a [ModelRole.READER_AND_CHAT] one.
     * @param installedIds descriptor ids already on the phone: they add nothing to a download.
     * @param searchModelBytes the search model's download still needed (0 when installed).
     * @param storageHeadroomBytes space that must stay free after the download.
     */
    operator fun invoke(
        device: DeviceProfile,
        catalog: List<AiModelDescriptor>,
        installedIds: Set<String>,
        searchModelBytes: Long,
        storageHeadroomBytes: Long = DEFAULT_STORAGE_HEADROOM_BYTES,
    ): ChatModelRecommendation {
        val reader = catalog.first { it.role == ModelRole.READER_AND_CHAT }
        val options = catalog.filter { it.supportsChat }.map { model ->
            val download = downloadBytes(model, reader, installedIds, searchModelBytes)
            ChatModelOption(model, fitOf(model, device, download, storageHeadroomBytes), download)
        }.sortedWith(compareBy<ChatModelOption> { it.fit is ChatModelFit.NotRecommended }.thenByDescending { it.descriptor.setupRank })
        val preselected = options
            .filter { it.fit == ChatModelFit.Recommended && it.descriptor.preselectable }
            .maxWithOrNull(compareBy<ChatModelOption> { it.descriptor.setupRank }.thenBy { it.descriptor.sizeBytes })
            ?.id
            ?: reader.id
        // "Recommended for this phone" is said of the one model the app preselects and of no other: a model that merely may be the default
        // (it fits and is not slow) is suitable, and says so.
        val labelled = options.map { if (it.fit == ChatModelFit.Recommended && it.id != preselected) it.copy(fit = ChatModelFit.Suitable) else it }
        return ChatModelRecommendation(labelled, preselected, reader)
    }

    /**
     * What choosing [chat] downloads: the chat model, the reader too unless the chat model is its own reader, and the search model,
     * each only if missing.
     */
    private fun downloadBytes(chat: AiModelDescriptor, reader: AiModelDescriptor, installedIds: Set<String>, searchModelBytes: Long): Long {
        val models = (if (chat.isItsOwnReader(reader)) listOf(chat) else listOf(reader, chat)).filter { it.id !in installedIds }
        return models.sumOf { it.sizeBytes } + searchModelBytes
    }

    private fun fitOf(model: AiModelDescriptor, device: DeviceProfile, downloadBytes: Long, headroomBytes: Long): ChatModelFit = when {
        !device.is64Bit -> ChatModelFit.NotRecommended(NotRecommendedReason.UNSUPPORTED_32_BIT)
        device.totalRamGb < model.minRamGb ->
            ChatModelFit.NotRecommended(NotRecommendedReason.MEMORY, requiredRamGb = model.minRamGb)
        device.availableStorageBytes < downloadBytes + headroomBytes ->
            ChatModelFit.NotRecommended(NotRecommendedReason.STORAGE, requiredStorageBytes = downloadBytes + headroomBytes)
        // "Recommended for this phone" is only said of models that may be the default; a slow one that fits is merely suitable.
        device.totalRamGb >= model.recommendedRamGb && model.preselectable -> ChatModelFit.Recommended
        else -> ChatModelFit.Suitable
    }

    companion object {
        /** Kept free for the system and the app, as the model manager's own storage check does. */
        const val DEFAULT_STORAGE_HEADROOM_BYTES = 512L * 1024 * 1024
    }
}
