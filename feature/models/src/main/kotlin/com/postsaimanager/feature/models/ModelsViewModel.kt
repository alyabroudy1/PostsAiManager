package com.postsaimanager.feature.models

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.postsaimanager.core.ai.catalog.CatalogEntry
import com.postsaimanager.core.ai.catalog.ModelCatalogRepository
import com.postsaimanager.core.ai.catalog.gguf.ModelImporter
import com.postsaimanager.core.ai.catalog.ModelCatalogState
import com.postsaimanager.core.ai.catalog.download.ModelDownloadStatus
import com.postsaimanager.core.ai.embed.install.EmbeddingModelManager
import com.postsaimanager.core.ai.embed.install.InstallStatus
import com.postsaimanager.core.domain.setup.DeviceCapabilities
import com.postsaimanager.core.domain.setup.RecommendChatModelUseCase
import com.postsaimanager.core.model.AiModelDescriptor
import com.postsaimanager.core.model.ChatModelFit
import com.postsaimanager.core.model.DeviceCapability
import com.postsaimanager.core.model.ModelFit
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface ModelsUiState {
    data object Loading : ModelsUiState

    data class Ready(
        val capability: DeviceCapability,
        val installed: List<CatalogEntry>,
        val available: List<CatalogEntry>,
        val usingBundledCatalog: Boolean,
        /** How each model suits this phone, by descriptor id; empty when it could not be worked out. */
        val fits: Map<String, ChatModelFit> = emptyMap(),
    ) : ModelsUiState

    /** [message] is the raw cause, if any; the screen shows a localized fallback when it is null. */
    data class Error(val message: String?) : ModelsUiState
}

/**
 * A user-facing reason a model cannot be installed. [textRes] is a string resource whose arguments are the [bytes] (in order), which
 * the screen formats as sizes in the app language.
 */
data class FitMessage(
    val textRes: Int,
    val bytes: List<Long> = emptyList(),
    val isBlocking: Boolean,
)

/** A one-off message for the snackbar: a string resource with arguments, or a raw text that came from a lower layer. */
sealed interface ModelsMessage {
    data class Res(val id: Int, val args: List<Any> = emptyList()) : ModelsMessage
    data class Raw(val text: String) : ModelsMessage
}

