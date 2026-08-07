package com.postsaimanager.feature.models

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.postsaimanager.core.ai.catalog.CatalogEntry
import com.postsaimanager.core.ai.catalog.ModelCatalogRepository
import com.postsaimanager.core.ai.catalog.gguf.ModelImporter
import com.postsaimanager.core.ai.catalog.ModelCatalogState
import com.postsaimanager.core.ai.catalog.download.ModelDownloadStatus
import com.postsaimanager.core.model.AiModelDescriptor
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
    ) : ModelsUiState

    data class Error(val message: String) : ModelsUiState
}

/** A user-facing reason a model cannot be installed, with an action where one exists. */
data class FitMessage(
    val text: String,
    val isBlocking: Boolean,
)

@HiltViewModel
class ModelsViewModel @Inject constructor(
    private val repository: ModelCatalogRepository,
    private val importer: ModelImporter,
) : ViewModel() {

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    val uiState: StateFlow<ModelsUiState> =
        repository.state
            .map<ModelCatalogState, ModelsUiState> { state ->
                ModelsUiState.Ready(
                    capability = state.capability,
                    installed = state.entries.filter { it.isInstalled },
                    available = state.entries.filterNot { it.isInstalled },
                    usingBundledCatalog = state.usingBundledCatalog,
                )
            }
            .catch { emit(ModelsUiState.Error(it.message ?: "Could not load the model catalog")) }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = ModelsUiState.Loading,
            )

    fun downloadStatus(descriptorId: String) = repository.downloadStatus(descriptorId)

    fun install(descriptor: AiModelDescriptor, allowMetered: Boolean = false) {
        viewModelScope.launch {
            val started = repository.startDownload(descriptor, allowMetered)
            if (!started) {
                // Unlike a silently-dropped write, the user is told why nothing happened.
                _message.value =
                    "${descriptor.name} cannot be installed yet: no verified download " +
                    "source is configured. Model downloads require a signed catalog."
            }
        }
    }

    fun cancel(descriptorId: String) = repository.cancelDownload(descriptorId)

    fun uninstall(modelId: String) = repository.uninstall(modelId)

    fun setActive(modelId: String) = repository.setActive(modelId)

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
            _message.value = "Checking the file…"
            when (val result = importer.import(uri)) {
                is com.postsaimanager.core.common.result.PamResult.Success ->
                    _message.value = "${result.data.name} imported and ready to use."
                is com.postsaimanager.core.common.result.PamResult.Error ->
                    _message.value = result.error.userMessage
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
                "Needs ${fit.requiredBytes.toGb()} free, ${fit.availableBytes.toGb()} available. " +
                    "You can install it now and close some apps before using it.",
                isBlocking = false,
            )

            is ModelFit.TooLargeForDevice -> FitMessage(
                "Too large for this device (needs ${fit.requiredBytes.toGb()}, " +
                    "device has ${fit.totalRamBytes.toGb()} total).",
                isBlocking = true,
            )

            is ModelFit.InsufficientStorage -> FitMessage(
                "Needs ${fit.requiredBytes.toGb()} of storage, ${fit.freeBytes.toGb()} free.",
                isBlocking = true,
            )

            ModelFit.UnsupportedAbi -> FitMessage(
                "This device's processor is not supported.",
                isBlocking = true,
            )

            ModelFit.NotInstallable -> FitMessage(
                "No verified download source yet.",
                isBlocking = true,
            )
        }

        private fun Long.toGb(): String = "%.1f GB".format(this / 1_073_741_824.0)
    }
}
