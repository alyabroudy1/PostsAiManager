package com.postsaimanager.core.domain.setup

import com.postsaimanager.core.model.AiModelDescriptor
import com.postsaimanager.core.model.ChatModelFit
import com.postsaimanager.core.model.ChatModelOption
import com.postsaimanager.core.model.ChatModelRecommendation
import com.postsaimanager.core.model.DeviceProfile
import com.postsaimanager.core.model.ModelRole
import com.postsaimanager.core.model.NotRecommendedReason
import javax.inject.Inject

/**
 * Which chat models suit this phone, and the one to check first.
 *
 * Letters are always read by the reader model ([ModelRole.READER_AND_CHAT]), so it is part of every download; the choice is the chat
 * model. All thresholds are on the descriptors ([AiModelDescriptor.minRamGb], [AiModelDescriptor.recommendedRamGb]), none here.
 *
 * The preselected model is the largest one whose recommended memory the phone has and whose whole download fits in the free space;
 * the reader when none does.
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
        val options = catalog.map { model ->
            val download = downloadBytes(model, reader, installedIds, searchModelBytes)
            ChatModelOption(model, fitOf(model, device, download, storageHeadroomBytes), download)
        }
        val preselected = options
            .filter { it.fit == ChatModelFit.Recommended }
            .maxByOrNull { it.descriptor.sizeBytes }
            ?.id
            ?: reader.id
        return ChatModelRecommendation(options, preselected, reader)
    }

    /** What choosing [chat] downloads: the reader and the chat model when they differ, and the search model, each only if missing. */
    private fun downloadBytes(chat: AiModelDescriptor, reader: AiModelDescriptor, installedIds: Set<String>, searchModelBytes: Long): Long {
        val models = listOf(reader, chat).distinctBy { it.id }.filter { it.id !in installedIds }
        return models.sumOf { it.sizeBytes } + searchModelBytes
    }

    private fun fitOf(model: AiModelDescriptor, device: DeviceProfile, downloadBytes: Long, headroomBytes: Long): ChatModelFit = when {
        !device.is64Bit -> ChatModelFit.NotRecommended(NotRecommendedReason.UNSUPPORTED_32_BIT)
        device.totalRamGb < model.minRamGb ->
            ChatModelFit.NotRecommended(NotRecommendedReason.MEMORY, requiredRamGb = model.minRamGb)
        device.availableStorageBytes < downloadBytes + headroomBytes ->
            ChatModelFit.NotRecommended(NotRecommendedReason.STORAGE, requiredStorageBytes = downloadBytes + headroomBytes)
        device.totalRamGb >= model.recommendedRamGb -> ChatModelFit.Recommended
        else -> ChatModelFit.Suitable
    }

    companion object {
        /** Kept free for the system and the app, as the model manager's own storage check does. */
        const val DEFAULT_STORAGE_HEADROOM_BYTES = 512L * 1024 * 1024
    }
}