@HiltViewModel
class ModelsViewModel @Inject constructor(
    private val repository: ModelCatalogRepository,
    private val importer: ModelImporter,
    private val embeddingModel: EmbeddingModelManager,
    private val deviceCapabilities: DeviceCapabilities,
    private val recommendChatModel: RecommendChatModelUseCase,
) : ViewModel() {

    private val _message = MutableStateFlow<ModelsMessage?>(null)
    val message: StateFlow<ModelsMessage?> = _message.asStateFlow()

    val uiState: StateFlow<ModelsUiState> =
        repository.state
            .map<ModelCatalogState, ModelsUiState> { state ->
                ModelsUiState.Ready(
                    capability = state.capability,
                    installed = state.entries.filter { it.isInstalled },
                    available = state.entries.filterNot { it.isInstalled },
                    usingBundledCatalog = state.usingBundledCatalog,
                    fits = chatFits(state),
                )
            }
            .catch { emit(ModelsUiState.Error(it.message)) }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = ModelsUiState.Loading,
            )

    /**
     * How each catalog model suits this phone, from the same use case as first-run setup. The search model is not part of a model's
     * download here (it is installed on its own card), so its bytes count as 0.
     */
    private suspend fun chatFits(state: ModelCatalogState): Map<String, ChatModelFit> =
        runCatching {
            recommendChatModel(
                device = deviceCapabilities.current(),
                catalog = state.entries.map { it.descriptor },
                installedIds = state.entries.filter { it.isInstalled }.map { it.descriptor.id }.toSet(),
                searchModelBytes = 0L,
            ).options.associate { it.id to it.fit }
        }.getOrDefault(emptyMap())

    /**
     * The embedding model, which is not part of the chat catalog.
     *
     * Kept as its own stream rather than folded into [uiState]: it is a different kind of
     * thing — one fixed asset that enables a feature, not a model the user chooses between
     * — and its progress updates far more often than the catalog changes.
     */
    val embeddingStatus: StateFlow<InstallStatus> =
        embeddingModel.status.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = if (embeddingModel.isInstalled()) {
                // Avoids a flash of "not installed" on an install that is already done,
                // which reads as the model having been lost.
                InstallStatus.Installed
            } else {
                InstallStatus.NotStarted
            },
        )

    val embeddingDownloadBytes: Long get() = embeddingModel.downloadBytes

    fun installEmbeddingModel(allowMetered: Boolean = false) {
        embeddingModel.install(allowMetered)
    }

    fun cancelEmbeddingInstall() {
        embeddingModel.cancel()
    }

    fun uninstallEmbeddingModel() {
        viewModelScope.launch {
            embeddingModel.uninstall()
            _message.value = ModelsMessage.Res(R.string.models_msg_search_off)
        }
    }

    fun downloadStatus(descriptorId: String) = repository.downloadStatus(descriptorId)

    fun install(descriptor: AiModelDescriptor, allowMetered: Boolean = false) {
        viewModelScope.launch {
            val started = repository.startDownload(descriptor, allowMetered)
            if (!started) {
                // Unlike a silently-dropped write, the user is told why nothing happened.
                _message.value = ModelsMessage.Res(R.string.models_msg_not_installable, listOf(descriptor.name))
            }
        }
    }

    fun cancel(descriptorId: String) = repository.cancelDownload(descriptorId)

    fun uninstall(modelId: String) = repository.uninstall(modelId)

    fun setActive(modelId: String) = repository.setActive(modelId)

    fun setExtractionModel(modelId: String) {
        repository.setExtractionModel(modelId)
        _message.value = ModelsMessage.Res(R.string.models_msg_reading_model)
    }

    fun onDownloadFinished(descriptor: AiModelDescriptor, status: ModelDownloadStatus) {
        if (status is ModelDownloadStatus.Complete && status.filePath.isNotBlank()) {
            repository.onDownloadComplete(descriptor, status.filePath)
        }
    }

    /**
     * Imports a user-picked GGUF file.
     *
     * Every outcome is reported — success and each distinct failure. An import that
     * silently does nothing is the pattern defect 6.7.12 exists to eliminate.
     */
    fun import(uri: Uri) {
        viewModelScope.launch {
            _message.value = ModelsMessage.Res(R.string.models_msg_checking_file)
            when (val result = importer.import(uri)) {
                is com.postsaimanager.core.common.result.PamResult.Success ->
                    _message.value = ModelsMessage.Res(R.string.models_msg_imported, listOf(result.data.name))
                is com.postsaimanager.core.common.result.PamResult.Error ->
                    _message.value = ModelsMessage.Raw(result.error.userMessage)
            }
        }
    }

    fun consumeMessage() {
        _message.value = null
    }

    companion object {
        /** Maps a [ModelFit] to something a person can act on. */
        fun fitMessage(fit: ModelFit): FitMessage? = when (fit) {
            is ModelFit.Fits -> null

            is ModelFit.InsufficientAvailableMemory -> FitMessage(
                R.string.models_fit_low_memory,
                listOf(fit.requiredBytes, fit.availableBytes),
                isBlocking = false,
            )

            is ModelFit.TooLargeForDevice -> FitMessage(
                R.string.models_fit_too_large,
                listOf(fit.requiredBytes, fit.totalRamBytes),
                isBlocking = true,
            )

            is ModelFit.InsufficientStorage -> FitMessage(
                R.string.models_fit_storage,
                listOf(fit.requiredBytes, fit.freeBytes),
                isBlocking = true,
            )

            ModelFit.UnsupportedAbi -> FitMessage(R.string.models_fit_unsupported_abi, isBlocking = true)

            ModelFit.NotInstallable -> FitMessage(R.string.models_fit_not_installable, isBlocking = true)
        }
    }
}
